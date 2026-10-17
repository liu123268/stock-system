"""AG-CODE-001R: reviewed PDF passages -> real CPU embeddings -> local Qdrant.

This is an isolated retrieval experiment, not an application API or answer model.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import tempfile
import time
import uuid

from download_model import MODEL_DIR, verify_file

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CHUNK_SIZE = 320
CHUNK_OVERLAP = 48
TOP_K = 3
MIN_SCORE = 0.45  # Calibrated on questions.json only; not an answer-confidence probability.
NORMALIZATION = "remove-whitespace-v1"
PIPELINE_VERSION = "rag-preflight-20261017-v2-calibrated-candidates"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def compact(text):
    return re.sub(r"\s+", "", text)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def extract_section(page_text, section):
    text = compact(page_text)
    start_marker = compact(section["start"])
    require(text.count(start_marker) == 1, "Reviewed paragraph start must occur exactly once")
    start = text.index(start_marker)
    end = len(text)
    if section.get("end"):
        marker = compact(section["end"])
        require(text.count(marker) == 1, "Reviewed paragraph end must occur exactly once")
        end = text.index(marker)
    require(end > start, "Paragraph boundary order changed")
    body = text[start:end]
    require(len(body) >= 20 and "\ufffd" not in body, "Unreadable or empty paragraph")
    require(all(compact(term) in body for term in section["contains"]), "Reviewed paragraph identity changed")
    return text, start, end


def split_section(page_text, section, size=CHUNK_SIZE, overlap=CHUNK_OVERLAP):
    require(size > overlap >= 0, "Invalid chunk bounds")
    page, start, end = extract_section(page_text, section)
    result = []
    while start < end:
        stop = min(start + size, end)
        result.append({"section": section["id"], "sectionTitle": section["title"],
                       "pdfPage": section["page"], "charStart": start, "charEnd": stop,
                       "text": page[start:stop], "charUnit": "UNICODE_CODE_POINT",
                       "normalization": NORMALIZATION})
        if stop == end:
            break
        start = stop - overlap
    return result


def reviewed_sources():
    base = read_json(ROOT / "scripts/report-sources/official-financial-reports.json")["reports"]
    sources = []
    for entry in read_json(HERE / "sources.json")["reports"]:
        if "financialSourceFile" in entry:
            matches = [row for row in base if row["file"] == entry["financialSourceFile"]]
            require(len(matches) == 1, "Financial report source reference must be unique")
            entry = {**matches[0], **entry}
        sources.append(entry)
    return sources


def source_path(source):
    directory = (ROOT / ".local/report-source/pdfs").resolve()
    path = (directory / source["file"]).resolve()
    require(path.parent == directory and path.is_file(), "Report must be an existing reviewed local file")
    require(path.stat().st_size <= 20 * 1024 * 1024, "Report exceeds experiment limit")
    require(digest(path.read_bytes()) == source["sha256"], "Report hash changed; review new material first")
    return path


def extract_reports(sources):
    import pymupdf
    from pypdf import PdfReader
    chunks, comparisons, pages = [], [], {}
    for source in sources:
        path = source_path(source)
        with pymupdf.open(path) as pdf:
            require(not pdf.is_encrypted and len(pdf) == source["pageCount"], "Unexpected PDF structure")
            for section in source["sections"]:
                number = section["page"]
                key = (source["sha256"], number)
                text = pdf[number - 1].get_text()
                pages[key] = compact(text)
                for chunk in split_section(text, section):
                    body_hash = digest(chunk["text"].encode())
                    point_id = str(uuid.uuid5(uuid.NAMESPACE_URL,
                        f"{source['sha256']}:{section['id']}:{chunk['charStart']}:{body_hash}:{NORMALIZATION}"))
                    chunks.append({**chunk, "id": point_id, "symbol": source["symbol"],
                        "reportVersion": source["sha256"], "reportTitle": source["title"],
                        "reportYear": source["year"], "sourceUrl": source["url"], "textSha256": body_hash,
                        "language": source["language"], "status": "AVAILABLE", "corpus": "REPORT"})
        # Keep evidence of the old parser's Chinese-font failure without silently accepting it.
        if source["file"] == "tencent-2025.pdf":
            markers = ["收入", "管理層討論及分析", "騰訊"]
            old_text = PdfReader(path).pages[8].extract_text()
            comparisons.append({"file": source["file"], "pdfPage": 9, "markers": markers,
                "pypdfMatches": sum(marker in old_text for marker in markers),
                "pymupdfMatches": sum(marker in pages[(source["sha256"], 9)] for marker in markers)})
    require(len({row["id"] for row in chunks}) == len(chunks), "Duplicate passage identity")
    for row in chunks:
        page = pages[(row["reportVersion"], row["pdfPage"])]
        require(page[row["charStart"]:row["charEnd"]] == row["text"], "Citation offset mismatch")
    return chunks, comparisons


def checked_vectors(values, expected_count, dimensions):
    import numpy as np
    matrix = np.asarray(list(values), dtype=np.float32)
    require(matrix.shape == (expected_count, dimensions), "Embedding count or dimension mismatch")
    require(bool(np.isfinite(matrix).all()), "Non-finite embedding")
    lengths = np.linalg.norm(matrix, axis=1)
    require(bool((lengths > 0).all()), "Zero embedding cannot support semantic retrieval")
    return matrix / lengths[:, None]


def bound_filter(scope, fingerprint, corpus="REPORT"):
    from qdrant_client import models
    require(bool(scope.get("symbol")) and bool(scope.get("reportVersion")), "Explicit stock and report version required")
    fields = {**scope, "embeddingVersion": fingerprint, "status": "AVAILABLE", "corpus": corpus}
    return models.Filter(must=[models.FieldCondition(key=key, match=models.MatchValue(value=value))
                              for key, value in fields.items()])


def validate_hit(hit, canonical):
    row = canonical.get(str(hit.id))
    require(row is not None and hit.payload == row, "Index payload differs from reviewed passage")
    require(digest(row["text"].encode()) == row["textSha256"], "Passage text changed")
    return {"id": str(hit.id), "score": hit.score, "section": row["section"],
            "pdfPage": row["pdfPage"], "charStart": row["charStart"], "charEnd": row["charEnd"],
            "sourceUrl": row["sourceUrl"], "reportVersion": row["reportVersion"], "text": row["text"]}


def terms(text):
    chinese = re.findall(r"[\u4e00-\u9fff]+", text)
    return re.findall(r"[a-z0-9]+", text.lower()) + [word[i:i + 2] for word in chinese for i in range(len(word) - 1)]


def keyword_search(query, rows):
    # A bounded CJK-bigram BM25 baseline; no thesaurus or simplified/traditional conversion.
    tokens = [Counter(terms(row["text"])) for row in rows]
    average = sum(sum(item.values()) for item in tokens) / max(len(rows), 1)
    scored = []
    for row, counts in zip(rows, tokens, strict=True):
        score = 0.0
        for term in set(terms(query)):
            frequency = counts[term]
            if not frequency:
                continue
            documents = sum(term in item for item in tokens)
            inverse = math.log(1 + (len(rows) - documents + 0.5) / (documents + 0.5))
            score += inverse * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * sum(counts.values()) / average))
        if score > 0:
            scored.append({"id": row["id"], "section": row["section"], "score": score})
    return sorted(scored, key=lambda item: (-item["score"], item["id"]))[:TOP_K]


def synthetic_passages():
    texts = ["自造样本甲通过直营门店、线上自营平台和授权经销商销售产品。",
             "自造样本甲提示原料涨价与物流中断风险，尚无资料确认订单增长。",
             "自造样本乙销售电脑零部件，通过线下零售商和网络代理销售。"]
    return [{"id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"rag-preflight-synthetic-{i}")),
        "symbol": "SAMPLE-A" if i < 2 else "SAMPLE-B", "reportVersion": "synthetic-v1",
        "corpus": "SYNTHETIC", "status": "AVAILABLE", "section": f"synthetic-{i}",
        "text": text, "textSha256": digest(text.encode()), "pdfPage": 0, "charStart": 0,
        "charEnd": len(text), "sourceUrl": None} for i, text in enumerate(texts)]


def peak_memory():
    if os.name != "nt":
        return None
    import ctypes
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ["PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage"]]
    info = Counters()
    info.cb = ctypes.sizeof(info)
    handle = ctypes.windll.kernel32.GetCurrentProcess
    handle.restype = wintypes.HANDLE
    get_memory = ctypes.windll.psapi.GetProcessMemoryInfo
    get_memory.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    get_memory.restype = wintypes.BOOL
    if not get_memory(handle(), ctypes.byref(info), info.cb):
        return None
    return round(info.PeakWorkingSetSize / (1024 * 1024), 2)


def retrieve(chunks, spec, output):
    # Weight loading is deliberately offline; downloading is a separate explicit command.
    for item in spec["files"]:
        verify_file(MODEL_DIR / item["name"], item)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    from fastembed import TextEmbedding
    from tokenizers import Tokenizer
    from qdrant_client import QdrantClient, models
    encoder_identity = {"model": spec, "normalization": NORMALIZATION,
        "libraries": {name: importlib.metadata.version(name) for name in ["fastembed", "onnxruntime", "tokenizers"]}}
    fingerprint = digest(json.dumps(encoder_identity, sort_keys=True, ensure_ascii=False).encode())
    started = time.perf_counter()
    model = TextEmbedding(model_name=spec["name"], specific_model_path=str(MODEL_DIR),
        cache_dir=str(ROOT / ".local/rag-preflight/cache"), local_files_only=True,
        threads=2, providers=["CPUExecutionProvider"])
    tokenizer = Tokenizer.from_file(str(MODEL_DIR / "tokenizer.json"))
    tokenizer.no_truncation()
    rows = synthetic_passages() + chunks
    for row in rows:
        row["embeddingVersion"] = fingerprint
    questions = []
    for name in ["questions.json", "holdout-questions.json"]:
        question_set = read_json(HERE / name)
        questions.extend({**item, "evaluationSet": question_set["split"]} for item in question_set["cases"])
    synthetic_query = "自造样本甲通过什么渠道销售？"
    queries = [synthetic_query] + [item["query"] for item in questions]
    inputs = [row["text"] for row in rows] + [spec["queryPrefix"] + query for query in queries]
    token_lengths = [len(tokenizer.encode(text).ids) for text in inputs]
    require(max(token_lengths) <= spec["maxTokens"], "Input would be silently truncated; review chunk boundaries")
    encode_start = time.perf_counter()
    vectors = checked_vectors(model.embed(inputs, batch_size=8), len(inputs), spec["dimensions"])
    encode_seconds = time.perf_counter() - encode_start
    document_vectors, query_vectors = vectors[:len(rows)], vectors[len(rows):]
    canonical = {row["id"]: row for row in rows}
    parent = (ROOT / ".local/rag-preflight").resolve()
    require(parent.is_relative_to(ROOT.resolve()), "Temporary store must remain in the workspace")
    store_path = Path(tempfile.mkdtemp(prefix="qdrant-probe-", dir=parent)).resolve()
    require(store_path.parent == parent and store_path.name.startswith("qdrant-probe-"), "Unsafe temporary store path")
    collection = "rag_preflight_" + uuid.uuid4().hex
    client = None
    outcome = {"pipelineVersion": PIPELINE_VERSION, "model": spec["name"], "modelRevision": spec["revision"], "embeddingVersion": fingerprint,
        "encoderIdentity": encoder_identity,
        "questionSetSha256": {name: digest((HERE / name).read_bytes()) for name in ["questions.json", "holdout-questions.json"]},
        "dimensions": spec["dimensions"], "vectorStore": "Qdrant Python local persistent mode",
        "topK": TOP_K, "minScore": MIN_SCORE, "maxInputTokens": max(token_lengths),
        "chunkCharacters": CHUNK_SIZE, "overlapCharacters": CHUNK_OVERLAP,
        "encodeSeconds": round(encode_seconds, 3), "questions": [], "checks": {}}
    def search(scope, vector, corpus="REPORT", cutoff=None):
        hits = client.query_points(collection, query=vector.tolist(), query_filter=bound_filter(scope, fingerprint, corpus),
            limit=TOP_K, score_threshold=cutoff, with_payload=True).points
        return [validate_hit(hit, canonical) for hit in hits]
    def save_outcome():
        (output / "retrieval.json").write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        client = QdrantClient(path=str(store_path))
        client.create_collection(collection, vectors_config=models.VectorParams(size=spec["dimensions"], distance=models.Distance.COSINE))
        points = [models.PointStruct(id=row["id"], vector=vector.tolist(), payload=row)
                  for row, vector in zip(rows, document_vectors, strict=True)]
        client.upsert(collection, points=points, wait=True)
        require(client.count(collection, exact=True).count == len(rows), "Incomplete index write")
        sample_scope = {"symbol": "SAMPLE-A", "reportVersion": "synthetic-v1"}
        sample = search(sample_scope, query_vectors[0], "SYNTHETIC")
        require(sample and sample[0]["section"] == "synthetic-0", "Self-made Chinese retrieval failed")
        outcome["checks"]["syntheticChineseRetrieval"] = True
        client.upsert(collection, points=points, wait=True)
        require(client.count(collection, exact=True).count == len(rows), "Duplicate import created extra points")
        outcome["checks"]["idempotentUpsert"] = True
        client.close()
        client = QdrantClient(path=str(store_path))
        require(search(sample_scope, query_vectors[0], "SYNTHETIC")[0]["id"] == sample[0]["id"], "Persistent reopen changed result")
        outcome["checks"]["persistentReopen"] = True
        require(not search({"symbol": "UNKNOWN", "reportVersion": "missing"}, query_vectors[0]), "Wrong stock leaked")
        require(not search({"symbol": chunks[0]["symbol"], "reportVersion": "missing"}, query_vectors[0]), "Wrong version leaked")
        outcome["checks"]["wrongStockAndVersionEmpty"] = True
        wrong_model = bound_filter(sample_scope, "wrong-embedding-version", "SYNTHETIC")
        require(not client.query_points(collection, query=query_vectors[0].tolist(), query_filter=wrong_model).points, "Mixed model index accepted")
        outcome["checks"]["wrongEmbeddingVersionEmpty"] = True
        client.set_payload(collection, payload={"status": "REMOVED"}, points=[rows[0]["id"], rows[1]["id"]], wait=True)
        require(not search(sample_scope, query_vectors[0], "SYNTHETIC"), "Removed passages returned")
        outcome["checks"]["removedPassagesEmpty"] = True
        client.upsert(collection, points=points[:2], wait=True)
        for test_case, vector in zip(questions, query_vectors[1:], strict=True):
            candidates = [row for row in chunks if row["symbol"] == test_case["symbol"]]
            versions = {row["reportVersion"] for row in candidates}
            require(len(versions) == 1, "Explicit applicable version required")
            scope = {"symbol": test_case["symbol"], "reportVersion": versions.pop()}
            dense = search(scope, vector)
            accepted = [hit for hit in dense if hit["score"] >= MIN_SCORE]
            keyword = keyword_search(test_case["query"], candidates)
            wanted = set(test_case["expectedSections"])
            outcome["questions"].append({**test_case, "denseCandidates": dense, "acceptedCandidates": accepted,
                "keywordCandidates": keyword, "retrievalStatus": "CANDIDATES_REQUIRE_ANSWER_REVIEW" if accepted else "NO_RELEVANT_PASSAGE",
                "denseHitAt3": bool(wanted.intersection(hit["section"] for hit in dense)) if wanted else None,
                "keywordHitAt3": bool(wanted.intersection(hit["section"] for hit in keyword)) if wanted else None,
                "noAnswerRejected": not accepted if not wanted else None})
        outcome["quality"] = {"answerQuality": "NOT_EVALUATED_NO_LLM", "sets": {}}
        for split in dict.fromkeys(row["evaluationSet"] for row in questions):
            evaluated = [row for row in outcome["questions"] if row["evaluationSet"] == split]
            positive = [row for row in evaluated if row["expectedSections"]]
            negative = [row for row in evaluated if not row["expectedSections"]]
            outcome["quality"]["sets"][split] = {"positiveQuestions": len(positive),
                "denseHitAt1": sum(bool(row["denseCandidates"] and row["denseCandidates"][0]["section"] in row["expectedSections"]) for row in positive),
                "denseHitAt3": sum(row["denseHitAt3"] for row in positive),
                "acceptedHitAt3": sum(bool(set(row["expectedSections"]).intersection(hit["section"] for hit in row["acceptedCandidates"])) for row in positive),
                "keywordHitAt3": sum(row["keywordHitAt3"] for row in positive), "negativeQuestions": len(negative),
                "negativeRejected": sum(row["noAnswerRejected"] for row in negative)}
        # Re-read actual source hashes before treating any stored offsets as usable.
        for source in reviewed_sources():
            source_path(source)
        outcome["checks"]["sourceVersionsRevalidated"] = True
    finally:
        try:
            if client is not None:
                try:
                    names = [item.name for item in client.get_collections().collections]
                    if collection in names:
                        # Local delete_collection drops its handle before SQLite close on Windows.
                        # Delete this run's exact point IDs, verify zero, then close the whole store.
                        client.delete(collection, points_selector=models.PointIdsList(points=list(canonical)), wait=True)
                        remaining = client.count(collection, exact=True).count
                        outcome["checks"]["pointsRemaining"] = remaining
                        require(remaining == 0, "Temporary vector data remains")
                finally:
                    client.close()
            require(store_path.parent == parent and store_path.name.startswith("qdrant-probe-"), "Unsafe cleanup target")
            shutil.rmtree(store_path)
            outcome["checks"]["temporaryStoreRemoved"] = not store_path.exists()
        finally:
            try:
                outcome["peakWorkingSetMiB"] = peak_memory()
                outcome["totalSeconds"] = round(time.perf_counter() - started, 3)
            finally:
                save_outcome()
    return outcome


def write_review(output, result):
    labels = {"CALIBRATION": "校准集", "HOLDOUT_AFTER_CUTOFF_FIXED": "留出改写集"}
    lines = ["# 本地报告段落检索预检", "",
        "这份记录只评价片段检索；没有调用回答模型，也没有接入业务网页。相似片段仍需核对是否支持回答。", "",
        f"模型：{result['model']}；维度：{result['dimensions']}；候选门槛：{result['minScore']}；最多 {result['topK']} 个候选。", "",
        "| 问题集 | 有答案题数 | 第一候选命中 | 前三候选命中 | 门槛后命中 | 关键词前三命中 | 无答案题过滤 |",
        "| --- | --- | --- | --- | --- | --- | --- |"]
    for name, scores in result["quality"]["sets"].items():
        count = scores["positiveQuestions"]
        lines.append(f"| {labels[name]} | {count} | {scores['denseHitAt1']}/{count} | {scores['denseHitAt3']}/{count} | "
            f"{scores['acceptedHitAt3']}/{count} | {scores['keywordHitAt3']}/{count} | {scores['negativeRejected']}/{scores['negativeQuestions']} |")
    lines += ["", "0.45 门槛只使用 CALIBRATION 问题确定，另一组改写问题随后执行，没有根据其结果再改门槛。", "",
        "| 问题集 | 问题 | 门槛后候选位置：PDF文件页码／章节 | 结果 |", "| --- | --- | --- | --- |"]
    for row in result["questions"]:
        locations = "；".join(f"[第 {hit['pdfPage']} 页]({hit['sourceUrl']}#page={hit['pdfPage']}) {hit['section']} ({hit['score']:.4f})"
                              for hit in row["acceptedCandidates"]) or "无相关片段"
        status = "候选仍需核对能否支持回答" if row["acceptedCandidates"] else "无相关片段"
        lines.append(f"| {labels[row['evaluationSet']]} | {row['query']} | {locations} | {status} |")
    lines += ["", "保留问题：校准集营销问题的第一候选为金融科技，正确营销段落在第二位。不能把第一名直接当成事实依据。", "",
        "来源范围为两份报告的七个页面、十个受审文字小节；复杂表格和其他章节未导入。", "",
        f"本机采样：向量化 {result['encodeSeconds']} 秒；模型加载至临时库清理 {result['totalSeconds']} 秒；进程峰值工作集 {result['peakWorkingSetMiB']} MiB。"
        "这些是小样本单轮记录，不代表服务端性能。", "",
        "Qdrant 使用 Python 本地持久化模式；Java 业务权限、服务端 Qdrant 通信、网页引用和模型回答质量尚待正式接入验证。", "",
        "测试向量已删除并核对为零，临时向量库目录已移除；模型缓存保留供复现。"]
    (output / "review.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["plan", "parse", "retrieve"], default="plan")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    sources = reviewed_sources()
    spec = read_json(HERE / "model.json")
    plan = {"pipelineVersion": PIPELINE_VERSION, "reports": [{"file": row["file"], "symbol": row["symbol"], "sha256": row["sha256"],
        "pdfPages": sorted({item["page"] for item in row["sections"]})} for row in sources],
        "model": spec["name"], "dimensions": spec["dimensions"], "modelRevision": spec["revision"],
        "execution": "LOCAL_CPU_QDRANT_LOCAL_ONLY", "paidApiCalls": 0, "applicationDatabaseWrites": 0,
        "applicationIntegration": "NOT_IMPLEMENTED_BY_THIS_PREFLIGHT"}
    if args.stage == "plan":
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return
    output = (args.output or ROOT / "target" / ("rag-preflight-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])).resolve()
    require(output.is_relative_to((ROOT / "target").resolve()) and output != (ROOT / "target").resolve(), "Output must be a task directory under target")
    output.mkdir(parents=True, exist_ok=False)
    chunks, comparisons = extract_reports(sources)
    (output / "passages.json").write_text(json.dumps(chunks, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result = {**plan, "stage": args.stage, "passages": len(chunks), "paragraphSections": sum(len(row["sections"]) for row in sources),
        "parserComparison": comparisons, "python": platform.python_version(), "platform": platform.platform(),
        "versions": {name: importlib.metadata.version(name) for name in ["PyMuPDF", "pypdf", "fastembed", "qdrant-client", "onnxruntime", "numpy", "tokenizers"]}}
    if args.stage == "retrieve":
        retrieval = retrieve(chunks, spec, output)
        result.update({"quality": retrieval["quality"], "checks": retrieval["checks"]})
        write_review(output, retrieval)
    (output / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"stage": args.stage, "output": str(output), "passages": len(chunks),
        "quality": result.get("quality"), "checks": result.get("checks"), "paidApiCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
