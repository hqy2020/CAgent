#!/usr/bin/env python3
"""
春招备考知识库批量导入脚本

清空所有现有知识库，然后创建四个面向春招备考的知识库并导入文档：
  1. 阿里云实习 (13 docs)  — 实习经历与面试速查
  2. 八股文备考 (219 docs) — Java/Spring/Redis/JVM 等核心八股
  3. 牛券oneCoupon (48 docs) — 优惠券系统项目实战
  4. AI大模型Ragent (25 docs) — RAG 系统设计与实践

用法：
  python3 scripts/import_spring_interview_kbs.py
"""

import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

import requests

# ────────────────────── 配置 ──────────────────────

BASE_URL = "http://localhost:8080/api/ragent"
LOGIN_USER = "admin"
LOGIN_PASS = "admin"

EMBEDDING_MODEL = "qwen-emb-8b"

UPLOAD_CONCURRENCY = 3
CHUNK_CONCURRENCY = 2
CHUNK_INTERVAL = 2.0  # 分块批次间隔（秒），避免 embedding 速率限制

PROGRESS_FILE = os.path.join(
    os.path.dirname(__file__), ".spring_kb_import_progress.json"
)

HIDDEN_DIRS = {
    ".ai-team", ".smart-env", ".cursor", ".obsidian", ".git",
    ".trash", ".claude", ".vscode", ".agent", ".serena",
    ".codex", ".minimax", ".claude-flow", ".DS_Store",
}

MIN_FILE_SIZE = 10  # bytes

# 知识库定义：name, collection, 源目录
KB_DEFINITIONS = [
    {
        "name": "阿里云实习",
        "collection": "kb_aliyun_intern",
        "source_dir": "/Users/openingcloud/Documents/GardenOfOpeningClouds/"
                      "1-Information（项目与任务）/202601_春招/阿里云",
    },
    {
        "name": "八股文备考",
        "collection": "kb_bagu_prep",
        "source_dir": "/Users/openingcloud/Documents/GardenOfOpeningClouds/"
                      "2-Resource（参考资源）/90_八股文",
    },
    {
        "name": "牛券oneCoupon",
        "collection": "kb_onecoupon",
        "source_dir": "/Users/openingcloud/Documents/GardenOfOpeningClouds/"
                      "2-Resource（参考资源）/21_课程讲座/牛券oneCoupon优惠券系统设计",
    },
    {
        "name": "AI大模型Ragent",
        "collection": "kb_ragent_project",
        "source_dir": "/Users/openingcloud/Documents/GardenOfOpeningClouds/"
                      "2-Resource（参考资源）/21_课程讲座/AI大模型Ragent项目",
    },
]

# ────────────────────── 断点续传 ──────────────────────


