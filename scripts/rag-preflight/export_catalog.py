"""Export only the reviewed public passages; no network, model or business DB calls."""
import json
import argparse
from pathlib import Path
from preflight import ROOT, extract_reports, reviewed_sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--symbol')
    parser.add_argument('--output')
    args = parser.parse_args()
    if bool(args.symbol) != bool(args.output):
        parser.error('--symbol and --output must be provided together for a single-report maintenance bundle')
    sources = reviewed_sources()
    chunks, _ = extract_reports(sources)
    if args.symbol:
        chunks = [row for row in chunks if row['symbol'] == args.symbol]
        if not chunks:
            raise ValueError('Symbol is outside the reviewed source list')
    data = {"version": "reviewed-reports-20261018-v1", "passages": chunks}
    target = (Path(args.output) if args.output else ROOT / "src/main/resources/reports/reviewed-passages.json").resolve()
    if not target.is_relative_to(ROOT.resolve()) or target.suffix != '.json':
        raise ValueError('Prepared report JSON must remain inside this workspace')
    target.parent.mkdir(parents=True, exist_ok=True)
    # Validate everything before atomically replacing the published catalog.
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(target)
    print(f"Exported {len(chunks)} reviewed passages from {len(sources)} reports to {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
