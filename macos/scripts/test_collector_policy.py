"""Portable local-mode checks; no launchctl, CLI tools, pairing or remote hosts."""
from contextlib import ExitStack, nullcontext
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


service = module('collector_service')
policy = service.policy


class PolicyFileTests(unittest.TestCase):
    def private_io(self):
        # Windows runs parser/atomic-replacement tests with POSIX metadata mocked;
        # permission and symlink validation are exercised separately below.
        stack = ExitStack()
        real_fstat = os.fstat
        stack.enter_context(patch.object(policy, '_owned'))
        stack.enter_context(patch.object(policy.os, 'getuid', return_value=0, create=True))
        stack.enter_context(patch.object(policy.os, 'fchmod', create=True))
        stack.enter_context(patch.object(policy.os, 'fstat', side_effect=lambda fd: SimpleNamespace(
            st_mode=stat.S_IFREG | 0o600, st_uid=0, st_size=real_fstat(fd).st_size)))
        return stack

    def test_missing_legacy_file_defaults_to_status_only_without_writing(self):
        with tempfile.TemporaryDirectory() as folder, self.private_io():
            path = Path(folder) / 'mode.json'
            self.assertEqual(policy.read_mode(path), 'status-only')
            self.assertFalse(path.exists())

    def test_modes_roundtrip_with_exact_schema_and_private_atomic_write(self):
        with tempfile.TemporaryDirectory() as folder, self.private_io():
            path = Path(folder) / 'mode.json'
            for mode in policy.MODES:
                policy.write_mode(path, mode)
                self.assertEqual(policy.read_mode(path), mode)
                self.assertEqual(json.loads(path.read_text()), {'version': 1, 'mode': mode})
            policy.os.fchmod.assert_called_with(unittest.mock.ANY, 0o600)
            self.assertEqual([item.name for item in Path(folder).iterdir()], ['mode.json'])

    def test_failed_replace_keeps_previous_mode_and_removes_temporary_file(self):
        with tempfile.TemporaryDirectory() as folder, self.private_io():
            path = Path(folder) / 'mode.json'
            policy.write_mode(path, 'status-only')
            before = path.read_bytes()
            with patch.object(policy.os, 'replace', side_effect=OSError('synthetic')):
                with self.assertRaises(OSError):
                    policy.write_mode(path, 'full')
            self.assertEqual(path.read_bytes(), before)
            self.assertEqual([item.name for item in Path(folder).iterdir()], ['mode.json'])

    def test_invalid_or_unknown_policy_never_grants_full_access(self):
        values = [None, [], {}, {'version': True, 'mode': 'full'}, {'version': 2, 'mode': 'full'},
                  {'version': 1, 'mode': True}, {'version': 1, 'mode': 'FULL'},
                  {'version': 1, 'mode': 'full', 'server_override': True}]
        with tempfile.TemporaryDirectory() as folder, self.private_io():
            path = Path(folder) / 'mode.json'
            for value in values:
                path.write_text(json.dumps(value))
                with self.subTest(value=value), self.assertRaises(ValueError):
                    policy.read_mode(path)
            path.write_text('x' * (policy.MAX_BYTES + 1))
            with self.assertRaises(ValueError):
                policy.read_mode(path)

    def test_private_path_rejects_foreign_owner_public_bits_and_symlink_parent(self):
        path = Mock()
        parent = Mock()
        path.parents = [parent]
        path.is_symlink.return_value = parent.is_symlink.return_value = False
        with patch.object(policy.os, 'getuid', return_value=501, create=True):
            for mode, uid in ((stat.S_IFREG | 0o600, 502), (stat.S_IFREG | 0o644, 501),
                              (stat.S_IFIFO | 0o600, 501)):
                path.stat.return_value = SimpleNamespace(st_mode=mode, st_uid=uid)
                with self.subTest(mode=mode, uid=uid), self.assertRaises(ValueError):
                    policy._owned(path)
            path.stat.return_value = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=501)
            policy._owned(path)
            parent.is_symlink.return_value = True
            with self.assertRaises(ValueError):
                policy._owned(path)

    def test_results_never_allows_replies_and_full_does_not_override_output_account_setting(self):
        self.assertEqual(policy.run_options('status-only'), {'status_only': True, 'allow_replies': False})
        self.assertEqual(policy.run_options('results'), {'status_only': False, 'allow_replies': False})
        self.assertEqual(policy.run_options('full'), {'status_only': False, 'allow_replies': True})
        for value in ('all', '', True, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                policy.run_options(value)


class ModeTransitionTests(unittest.TestCase):
    def harness(self, *, mode='status-only', loaded=True, fail_stop=False, fail_start=False, fail_write=False):
        stack = ExitStack()
        self.addCleanup(stack.close)
        state = {'mode': mode, 'events': [], 'starts': 0}
        locations = service.paths(Path('/Users/example'))
        stack.enter_context(patch.object(service, 'current_runtime', return_value=Path('/runtime')))
        stack.enter_context(patch.object(policy, 'mode_lock', return_value=nullcontext()))
        stack.enter_context(patch.object(policy, 'read_mode', side_effect=lambda _: state['mode']))
        stack.enter_context(patch.object(service, 'service_loaded', return_value=loaded))

        def stop(_):
            state['events'].append('stop')
            if fail_stop:
                raise RuntimeError('old child still stopping')

        def write(_, value):
            state['events'].append('write:' + value)
            if fail_write and value != mode:
                raise OSError('synthetic atomic-write failure')
            state['mode'] = value

        def start(_):
            state['events'].append('start:' + state['mode'])
            state['starts'] += 1
            if fail_start and state['starts'] == 1:
                raise RuntimeError('synthetic start failure')

        stack.enter_context(patch.object(service, 'stop', side_effect=stop))
        stack.enter_context(patch.object(policy, 'write_mode', side_effect=write))
        stack.enter_context(patch.object(service, 'start', side_effect=start))
        return locations, state

    def test_running_switch_waits_for_stop_before_write_and_restart(self):
        locations, state = self.harness(mode='full')
        service.configure_mode(locations, 'results')
        self.assertEqual(state['events'], ['stop', 'write:results', 'start:results'])
        self.assertEqual(state['mode'], 'results')

    def test_paused_switch_stays_paused(self):
        locations, state = self.harness(loaded=False)
        service.configure_mode(locations, 'full')
        self.assertEqual(state['events'], ['stop', 'write:full'])

    def test_same_mode_does_not_restart_or_write(self):
        locations, state = self.harness(mode='results')
        service.configure_mode(locations, 'results')
        self.assertEqual(state['events'], [])

    def test_failed_stop_never_changes_policy_or_starts_new_worker(self):
        locations, state = self.harness(mode='full', fail_stop=True)
        with self.assertRaises(RuntimeError):
            service.configure_mode(locations, 'status-only')
        self.assertEqual(state['mode'], 'full')
        self.assertEqual(state['events'], ['stop'])

    def test_failed_new_start_restores_previous_mode_and_running_state(self):
        locations, state = self.harness(fail_start=True)
        with self.assertRaisesRegex(RuntimeError, 'previous policy restored'):
            service.configure_mode(locations, 'full')
        self.assertEqual(state['mode'], 'status-only')
        self.assertEqual(state['events'], ['stop', 'write:full', 'start:full', 'stop',
                                          'write:status-only', 'start:status-only'])

    def test_failed_write_restores_paused_policy_without_starting(self):
        locations, state = self.harness(loaded=False, fail_write=True)
        with self.assertRaisesRegex(RuntimeError, 'previous policy restored'):
            service.configure_mode(locations, 'results')
        self.assertEqual(state['mode'], 'status-only')
        self.assertNotIn('start:status-only', state['events'])


class UpgradeTests(unittest.TestCase):
    def test_upgrade_preserves_mode_pairing_hooks_environment_and_pause(self):
        for loaded in (False, True):
            with self.subTest(loaded=loaded), tempfile.TemporaryDirectory() as folder, ExitStack() as stack:
                root = Path(folder)
                home = root / 'home'
                project = root / 'source'
                (project / 'agent_monitor').mkdir(parents=True)
                (project / 'agent_monitor/remote_agent.py').write_text('# synthetic source')
                locations = service.paths(home)
                for key in ('base', 'state', 'hooks'):
                    locations[key].mkdir(parents=True, exist_ok=True)
                (locations['state'] / 'config.json').write_text('{"synthetic_pairing":"keep"}')
                (locations['hooks'] / 'event.json').write_text('{"synthetic_hook":"keep"}')
                locations['owner'].write_text(json.dumps({'owner': service.OWNER}))
                locations['mode'].write_text('{"version":1,"mode":"results"}')
                locations['environment'].write_text('{"HTTPS_PROXY":"http://retained.invalid"}')
                preserved = {path: path.read_bytes() for path in
                             (locations['mode'], locations['state'] / 'config.json', locations['hooks'] / 'event.json')}
                stack.enter_context(patch.object(service, 'require_install'))
                stack.enter_context(patch.object(service, 'owned_path'))
                stack.enter_context(patch.object(service, 'private_dir', side_effect=lambda p: p.mkdir(parents=True, exist_ok=True)))
                stack.enter_context(patch.object(service, 'write_private', side_effect=lambda p, d: p.write_bytes(d)))
                stack.enter_context(patch.object(policy, 'read_mode', return_value='results'))
                write_mode = stack.enter_context(patch.object(policy, 'write_mode'))
                stack.enter_context(patch.object(service, 'service_loaded', return_value=loaded))
                stack.enter_context(patch.object(service, 'stop'))
                start = stack.enter_context(patch.object(service, 'start'))
                stack.enter_context(patch.object(service.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)))
                stack.enter_context(patch.dict(service.os.environ, {}, clear=True))
                service.install(home, project)
                write_mode.assert_not_called()
                self.assertEqual(start.call_count, 1 if loaded else 0)
                self.assertEqual({p: p.read_bytes() for p in preserved}, preserved)
                environment = json.loads(locations['environment'].read_text())
                self.assertEqual(environment['HTTPS_PROXY'], 'http://retained.invalid')
                release = json.loads(locations['current'].read_text())['release']
                self.assertTrue((locations['releases'] / release / 'scripts/collector_policy.py').is_file())


