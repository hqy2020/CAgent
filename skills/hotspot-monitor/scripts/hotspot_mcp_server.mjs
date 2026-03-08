#!/usr/bin/env node

import { McpServer } from "../../../scripts/mcp-bridge/node_modules/@modelcontextprotocol/sdk/dist/esm/server/mcp.js";
import { StdioServerTransport } from "../../../scripts/mcp-bridge/node_modules/@modelcontextprotocol/sdk/dist/esm/server/stdio.js";
import * as z from "../../../scripts/mcp-bridge/node_modules/zod/v4/index.js";

const BASE_URL = (process.env.RAGENT_BASE_URL || "http://127.0.0.1:8080/api/ragent").replace(/\/$/, "");
const USERNAME = (process.env.RAGENT_USERNAME || "").trim();
const PASSWORD = (process.env.RAGENT_PASSWORD || "").trim();
let tokenCache = (process.env.RAGENT_TOKEN || "").trim();

function ensureTrailingSlash(value) {
  return value.endsWith("/") ? value : `${value}/`;
}

function toSourceCsv(sources) {
  if (!sources) {
    return undefined;
  }
  if (Array.isArray(sources)) {
    const normalized = sources.map((item) => `${item}`.trim()).filter(Boolean);
    return normalized.length > 0 ? normalized.join(",") : undefined;
  }
  const normalized = `${sources}`.trim();
  return normalized || undefined;
}

function buildToolText(title, payload) {
  return `${title}\n${JSON.stringify(payload, null, 2)}`;
}

function buildWsUrl(token) {
  if (BASE_URL.startsWith("https://")) {
    return `wss://${BASE_URL.slice("https://".length)}/ws/hotspots?token=${encodeURIComponent(token)}`;
  }
  if (BASE_URL.startsWith("http://")) {
    return `ws://${BASE_URL.slice("http://".length)}/ws/hotspots?token=${encodeURIComponent(token)}`;
  }
  return `ws://${BASE_URL}/ws/hotspots?token=${encodeURIComponent(token)}`;
}

async function resolveToken() {
  if (tokenCache) {
    return tokenCache;
  }
  if (!USERNAME || !PASSWORD) {
    throw new Error("RAGENT_TOKEN or RAGENT_USERNAME/RAGENT_PASSWORD is required");
  }
  const loginUrl = new URL("auth/login", ensureTrailingSlash(BASE_URL));
  const response = await fetch(loginUrl, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD })
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`login failed: HTTP ${response.status} ${body}`);
  }
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new Error(`login failed: ${body}`);
  }
  if (parsed?.code !== "0") {
    throw new Error(parsed?.message || "login failed");
  }
  tokenCache = parsed?.data?.token || "";
  if (!tokenCache) {
    throw new Error("login succeeded but token is empty");
  }
  return tokenCache;
}