@dataclass
class Progress:
    created_kbs: dict = field(default_factory=dict)   # collection -> kb_id
    uploaded: dict = field(default_factory=dict)       # file_path -> doc_id
    chunked: set = field(default_factory=set)          # set of doc_id
    cleared: bool = False                              # 是否已清空旧知识库

    def save(self, path: str):
        data = {
            "created_kbs": self.created_kbs,
            "uploaded": self.uploaded,
            "chunked": list(self.chunked),
            "cleared": self.cleared,
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
        p.cleared = data.get("cleared", False)
        return p


# ────────────────────── API 客户端 ──────────────────────


class RAgentClient:
    def __init__(self, base_url: str, max_retries: int = 3):
        self.base_url = base_url.rstrip("/")
        self.max_retries = max_retries
        self.session = requests.Session()
        self.session.trust_env = False  # 禁用系统代理，localhost 不走 Clash
        self.token = None

    def login(self, username: str, password: str) -> str:
        """登录并返回 SA-Token"""
        url = f"{self.base_url}/auth/login"
        resp = self.session.post(url, json={
            "username": username,
            "password": password,
        }, timeout=30)
        resp.raise_for_status()
        result = resp.json()
        if str(result.get("code")) != "0":
            raise RuntimeError(f"登录失败: {result.get('message')}")
        self.token = result["data"]["token"]
        self.session.headers.update({"Authorization": self.token})
        return self.token

    def _request(self, method: str, path: str, **kwargs) -> dict:
        url = f"{self.base_url}{path}"
        for attempt in range(self.max_retries):
            try:
                resp = self.session.request(method, url, timeout=120, **kwargs)
                resp.raise_for_status()
                result = resp.json()
                if str(result.get("code")) != "0":
                    raise RuntimeError(
                        f"API error: {result.get('message', 'unknown')} "
                        f"(code={result.get('code')})"
                    )
                return result
            except (requests.RequestException, RuntimeError) as e:
                if attempt == self.max_retries - 1:
                    raise
                wait = 2 ** attempt
                print(f"  [重试 {attempt + 1}/{self.max_retries}] {e} — 等待 {wait}s")
                time.sleep(wait)

    def list_knowledge_bases(self) -> list:
        result = self._request("GET", "/knowledge-base", params={"size": 100})
        return result.get("data", {}).get("records", [])

    def delete_knowledge_base(self, kb_id: str):
        self._request("DELETE", f"/knowledge-base/{kb_id}")

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

    def list_documents(self, kb_id: str) -> list:
        """分页获取知识库下所有文档"""
        all_docs = []
        page_no = 1
        while True:
            result = self._request(
                "GET", f"/knowledge-base/{kb_id}/docs",
                params={"pageNo": page_no, "pageSize": 100},
            )
            records = result.get("data", {}).get("records", [])
            all_docs.extend(records)
            total = result.get("data", {}).get("total", 0)
            if len(all_docs) >= total or not records:
                break
            page_no += 1
        return all_docs

    def delete_document(self, doc_id: str):
        self._request("DELETE", f"/knowledge-base/docs/{doc_id}")


# ────────────────────── 文件扫描 ──────────────────────


def scan_md_files(root: str) -> list:
    """递归扫描目录下的 .md 文件"""
    files = []
    if not os.path.isdir(root):
        print(f"  警告: 目录不存在 — {root}")
        return files

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [
            d for d in dirnames
            if not d.startswith(".") and d not in HIDDEN_DIRS
        ]
        for fname in sorted(filenames):
            if fname.startswith("."):
                continue
            if not fname.endswith(".md"):
                continue
            fpath = os.path.join(dirpath, fname)
            if os.path.getsize(fpath) < MIN_FILE_SIZE:
                continue
            files.append(fpath)

    return sorted(files)


# ────────────────────── 主流程 ──────────────────────


def step_clear_old_kbs(client: RAgentClient, progress: Progress):
    """清空所有现有知识库（先删文档，再删知识库）"""
    if progress.cleared:
        print("  已清空（上次运行），跳过。")
        return

    existing = client.list_knowledge_bases()
    if not existing:
        print("  没有现有知识库，跳过。")
        progress.cleared = True
        return

    print(f"  发现 {len(existing)} 个现有知识库，逐个清理...")
    for kb in existing:
        kb_id = str(kb["id"])
        kb_name = kb.get("name", "unknown")

        # 先删除该知识库下所有文档
        try:
            docs = client.list_documents(kb_id)
            if docs:
                print(f"    {kb_name}: 删除 {len(docs)} 个文档...")
                for doc in docs:
                    doc_id = str(doc["id"])
                    try:
                        client.delete_document(doc_id)
                    except Exception as e:
                        print(f"      删除文档失败: doc={doc_id} — {e}")
        except Exception as e:
            print(f"    {kb_name}: 获取文档列表失败 — {e}")

        # 再删除知识库
        try:
            client.delete_knowledge_base(kb_id)
            print(f"    删除知识库: {kb_name} (id={kb_id})")
        except Exception as e:
            print(f"    删除知识库失败: {kb_name} (id={kb_id}) — {e}")

    progress.cleared = True


def step_create_kbs(client: RAgentClient, progress: Progress) -> dict:
    """创建三个新知识库，返回 collection -> kb_id 映射"""
    kb_map = dict(progress.created_kbs)

    for defn in KB_DEFINITIONS:
        collection = defn["collection"]
        name = defn["name"]

        if collection in kb_map:
            print(f"  [复用] {name} (id={kb_map[collection]})")
            continue

        kb_id = client.create_knowledge_base(name, collection)
        print(f"  [新建] {name} (collection={collection}, id={kb_id})")
        kb_map[collection] = kb_id

    progress.created_kbs = kb_map
    return kb_map


def step_upload(
    client: RAgentClient,
    kb_id: str,
    kb_name: str,
    files: list,
    progress: Progress,
):
    """上传一个知识库的所有文档"""
    to_upload = [f for f in files if f not in progress.uploaded]

    if not to_upload:
        print(f"  {kb_name}: 全部 {len(files)} 个文件已上传，跳过。")
        return

    already = len(files) - len(to_upload)
    print(f"  {kb_name}: 待上传 {len(to_upload)} 个 (已完成 {already})")

    done = 0
    errors = 0

    def _upload_one(fpath):
        try:
            doc_id = client.upload_document(kb_id, fpath)
            return fpath, doc_id, None
        except Exception as e:
            return fpath, None, str(e)

    with ThreadPoolExecutor(max_workers=UPLOAD_CONCURRENCY) as pool:
        futures = {pool.submit(_upload_one, f): f for f in to_upload}

        for future in as_completed(futures):
            fpath, doc_id, error = future.result()
            done += 1
            fname = os.path.basename(fpath)

            if error:
                errors += 1
                print(f"    [{done}/{len(to_upload)}] FAIL: {fname} — {error}")
            else:
                progress.uploaded[fpath] = doc_id
                print(f"    [{done}/{len(to_upload)}] OK: {fname} → {doc_id}")

            if done % 10 == 0:
                progress.save(PROGRESS_FILE)

    progress.save(PROGRESS_FILE)
    print(f"  {kb_name}: 上传完成 — 成功 {done - errors}, 失败 {errors}")


def step_chunk(client: RAgentClient, progress: Progress):
    """批量触发分块"""
    to_chunk = [
        (fpath, doc_id)
        for fpath, doc_id in progress.uploaded.items()
        if doc_id and doc_id not in progress.chunked
    ]

    if not to_chunk:
        print("  所有文档已触发分块，跳过。")
        return

    print(f"  待分块: {len(to_chunk)} 个文档 (已完成: {len(progress.chunked)})")

    done = 0
    errors = 0

    def _chunk_one(item):
        fpath, doc_id = item
        try:
            client.trigger_chunk(doc_id)
            return doc_id, None
        except Exception as e:
            return doc_id, str(e)

    with ThreadPoolExecutor(max_workers=CHUNK_CONCURRENCY) as pool:
        futures = {}
        for i, item in enumerate(to_chunk):
            future = pool.submit(_chunk_one, item)
            futures[future] = item
            if (i + 1) % CHUNK_CONCURRENCY == 0 and CHUNK_INTERVAL > 0:
                time.sleep(CHUNK_INTERVAL)

        for future in as_completed(futures):
            doc_id, error = future.result()
            done += 1

            if error:
                errors += 1
                print(f"    [{done}/{len(to_chunk)}] FAIL: doc={doc_id} — {error}")
            else:
                progress.chunked.add(doc_id)
                print(f"    [{done}/{len(to_chunk)}] OK: doc={doc_id}")

            if done % 10 == 0:
                progress.save(PROGRESS_FILE)

    progress.save(PROGRESS_FILE)
    print(f"  分块完成: 成功 {done - errors}, 失败 {errors}")


def print_summary(kb_files: dict, progress: Progress):
    """打印最终统计"""
    print("\n" + "=" * 55)
    print("  春招备考知识库导入完成")
    print("=" * 55)

    total_files = 0
    total_uploaded = 0
    total_chunked = 0

    for defn in KB_DEFINITIONS:
        name = defn["name"]
        files = kb_files.get(defn["collection"], [])
        uploaded = sum(1 for f in files if f in progress.uploaded)
        chunked = sum(
            1 for f in files
            if f in progress.uploaded and progress.uploaded[f] in progress.chunked
        )
        total_files += len(files)
        total_uploaded += uploaded
        total_chunked += chunked
        print(f"  {name:12s}  文件 {len(files):3d}  上传 {uploaded:3d}  分块 {chunked:3d}")

    print(f"  {'总计':12s}  文件 {total_files:3d}  上传 {total_uploaded:3d}  分块 {total_chunked:3d}")
    print("=" * 55)


# ────────────────────── 入口 ──────────────────────


def main():
    print("=" * 55)
    print("  春招备考知识库批量导入")
    print("=" * 55)
    print(f"  API: {BASE_URL}")
    print(f"  进度文件: {PROGRESS_FILE}")
    print()

    # 0. 扫描所有源目录
    print("[1/5] 扫描文档文件...")
    kb_files = {}  # collection -> [file_paths]
    for defn in KB_DEFINITIONS:
        files = scan_md_files(defn["source_dir"])
        kb_files[defn["collection"]] = files
        print(f"  {defn['name']}: {len(files)} 个 .md 文件")

    total = sum(len(f) for f in kb_files.values())
    print(f"  总计: {total} 个文件")

    if total == 0:
        print("没有找到任何文档，退出。")
        sys.exit(1)

    # 1. 加载进度
    progress = Progress.load(PROGRESS_FILE)

    # 2. 登录
    print("\n[2/5] 登录...")
    client = RAgentClient(BASE_URL)
    token = client.login(LOGIN_USER, LOGIN_PASS)
    print(f"  登录成功，token={token[:20]}...")

    # 3. 清空旧知识库
    print("\n[3/5] 清空现有知识库...")
    step_clear_old_kbs(client, progress)
    progress.save(PROGRESS_FILE)

    # 4. 创建新知识库
    print("\n[4/5] 创建知识库...")
    kb_map = step_create_kbs(client, progress)
    progress.save(PROGRESS_FILE)

    # 5. 上传文档
    print("\n[5/5] 上传文档...")
    for defn in KB_DEFINITIONS:
        collection = defn["collection"]
        kb_id = kb_map[collection]
        files = kb_files[collection]
        step_upload(client, kb_id, defn["name"], files, progress)

    # 6. 触发分块
    print("\n[bonus] 触发文档分块...")
    step_chunk(client, progress)

    # 7. 统计
    print_summary(kb_files, progress)

    # 清理进度文件
    if os.path.exists(PROGRESS_FILE):
        os.remove(PROGRESS_FILE)
        print("\n进度文件已清理。")


if __name__ == "__main__":
    main()