class StatusProtocolTests(unittest.TestCase):
    def test_status_reports_local_mode_and_explicit_supported_protocol(self):
        with tempfile.TemporaryDirectory() as folder:
            locations = service.paths(Path(folder))
            locations['state'].mkdir(parents=True)
            locations['owner'].write_text('{}')
            (locations['state'] / 'config.json').write_text('{}')
            for mode in policy.MODES:
                output = io.StringIO()
                with self.subTest(mode=mode), redirect_stdout(output), \
                        patch.object(service.os, 'getuid', return_value=501, create=True), \
                        patch.object(policy, 'read_mode', return_value=mode), \
                        patch.object(service, 'service_result', return_value=SimpleNamespace(
                            returncode=0, stdout='state = running\n')):
                    service.status(locations)
                self.assertEqual(json.loads(output.getvalue()), {
                        'installed': True, 'paired': True, 'server_scope_version': 1, 'service': 'running', 'mode': mode,
                    'mode_schema': 1, 'supported_modes': ['status-only', 'results', 'full']})

    def test_invalid_policy_produces_no_fallback_success_status(self):
        with tempfile.TemporaryDirectory() as folder:
            locations = service.paths(Path(folder))
            locations['base'].mkdir(parents=True)
            locations['owner'].write_text('{}')
            output = io.StringIO()
            with redirect_stdout(output), patch.object(policy, 'read_mode', side_effect=ValueError('invalid')), \
                    patch.object(service.os, 'getuid', return_value=501, create=True), \
                    patch.object(service, 'service_result', return_value=SimpleNamespace(returncode=1, stdout='')):
                with self.assertRaises(ValueError):
                    service.status(locations)
            self.assertEqual(output.getvalue(), '')


