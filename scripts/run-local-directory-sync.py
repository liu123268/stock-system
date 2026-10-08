"""Windows daily runner. Uses a prepared runtime, never starts Spring or market clients."""
import argparse
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import msvcrt
import os
from pathlib import Path
import sys
import winreg
import zipfile

CHINA = timezone(timedelta(hours=8))


def load_pipeline(script_dir):
    spec = importlib.util.spec_from_file_location('directory_pipeline', script_dir / 'sync-stock-directory.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def user_database_password():
    # Scheduled tasks may inherit an older environment. Read the existing user setting;
    # never put its value in task XML, command arguments, runtime.json, or receipts.
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as key:
            value, _ = winreg.QueryValueEx(key, 'DB_PASSWORD')
    except FileNotFoundError:
        value = os.environ.get('DB_PASSWORD')
    if not isinstance(value, str) or not value:
        raise ValueError('DB_PASSWORD is not configured for the current Windows user')
    return value


def read_runtime(runtime):
    config = json.loads((runtime / 'runtime.json').read_text(encoding='utf-8-sig'))
    for key in ('python', 'java', 'jar'):
        if not Path(config[key]).is_file():
            raise ValueError('Missing prepared runtime file: ' + key)
    if Path(config['jar']).resolve() != (runtime / 'app.jar').resolve():
        raise ValueError('Unexpected prepared JAR location')
    with zipfile.ZipFile(config['jar']) as jar:
        if 'BOOT-INF/classes/com/stock/system/stock/directory/StockDirectorySyncCommand.class' not in jar.namelist():
            raise ValueError('Prepared JAR has no directory synchronization entry')
    return config


def already_applied_today(state, now):
    success = state / 'last-success.json'
    if not success.exists():
        return False
    receipt = json.loads(success.read_text(encoding='utf-8'))
    return (receipt.get('status') == 'APPLIED'
            and receipt.get('import', {}).get('database') == 'stock_system'
            and datetime.fromisoformat(receipt['finishedAt']).astimezone(CHINA).date() == now.date())


def run(runtime, verify=False, force=False, check=False):
    runtime = Path(runtime).resolve()
    state = runtime / ('verification' if verify else 'state')
    state.mkdir(parents=True, exist_ok=True)
    pipeline = load_pipeline(runtime / 'scripts')
    config = read_runtime(runtime)
    os.environ['DB_PASSWORD'] = user_database_password()
    if check:
        import pandas, openpyxl, opencc, pypinyin  # noqa: F401
        return {'status': 'READY', 'database': 'stock_system_test' if verify else 'stock_system'}

    # This lock protects both a manual run and the scheduled run before collection.
    # MySQL's named lock additionally protects concurrent imports from other processes.
    with (state / 'sync.lock').open('a+b') as lock:
        lock.seek(0, 2)
        if lock.tell() == 0:
            lock.write(b'0')
            lock.flush()
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            return {'status': 'SKIPPED_RUNNING'}
        try:
            now = datetime.now(CHINA)
            if not verify and not force:
                if (now.hour, now.minute) < (6, 30):
                    return {'status': 'SKIPPED_NOT_DUE'}
                if already_applied_today(state, now):
                    return {'status': 'SKIPPED_ALREADY_APPLIED'}
            catalog = 'stock_system_test' if verify else 'stock_system'
            # Pin the host as well as the catalog. A similarly named cloud database
            # must never become a local task target through inherited environment settings.
            jdbc = ('jdbc:mysql://localhost:3306/' + catalog
                    + '?createDatabaseIfNotExist=false&useUnicode=true&characterEncoding=utf-8'
                    + '&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true')
            command = [config['java'], '-Dspring.datasource.url=' + jdbc, '-Duser.timezone=Asia/Shanghai',
                       '-Dloader.main=com.stock.system.stock.directory.StockDirectorySyncCommand',
                       '-cp', config['jar'], 'org.springframework.boot.loader.launch.PropertiesLauncher',
                       'test' if verify else 'dev', 'verify' if verify else 'apply']
            return pipeline.run(state, command)
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-dir', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--verify', action='store_true', help='Only stock_system_test; both imports roll back')
    parser.add_argument('--force', action='store_true', help='Ignore the time/day check; keep both locks')
    parser.add_argument('--check', action='store_true', help='Runtime preflight; no network or database access')
    args = parser.parse_args()
    try:
        result = run(args.runtime_dir, args.verify, args.force, args.check)
    except Exception as failure:
        result = {'status': 'FAILED', 'phase': 'LOCAL_PREFLIGHT', 'errorType': type(failure).__name__}
        # Raw exceptions and environment values must not enter task logs.
        state = args.runtime_dir / ('verification' if args.verify else 'state')
        state.mkdir(parents=True, exist_ok=True)
        (state / 'local-preflight-failure.json').write_text(json.dumps(result), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True))
    sys.exit(1 if result['status'] == 'FAILED' else 0)
