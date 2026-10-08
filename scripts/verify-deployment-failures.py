"""Isolated cloud acceptance of the released image; upstream CLI is a substitute.

No production business writes or production Longbridge authorization mounts.
Run as ubuntu on the verified deployment host, after a normal release.
"""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path('/home/ubuntu/stock-system-deploy')
assert socket.gethostname() == 'VM-0-8-ubuntu' and os.getuid() == 1000
run_id = uuid.uuid4().hex[:8]
name = 'stock-v1-fault-' + run_id
username = 'v1fault_' + run_id
stock_name = '部署验收' + run_id
prefix = 'stock-system:deployment-check:' + run_id
image = 'stock-system-backend:release-' + (ROOT / '.last-release').read_text().strip()
port = 18082
checks = []


def docker(*args, input=None):
    return subprocess.run(['sudo', '-n', 'docker', *args], input=input, capture_output=True, check=True).stdout.decode().strip()


def sql(statement):
    command = 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysql -uroot --default-character-set=utf8mb4 --database=stock_system_test -N -B'
    return docker('exec', '-i', 'stock-system-mysql-1', 'sh', '-c', command, input=statement.encode())


def api(path, token=None, payload=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:' + str(port) + '/api' + path,
                                    data=json.dumps(payload).encode() if payload is not None else None,
                                    headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as response:
        return response.code, json.load(response)


def remove_container():
    inspection = subprocess.run(['sudo', '-n', 'docker', 'inspect', '-f', '{{index .Config.Labels "codex.v1.run"}}', name], capture_output=True)
    if inspection.returncode == 0:
        assert inspection.stdout.decode().strip() == run_id
        docker('rm', '-f', name)


production_ids = {item: docker('inspect', '-f', '{{.Id}}', 'stock-system-' + item + '-1')
                  for item in ('backend', 'frontend', 'mysql', 'redis')}
network = docker('inspect', '-f', '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}', 'stock-system-backend-1')
assert sql('select database();') == 'stock_system_test'
assert sql("select count(*) from stock where code='600519.SH';") == '0'
with socket.socket() as port_check:
    port_check.bind(('127.0.0.1', port))

with tempfile.TemporaryDirectory(prefix='v1-fault-', dir=ROOT / '.incoming') as temporary:
    stage = Path(temporary)
    jwt = stage / 'jwt_secret'
    jwt.write_text(secrets.token_hex(32))
    jwt.chmod(0o600)
    cli = stage / 'fake-cli.sh'
    cli.write_text('''#!/bin/sh
set -eu
mode=$(cat /v1/mode)
test "$mode" != unauthorized || { echo 'Token not found (test substitute)' >&2; exit 1; }
while IFS= read -r request; do
  test "$mode" != timeout || sleep 10
  id=$(printf '%s' "$request" | sed -n 's/.*"id":\\([0-9]*\\).*/\\1/p')
  test -n "$id"
  echo request >> /v1/counter
  printf '{"jsonrpc":"2.0","id":%s,"result":[{"symbol":"600519.SH","last_done":"100","timestamp":"2026-09-09T07:00:00Z","trade_status":"Normal"}]}\\n' "$id"
  test "$mode" != exit-after-one || exit 0
done
''')
    cli.chmod(0o750)
    (stage / 'counter').touch()

    def start(mode, redis=False, bad_redis=False):
        remove_container()
        (stage / 'mode').write_text(mode)
        executable = '/v1/missing-cli' if mode == 'missing' else '/v1/fake-cli.sh'
        arguments = ['run', '-d', '--name', name, '--label', 'codex.v1.run=' + run_id,
                     '--memory', '768m', '--network', network, '-p', '127.0.0.1:' + str(port) + ':8082',
                     '-e', 'JAVA_TOOL_OPTIONS=-Xms64m -Xmx384m -Duser.timezone=Asia/Shanghai',
                     '-v', str(stage) + ':/v1',
                     '-v', str(ROOT / 'secrets/mysql_root_password') + ':/run/secrets/mysql_app_password:ro',
                     '-v', str(jwt) + ':/run/secrets/jwt_secret:ro', image,
                     '--spring.profiles.active=prod,test', '--server.port=8082',
                     '--spring.datasource.url=jdbc:mysql://mysql:3306/stock_system_test?serverTimezone=Asia/Shanghai',
                     '--spring.datasource.username=root', '--stock-system.market.mode=longbridge',
                     '--stock-system.market.longbridge.transport=cli',
                     '--stock-system.market.longbridge.executable=' + executable,
                     '--stock-system.market.longbridge.request-timeout=2s',
                     '--stock-system.market.redis.enabled=' + str(redis).lower(),
                     '--stock-system.market.redis.key-prefix=' + prefix,
                     '--stock-system.market.redis.quote-ttl=120s', '--stock-system.market.redis.ttl-jitter-percent=0',
                     '--spring.data.redis.host=' + ('127.0.0.1' if bad_redis else 'redis'),
                     '--spring.data.redis.port=' + ('1' if bad_redis else '6379')]
        docker(*arguments)
        for attempt in range(60):
            try:
                status, result = api('/test/environment')
                if status == 200:
                    assert result['data']['database'] == 'stock_system_test' and result['data']['profile'] == 'test'
                    return
            except (urllib.error.URLError, ConnectionError):
                pass
            time.sleep(0.5)
        raise AssertionError('Isolated backend did not become ready')

    def requests_received():
        return len((stage / 'counter').read_text().splitlines())

    try:
        start('missing')
        assert api('/auth/register', payload={'username': username, 'password': 'BrowserPass123', 'nickname': stock_name})[0] == 201
        status, login = api('/auth/login', payload={'username': username, 'password': 'BrowserPass123'})
        assert status == 200
        token = login['data']['accessToken']
        status, workspace = api('/workspaces', token, {'name': stock_name})
        assert status == 201
        workspace_id = workspace['data']['workspaceId']
        sql("insert into stock(code,name,exchange,currency,listing_status,source,search_aliases,pinyin_initials) "
            "values('600519.SH','" + stock_name + "','SSE','CNY','LISTED','SSE_DIRECTORY','600519.SH','bsy');")
        add = {'code': '600519.SH', 'stance': 'NEUTRAL', 'reason': 'isolated deployment failure check', 'horizon': 'LONG'}
        for index, mode in enumerate(('missing', 'unauthorized', 'timeout')):
            if index:
                start(mode)
            status, result = api('/stocks/search?keyword=600519', token)
            assert status == 200 and len(result['data']) == 1 and result['data'][0]['price'] is None
            status, result = api('/workspaces/' + str(workspace_id) + '/stocks', token, add)
            assert status == 503 and result['code'] == 'MARKET_QUOTE_UNAVAILABLE'
            assert sql('select (select count(*) from workspace_stock)+(select count(*) from research_cycle)+(select count(*) from stock_opinion);') == '0'
            checks.append(mode + ': directory retained, add rejected, no partial rows')
            print(json.dumps({'completed': checks[-1]}), flush=True)

        start('normal', redis=True)
        status, first = api('/stocks/search?keyword=600519', token)
        assert status == 200 and first['data'][0]['price'] == 100
        count = requests_received()
        start('normal', redis=True)
        status, cached = api('/stocks/search?keyword=600519', token)
        assert status == 200 and cached['data'] == first['data'] and requests_received() == count
        checks.append('Redis shared hit after backend restart; no new CLI request')
        print(json.dumps({'completed': checks[-1]}), flush=True)

        start('normal', redis=True, bad_redis=True)
        status, result = api('/stocks/search?keyword=600519', token)
        assert status == 200 and result['data'][0]['price'] == 100 and requests_received() == count + 1
        checks.append('Redis connection failure falls back to upstream transport')
        print(json.dumps({'completed': checks[-1]}), flush=True)

        start('exit-after-one')
        assert api('/stocks/search?keyword=600519', token)[0] == 200
        count = requests_received()
        time.sleep(6)
        status, result = api('/stocks/search?keyword=600519', token)
        assert status == 200 and result['data'][0]['price'] == 100 and requests_received() == count + 1
        checks.append('Exited sidecar recreated on next uncached request')
        print(json.dumps({'success': True, 'checks': checks, 'upstream': 'substitute; no real authorization mounted'}), flush=True)
    finally:
        remove_container()
        # Delete only the current run's account, space and stock identity.
        sql("create temporary table v1_spaces as select id from workspace where created_by_user_id in "
            "(select id from app_user where username='" + username + "');"
            "create temporary table v1_stocks as select id from workspace_stock where workspace_id in (select id from v1_spaces);"
            "create temporary table v1_cycles as select id from research_cycle where workspace_stock_id in (select id from v1_stocks);"
            "delete from research_attention_change where research_cycle_id in (select id from v1_cycles);"
            "delete from stock_opinion where research_cycle_id in (select id from v1_cycles);"
            "delete from research_cycle where id in (select id from v1_cycles);"
            "delete from workspace_stock where id in (select id from v1_stocks);"
            "delete from workspace_invitation where workspace_id in (select id from v1_spaces);"
            "delete from workspace_member where workspace_id in (select id from v1_spaces);"
            "delete from workspace where id in (select id from v1_spaces);"
            "delete from app_user where username='" + username + "';")
        sql("delete from stock where code='600519.SH' and name='" + stock_name + "';")
        assert sql("select (select count(*) from app_user where username='" + username + "')+"
                   "(select count(*) from workspace where name='" + stock_name + "')+"
                   "(select count(*) from stock where name='" + stock_name + "');") == '0'
        keys = docker('exec', 'stock-system-redis-1', 'redis-cli', '--scan', '--pattern', prefix + ':*').splitlines()
        assert all(key.startswith(prefix + ':') for key in keys)
        if keys:
            docker('exec', 'stock-system-redis-1', 'redis-cli', 'unlink', *keys)
        assert not docker('exec', 'stock-system-redis-1', 'redis-cli', '--scan', '--pattern', prefix + ':*')
        assert production_ids == {item: docker('inspect', '-f', '{{.Id}}', 'stock-system-' + item + '-1') for item in production_ids}
        print(json.dumps({'cleanupRemaining': 0, 'productionContainerIdsUnchanged': True}), flush=True)
