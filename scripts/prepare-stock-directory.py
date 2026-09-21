"""Batch-normalize downloaded public directories. No database access.

Requires pandas/openpyxl, opencc-python-reimplemented==0.1.7 and
pypinyin==0.55.0.
Raw downloads are produced by probe-stock-directory.py; CNINFO_CODES.txt is
the public https://www.cninfo.com.cn/new/data/szse_stock.json response.
"""
import datetime
import json
from pathlib import Path
import re
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target/market-python'))
from stock_directory_pinyin import pinyin_initials

OUT = ROOT / 'target/market-directory-probe'


def build():
    import pandas as pd
    from opencc import OpenCC

    converter = OpenCC('t2s')
    stocks = []

    def add(code, name, exchange, currency, source, english=''):
        original = unicodedata.normalize('NFKC', name).strip()
        name = converter.convert(original)
        stocks.append(dict(code=code, name=name, exchange=exchange, currency=currency,
                           source=source, searchAliases=' '.join(x for x in [original, english.strip()] if x),
                           pinyinInitials=pinyin_initials(name),
                           listingStatus='UNKNOWN'))

    for market in ['SSE_MAIN', 'SSE_STAR']:
        data = json.loads((OUT / (market + '.json')).read_bytes())
        if len(data['result']) != data['pageHelp']['total']:
            raise ValueError('Incomplete Shanghai directory')
        for row in data['result']:
            add(row['A_STOCK_CODE'] + '.SH', row['SEC_NAME_CN'], 'SSE', 'CNY', 'SSE_DIRECTORY')
    sz = pd.read_excel(OUT / 'SZSE.xlsx', dtype=str).fillna('')
    for row in sz.to_dict('records'):
        if row['A股代码']:
            add(row['A股代码'] + '.SZ', row['A股简称'], 'SZSE', 'CNY', 'SZSE_DIRECTORY')

    # Current 920xxx BSE identities from CNINFO. Do not infer old 43/83/87
    # codes: those overlap with NEEQ and require an explicit mapping.
    bj = json.loads((OUT / 'CNINFO_CODES.txt').read_bytes())['stockList']
    for row in bj:
        if re.fullmatch(r'920\d{3}', row['code']) and row['category'] == 'A股':
            add(row['code'] + '.BJ', row['zwjc'], 'BSE', 'CNY', 'CNINFO_DIRECTORY')

    en = pd.read_excel(OUT / 'HKEX.xlsx', header=2, dtype=str).fillna('')
    zh = pd.read_excel(OUT / 'HKEX_ZH.xlsx', header=2, dtype=str).fillna('')
    if zh['股份代號'].duplicated().any():
        raise ValueError('Duplicate HK Chinese codes')
    zh = zh.set_index('股份代號')
    candidates = en[(en['Category'] == 'Equity') & en['Sub-Category'].isin(
        ['Equity Securities (Main Board)', 'Equity Securities (GEM)']) & (en['Trading Currency'] == 'HKD')]
    for row in candidates.to_dict('records'):
        chinese = zh.loc[row['Stock Code']]
        if chinese['國際證券號碼 (ISIN)'] != row['ISIN'] or chinese['交易貨幣'] != row['Trading Currency']:
            raise ValueError('HK English/Chinese identity mismatch')
        add(row['Stock Code'].zfill(5) + '.HK', chinese['股份名稱'], 'HKEX', 'HKD',
            'HKEX_DIRECTORY', row['Name of Securities'])

    suffixes = {'SSE': 'SH', 'SZSE': 'SZ', 'BSE': 'BJ', 'HKEX': 'HK'}
    if len({r['code'] for r in stocks}) != len(stocks):
        raise ValueError('Duplicate normalized identity')
    for r in stocks:
        width = 5 if r['exchange'] == 'HKEX' else 6
        if not re.fullmatch(r'\d{' + str(width) + r'}\.' + suffixes[r['exchange']], r['code']):
            raise ValueError('Invalid code/exchange pair')
        if (not 1 <= len(r['name']) <= 50 or len(r['searchAliases']) > 200
                or not re.fullmatch(r'[a-z]{1,50}', r['pinyinInitials'])):
            raise ValueError('Invalid name length')
    return {'generatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'coverageNote': 'BSE: CNINFO 920xxx subset; full-market reconciliation pending. HK: HKD Main Board/GEM equities only. Trading status unknown.',
            'stocks': sorted(stocks, key=lambda r: r['code'])}


def enrich_pinyin(directory):
    if not isinstance(directory, dict) or not isinstance(directory.get('stocks'), list) or not directory['stocks']:
        raise ValueError('Empty directory')
    for row in directory['stocks']:
        if not isinstance(row, dict) or not isinstance(row.get('name'), str):
            raise ValueError('Invalid directory row')
        row['pinyinInitials'] = pinyin_initials(row['name'])
    return directory


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--enrich-pinyin':
        result = enrich_pinyin(json.loads(Path(sys.argv[2]).read_text(encoding='utf-8')))
        output = Path(sys.argv[3])
        output.parent.mkdir(parents=True, exist_ok=True)
    elif len(sys.argv) == 1:
        result = build()
        output = OUT / 'directory.json'
    else:
        raise SystemExit('Usage: prepare-stock-directory.py [--enrich-pinyin <input-json> <output-json>]')
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'total': len(result['stocks']), 'markets': {
        e: sum(r['exchange'] == e for r in result['stocks']) for e in ['SSE', 'SZSE', 'BSE', 'HKEX']},
        'samples': [r for r in result['stocks'] if r['code'] in ['000001.SZ', '00700.HK', '920002.BJ']]}, ensure_ascii=True))
