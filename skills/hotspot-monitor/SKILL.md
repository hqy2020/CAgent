---
name: hotspot-monitor
description: Use when the user wants to create hotspot monitoring tasks, fetch multi-source hotspot reports, inspect real-time alerts, or operate the ragent hotspot tracking APIs and WebSocket stream from an AI coding tool.
---

# Hotspot Monitor

This skill operates the hotspot tracking module exposed by this repository.

Use it when the user asks to:
- monitor a keyword continuously
- fetch a multi-source hotspot report
- review recent hotspot alerts or scan runs
- trigger an immediate hotspot scan
- wire Cursor, Claude Code, VSCode Copilot, or another MCP-capable AI tool into the hotspot monitoring API

## Prerequisites

- The backend is running and reachable at `RAGENT_BASE_URL` or `http://127.0.0.1:8080/api/ragent`.
- Authentication is available through `RAGENT_TOKEN` or `RAGENT_USERNAME` + `RAGENT_PASSWORD`.
- For email notifications, the backend must already have `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` configured.

## Fast Path

Use the bundled client:

```bash
python3 skills/hotspot-monitor/scripts/hotspot_client.py report --query "OpenAI"
python3 skills/hotspot-monitor/scripts/hotspot_client.py create-monitor --keyword "Cursor" --sources twitter,bing,hackernews,weibo
python3 skills/hotspot-monitor/scripts/hotspot_client.py list-monitors
python3 skills/hotspot-monitor/scripts/hotspot_client.py scan-monitor --id 123456789
python3 skills/hotspot-monitor/scripts/hotspot_client.py list-events
python3 skills/hotspot-monitor/scripts/hotspot_client.py ws-url
```

For AI coding tools that speak MCP, register this stdio server:

```bash
node skills/hotspot-monitor/scripts/hotspot_mcp_server.mjs
```

## API Surface

- `GET /admin/hotspots/report`
- `GET /admin/hotspots/monitors`
- `POST /admin/hotspots/monitors`
- `PUT /admin/hotspots/monitors/{id}`
- `POST /admin/hotspots/monitors/{id}/toggle?enabled=true|false`
- `POST /admin/hotspots/monitors/{id}/scan`
- `GET /admin/hotspots/events`
- `GET /admin/hotspots/runs`
- WebSocket: `/ws/hotspots?token=<RAGENT_TOKEN>`
- MCP tools:
  - `hotspot_report`
  - `hotspot_create_monitor`
  - `hotspot_list_monitors`
  - `hotspot_toggle_monitor`
  - `hotspot_scan_monitor`
  - `hotspot_list_events`
  - `hotspot_list_runs`
  - `hotspot_ws_url`

## Usage Notes

- Prefer `report` for one-off inspection and `create-monitor` for ongoing tracking.
- If the user asks for real-time push debugging, first call `ws-url` and verify the token.
- If a source like Twitter returns warnings, report the warning instead of silently dropping it; the backend supports the source but may require extra credentials.
- When configuring external AI tools, use the MCP server command above instead of teaching the model raw HTTP calls.
