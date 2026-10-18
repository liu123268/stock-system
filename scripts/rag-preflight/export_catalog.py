"""Export only the reviewed public passages; no network, model or business DB calls."""
import json
from preflight import ROOT, extract_reports, reviewed_sources


def main():
    sources = reviewed_sources()
    chunks, _ = extract_reports(sources)
    data = {"version": "reviewed-reports-20261018-v1", "passages": chunks}
    target = ROOT / "src/main/resources/reports/reviewed-passages.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    # Validate everything before atomically replacing the published catalog.
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(target)
    print(f"Exported {len(chunks)} reviewed passages from {len(sources)} reports to {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