async function apiRequest(method, path, { params, payload } = {}) {
  const token = await resolveToken();
  const url = new URL(path.replace(/^\//, ""), ensureTrailingSlash(BASE_URL));
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value === undefined || value === null || value === "") {
        return;
      }
      url.searchParams.set(key, `${value}`);
    });
  }
  const response = await fetch(url, {
    method,
    headers: {
      Authorization: token,
      ...(payload ? { "Content-Type": "application/json" } : {})
    },
    body: payload ? JSON.stringify(payload) : undefined
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${body}`);
  }
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new Error(`invalid JSON response: ${body}`);
  }
  if (parsed?.code !== "0") {
    throw new Error(parsed?.message || "request failed");
  }
  return parsed?.data;
}

async function wrapTool(title, executor) {
  try {
    const payload = await executor();
    return {
      content: [{ type: "text", text: buildToolText(title, payload) }],
      structuredContent: payload
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {
      isError: true,
      content: [{ type: "text", text: `${title} failed\n${message}` }]
    };
  }
}

const server = new McpServer({
  name: "ragent-hotspot-monitor",
  version: "1.0.0"
});

server.registerTool(
  "hotspot_report",
  {
    title: "Get Hotspot Report",
    description: "Fetch a multi-source hotspot report with optional AI relevance and credibility analysis.",
    inputSchema: {
      query: z.string().min(1).describe("Keyword to track, for example OpenAI or Cursor."),
      sources: z.array(z.string()).optional().describe("Optional source codes such as twitter,bing,hackernews."),
      limit: z.number().int().min(1).max(30).optional().describe("Maximum number of hotspot items to return."),
      analyze: z.boolean().optional().describe("Whether to run AI analysis.")
    }
  },
  ({ query, sources, limit, analyze }) =>
    wrapTool("hotspot_report", async () =>
      apiRequest("GET", "/admin/hotspots/report", {
        params: {
          query,
          sources: toSourceCsv(sources),
          limit,
          analyze: analyze ?? true
        }
      })
    )
);

server.registerTool(
  "hotspot_create_monitor",
  {
    title: "Create Hotspot Monitor",
    description: "Create a scheduled hotspot monitor with WebSocket and optional email notification.",
    inputSchema: {
      keyword: z.string().min(1).describe("Keyword to monitor continuously."),
      sources: z.array(z.string()).optional().describe("Optional source codes. Defaults to the backend defaults."),
      enabled: z.boolean().optional(),
      email: z.string().optional().describe("Optional email address for notification."),
      emailEnabled: z.boolean().optional(),
      websocketEnabled: z.boolean().optional(),
      scanIntervalMinutes: z.number().int().min(5).max(1440).optional(),
      relevanceThreshold: z.number().min(0).max(1).optional(),
      credibilityThreshold: z.number().min(0).max(1).optional()
    }
  },
  (args) =>
    wrapTool("hotspot_create_monitor", async () => {
      const monitorId = await apiRequest("POST", "/admin/hotspots/monitors", {
        payload: {
          keyword: args.keyword,
          sources: args.sources,
          enabled: args.enabled ?? true,
          email: args.email || "",
          emailEnabled: args.emailEnabled ?? false,
          websocketEnabled: args.websocketEnabled ?? true,
          scanIntervalMinutes: args.scanIntervalMinutes ?? 30,
          relevanceThreshold: args.relevanceThreshold ?? 0.55,
          credibilityThreshold: args.credibilityThreshold ?? 0.45
        }
      });
      return { monitorId };
    })
);

server.registerTool(
  "hotspot_list_monitors",
  {
    title: "List Hotspot Monitors",
    description: "List current hotspot monitoring tasks for the authenticated user.",
    inputSchema: {
      pageNo: z.number().int().min(1).optional(),
      pageSize: z.number().int().min(1).max(100).optional()
    }
  },
  ({ pageNo, pageSize }) =>
    wrapTool("hotspot_list_monitors", async () =>
      apiRequest("GET", "/admin/hotspots/monitors", {
        params: { pageNo: pageNo ?? 1, pageSize: pageSize ?? 20 }
      })
    )
);

server.registerTool(
  "hotspot_toggle_monitor",
  {
    title: "Toggle Hotspot Monitor",
    description: "Enable or disable a hotspot monitor task.",
    inputSchema: {
      id: z.string().min(1).describe("Monitor id."),
      enabled: z.boolean().describe("Whether the monitor should be enabled.")
    }
  },
  ({ id, enabled }) =>
    wrapTool("hotspot_toggle_monitor", async () => {
      await apiRequest("POST", `/admin/hotspots/monitors/${id}/toggle`, {
        params: { enabled }
      });
      return { id, enabled };
    })
);

server.registerTool(
  "hotspot_scan_monitor",
  {
    title: "Scan Hotspot Monitor",
    description: "Trigger an immediate hotspot scan for an existing monitor.",
    inputSchema: {
      id: z.string().min(1).describe("Monitor id.")
    }
  },
  ({ id }) =>
    wrapTool("hotspot_scan_monitor", async () =>
      apiRequest("POST", `/admin/hotspots/monitors/${id}/scan`)
    )
);

server.registerTool(
  "hotspot_list_events",
  {
    title: "List Hotspot Events",
    description: "List recent hotspot alert events that matched monitor rules.",
    inputSchema: {
      monitorId: z.string().optional(),
      pageNo: z.number().int().min(1).optional(),
      pageSize: z.number().int().min(1).max(100).optional()
    }
  },
  ({ monitorId, pageNo, pageSize }) =>
    wrapTool("hotspot_list_events", async () =>
      apiRequest("GET", "/admin/hotspots/events", {
        params: {
          monitorId,
          pageNo: pageNo ?? 1,
          pageSize: pageSize ?? 20
        }
      })
    )
);

server.registerTool(
  "hotspot_list_runs",
  {
    title: "List Hotspot Runs",
    description: "List recent hotspot scan executions.",
    inputSchema: {
      monitorId: z.string().optional(),
      pageNo: z.number().int().min(1).optional(),
      pageSize: z.number().int().min(1).max(100).optional()
    }
  },
  ({ monitorId, pageNo, pageSize }) =>
    wrapTool("hotspot_list_runs", async () =>
      apiRequest("GET", "/admin/hotspots/runs", {
        params: {
          monitorId,
          pageNo: pageNo ?? 1,
          pageSize: pageSize ?? 20
        }
      })
    )
);

server.registerTool(
  "hotspot_ws_url",
  {
    title: "Get Hotspot WebSocket URL",
    description: "Return the authenticated WebSocket URL for receiving realtime hotspot alerts.",
    inputSchema: {}
  },
  () =>
    wrapTool("hotspot_ws_url", async () => {
      const token = await resolveToken();
      return { url: buildWsUrl(token) };
    })
);

const transport = new StdioServerTransport();

server.connect(transport).catch((error) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  process.stderr.write(`${message}\n`);
  process.exit(1);
});
