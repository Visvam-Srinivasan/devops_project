#!/usr/bin/env python3
"""
ingest.py — unzips the Jenkins-validated artifact, chunks the document,
embeds each chunk locally (sentence-transformers, no external API), and
upserts the chunks into ChromaDB tagged with manifest metadata.

Run by Ansible's deploy.yml on the ingestion-worker "VM", e.g.:

    python3 ingest.py --input /opt/ingestion/incoming \
                       --chroma-host chroma --chroma-port 8000 \
                       --state-dir /opt/ingestion/state

Idempotency note: this script always upserts with a deterministic ID
(sha256-chunkindex), so re-running it for the same document never creates
duplicate chunks. On top of that it touches a "<sha256>.done" marker file
in --state-dir so Ansible's `creates=` argument can skip re-running it
entirely once a given document has been ingested.
"""
import argparse
import json
import re
from pathlib import Path

import chromadb
from sentence_transformers import SentenceTransformer

COLLECTION_NAME = "rag_docs"
MODEL_NAME = "all-MiniLM-L6-v2"
CHUNK_SIZE = 500      # characters
CHUNK_OVERLAP = 50    # characters


def strip_frontmatter(text: str) -> str:
    """Drops a leading '---\\n...\\n---' YAML block, if present, so we only embed body text."""
    if not text.lstrip().startswith("---"):
        return text
    parts = text.lstrip().split("\n")
    if parts[0].strip() != "---":
        return text
    for i in range(1, len(parts)):
        if parts[i].strip() == "---":
            return "\n".join(parts[i + 1:])
    return text


def chunk_text(text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[str]:
    """Simple fixed-size sliding-window chunker over whitespace-collapsed text."""
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return []
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        chunks.append(text[start:end])
        if end == len(text):
            break
        start = end - overlap
    return chunks


def main():
    parser = argparse.ArgumentParser(description="Chunk, embed, and upsert a validated doc into Chroma.")
    parser.add_argument("--input", required=True, help="Directory containing the extracted doc + manifest.json")
    parser.add_argument("--chroma-host", default="chroma")
    parser.add_argument("--chroma-port", type=int, default=8000)
    parser.add_argument("--state-dir", required=True, help="Directory to write the <sha256>.done idempotency marker into")
    args = parser.parse_args()

    input_dir = Path(args.input)
    manifest_path = input_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    doc_path = input_dir / manifest["filename"]
    raw_text = doc_path.read_text(encoding="utf-8")
    body = strip_frontmatter(raw_text)

    chunks = chunk_text(body)
    if not chunks:
        raise SystemExit(f"No chunkable content found in {doc_path}")

    print(f"Loading embedding model '{MODEL_NAME}' (downloads on first run, then cached)...")
    model = SentenceTransformer(MODEL_NAME)
    embeddings = model.encode(chunks, show_progress_bar=False).tolist()

    client = chromadb.HttpClient(
        host=args.chroma_host, port=args.chroma_port,
        settings=chromadb.config.Settings(anonymized_telemetry=False),
    )
    collection = client.get_or_create_collection(COLLECTION_NAME)

    sha256 = manifest["sha256"]
    ids = [f"{sha256}-{i}" for i in range(len(chunks))]
    metadatas = [
        {
            "source": manifest["filename"],
            "sha256": sha256,
            "timestamp": manifest["timestamp"],
            "chunk_index": i,
        }
        for i in range(len(chunks))
    ]

    collection.upsert(ids=ids, embeddings=embeddings, documents=chunks, metadatas=metadatas)
    print(f"Upserted {len(chunks)} chunk(s) from '{manifest['filename']}' (sha256={sha256}) into '{COLLECTION_NAME}'.")

    state_dir = Path(args.state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / f"{sha256}.done").touch()


if __name__ == "__main__":
    main()
