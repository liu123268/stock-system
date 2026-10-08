"""Read-only public directory probe; writes evidence under ignored target/, never SQL."""
import concurrent.futures
import argparse
import datetime
import http.cookiejar
import io
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OUT = Path(__file__).resolve().parents[1] / 'target' / 'market-directory-probe'


def fetch(url, referer=None, data=None, opener=None):
    headers = {'User-Agent': 'Mozilla/5.0'}
    if referer:
        headers['Referer'] = referer
    if data is not None:
        data = urllib.parse.urlencode(data).encode('utf-8')
    for attempt in range(3):
        try:
            open_url = opener.open if opener is not None else urllib.request.urlopen
            with open_url(urllib.request.Request(url, headers=headers, data=data), timeout=20) as r:
                return r.read()
        except urllib.error.HTTPError as failure:
            if failure.code not in (408, 429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 2:
                raise
        time.sleep(0.5 * (attempt + 1))


def bse_official():
    page_url = 'https://www.bse.cn/nq/listedcompany.html'
    endpoint = 'https://www.bse.cn/nqxxController/nqxxCnzq.do'
    # The official API requires the session established by its list page.
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    fetch(page_url, opener=opener)

    def read_page(number):
        params = {'page': str(number), 'typejb': 'T', 'xxfcbj[]': '2', 'xxzqdm': '',
                  'sortfield': 'xxzqdm', 'sorttype': 'asc'}
        body = fetch(endpoint, page_url, params, opener=opener).decode('utf-8')
        wrapped = re.fullmatch(r'null\((.*)\);?\s*', body, re.S)
        if wrapped is None:
            raise ValueError('Unexpected official BSE response wrapper')
        payload = json.loads(wrapped.group(1))
        if not isinstance(payload, list) or len(payload) != 1 or payload[0]['number'] != number:
            raise ValueError('Unexpected official BSE page')
        return payload[0]

    first = read_page(0)
    total, pages = first['totalElements'], first['totalPages']
    if not isinstance(total, int) or not 0 < total <= 10000 or not 0 < pages <= 500:
        raise ValueError('Invalid official BSE totals')
    rows = first['content'][:]
    for number in range(1, pages):
        page = read_page(number)
        if page['totalElements'] != total or page['totalPages'] != pages or not page['content']:
            raise ValueError('Official BSE pagination changed or ended early')
        rows.extend(page['content'])
    codes = [row['xxzqdm'] for row in rows]
    if len(rows) != total or len(set(codes)) != total or any(not re.fullmatch(r'920\d{3}', code) for code in codes):
        raise ValueError('Incomplete or duplicate official BSE identities')
    if read_page(0)['totalElements'] != total:
        raise ValueError('Official BSE changed during collection')
    return {'total': total, 'pages': pages, 'provider': 'BSE_OFFICIAL',
            'rows': [{'code': row['xxzqdm'], 'name': row['xxzqjc']} for row in rows]}


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
        elif market in ('BSE', 'BSE_OFFICIAL'):
            url = 'https://www.bse.cn/nqxxController/nqxxCnzq.do'
            official = bse_official()
            (OUT / 'BSE_OFFICIAL.json').write_text(json.dumps(official, ensure_ascii=False), encoding='utf-8')
            result.update(rows=official['total'], pages=official['pages'], provider=official['provider'],
                          samples=official['rows'][:3], status='full_official_identity_list_collected')
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=OUT)
    parser.add_argument('markets', nargs='*', default=[])
    options = parser.parse_args()
    OUT = options.output_dir.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        markets = options.markets or ['SSE_MAIN', 'SSE_STAR', 'SZSE', 'CNINFO', 'HKEX', 'HKEX_ZH']
        allowed = {'SSE_MAIN', 'SSE_STAR', 'SZSE', 'BSE', 'BSE_OFFICIAL', 'CNINFO', 'HKEX', 'HKEX_ZH'}
        if not set(markets) <= allowed:
            raise ValueError('Unknown source')
        results = list(pool.map(probe, markets))
    report = OUT / 'report.json'
    previous = json.loads(report.read_text(encoding='utf-8')) if report.exists() else []
    results = [r for r in previous if r['market'] not in {x['market'] for x in results}] + results
    report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps([{k: v for k, v in r.items() if k not in ('samples', 'download_links', 'pagination')}
                      for r in results if r['market'] in markets], ensure_ascii=True, indent=2))
    if any(r['status'] == 'failed' for r in results if r['market'] in markets):
        sys.exit(1)
