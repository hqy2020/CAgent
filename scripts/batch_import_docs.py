#!/usr/bin/env python3
"""
RAgent 知识库批量导入脚本
将 GardenOfOpeningClouds 下的 Markdown/PDF/DOC 文档批量上传到 RAgent 知识库。

知识库映射：
  1-Information（项目与任务） → kb_information
  2-Resource（参考资源）      → kb_resource
  3-Knowledge（知识库）       → kb_knowledge

用法：
  python scripts/batch_import_docs.py \
    --base-url http://localhost:8080/api/ragent \
    --token <SA-TOKEN> \
    --docs-root /Users/openingcloud/Documents/GardenOfOpeningClouds \
    --upload-concurrency 3 \
    --chunk-concurrency 2
"""

import argparse
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import requests

# ────────────────────── 常量 ──────────────────────

SUPPORTED_EXTENSIONS = {".md", ".pdf", ".doc", ".docx", ".txt"}

HIDDEN_DIRS = {
    ".ai-team", ".smart-env", ".cursor", ".obsidian", ".git",
    ".trash", ".claude", ".vscode", ".agent", ".serena",
    ".codex", ".minimax", ".claude-flow", ".DS_Store",
}

MIN_FILE_SIZE = 10  # 小于 10 bytes 的文件跳过

# 知识库目录映射
KB_MAPPINGS = [
    {
        "dir_prefix": "1-Information",
        "kb_name": "项目与任务",
        "collection": "kb_information",
    },
    {
        "dir_prefix": "2-Resource",
        "kb_name": "参考资源",
        "collection": "kb_resource",
    },
    {
        "dir_prefix": "3-Knowledge",
        "kb_name": "知识库",
        "collection": "kb_knowledge",
    },
]

EMBEDDING_MODEL = "qwen3-embedding:8b-fp16"

# ────────────────────── 数据结构 ──────────────────────


