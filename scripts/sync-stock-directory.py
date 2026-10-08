"""One daily collection transaction. A failed run never replaces last-success.json.

The import command is a JSON argv array, not shell text. It receives directory
JSON on stdin and must return the StockDirectorySyncCommand result on stdout.
Omit --import-command-json to collect and verify without database access.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

from stock_directory_coverage import REQUIRED_SOURCES, read_json, validate_sources, validate_directory

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = Path(__file__).resolve().parent


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    os.replace(temporary, path)


def run(state_dir, command=None):
    state_dir = Path(state_dir).resolve()
    state_dir.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc)
    run_id = started.strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    run_dir = state_dir / 'runs' / run_id
    run_dir.mkdir(parents=True)
    result = {'runId': run_id, 'startedAt': started.isoformat(), 'status': 'RUNNING', 'phase': 'COLLECT'}
    try:
        # Each invocation gets a new directory. Old raw downloads cannot satisfy this run.
        with (run_dir / 'collect.log').open('w', encoding='utf-8') as log:
            subprocess.run([sys.executable, str(SCRIPTS / 'probe-stock-directory.py'),
                            '--output-dir', str(run_dir), *REQUIRED_SOURCES],
                           check=True, stdout=log, stderr=log, timeout=240)
        result['phase'] = 'COVERAGE'
        coverage = validate_sources(run_dir, started)
        with (run_dir / 'prepare.log').open('w', encoding='utf-8') as log:
            subprocess.run([sys.executable, str(SCRIPTS / 'prepare-stock-directory.py'),
                            '--input-dir', str(run_dir)], check=True,
                           stdout=log, stderr=log, timeout=120)
        directory_path = run_dir / 'directory.json'
        directory = read_json(directory_path)
        result['counts'] = validate_directory(run_dir, directory, coverage)
        directory['coverageNote'] = ('BSE: CNINFO identities cover the complete paginated official listed-company scope; '
                                     'explicitly reconciled identities outside that scope retained without inferring trade status. '
                                     'HK: official HKD Main Board/GEM equity scope reconciled in English/Chinese. '
                                     'Trading status unknown; absence never implies delisting.')
        atomic_json(directory_path, directory)
        payload = directory_path.read_bytes()
        result.update(coverage=coverage, directory=str(directory_path),
                      sha256=hashlib.sha256(payload).hexdigest(), generatedAt=directory['generatedAt'])
        if command:
            result['phase'] = 'IMPORT'
            completed = subprocess.run(command, input=payload, capture_output=True,
                                       timeout=180, check=False)
            # Spring/JVM may print informational lines. Only accept the final JSON result.
            lines = completed.stdout.decode('utf-8').strip().splitlines()
            imported = json.loads(lines[-1]) if lines else {}
            if completed.returncode or imported.get('status') not in ('APPLIED', 'VERIFIED'):
                raise RuntimeError('Directory import failed')
            if imported.get('sha256') != result['sha256'] or imported.get('rows') != len(directory['stocks']):
                raise RuntimeError('Directory import receipt mismatch')
            result['import'] = imported
            result['status'] = imported['status']
        else:
            result['status'] = 'COLLECTED'
        result['phase'] = 'DONE'
    except Exception as failure:
        result.update(status='FAILED', errorType=type(failure).__name__)
        # Errors and command argv may contain credentials; preserve only a safe failure category.
    result['finishedAt'] = datetime.now(timezone.utc).isoformat()
    atomic_json(run_dir / 'result.json', result)
    atomic_json(state_dir / 'last-run.json', result)
    if result['status'] == 'APPLIED':
        atomic_json(state_dir / 'last-success.json', result)
    elif result['status'] in ('COLLECTED', 'VERIFIED'):
        atomic_json(state_dir / 'last-verified.json', result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, default=ROOT / 'target' / 'directory-sync')
    parser.add_argument('--import-command-json')
    options = parser.parse_args()
    command = json.loads(options.import_command_json) if options.import_command_json else None
    if command is not None and (not isinstance(command, list) or not command
                                or not all(isinstance(arg, str) and arg for arg in command)):
        parser.error('Import command must be a nonempty JSON argv array')
    receipt = run(options.state_dir, command)
    print(json.dumps(receipt, ensure_ascii=True))
    raise SystemExit(1 if receipt['status'] == 'FAILED' else 0)
