"""Read-only documentation checks; uses the Python standard library only.

Run from any directory: python <project>/scripts/check-docs.py
Checks local Markdown links/anchors and reports entry size without a hard limit.
It does not prove semantic consistency or verify historical business tests.
"""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ENTRY = ('AGENTS.md', 'docs/project/project-rules.md', 'docs/project/status.md')


def prose(text):
    return re.sub(r'^(`{3,}|~{3,}).*?^\1\s*$', '', text, flags=re.M | re.S)


def anchors(text):
    result, counts = set(), {}
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', prose(text), re.M):
        slug = re.sub(r'[^\w\-\s]', '', heading.lower()).replace(' ', '-')
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if not count else f'{slug}-{count}')
    result.update(re.findall(r'(?:id|name)=[\"\']([^\"\']+)', text))
    return result


def main():
    files = [ROOT / 'AGENTS.md', ROOT / 'README.md', ROOT / 'PRODUCT.md']
    files += sorted((ROOT / 'docs').rglob('*.md'))
    bridge = ROOT.parent / 'AGENTS.md'
    if bridge.exists():
        files.append(bridge)
    errors, link_count = [], 0
    for file in files:
        text = file.read_text(encoding='utf-8-sig')
        for match in re.finditer(r'(?<!!)\[[^\]\n]+\]\(([^)\n]+)\)', prose(text)):
            url = match.group(1).strip().strip('<>')
            if urlsplit(url).scheme or url.startswith('//'):
                continue
            path, _, fragment = unquote(url).partition('#')
            target = (file.parent / path).resolve() if path else file
            line = text.count('\n', 0, text.find(match.group(0))) + 1
            link_count += 1
            if not target.exists():
                errors.append(f'{file.relative_to(ROOT.parent)}:{line}: missing {url}')
            elif fragment and target.suffix == '.md':
                if fragment not in anchors(target.read_text(encoding='utf-8-sig')):
                    errors.append(f'{file.relative_to(ROOT.parent)}:{line}: missing anchor {url}')
    size = sum((ROOT / name).stat().st_size for name in ENTRY)
    print(f'Checked {len(files)} Markdown files; {link_count} local links; entry + route + status: {size} bytes.')
    if errors:
        print('\n'.join(errors))
        return 1
    print('PASS: local links and anchors. Entry size is informational, not a content limit.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
