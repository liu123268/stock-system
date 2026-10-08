"""Directory coverage and publication failure boundaries; no network or database."""
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from stock_directory_coverage import REQUIRED_SOURCES, validate_sources

spec = importlib.util.spec_from_file_location('directory_sync', Path(__file__).with_name('sync-stock-directory.py'))
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)
probe_spec = importlib.util.spec_from_file_location('directory_probe', Path(__file__).with_name('probe-stock-directory.py'))
probe = importlib.util.module_from_spec(probe_spec)
probe_spec.loader.exec_module(probe)
TEST_ROOT = Path(__file__).resolve().parents[1] / 'target' / 'directory-tests'
TEST_ROOT.mkdir(parents=True, exist_ok=True)


class CoverageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=TEST_ROOT)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.started = datetime.now(timezone.utc) - timedelta(seconds=1)
        self.report = [{'market': source, 'status': 'ok', 'checked_at': datetime.now(timezone.utc).isoformat()}
                       for source in REQUIRED_SOURCES]
        self.write('report.json', self.report)
        self.write('CNINFO_CODES.txt', {'stockList': [{'code': '920002', 'category': 'A股', 'zwjc': '万达轴承'}]})
        self.write('BSE_OFFICIAL.json', {'provider': 'BSE_OFFICIAL', 'total': 1, 'rows': [{'code': '920002', 'name': '万达轴承'}]})

    def write(self, name, value):
        (self.directory / name).write_text(json.dumps(value), encoding='utf-8')

    def test_matching_code_sets_pass(self):
        self.assertEqual(validate_sources(self.directory, self.started)['bseIdentities'], 1)

    def test_missing_new_stock_fails_instead_of_accepting_subset(self):
        self.write('BSE_OFFICIAL.json', {'provider': 'BSE_OFFICIAL', 'total': 2, 'rows': [{'code': '920002', 'name': '万达轴承'},
                                                     {'code': '920999', 'name': '新增公司'}]})
        with self.assertRaisesRegex(ValueError, 'missing from directory'):
            validate_sources(self.directory, self.started)

    def test_missing_comparison_identity_fails(self):
        self.write('BSE_OFFICIAL.json', {'provider': 'BSE_OFFICIAL', 'total': 0, 'rows': []})
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            validate_sources(self.directory, self.started)

    def test_known_exclusion_requires_matching_identity(self):
        self.write('CNINFO_CODES.txt', {'stockList': [{'code': '920002', 'category': 'A股', 'zwjc': '万达轴承'},
                                                     {'code': '920305', 'category': 'A股', 'zwjc': '其他公司'}]})
        with self.assertRaisesRegex(ValueError, 'Unexplained'):
            validate_sources(self.directory, self.started)

    def test_known_historical_identity_is_retained_without_claiming_current_listing(self):
        self.write('CNINFO_CODES.txt', {'stockList': [{'code': '920002', 'category': 'A股', 'zwjc': '万达轴承'},
                                                     {'code': '920305', 'category': 'A股', 'zwjc': '云创退'}]})
        result = validate_sources(self.directory, self.started)
        self.assertEqual(result['bseIdentities'], 2)
        self.assertEqual(result['bseCurrentlyListed'], 1)
        self.assertEqual(result['bseRetainedOutsideCurrentList'], {'920305': '云创退'})

    def test_stale_source_cannot_reuse_old_download(self):
        self.report[0]['checked_at'] = (self.started - timedelta(days=1)).isoformat()
        self.write('report.json', self.report)
        with self.assertRaisesRegex(ValueError, 'Stale'):
            validate_sources(self.directory, self.started)


class PublicationTests(unittest.TestCase):
    def test_failed_collection_keeps_success_pointer_and_records_failure(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            state = Path(temporary)
            previous = {'status': 'APPLIED', 'runId': 'previous-success'}
            pipeline.atomic_json(state / 'last-success.json', previous)
            with patch.object(pipeline.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, ['probe'])):
                result = pipeline.run(state)
            self.assertEqual(result['status'], 'FAILED')
            self.assertEqual(result['phase'], 'COLLECT')
            self.assertEqual(pipeline.read_json(state / 'last-success.json'), previous)
            self.assertEqual(pipeline.read_json(state / 'last-run.json')['errorType'], 'CalledProcessError')

    def test_import_receipt_mismatch_cannot_publish_success(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            state = Path(temporary)
            previous = {'status': 'APPLIED', 'runId': 'previous-success'}
            pipeline.atomic_json(state / 'last-success.json', previous)

            def fake_process(command, **kwargs):
                if 'prepare-stock-directory.py' in command[1]:
                    raw = Path(command[-1])
                    pipeline.atomic_json(raw / 'directory.json', {'stocks': [{}], 'generatedAt': '2026-10-07T00:00:00Z'})
                return subprocess.CompletedProcess(command, 0, b'{"status":"APPLIED","sha256":"wrong","rows":1}', b'')

            with patch.object(pipeline.subprocess, 'run', side_effect=fake_process), \
                    patch.object(pipeline, 'validate_sources', return_value={'bseComparisonProvider': 'BSE_OFFICIAL'}), \
                    patch.object(pipeline, 'validate_directory', return_value={'BSE': 1}):
                result = pipeline.run(state, ['java', 'import'])
            self.assertEqual(result['status'], 'FAILED')
            self.assertEqual(result['phase'], 'IMPORT')
            self.assertEqual(pipeline.read_json(state / 'last-success.json'), previous)


class OfficialTransportTests(unittest.TestCase):
    def page(self, number=0, total=1, pages=1, codes=('920002',)):
        data = {'number': number, 'totalElements': total, 'totalPages': pages,
                'content': [{'xxzqdm': code, 'xxzqjc': 'sample'} for code in codes]}
        return ('null(' + json.dumps([data]) + ')').encode()

    def test_official_probe_keeps_provider_and_url_evidence(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT) as temporary:
            official = {'provider': 'BSE_OFFICIAL', 'pages': 1, 'total': 1, 'rows': [{'code': '920002', 'name': 'sample'}]}
            with patch.object(probe, 'OUT', Path(temporary)), patch.object(probe, 'bse_official', return_value=official):
                result = probe.probe('BSE_OFFICIAL')
            self.assertNotEqual(result['status'], 'failed')
            self.assertEqual(result['provider'], 'BSE_OFFICIAL')
            self.assertIn('www.bse.cn', result['url'])

    def test_page_and_api_share_session_and_result_has_only_identities(self):
        with patch.object(probe, 'fetch', side_effect=[b'<html>', self.page(), self.page()]) as request:
            result = probe.bse_official()
        self.assertEqual(result['rows'], [{'code': '920002', 'name': 'sample'}])
        sessions = [call.kwargs['opener'] for call in request.call_args_list]
        self.assertTrue(all(session is sessions[0] for session in sessions))

    def test_missing_official_page_cannot_be_accepted(self):
        replies = [b'<html>', self.page(total=2, pages=2), self.page(number=1, total=2, pages=2, codes=())]
        with patch.object(probe, 'fetch', side_effect=replies):
            with self.assertRaisesRegex(ValueError, 'ended early'):
                probe.bse_official()

    def test_changed_official_count_cannot_publish_success(self):
        replies = [b'<html>', self.page(), self.page(total=2)]
        with patch.object(probe, 'fetch', side_effect=replies):
            with self.assertRaisesRegex(ValueError, 'changed during collection'):
                probe.bse_official()


if __name__ == '__main__':
    unittest.main()