class MiseEnvironmentTests(unittest.TestCase):
    """Real fixture trees; Windows mocks POSIX metadata and symlink resolution only."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='monitor-mise-test-')
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name).resolve()
        self.root = self.home / '.local/share/mise/installs/node'
        self.root.mkdir(parents=True)
        self.aliases = {}
        self.executables = set()
        if os.name == 'nt':
            stack = ExitStack()
            self.addCleanup(stack.close)
            real_stat, real_lstat, real_resolve = Path.stat, Path.lstat, Path.resolve

            def resolved(path, *args, **kwargs):
                for source, target in self.aliases.items():
                    if path == source or path.is_relative_to(source):
                        path = target / path.relative_to(source)
                        break
                return real_resolve(path, *args, **kwargs)

            def metadata(method, path, *args, **kwargs):
                info = method(path, *args, **kwargs)
                return SimpleNamespace(st_uid=0, st_mode=info.st_mode & ~0o022)

            stack.enter_context(patch.object(Path, 'resolve', resolved))
            stack.enter_context(patch.object(Path, 'stat', lambda p, *a, **kw: metadata(real_stat, p, *a, **kw)))
            stack.enter_context(patch.object(Path, 'lstat', lambda p, *a, **kw: metadata(real_lstat, p, *a, **kw)))
            stack.enter_context(patch.object(service.os, 'getuid', return_value=0, create=True))
            stack.enter_context(patch.object(service.os, 'access', side_effect=lambda p, _: p in self.executables))

    def executable(self, path, content='#!/bin/sh\nexit 0\n'):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(0o755)
        self.executables.add(path)
        return path

    def runtime(self, name='24.20.0'):
        runtime = self.root / name
        self.executable(runtime / 'bin/node')
        target = self.executable(runtime / 'lib/node_modules/@openai/codex/bin/codex.js', '#!/usr/bin/env node\n')
        self.alias(runtime / 'bin/codex', target, directory=False)
        return runtime

    def alias(self, alias, target, *, directory=True):
        if os.name == 'nt':
            if directory:
                alias.mkdir(parents=True)
            else:
                alias.touch()
            self.aliases[alias] = target
        else:
            alias.symlink_to(target, target_is_directory=directory)

    def test_aliases_resolve_to_one_runtime_and_npm_symlink_stays_inside(self):
        runtime = self.runtime()
        for name in ('lts-krypton', '24.20', 'latest', 'lts', '24'):
            self.alias(self.root / name, runtime)
        self.assertEqual(service.mise_node_bins(self.home), [runtime / 'bin'])

    def test_distinct_runtimes_are_ambiguous_even_with_latest_alias(self):
        first = self.runtime('24.20.0')
        self.runtime('22.21.0')
        self.alias(self.root / 'latest', first)
        self.assertEqual(service.mise_node_bins(self.home), [])

    def test_missing_or_nonexecutable_node_does_not_advertise_codex(self):
        runtime = self.runtime()
        node = runtime / 'bin/node'
        node.chmod(0o644)
        self.executables.discard(node)
        self.assertEqual(service.mise_node_bins(self.home), [])
        node.unlink()
        self.assertEqual(service.mise_node_bins(self.home), [])

    def test_runtime_alias_and_codex_target_cannot_escape_root(self):
        outside = self.home / 'outside'
        self.executable(outside / 'bin/node')
        self.executable(outside / 'bin/codex')
        self.alias(self.root / 'latest', outside)
        self.assertEqual(service.mise_node_bins(self.home), [])
        runtime = self.runtime()
        codex = runtime / 'bin/codex'
        codex.unlink()
        self.aliases.pop(codex, None)
        self.alias(codex, outside / 'bin/codex', directory=False)
        self.assertEqual(service.mise_node_bins(self.home), [])

    def test_node_target_cannot_escape_its_runtime(self):
        runtime = self.runtime()
        outside = self.executable(self.home / 'outside-node')
        node = runtime / 'bin/node'
        node.unlink()
        self.executables.discard(node)
        self.alias(node, outside, directory=False)
        self.assertEqual(service.mise_node_bins(self.home), [])

    def test_mise_root_cannot_be_redirected(self):
        self.root.rmdir()
        outside = self.home / 'outside-node-root'
        outside.mkdir()
        self.alias(self.root, outside)
        self.assertEqual(service.mise_node_bins(self.home), [])

    def test_fixed_path_order_and_unrelated_environment_are_preserved(self):
        before = service.captured_environment(self.home, {})['PATH']
        runtime = self.runtime()
        settings = {key: 'http://fixture.invalid:8080' for key in service.PROXY_KEYS}
        settings.update(CODEX_HOME='/fixture/codex home', CLAUDE_CONFIG_DIR='/fixture/claude home')
        after = service.captured_environment(self.home, {'PATH': '/untrusted'}, settings)
        self.assertEqual(after['PATH'], before + ':' + str(runtime / 'bin'))
        self.assertEqual({key: after[key] for key in settings}, settings)
        self.assertNotIn('/untrusted', after['PATH'])

    def test_owned_metadata_rejects_foreign_writable_and_nonregular_files(self):
        path = Mock()
        for mode, uid in ((stat.S_IFREG | 0o755, 502), (stat.S_IFREG | 0o775, 501), (stat.S_IFIFO | 0o755, 501)):
            path.stat.return_value = SimpleNamespace(st_mode=mode, st_uid=uid)
            self.assertFalse(service._mise_owned(path, 501))
        path.stat.return_value = SimpleNamespace(st_mode=stat.S_IFREG | 0o755, st_uid=501)
        self.assertTrue(service._mise_owned(path, 501))

    @unittest.skipUnless(sys.platform == 'darwin', 'Real env-node shebang check requires macOS')
    def test_discovered_bin_can_launch_codex_node_shebang_without_shell_init(self):
        runtime = self.runtime()
        self.executable(runtime / 'bin/node', '#!/bin/sh\nprintf "fixture-node-ok"\n')
        discovered = service.mise_node_bins(self.home)
        self.assertEqual(discovered, [runtime / 'bin'])
        result = subprocess.run([str(discovered[0] / 'codex')], env={'PATH': str(discovered[0])},
                                capture_output=True, text=True, timeout=5, check=True)
        self.assertEqual(result.stdout, 'fixture-node-ok')


@unittest.skipUnless(sys.platform == 'darwin', 'Real POSIX policy checks require macOS')
class MacPrivatePolicyTests(unittest.TestCase):
    """Real macOS filesystem/flock checks, confined to a resolved temporary root."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='monitor-policy-test-')
        self.addCleanup(temporary.cleanup)
        # macOS may expose /var or /tmp through aliases; validate the real path.
        self.root = Path(temporary.name).resolve()
        self.root.chmod(0o700)
        self.path = self.root / 'mode.json'

    def test_real_private_roundtrip_replaces_atomically(self):
        self.assertEqual(policy.read_mode(self.path), 'status-only')
        self.assertFalse(self.path.exists())
        policy.write_mode(self.path, 'results')
        self.assertEqual(stat.S_IMODE(self.root.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        with self.path.open('rb') as previous:
            old_inode = os.fstat(previous.fileno()).st_ino
            policy.write_mode(self.path, 'full')
            self.assertNotEqual(self.path.stat().st_ino, old_inode)
            self.assertEqual(json.loads(previous.read())['mode'], 'results')
        self.assertEqual(policy.read_mode(self.path), 'full')
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(sorted(item.name for item in self.root.iterdir()), ['mode.json'])

    def test_real_public_file_or_parent_permissions_are_rejected(self):
        policy.write_mode(self.path, 'results')
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            policy.read_mode(self.path)
        with self.assertRaises(ValueError):
            policy.write_mode(self.path, 'full')
        self.path.chmod(0o600)
        self.root.chmod(0o755)
        try:
            with self.assertRaises(ValueError):
                policy.read_mode(self.path)
            with self.assertRaises(ValueError):
                policy.write_mode(self.path, 'full')
        finally:
            self.root.chmod(0o700)
        self.assertEqual(policy.read_mode(self.path), 'results')

    def test_real_file_and_parent_symlinks_are_rejected_without_target_changes(self):
        policy.write_mode(self.path, 'results')
        before = self.path.read_bytes()
        alias = self.root / 'linked-mode.json'
        alias.symlink_to(self.path)
        for operation in (lambda: policy.read_mode(alias), lambda: policy.write_mode(alias, 'full')):
            with self.assertRaises(ValueError):
                operation()
        private = self.root / 'private'
        private.mkdir(mode=0o700)
        linked_parent = self.root / 'linked-parent'
        linked_parent.symlink_to(private, target_is_directory=True)
        with self.assertRaises(ValueError):
            policy.read_mode(linked_parent / 'mode.json')
        with self.assertRaises(ValueError):
            policy.write_mode(linked_parent / 'mode.json', 'full')
        self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(list(private.iterdir()), [])

    def test_real_mode_lock_rejects_competitor_then_releases(self):
        with policy.mode_lock(self.root):
            with self.assertRaisesRegex(RuntimeError, 'in progress'):
                with policy.mode_lock(self.root):
                    self.fail('Competing lock acquired')
        self.assertEqual(stat.S_IMODE((self.root / 'mode-change.lock').stat().st_mode), 0o600)
        with policy.mode_lock(self.root):
            pass


if __name__ == '__main__':
    unittest.main()
