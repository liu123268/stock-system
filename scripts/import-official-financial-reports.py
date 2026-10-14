"""Import only reviewed, hash-pinned report tables. No OCR, model, database or arbitrary URL access."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import sys

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
METRICS = {"REVENUE", "NET_PROFIT", "TOTAL_ASSETS", "TOTAL_LIABILITIES", "OPERATING_CASH_FLOW"}


def table_values(pages, locator, unit, count):
    text = pages[locator["page"]]
    lines = text.splitlines()
    if "linePattern" in locator:
        matches = [line for line in lines if re.fullmatch(locator["linePattern"], line)]
    else:
        row_start = re.compile(r"^\s*" + re.escape(locator["prefix"]) + r"\s*(?=[0-9(-])")
        matches = [line for line in lines if row_start.match(line)]
    if len(matches) != 1:
        raise ValueError(f"Expected one reviewed row on PDF page {locator['page']}; found {len(matches)}")
    pattern = r"-?\d{1,3}(?:,\d{3})+\.\d{2}" if unit == "YUAN" else r"-?\d{1,3}(?:,\d{3})+"
    numbers = re.findall(pattern, matches[0])
    if len(numbers) != count:
        raise ValueError("Table number count changed")
    return [Decimal(value.replace(",", "")) for value in numbers[:2]]


def read_report(source, directory):
    path = directory / source["file"]
    if path.parent.resolve() != directory or path.stat().st_size > 20 * 1024 * 1024:
        raise ValueError("Unexpected report file")
    if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
        raise ValueError("Source hash changed; review the new report before importing")
    reader = PdfReader(path)
    if reader.is_encrypted or len(reader.pages) != source["pageCount"]:
        raise ValueError("Unexpected report structure")
    needed = {check["page"] for check in source["checks"]}
    for row in source["rows"]:
        if "subtract" in row:
            needed.update(part["page"] for part in row["subtract"])
        else:
            needed.add(row["page"])
    pages = {number: reader.pages[number - 1].extract_text(extraction_mode="layout") for number in needed}
    for check in source["checks"]:
        compact = re.sub(r"\s+", "", pages[check["page"]])
        if any(re.sub(r"\s+", "", term) not in compact for term in check["contains"]):
            raise ValueError("Report identity, year columns or unit check failed")
    values = []
    for row in source["rows"]:
        if row["metric"] not in METRICS:
            raise ValueError("Unapproved financial metric")
        if "subtract" in row:
            left, right = [table_values(pages, part, source["unit"], row["count"]) for part in row["subtract"]]
            amounts = [a - b for a, b in zip(left, right, strict=True)]
            pdf_pages = sorted({part["page"] for part in row["subtract"]})
        else:
            amounts = table_values(pages, row, source["unit"], row["count"])
            pdf_pages = [row["page"]]
        for year, amount in zip([source["year"], source["year"] - 1], amounts, strict=True):
            values.append({"metric": row["metric"], "year": year, "amount": str(amount),
                           "sourceField": row["field"], "sourceName": row["name"], "pdfPages": pdf_pages})
    if len(values) != 10 or len({(value["metric"], value["year"]) for value in values}) != 10:
        raise ValueError("Expected five unique metrics and two years")
    return {**{key: source[key] for key in ["symbol", "title", "url", "sha256", "year", "currency", "unit"]},
            "retrievedAt": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
            "disclosedOn": None, "values": values}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=ROOT / ".local/report-source/pdfs")
    parser.add_argument("--check", action="store_true", help="Compare with the committed catalog without changing it")
    args = parser.parse_args()
    directory = args.source_dir.resolve()
    sources = json.loads((ROOT / "scripts/report-sources/official-financial-reports.json").read_text(encoding="utf-8"))
    result = {"version": 1, "reports": [read_report(source, directory) for source in sources["reports"]]}
    destination = ROOT / "src/main/resources/financial/official-annual-reports.json"
    if args.check:
        existing = json.loads(destination.read_text(encoding="utf-8"))
        # Retrieval time changes after re-downloading identical bytes; it is not part of the report's content version.
        for report in existing["reports"] + result["reports"]:
            report.pop("retrievedAt", None)
        if result != existing:
            raise ValueError("Imported facts differ from the reviewed catalog")
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(destination)
    print(json.dumps({"status": "VERIFIED" if args.check else "IMPORTED", "reports": len(result["reports"]),
                      "facts": sum(len(report["values"]) for report in result["reports"]), "model": False, "database": False}))


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print(f"REPORT_IMPORT_FAILED type={type(failure).__name__}", file=sys.stderr)
        sys.exit(2)
