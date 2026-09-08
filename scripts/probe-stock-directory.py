"""Read-only public directory probe; writes evidence under ignored target/, never SQL."""
import concurrent.futures
import datetime
import io
import json
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request

OUT = Path(__file__).resolve().parents[1] / 'target' / 'market-directory-probe'


def fetch(url, referer=None, data=None):
    headers = {'User-Agent': 'Mozilla/5.0'}
    if referer:
        headers['Referer'] = referer
    if data is not None:
        data = urllib.parse.urlencode(data).encode('utf-8')
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers, data=data), timeout=20) as r:
        return r.read()


def probe(market):
    result = {'market': market, 'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        if market in ('SSE_MAIN', 'SSE_STAR'):
            params = {'STOCK_TYPE': '1' if market == 'SSE_MAIN' else '8',
                      'sqlId': 'COMMON_SSE_CP_GPJCTPZ_GPLB_GP_L', 'COMPANY_STATUS': '2,4,5,7,8',
                      'type': 'inParams', 'isPagination': 'true', 'pageHelp.pageSize': '10000',
                      'pageHelp.pageNo': '1', 'pageHelp.beginPage': '1', 'pageHelp.endPage': '1'}
            url = 'https://query.sse.com.cn/sseQuery/commonQuery.do?' + urllib.parse.urlencode(params)
            body = fetch(url, 'https://www.sse.com.cn/assortment/stock/list/share/')
            data = json.loads(body)
            rows = data['result']
            result.update(rows=len(rows), fields=list(rows[0]) if rows else [], samples=rows[:3],
                          pagination=data.get('pageHelp'))
            # Do not duplicate all directory rows inside pagination evidence.
            if isinstance(result['pagination'], dict):
                result['pagination'] = {k: v for k, v in result['pagination'].items() if not isinstance(v, (list, dict))}
            (OUT / (market + '.json')).write_bytes(body)
        elif market == 'SZSE':
            import pandas as pd
            url = 'https://www.szse.cn/api/report/ShowReport?SHOWTYPE=xlsx&CATALOGID=1110&TABKEY=tab1'
            body = fetch(url)
            frame = pd.read_excel(io.BytesIO(body), dtype=str).fillna('')
            result.update(rows=len(frame), fields=list(frame.columns), samples=frame.head(3).to_dict('records'))
            (OUT / 'SZSE.xlsx').write_bytes(body)
        elif market == 'BSE':
            url = 'https://www.bse.cn/nqxxController/nqxxCnzq.do'
            body = fetch(url, 'https://www.bse.cn/nq/listedcompany.html',
                         {'page': '0', 'typejb': 'T', 'xxfcbj[]': '2', 'xxzqdm': '',
                          'sortfield': 'xxzqdm', 'sorttype': 'asc'})
            (OUT / 'BSE-first-response.txt').write_bytes(body)
            result.update(bytes=len(body), preview=body.decode('utf-8')[:4000], status='response_pending_parse')
        elif market == 'BSE_EM':
            url = 'https://82.push2.eastmoney.com/api/qt/clist/get'
            rows = []
            total = None
            for page in range(1, 101):
                params = {'pn': page, 'pz': 100, 'po': 1, 'np': 1, 'fltt': 2,
                          'invt': 2, 'fid': 'f12', 'fs': 'm:0+t:81+s:2048', 'fields': 'f12,f13,f14'}
                data = json.loads(fetch(url + '?' + urllib.parse.urlencode(params)))['data']
                if total is None:
                    total = data['total']
                if total != data['total'] or not data['diff']:
                    raise ValueError('Directory changed or pagination ended early')
                rows.extend(data['diff'])
                if len(rows) >= total:
                    break
            if not total or len(rows) != total or len({r['f12'] for r in rows}) != total:
                raise ValueError('Incomplete or duplicate BSE directory')
            (OUT / 'BSE_EM.json').write_text(json.dumps({'total': total, 'rows': rows}, ensure_ascii=False), encoding='utf-8')
            result.update(rows=len(rows), fields=list(rows[0]), samples=rows[:3], status='response_parsed_not_full_acceptance')
        elif market == 'CNINFO':
            url = 'https://www.cninfo.com.cn/new/data/szse_stock.json'
            body = fetch(url)
            stocks = json.loads(body)['stockList']
            bj = [r for r in stocks if re.fullmatch(r'920\d{3}', r['code']) and r['category'] == 'A股']
            if not bj:
                raise ValueError('No BSE identities returned')
            (OUT / 'CNINFO_CODES.txt').write_bytes(body)
            result.update(rows=len(bj), samples=bj[:3], status='BSE_subset_full_coverage_unverified')
        else:
            language = 'zh-HK' if market == 'HKEX_ZH' else 'en'
            url = 'https://www.hkex.com.hk/Services/Trading/Securities/Securities-Lists?sc_lang=' + language
            body = fetch(url)
            links = re.findall(r'href=[\"\x27]([^\"\x27]+\.(?:xlsx|xls)[^\"\x27]*)', body.decode('utf-8'), re.I)
            result.update(download_links=[urllib.parse.urljoin(url, x) for x in links], status='links_only_not_directory_verified')
            full_links = [x for x in result['download_links'] if 'listofsecurities' in x.lower()]
            if full_links:
                import pandas as pd
                full_body = fetch(full_links[0])
                (OUT / (market + '.xlsx')).write_bytes(full_body)
                frame = pd.read_excel(io.BytesIO(full_body), header=None, dtype=str).fillna('')
                result.update(status='workbook_downloaded_header_pending', workbook_rows=len(frame),
                              preview=frame.head(8).values.tolist(), workbook_url=full_links[0])
        result['url'] = url
        result.setdefault('status', 'response_parsed_not_full_acceptance')
    except Exception as exc:
        result.update(status='failed', error=type(exc).__name__ + ': ' + str(exc))
    return result


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        markets = sys.argv[1:] or ['SSE_MAIN', 'SSE_STAR', 'SZSE', 'CNINFO', 'HKEX', 'HKEX_ZH']
        allowed = {'SSE_MAIN', 'SSE_STAR', 'SZSE', 'BSE', 'BSE_EM', 'CNINFO', 'HKEX', 'HKEX_ZH'}
        if not set(markets) <= allowed:
            raise ValueError('Unknown source')
        results = list(pool.map(probe, markets))
    report = OUT / 'report.json'
    previous = json.loads(report.read_text(encoding='utf-8')) if report.exists() else []
    results = [r for r in previous if r['market'] not in {x['market'] for x in results}] + results
    report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps([{k: v for k, v in r.items() if k not in ('samples', 'download_links', 'pagination')}
                      for r in results if r['market'] in (sys.argv[1:] or [r['market']])], ensure_ascii=True, indent=2))
    if any(r['status'] == 'failed' for r in results if r['market'] in markets):
        sys.exit(1)
