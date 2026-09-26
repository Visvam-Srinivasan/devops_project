#!/usr/bin/env python3
"""
query.py — CLI that proves retrieval works: embeds a question locally and
prints the top-k matching chunks from ChromaDB, with their source doc.

Usage:
    python3 query.py "how should containers run" --top-k 3

By default this assumes you're running it from the same place ingest.py
runs (inside the ingestion-worker container, talking to the 'chroma'
service by its docker-compose service name). Pass --chroma-host localhost
if you're running it from the host machine instead (with docker-compose's
port mapping exposing 8000 on localhost).
"""
import argparse

import chromadb
from sentence_transformers import SentenceTransformer

COLLECTION_NAME = "rag_docs"
MODEL_NAME = "all-MiniLM-L6-v2"


def main():
    parser = argparse.ArgumentParser(description="Query the RAG knowledge base.")
    parser.add_argument("question", help="Question / search text")
    parser.add_argument("--top-k", type=int, default=3)
    parser.add_argument("--chroma-host", default="chroma")
    parser.add_argument("--chroma-port", type=int, default=8000)
    args = parser.parse_args()

    model = SentenceTransformer(MODEL_NAME)
    query_embedding = model.encode([args.question]).tolist()

    client = chromadb.HttpClient(
        host=args.chroma_host, port=args.chroma_port,
        settings=chromadb.config.Settings(anonymized_telemetry=False),
    )
    try:
        collection = client.get_collection(COLLECTION_NAME)
    except Exception:
        print(f"Collection '{COLLECTION_NAME}' does not exist yet — nothing has been ingested.")
        return

    results = collection.query(query_embeddings=query_embedding, n_results=args.top_k)

    ids = results.get("ids", [[]])[0]
    documents = results.get("documents", [[]])[0]
    metadatas = results.get("metadatas", [[]])[0]
    distances = results.get("distances", [[]])[0]

    if not ids:
        print("No results found.")
        return

    print(f"Top {len(ids)} result(s) for: {args.question!r}\n")
    for rank, (chunk_id, doc, meta, dist) in enumerate(zip(ids, documents, metadatas, distances), start=1):
        print(f"#{rank}  source={meta.get('source')}  chunk_index={meta.get('chunk_index')}  distance={dist:.4f}")
        snippet = doc if len(doc) <= 200 else doc[:200] + "..."
        print(f"    {snippet}\n")


if __name__ == "__main__":
    main()
