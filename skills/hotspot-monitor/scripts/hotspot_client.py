#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


BASE_URL = os.environ.get("RAGENT_BASE_URL", "http://127.0.0.1:8080/api/ragent").rstrip("/")
TOKEN = os.environ.get("RAGENT_TOKEN", "").strip()
USERNAME = os.environ.get("RAGENT_USERNAME", "").strip()
PASSWORD = os.environ.get("RAGENT_PASSWORD", "").strip()
TOKEN_CACHE = TOKEN


def resolve_token():
    global TOKEN_CACHE
    if TOKEN_CACHE:
        return TOKEN_CACHE
    if not USERNAME or not PASSWORD:
        return ""
    payload = json.dumps({"username": USERNAME, "password": PASSWORD}).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/auth/login",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        body = resp.read().decode("utf-8")
    parsed = json.loads(body)
    if parsed.get("code") != "0":
        raise SystemExit(parsed.get("message") or "login failed")
    TOKEN_CACHE = ((parsed.get("data") or {}).get("token") or "").strip()
    return TOKEN_CACHE


def api_request(method: str, path: str, params=None, payload=None):
    query = f"?{urllib.parse.urlencode(params)}" if params else ""
    url = f"{BASE_URL}{path}{query}"
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    token = resolve_token()
    if token:
        headers["Authorization"] = token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req) as resp:
        body = resp.read().decode("utf-8")
    parsed = json.loads(body)
    if isinstance(parsed, dict) and "code" in parsed:
        if parsed.get("code") != "0":
            raise SystemExit(parsed.get("message") or "request failed")
        return parsed.get("data")
    return parsed


def command_report(args):
    data = api_request(
        "GET",
        "/admin/hotspots/report",
        {
            "query": args.query,
            "sources": args.sources,
            "limit": args.limit,
            "analyze": str(not args.no_analyze).lower(),
        },
    )
    print(json.dumps(data, ensure_ascii=False, indent=2))


def command_create_monitor(args):
    payload = {
        "keyword": args.keyword,
        "sources": [item.strip() for item in args.sources.split(",") if item.strip()],
        "enabled": not args.disabled,
        "email": args.email or "",
        "emailEnabled": bool(args.email_enabled),
        "websocketEnabled": not args.no_websocket,
        "scanIntervalMinutes": args.interval,
        "relevanceThreshold": args.relevance,
        "credibilityThreshold": args.credibility,
    }
    data = api_request("POST", "/admin/hotspots/monitors", payload=payload)
    print(data)


def command_list_monitors(_args):
    data = api_request("GET", "/admin/hotspots/monitors", {"pageNo": 1, "pageSize": 50})
    print(json.dumps(data, ensure_ascii=False, indent=2))


def command_scan_monitor(args):
    data = api_request("POST", f"/admin/hotspots/monitors/{args.id}/scan")
    print(json.dumps(data, ensure_ascii=False, indent=2))


def command_list_events(args):
    params = {"pageNo": 1, "pageSize": args.page_size}
    if args.monitor_id:
        params["monitorId"] = args.monitor_id
    data = api_request("GET", "/admin/hotspots/events", params)
    print(json.dumps(data, ensure_ascii=False, indent=2))


def command_list_runs(args):
    params = {"pageNo": 1, "pageSize": args.page_size}
    if args.monitor_id:
        params["monitorId"] = args.monitor_id
    data = api_request("GET", "/admin/hotspots/runs", params)
    print(json.dumps(data, ensure_ascii=False, indent=2))


def command_ws_url(_args):
    token = resolve_token()
    if not token:
        raise SystemExit("RAGENT_TOKEN or RAGENT_USERNAME/RAGENT_PASSWORD is required")
    if BASE_URL.startswith("https://"):
        prefix = "wss://"
        host = BASE_URL[len("https://"):]
    elif BASE_URL.startswith("http://"):
        prefix = "ws://"
        host = BASE_URL[len("http://"):]
    else:
        prefix = "ws://"
        host = BASE_URL
    print(f"{prefix}{host}/ws/hotspots?token={urllib.parse.quote(token)}")


def build_parser():
    parser = argparse.ArgumentParser(description="Operate ragent hotspot monitor APIs")
    sub = parser.add_subparsers(dest="command", required=True)

    report = sub.add_parser("report")
    report.add_argument("--query", required=True)
    report.add_argument("--sources", default="twitter,bing,hackernews,sogou,bilibili,weibo,duckduckgo,reddit")
    report.add_argument("--limit", type=int, default=12)
    report.add_argument("--no-analyze", action="store_true")
    report.set_defaults(func=command_report)

    create = sub.add_parser("create-monitor")
    create.add_argument("--keyword", required=True)
    create.add_argument("--sources", default="twitter,bing,hackernews,weibo")
    create.add_argument("--interval", type=int, default=30)
    create.add_argument("--relevance", type=float, default=0.55)
    create.add_argument("--credibility", type=float, default=0.45)
    create.add_argument("--email", default="")
    create.add_argument("--email-enabled", action="store_true")
    create.add_argument("--disabled", action="store_true")
    create.add_argument("--no-websocket", action="store_true")
    create.set_defaults(func=command_create_monitor)

    monitors = sub.add_parser("list-monitors")
    monitors.set_defaults(func=command_list_monitors)

    scan = sub.add_parser("scan-monitor")
    scan.add_argument("--id", required=True)
    scan.set_defaults(func=command_scan_monitor)

    events = sub.add_parser("list-events")
    events.add_argument("--monitor-id")
    events.add_argument("--page-size", type=int, default=20)
    events.set_defaults(func=command_list_events)

    runs = sub.add_parser("list-runs")
    runs.add_argument("--monitor-id")
    runs.add_argument("--page-size", type=int, default=20)
    runs.set_defaults(func=command_list_runs)

    ws_url = sub.add_parser("ws-url")
    ws_url.set_defaults(func=command_ws_url)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.func(args)
    except urllib.error.HTTPError as exc:
        sys.stderr.write(f"HTTP {exc.code}: {exc.read().decode('utf-8', errors='ignore')}\n")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
