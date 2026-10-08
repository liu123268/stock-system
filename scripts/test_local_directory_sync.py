"""Windows runner boundaries. No network or database; temporary files belong to this run."""
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

if os.name == 'nt':
    import msvcrt
    spec = importlib.util.spec_from_file_location('local_runner', Path(__file__).with_name('run-local-directory-sync.py'))
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
TEST_ROOT = Path(__file__).resolve().parents[1] / 'target' / 'local-directory-tests'
TEST_ROOT.mkdir(parents=True, exist_ok=True)


@unittest.skipUnless(os.name == 'nt', 'Windows scheduled runner boundaries')
class LocalRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=TEST_ROOT)
        self.addCleanup(self.temporary.cleanup)
        self.runtime = Path(self.temporary.name)
        self.pipeline = Mock()
        self.pipeline.run.return_value = {'status': 'VERIFIED'}

    def invoke(self, **kwargs):
        with patch.object(runner, 'load_pipeline', return_value=self.pipeline), \
                patch.object(runner, 'read_runtime', return_value={'java': 'java.exe', 'jar': 'prepared.jar'}), \
                patch.object(runner, 'user_database_password', return_value='fake-test-setting'), \
                patch.dict(runner.os.environ):
            return runner.run(self.runtime, **kwargs)

    def success(self, finished, database='stock_system'):
        state = self.runtime / 'state'
        state.mkdir()
        (state / 'last-success.json').write_text(json.dumps({
            'status': 'APPLIED', 'finishedAt': finished.isoformat(), 'import': {'database': database}}))
        return state

    def test_today_is_compared_in_china_time_even_when_receipt_is_utc(self):
        now = datetime(2026, 10, 9, 7, tzinfo=runner.CHINA)
        state = self.success(datetime(2026, 10, 8, 22, 30, tzinfo=timezone.utc))
        self.assertTrue(runner.already_applied_today(state, now))

    def test_yesterday_or_another_database_cannot_suppress_todays_run(self):
        now = datetime(2026, 10, 9, 7, tzinfo=runner.CHINA)
        state = self.success(now - timedelta(days=1))
        self.assertFalse(runner.already_applied_today(state, now))
        (state / 'last-success.json').write_text(json.dumps({
            'status': 'APPLIED', 'finishedAt': now.isoformat(), 'import': {'database': 'stock_system_test'}}))
        self.assertFalse(runner.already_applied_today(state, now))

    def test_verification_pins_local_test_catalog_and_rolls_back_mode(self):
        self.invoke(verify=True)
        state, command = self.pipeline.run.call_args.args
        self.assertEqual(state.name, 'verification')
        self.assertEqual(command[-2:], ['test', 'verify'])
        self.assertIn('jdbc:mysql://localhost:3306/stock_system_test?', command[1])
        self.assertNotIn('prod', command)

    def test_manual_development_operation_pins_local_host_despite_inherited_url(self):
        with patch.dict(runner.os.environ, {'SPRING_DATASOURCE_URL': 'jdbc:mysql://remote-host/stock_system'}):
            self.invoke(force=True)
        command = self.pipeline.run.call_args.args[1]
        self.assertEqual(command[-2:], ['dev', 'apply'])
        self.assertIn('jdbc:mysql://localhost:3306/stock_system?', command[1])
        self.assertNotIn('remote-host', ' '.join(command))

    def test_already_applied_today_skips_collection_and_import(self):
        morning = datetime(2026, 10, 9, 7, tzinfo=runner.CHINA)
        self.success(morning)
        with patch.object(runner, 'datetime', wraps=datetime) as clock:
            clock.now.return_value = morning
            self.assertEqual(self.invoke()['status'], 'SKIPPED_ALREADY_APPLIED')
        self.pipeline.run.assert_not_called()

    def test_login_before_the_daily_time_does_not_start_an_early_sync(self):
        with patch.object(runner, 'datetime', wraps=datetime) as clock:
            clock.now.return_value = datetime(2026, 10, 9, 6, 29, tzinfo=runner.CHINA)
            self.assertEqual(self.invoke()['status'], 'SKIPPED_NOT_DUE')
        self.pipeline.run.assert_not_called()

    def test_another_windows_runner_blocks_collection_before_mysql(self):
        state = self.runtime / 'verification'
        state.mkdir()
        with (state / 'sync.lock').open('w+b') as owner:
            owner.write(b'0')
            owner.flush()
            owner.seek(0)
            msvcrt.locking(owner.fileno(), msvcrt.LK_NBLCK, 1)
            try:
                self.assertEqual(self.invoke(verify=True)['status'], 'SKIPPED_RUNNING')
                self.pipeline.run.assert_not_called()
            finally:
                owner.seek(0)
                msvcrt.locking(owner.fileno(), msvcrt.LK_UNLCK, 1)


if __name__ == '__main__':
    unittest.main()
