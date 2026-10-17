"""Download only pinned public model assets; never send documents or credentials."""
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MODEL_DIR = ROOT / ".local/rag-preflight/model"


def verify_file(path, item):
    data = path.read_bytes()
    if len(data) != item["size"]:
        raise ValueError("Model file size changed")
    if "sha256" in item:
        digest = hashlib.sha256(data).hexdigest()
        expected = item["sha256"]
    else:
        digest = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        expected = item["gitBlobSha1"]
    if digest != expected:
        raise ValueError("Model file hash changed")


def main():
    spec = json.loads((HERE / "model.json").read_text(encoding="utf-8"))
    if spec["repository"] != "Qdrant/bge-small-zh-v1.5" or len(spec["revision"]) != 40:
        raise ValueError("Unreviewed model source")
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    for item in spec["files"]:
        if Path(item["name"]).name != item["name"]:
            raise ValueError("Invalid model file path")
        path = MODEL_DIR / item["name"]
        if path.is_file():
            verify_file(path, item)
            print(f"MODEL_ASSET_VERIFIED {item['name']}", flush=True)
            continue
        temporary = path.with_suffix(path.suffix + ".part")
        url = f"https://huggingface.co/{spec['repository']}/resolve/{spec['revision']}/{item['name']}"
        try:
            count = 0
            with urlopen(Request(url, headers={"User-Agent": "stock-system-rag-preflight"}), timeout=30) as response:
                with temporary.open("wb") as output:
                    while block := response.read(1024 * 1024):
                        count += len(block)
                        if count > item["size"]:
                            raise ValueError("Unexpected model asset response")
                        output.write(block)
            verify_file(temporary, item)
            temporary.replace(path)
            print(f"MODEL_ASSET_VERIFIED {item['name']}", flush=True)
        finally:
            temporary.unlink(missing_ok=True)
    print("RAG_MODEL_DOWNLOAD_VERIFIED", flush=True)


if __name__ == "__main__":
    main()
