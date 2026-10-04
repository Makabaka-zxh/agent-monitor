import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from agent_monitor.remote_replies import RemoteReplyWorker

FIRST = '11111111-1111-4111-8111-111111111111'
SECOND = '22222222-2222-4222-8222-222222222222'
SOURCE = 'codex:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'


class FakeExecutor:
    def __init__(self, emit):
        self.emit = emit
        self.starts = []
        self.cancels = []
        self.closed = 0
        self.on_start = None

    def start(self, command_id, source, prompt):
        if self.on_start:
            self.on_start()
        self.starts.append((command_id, source, prompt))
        return {'id': command_id, 'state': 'running'}

    def cancel(self, command_id):
        self.cancels.append(command_id)

    def close(self):
        self.closed += 1


class FakeHub:
    def __init__(self):
        self.calls = []
        self.commands = []
        self.canceled = []
        self.update = lambda value: {'id': value['id'], 'state': value['state']}
        self.poll_error = None

    def post(self, path, value, *, token):
        self.calls.append((path, dict(value)))
        if path.endswith('/poll'):
            if self.poll_error:
                raise self.poll_error
            return {'command': self.commands.pop(0) if self.commands else None, 'cancel_ids': self.canceled}
        return self.update(value)


class RemoteReplyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = Path(self.temporary.name)
        self.hub = FakeHub()
        self.worker = None

    def tearDown(self):
        if self.worker:
            self.worker.close()
        self.temporary.cleanup()

    def create(self):
        self.worker = RemoteReplyWorker(self.hub, SimpleNamespace(token='synthetic-never-live'), self.state,
                                        executor_factory=FakeExecutor, tool_finder=lambda: ['codex'])
        return self.worker

    def command(self, command_id=FIRST):
        return {'id': command_id, 'source_id': SOURCE, 'text': 'Only the user supplied reply', 'tool': 'codex'}

    def test_receipt_is_durable_before_ack_and_ack_before_launch(self):
        worker = self.create()
        self.hub.commands.append(self.command())

        def acknowledge(value):
            saved = json.loads(worker.path.read_text())
            self.assertEqual(saved[FIRST]['state'], 'received')
            self.assertEqual(worker.executor.starts, [])
            self.assertNotIn('Only the user supplied reply', worker.path.read_text())
            return {'id': FIRST, 'state': 'running'}

        self.hub.update = acknowledge
        worker.executor.on_start = lambda: self.assertEqual(json.loads(worker.path.read_text())[FIRST]['state'], 'running')
        worker._tick()
        self.assertEqual(len(worker.executor.starts), 1)

    def test_duplicate_dispatch_never_launches_twice(self):
        worker = self.create()
        self.hub.commands.extend([self.command(), self.command()])
        worker._tick()
        worker._tick()
        self.assertEqual(len(worker.executor.starts), 1)

    def test_lost_ack_never_launches_even_on_duplicate_delivery(self):
        worker = self.create()
        self.hub.commands.extend([self.command(), self.command()])

        def update(value):
            if value['state'] == 'running':
                raise TimeoutError('response lost after hub may have accepted')
            return {'id': value['id'], 'state': value['state']}

        self.hub.update = update
        worker._tick()
        worker._tick()
        self.assertEqual(worker.executor.starts, [])
        self.assertEqual(worker.ledger[FIRST]['state'], 'uncertain')
        running_acks = [value for path, value in self.hub.calls if path.endswith('/update') and value['state'] == 'running']
        self.assertEqual(len(running_acks), 1)

    def test_terminal_ack_does_not_launch(self):
        for state in ('blocked', 'uncertain', 'failed', 'succeeded', None):
            with self.subTest(state=state):
                temporary = tempfile.TemporaryDirectory()
                hub = FakeHub()
                hub.commands.append(self.command())
                hub.update = lambda value: {'id': FIRST, 'state': state}
                worker = RemoteReplyWorker(hub, SimpleNamespace(token='synthetic'), Path(temporary.name),
                                           executor_factory=FakeExecutor, tool_finder=lambda: ['codex'])
                try:
                    worker._tick()
                    self.assertEqual(worker.executor.starts, [])
                finally:
                    worker.close()
                    temporary.cleanup()

    def test_old_404_update_is_retired_without_blocking_poll(self):
        worker = self.create()
        worker.ledger[FIRST] = {'state': 'succeeded', 'pending': True, 'updated': 1}
        def retired(value):
            try:
                raise HTTPError('https://test.invalid', 404, 'retired', {}, None)
            except HTTPError as cause:
                raise RuntimeError('sanitized hub error') from cause

        self.hub.update = retired
        worker._tick()
        worker._tick()
        self.assertFalse(worker.ledger[FIRST]['pending'])
        self.assertTrue(worker.ledger[FIRST]['retired'])
        self.assertEqual(sum(path.endswith('/poll') for path, _ in self.hub.calls), 2)
        self.assertEqual(sum(path.endswith('/update') for path, _ in self.hub.calls), 1)

    def test_failed_old_update_does_not_starve_cancellation_or_newer_ack(self):
        worker = self.create()
        worker.ledger[FIRST] = {'state': 'succeeded', 'pending': True, 'updated': 1}
        worker.ledger[SECOND] = {'state': 'succeeded', 'pending': True, 'updated': 2}
        self.hub.canceled = [SECOND]

        def update(value):
            if value['id'] == FIRST:
                raise TimeoutError('old unavailable record')
            return {'id': value['id'], 'state': value['state']}

        self.hub.update = update
        worker._tick()
        worker._tick()
        self.assertEqual(worker.executor.cancels, [SECOND, SECOND])
        self.assertFalse(worker.ledger[SECOND]['pending'])
        self.assertTrue(worker.ledger[FIRST]['pending'])
        self.assertTrue(self.hub.calls[0][0].endswith('/poll'))

    def test_401_at_poll_stops_active_executor_and_preserves_uncertainty(self):
        worker = self.create()
        worker.ledger[FIRST] = {'state': 'running', 'pending': False, 'updated': 1}
        self.hub.poll_error = HTTPError('https://test.invalid', 401, 'revoked', {}, None)
        with self.assertRaises(HTTPError):
            worker._tick()
        self.assertTrue(worker.stop.is_set())
        self.assertEqual(worker.executor.closed, 1)
        self.assertEqual(worker.ledger[FIRST]['state'], 'uncertain')
        worker._emit({'id': FIRST, 'state': 'succeeded'})
        self.assertEqual(worker.ledger[FIRST]['state'], 'uncertain')
        worker.close()
        self.assertEqual(worker.executor.closed, 1)

    def test_401_after_durable_receipt_never_launches(self):
        worker = self.create()
        self.hub.commands.append(self.command())
        self.hub.update = lambda value: (_ for _ in ()).throw(HTTPError('https://test.invalid', 401, 'revoked', {}, None))
        with self.assertRaises(HTTPError):
            worker._tick()
        self.assertEqual(worker.executor.starts, [])
        self.assertTrue(worker.stop.is_set())
        self.assertEqual(worker.executor.closed, 1)

    def test_restart_keeps_running_and_received_ids_as_nonreplayable_tombstones(self):
        (self.state / 'reply-ledger.json').write_text(json.dumps({
            FIRST: {'state': 'running', 'pending': False, 'updated': 1},
            SECOND: {'state': 'received', 'pending': False, 'updated': 2},
        }))
        worker = self.create()
        self.hub.commands.extend([self.command(FIRST), self.command(SECOND)])
        worker._tick()
        worker._tick()
        self.assertEqual(worker.executor.starts, [])
        self.assertEqual(worker.ledger[FIRST]['state'], 'uncertain')
        self.assertEqual(worker.ledger[SECOND]['state'], 'uncertain')

    def test_cancel_in_same_poll_prevents_launch(self):
        worker = self.create()
        self.hub.commands.append(self.command())
        self.hub.canceled = [FIRST]
        worker._tick()
        self.assertEqual(worker.executor.starts, [])
        self.assertEqual(worker.ledger[FIRST]['state'], 'blocked')

    def test_full_ledger_never_discards_old_tombstones_to_execute_new_commands(self):
        worker = self.create()
        worker.ledger[FIRST] = {'state': 'succeeded', 'pending': False, 'updated': 1}
        worker.ledger[SECOND] = {'state': 'uncertain', 'pending': False, 'updated': 1}
        third = '33333333-3333-4333-8333-333333333333'
        self.hub.commands.append(self.command(third))
        with patch('agent_monitor.remote_replies.MAX_LEDGER', 2):
            worker._tick()
        self.assertEqual(set(worker.ledger), {FIRST, SECOND})
        self.assertEqual(worker.executor.starts, [])

    def test_oversized_reply_is_blocked_before_running_ack(self):
        worker = self.create()
        command = self.command()
        command['text'] = 'x' * 8001
        self.hub.commands.append(command)
        worker._tick()
        self.assertEqual(worker.executor.starts, [])
        self.assertEqual(worker.ledger[FIRST]['state'], 'blocked')
        self.assertFalse(any(path.endswith('/update') and value['state'] == 'running' for path, value in self.hub.calls))


if __name__ == '__main__':
    unittest.main()
