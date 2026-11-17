"""Synthetic desktop controller tests: no live network, config, logs or window."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch

from agent_monitor import desktop_controller as d
from agent_monitor import remote_agent as c
from agent_monitor.usage_ledger import UsageLedger

SERVER = "https://monitor.example.test"
REQUEST = "r" * 32
SECRET = "s" * 40
TOKEN = "synthetic-token-do-not-render"


class Client:
    def __init__(self, results):
        self.server = SERVER
        self.results = list(results)
        self.calls = []
    def post(self, endpoint, payload):
        self.calls.append((endpoint, payload))
        result = self.results.pop(0)
        if isinstance(result, BaseException):
            raise result
        return result


def start_reply(**changes):
    return dict(request_id=REQUEST, poll_secret=SECRET,
                verification_url=SERVER + "/#/pairing-confirm/" + REQUEST, interval=3, **changes)


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.state = Path(self.tmp.name)
        self.clock = [0.0]
        self.saved = []
    def session(self, *results):
        client = Client(results)
        session = d.PairingSession(self.state, client_factory=lambda _: client,
            clock=lambda: self.clock[0], save_new=lambda config, _, **options: self.saved.append(config))
        return session, client
    def test_only_verified_url_exposed_and_phone_approval_required(self):
        s, client = self.session(start_reply(), {"status": "pending"},
            {"status": "approved", "device_id": "synthetic-device", "token": TOKEN})
        s.start(SERVER, "Synthetic desktop")
        self.assertNotIn(SECRET, json.dumps(s.view()))
        self.assertEqual(s.view()["verification_url"], start_reply()["verification_url"])
        s.poll();self.assertEqual(len(client.calls), 1)
        self.clock[0] = 3;s.poll();self.assertEqual(self.saved, [])
        self.clock[0] = 6;s.poll()
        self.assertEqual(s.view()["status"], "approved")
        self.assertEqual(len(self.saved), 1)
        self.assertEqual(self.saved[0].token, TOKEN)
        self.assertIsNone(s.view()["verification_url"])
        self.assertNotIn(TOKEN, json.dumps(s.view()))
    def test_http_or_cross_origin_qr_rejected(self):
        s, client = self.session()
        with self.assertRaises(c.RemoteAgentError):s.start("http://example.test", "Synthetic")
        self.assertEqual(client.calls, [])
        bad = start_reply();bad["verification_url"] = "https://elsewhere.example.test/"
        s, _ = self.session(bad);s.start(SERVER, "Synthetic")
        self.assertEqual(s.view()["status"], "failed")
        self.assertIsNone(s.view()["verification_url"])
    def test_bad_identifier_secret_interval_cannot_reach_qr(self):
        for key, value in (("request_id", "short"), ("poll_secret", "bad"), ("interval", True), ("interval", 2)):
            with self.subTest(key=key):
                bad=start_reply();bad[key]=value
                s,_=self.session(bad);s.start(SERVER,"Synthetic")
                self.assertEqual(s.view()["status"],"failed")
                self.assertIsNone(s.view()["verification_url"])
    def test_expiry_stops_without_poll_or_new_request(self):
        s, client = self.session(start_reply());s.start(SERVER, "Synthetic")
        self.clock[0] = 600;s.poll();s.poll()
        self.assertEqual(s.view()["status"], "expired")
        self.assertEqual(len(client.calls), 1)
        with self.assertRaises(c.RemoteAgentError):s.start(SERVER, "Synthetic")
    def test_backoff_is_bounded_and_terminal_denial_not_retried(self):
        s, client = self.session(start_reply(), c.RemoteAgentError("synthetic"), c.PairingRequestError("synthetic"))
        s.start(SERVER, "Synthetic");self.clock[0]=3;s.poll()
        self.assertEqual(s.view()["status"], "retrying")
        self.clock[0]=4;s.poll();self.assertEqual(len(client.calls),2)
        self.clock[0]=9;s.poll();s.poll()
        self.assertEqual(s.view()["status"], "failed")
        self.assertEqual(len(client.calls),3)
    def test_cancel_in_flight_never_persists_approved_secret(self):
        s,client=self.session(start_reply());s.start(SERVER,"Synthetic")
        def response(*_):
            s.cancel()
            return {"status":"approved","device_id":"synthetic","token":TOKEN}
        client.post=response;self.clock[0]=3;s.poll()
        self.assertEqual(s.view()["status"],"cancelled")
        self.assertEqual(self.saved,[])
    def test_existing_pairing_blocks_before_network(self):
        path=self.state/c.CONFIG_FILENAME;path.write_bytes(b'synthetic untouched')
        s,client=self.session();s.start(SERVER,"Synthetic")
        self.assertEqual(s.view()["status"],"existing")
        self.assertEqual(client.calls,[])
        self.assertEqual(path.read_bytes(),b'synthetic untouched')
    def test_new_config_publish_does_not_overwrite_existing(self):
        cfg=c.AgentConfig(SERVER,"synthetic",TOKEN,"Synthetic")
        d._save_new_config(cfg,self.state)
        path=self.state/c.CONFIG_FILENAME;before=path.read_bytes()
        with self.assertRaises(c.RemoteAgentError):d._save_new_config(cfg,self.state)
        self.assertEqual(before,path.read_bytes())
        self.assertEqual(c.load_config(self.state).token,TOKEN)
        self.assertEqual(list(self.state.glob('.desktop-pair-*')),[])
    def test_cancel_during_staging_prevents_atomic_publication(self):
        event=threading.Event();original=c.save_config
        def staging(config,path):
            original(config,path)
            event.set()
        with patch.object(c,'save_config',side_effect=staging),self.assertRaises(d._PairingCancelled):
            d._save_new_config(c.AgentConfig(SERVER,'synthetic',TOKEN),self.state,cancelled=event.is_set)
        self.assertFalse((self.state/c.CONFIG_FILENAME).exists())
        self.assertEqual(list(self.state.glob('.desktop-pair-*')),[])
    def test_cleanup_error_after_publish_remains_approved(self):
        original=tempfile.TemporaryDirectory
        class CleanupWarning:
            def __init__(self,**kwargs):
                self.temp=original(**kwargs);self.name=self.temp.name
            def cleanup(self):
                self.temp.cleanup()
                raise OSError('synthetic cleanup failure')
        s,client=self.session(start_reply(),{'status':'approved','device_id':'synthetic','token':TOKEN})
        s._save=d._save_new_config;s.start(SERVER,'Synthetic');self.clock[0]=3
        with patch.object(d.tempfile,'TemporaryDirectory',CleanupWarning):s.poll()
        self.assertEqual(s.view()['status'],'approved')
        self.assertFalse(s.view()['temporary_cleanup_verified'])
        self.assertTrue((self.state/c.CONFIG_FILENAME).is_file())
        self.assertIsNone(s.view()['verification_url'])


class PresentationTests(unittest.TestCase):
    def summary(self, **provider):
        return {"day":"2026-10-30","providers":[dict(tool="codex",**provider)]}
    def test_missing_values_hide_and_actual_zero_retained(self):
        self.assertEqual(d.present_usage(self.summary(quotas=[])),[])
        cards=d.present_usage(self.summary(quotas=[{"window_minutes":10080,"used_percent":0}]))
        self.assertEqual(cards[0]["quotas"][0]["used_percent"],0)
        self.assertEqual(cards[0]["quotas"][0]["label"],"7 天")
        self.assertEqual(len(cards[0]["quotas"]),1)
    def test_expired_or_foreign_source_hidden_no_assumed_five_hours(self):
        cards=d.present_usage(self.summary(today_tokens=10,quotas=[
            {"window_minutes":300,"used_percent":23,"resets_at":10},
            {"window_minutes":10080,"used_percent":12,"source_device_id":"other"}]),now=10)
        self.assertEqual(cards[0]["quotas"],[])
    def test_stale_unexpired_is_explicitly_retained(self):
        cards=d.present_usage(self.summary(quotas=[{"window_minutes":10080,"used_percent":8,"resets_at":11,"stale":True}]),now=10)
        self.assertTrue(cards[0]["quotas"][0]["stale"])
    def test_model_distribution_includes_unknown_in_same_day_denominator(self):
        history={"days":[{"day":"2026-10-30","tokens":100,"models":[{"model":"gpt-5.4","tokens":60}],"unknown_model_tokens":40}]}
        card=d.present_usage(self.summary(today_tokens=100,history=history))[0]
        self.assertEqual([m["percent"] for m in card["models"]],[60,40])
        self.assertEqual(sum(m["tokens"] for m in card["models"]),100)
    def test_mismatched_or_unknown_only_models_hidden(self):
        for models,unknown in (([],100),([{"model":"gpt-5.4","tokens":60}],30)):
            history={"days":[{"day":"2026-10-30","tokens":100,"models":models,"unknown_model_tokens":unknown}]}
            self.assertEqual(d.present_usage(self.summary(today_tokens=100,history=history))[0]["models"],[])
    def test_malformed_numeric_and_arbitrary_labels_not_rendered(self):
        for value in (True, None, float('nan'), -1, 101):
            self.assertEqual(d.present_usage(self.summary(quotas=[{"window_minutes":300,"used_percent":value}])),[])
        row={"window_minutes":300,"used_percent":3,"bucket_label":TOKEN,"model":SECRET}
        self.assertNotIn(TOKEN,json.dumps(d.present_usage(self.summary(quotas=[row]))))
        self.assertNotIn(SECRET,json.dumps(d.present_usage(self.summary(quotas=[row]))))
    def test_demo_is_pure_and_contains_both_distinct_sections(self):
        with patch('sqlite3.connect',side_effect=AssertionError),patch.object(c.HubClient,'post',side_effect=AssertionError):
            cards=d.present_usage(d.demo_summary())
        self.assertEqual([c['tool'] for c in cards],['codex','claude'])
        self.assertTrue(all(c['quotas'] and c['models'] for c in cards))


class ReadOnlyTests(unittest.TestCase):
    def test_missing_db_not_created(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'missing.sqlite3'
            self.assertIsNone(d.read_usage(path));self.assertFalse(path.exists())
    def test_existing_ledger_read_without_initialization_scan_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'usage.sqlite3'
            ledger=UsageLedger(path);ledger.close()
            before=hashlib.sha256(path.read_bytes()).hexdigest()
            with patch.object(UsageLedger,'__init__',side_effect=AssertionError),patch.object(UsageLedger,'scan',side_effect=AssertionError):
                summary=d.read_usage(path,now=datetime(2026,10,30,tzinfo=timezone.utc))
            self.assertEqual(len(summary['providers']),2)
            self.assertEqual(before,hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(d.present_usage(summary),[])


if __name__ == '__main__':unittest.main()