@dataclass
class Progress:
    """断点续传进度"""
    created_kbs: dict = field(default_factory=dict)  # collection -> kb_id
    uploaded: dict = field(default_factory=dict)      # file_path -> doc_id
    chunked: set = field(default_factory=set)         # set of doc_id

    def save(self, path: str):
        data = {
            "created_kbs": self.created_kbs,
            "uploaded": self.uploaded,
            "chunked": list(self.chunked),
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    @classmethod
    def load(cls, path: str) -> "Progress":
        if not os.path.exists(path):
            return cls()
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        p = cls()
        p.created_kbs = data.get("created_kbs", {})
        p.uploaded = data.get("uploaded", {})
        p.chunked = set(data.get("chunked", []))
        return p


# ────────────────────── API 客户端 ──────────────────────


class RAgentClient:
    def __init__(self, base_url: str, token: str, max_retries: int = 3):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.max_retries = max_retries
        self.session = requests.Session()
        self.session.headers.update({"satoken": token})

    def _request(self, method: str, path: str, **kwargs) -> dict:
        url = f"{self.base_url}{path}"
        for attempt in range(self.max_retries):
            try:
                resp = self.session.request(method, url, timeout=120, **kwargs)
                resp.raise_for_status()
                result = resp.json()
                if str(result.get("code")) != "0":
                    raise RuntimeError(
                        f"API error: {result.get('message', 'unknown')} (code={result.get('code')})"
                    )
                return result
            except (requests.RequestException, RuntimeError) as e:
                if attempt == self.max_retries - 1:
                    raise
                wait = 2 ** attempt
                print(f"  [重试 {attempt + 1}/{self.max_retries}] {e} — 等待 {wait}s")
                time.sleep(wait)

    def list_knowledge_bases(self, name: str = None) -> list:
        params = {"size": 100}
        if name:
            params["name"] = name
        result = self._request("GET", "/knowledge-base", params=params)
        return result.get("data", {}).get("records", [])

    def create_knowledge_base(self, name: str, collection: str) -> str:
        body = {
            "name": name,
            "embeddingModel": EMBEDDING_MODEL,
            "collectionName": collection,
        }
        result = self._request("POST", "/knowledge-base", json=body)
        return str(result["data"])

    def upload_document(self, kb_id: str, file_path: str) -> str:
        with open(file_path, "rb") as f:
            files = {"file": (os.path.basename(file_path), f)}
            data = {
                "processMode": "chunk",
                "chunkStrategy": "structure_aware",
            }
            result = self._request(
                "POST",
                f"/knowledge-base/{kb_id}/docs/upload",
                files=files,
                data=data,
            )
        doc_data = result.get("data", {})
        return str(doc_data.get("id", ""))

    def trigger_chunk(self, doc_id: str):
        self._request("POST", f"/knowledge-base/docs/{doc_id}/chunk")

    def get_document(self, doc_id: str) -> dict:
        result = self._request("GET", f"/knowledge-base/docs/{doc_id}")
        return result.get("data", {})


# ────────────────────── 文件扫描 ──────────────────────


def should_skip_dir(dir_name: str) -> bool:
    if dir_name.startswith("."):
        return True
    if dir_name in HIDDEN_DIRS:
        return True
    return False


def scan_files(root: str) -> list:
    """递归扫描目录下的文档文件，排除隐藏目录和太小的文件"""
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        # 过滤隐藏目录（就地修改 dirnames 以阻止递归）
        dirnames[:] = [d for d in dirnames if not should_skip_dir(d)]

        for fname in filenames:
            if fname.startswith("."):
                continue
            ext = os.path.splitext(fname)[1].lower()
            if ext not in SUPPORTED_EXTENSIONS:
                continue
            fpath = os.path.join(dirpath, fname)
            if os.path.getsize(fpath) < MIN_FILE_SIZE:
                continue
            files.append(fpath)

    return sorted(files)


def resolve_kb_for_file(file_path: str, docs_root: str) -> Optional[dict]:
    """根据文件路径确定属于哪个知识库"""
    rel = os.path.relpath(file_path, docs_root)
    top_dir = rel.split(os.sep)[0]

    for mapping in KB_MAPPINGS:
        if top_dir.startswith(mapping["dir_prefix"]):
            return mapping
    return None


# ────────────────────── 主流程 ──────────────────────


def ensure_knowledge_bases(client: RAgentClient, progress: Progress) -> dict:
    """确保三个知识库存在，返回 collection -> kb_id 映射"""
    kb_map = dict(progress.created_kbs)

    for mapping in KB_MAPPINGS:
        collection = mapping["collection"]
        kb_name = mapping["kb_name"]

        if collection in kb_map:
            print(f"  [复用] {kb_name} (collection={collection}, id={kb_map[collection]})")
            continue

        # 按名称查找已存在的知识库
        existing = client.list_knowledge_bases(name=kb_name)
        found = None
        for kb in existing:
            if kb.get("collectionName") == collection:
                found = kb
                break

        if found:
            kb_id = str(found["id"])
            print(f"  [已存在] {kb_name} (collection={collection}, id={kb_id})")
        else:
            kb_id = client.create_knowledge_base(kb_name, collection)
            print(f"  [新建] {kb_name} (collection={collection}, id={kb_id})")

        kb_map[collection] = kb_id

    progress.created_kbs = kb_map
    return kb_map


def upload_files(
    client: RAgentClient,
    kb_map: dict,
    files: list,
    progress: Progress,
    progress_path: str,
    concurrency: int,
):
    """并发上传文件"""
    to_upload = [f for f in files if f not in progress.uploaded]

    if not to_upload:
        print(f"\n所有 {len(files)} 个文件已上传，跳过。")
        return

    print(f"\n开始上传: {len(to_upload)} 个文件 (已完成: {len(progress.uploaded)})")

    done = 0
    errors = 0

    def _upload_one(file_path: str) -> tuple:
        mapping = resolve_kb_for_file(file_path, args.docs_root)
        if not mapping:
            return file_path, None, "无法确定知识库映射"
        kb_id = kb_map.get(mapping["collection"])
        if not kb_id:
            return file_path, None, f"知识库 {mapping['collection']} 未创建"
        try:
            doc_id = client.upload_document(kb_id, file_path)
            return file_path, doc_id, None
        except Exception as e:
            return file_path, None, str(e)

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = {pool.submit(_upload_one, f): f for f in to_upload}

        for future in as_completed(futures):
            file_path, doc_id, error = future.result()
            done += 1
            fname = os.path.basename(file_path)

            if error:
                errors += 1
                print(f"  [{done}/{len(to_upload)}] FAIL: {fname} — {error}")
            else:
                progress.uploaded[file_path] = doc_id
                print(f"  [{done}/{len(to_upload)}] OK: {fname} → doc={doc_id}")

            # 每 10 个保存一次进度
            if done % 10 == 0:
                progress.save(progress_path)

    progress.save(progress_path)
    print(f"\n上传完成: 成功 {done - errors}, 失败 {errors}")


def trigger_chunking(
    client: RAgentClient,
    progress: Progress,
    progress_path: str,
    concurrency: int,
    interval: float,
):
    """批量触发分块"""
    to_chunk = [
        (fpath, doc_id)
        for fpath, doc_id in progress.uploaded.items()
        if doc_id and doc_id not in progress.chunked
    ]

    if not to_chunk:
        print(f"\n所有文档已触发分块，跳过。")
        return

    print(f"\n开始分块: {len(to_chunk)} 个文档 (已完成: {len(progress.chunked)})")

    done = 0
    errors = 0

    def _chunk_one(item: tuple) -> tuple:
        fpath, doc_id = item
        try:
            client.trigger_chunk(doc_id)
            return doc_id, None
        except Exception as e:
            return doc_id, str(e)

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = {}
        for i, item in enumerate(to_chunk):
            future = pool.submit(_chunk_one, item)
            futures[future] = item
            # 控制提交节奏，避免服务端过载
            if (i + 1) % concurrency == 0 and interval > 0:
                time.sleep(interval)

        for future in as_completed(futures):
            doc_id, error = future.result()
            done += 1

            if error:
                errors += 1
                print(f"  [{done}/{len(to_chunk)}] CHUNK FAIL: doc={doc_id} — {error}")
            else:
                progress.chunked.add(doc_id)
                print(f"  [{done}/{len(to_chunk)}] CHUNKED: doc={doc_id}")

            if done % 10 == 0:
                progress.save(progress_path)

    progress.save(progress_path)
    print(f"\n分块完成: 成功 {done - errors}, 失败 {errors}")


def print_summary(progress: Progress, files: list):
    """打印最终统计"""
    print("\n" + "=" * 50)
    print("导入完成统计")
    print("=" * 50)

    # 按知识库统计
    kb_stats = {}
    for fpath, doc_id in progress.uploaded.items():
        mapping = resolve_kb_for_file(fpath, args.docs_root)
        if mapping:
            name = mapping["kb_name"]
            kb_stats.setdefault(name, {"uploaded": 0, "chunked": 0})
            kb_stats[name]["uploaded"] += 1
            if doc_id in progress.chunked:
                kb_stats[name]["chunked"] += 1

    for name, stats in kb_stats.items():
        print(f"  {name}: 上传 {stats['uploaded']}, 分块 {stats['chunked']}")

    total_uploaded = len(progress.uploaded)
    total_chunked = len(progress.chunked)
    total_files = len(files)

    print(f"\n  总计: {total_uploaded}/{total_files} 上传, {total_chunked} 已分块")
    not_uploaded = total_files - total_uploaded
    if not_uploaded > 0:
        print(f"  未上传: {not_uploaded} 个文件")
    print("=" * 50)


# ────────────────────── CLI ──────────────────────

def parse_args():
    parser = argparse.ArgumentParser(
        description="RAgent 知识库批量导入脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--base-url",
        required=True,
        help="RAgent API 基础 URL, e.g. http://localhost:8080/api/ragent",
    )
    parser.add_argument(
        "--token",
        required=True,
        help="SA-Token 认证令牌",
    )
    parser.add_argument(
        "--docs-root",
        required=True,
        help="GardenOfOpeningClouds 根目录路径",
    )
    parser.add_argument(
        "--upload-concurrency",
        type=int,
        default=3,
        help="上传并发数 (默认: 3)",
    )
    parser.add_argument(
        "--chunk-concurrency",
        type=int,
        default=2,
        help="分块并发数 (默认: 2)",
    )
    parser.add_argument(
        "--chunk-interval",
        type=float,
        default=2.0,
        help="分块批次间隔秒数 (默认: 2.0)",
    )
    parser.add_argument(
        "--progress-file",
        default=None,
        help="进度文件路径 (默认: <docs-root>/.ragent_import_progress.json)",
    )
    parser.add_argument(
        "--skip-chunk",
        action="store_true",
        help="只上传，不触发分块",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="模拟运行，只扫描文件不实际上传",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()

    docs_root = os.path.expanduser(args.docs_root)
    if not os.path.isdir(docs_root):
        print(f"错误: 文档目录不存在: {docs_root}")
        sys.exit(1)

    progress_path = args.progress_file or os.path.join(
        docs_root, ".ragent_import_progress.json"
    )

    print("RAgent 知识库批量导入")
    print(f"  文档根目录: {docs_root}")
    print(f"  API 地址: {args.base_url}")
    print(f"  进度文件: {progress_path}")
    print()

    # 1. 扫描文件
    print("[1/4] 扫描文档文件...")
    all_files = scan_files(docs_root)
    # 过滤只保留有知识库映射的文件
    all_files = [f for f in all_files if resolve_kb_for_file(f, docs_root) is not None]
    print(f"  发现 {len(all_files)} 个待导入文件")

    if args.dry_run:
        for f in all_files:
            mapping = resolve_kb_for_file(f, docs_root)
            print(f"  → [{mapping['kb_name']}] {os.path.relpath(f, docs_root)}")
        print("\n(dry-run 模式，不执行实际操作)")
        sys.exit(0)

    # 2. 加载进度
    progress = Progress.load(progress_path)
    client = RAgentClient(args.base_url, args.token)

    # 3. 确保知识库存在
    print("\n[2/4] 检查/创建知识库...")
    kb_map = ensure_knowledge_bases(client, progress)
    progress.save(progress_path)

    # 4. 上传文件
    print("\n[3/4] 上传文档...")
    upload_files(client, kb_map, all_files, progress, progress_path, args.upload_concurrency)

    # 5. 触发分块
    if not args.skip_chunk:
        print("\n[4/4] 触发文档分块...")
        trigger_chunking(client, progress, progress_path, args.chunk_concurrency, args.chunk_interval)
    else:
        print("\n[4/4] 跳过分块 (--skip-chunk)")

    # 6. 统计
    print_summary(progress, all_files)
