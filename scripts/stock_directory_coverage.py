"""Coverage gate for fresh public identity data; never infers listing status."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re

REQUIRED_SOURCES = ('SSE_MAIN', 'SSE_STAR', 'SZSE', 'CNINFO', 'BSE_OFFICIAL', 'HKEX', 'HKEX_ZH')
# Identity history is wider than the official current listed-company scope.
# Keep these explicitly reconciled identities; never infer their trade status.
BSE_KNOWN_OUTSIDE_CURRENT_LIST = {
    '920157': '宇特光电', '920162': '莫森泰克', '920196': '南方乳业',
    '920305': '云创退', '920680': '广道退',
}


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def validate_sources(raw_dir, started_at):
    raw_dir = Path(raw_dir)
    report = read_json(raw_dir / 'report.json')
    sources = {entry['market']: entry for entry in report}
    if len(sources) != len(report):
        raise ValueError('Duplicate source evidence')
    now = datetime.now(timezone.utc)
    for market in REQUIRED_SOURCES:
        source = sources.get(market)
        if source is None or source['status'] == 'failed':
            raise ValueError('Missing or failed directory source: ' + market)
        checked = datetime.fromisoformat(source['checked_at'])
        if checked.tzinfo is None or checked < started_at or checked > now + timedelta(minutes=5):
            raise ValueError('Stale or invalid source evidence: ' + market)
    cn_rows = read_json(raw_dir / 'CNINFO_CODES.txt')['stockList']
    cn_codes = [row['code'] for row in cn_rows
                if re.fullmatch(r'920\d{3}', row['code']) and row['category'] == 'A股']
    feed = read_json(raw_dir / 'BSE_OFFICIAL.json')
    if feed.get('provider') != 'BSE_OFFICIAL':
        raise ValueError('Unknown BSE official provider')
    feed_rows = feed['rows']
    feed_codes = [row['code'] for row in feed_rows]
    if (not cn_codes or not feed_codes or len(set(cn_codes)) != len(cn_codes)
            or len(set(feed_codes)) != len(feed_codes) or len(feed_codes) != feed['total']):
        raise ValueError('Incomplete or duplicate BSE identities')
    if not set(feed_codes) <= set(cn_codes):
        raise ValueError('BSE current listed identity missing from directory')
    extras = {row['code']: row['zwjc'] for row in cn_rows if row['code'] in cn_codes and row['code'] not in feed_codes}
    if any(BSE_KNOWN_OUTSIDE_CURRENT_LIST.get(code) != name for code, name in extras.items()):
        raise ValueError('Unexplained BSE identities outside current official list: ' + ','.join(sorted(extras)))
    return {'bseIdentities': len(cn_codes), 'bseCurrentlyListed': len(feed_codes),
            'bseRetainedOutsideCurrentList': extras, 'bseComparisonProvider': feed['provider'],
            'sources': {market: sources[market]['checked_at'] for market in REQUIRED_SOURCES}}


def validate_directory(raw_dir, directory, coverage):
    stocks = directory['stocks']
    codes = [row['code'] for row in stocks]
    if len(codes) != len(set(codes)):
        raise ValueError('Duplicate normalized directory codes')
    counts = {market: sum(row['exchange'] == market for row in stocks)
              for market in ('SSE', 'SZSE', 'BSE', 'HKEX')}
    if min(counts.values()) <= 0 or counts['BSE'] != coverage['bseIdentities']:
        raise ValueError('Incomplete normalized market coverage')
    # HK comparison is against the full official workbook in the chosen scope.
    import pandas as pd
    english = pd.read_excel(Path(raw_dir) / 'HKEX.xlsx', header=2, dtype=str).fillna('')
    eligible = english[(english['Category'] == 'Equity') & english['Sub-Category'].isin(
        ['Equity Securities (Main Board)', 'Equity Securities (GEM)']) &
        (english['Trading Currency'] == 'HKD')]
    expected = {code.zfill(5) + '.HK' for code in eligible['Stock Code']}
    actual = {row['code'] for row in stocks if row['exchange'] == 'HKEX'}
    if len(expected) != len(eligible) or expected != actual:
        raise ValueError('HKEX scope coverage mismatch')
    return counts
