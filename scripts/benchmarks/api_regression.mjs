import path from "node:path";
import process from "node:process";

import {
  buildBlockedResults,
  createReportContext,
  detectFallbackUsed,
  evaluateCase,
  exitCodeForResults,
  loadCases,
  parseSseTranscript,
  writeReports
} from "./second_brain_report_utils.mjs";

const ROOT_DIR = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..", "..");
const CASES_FILE = process.env.SECOND_BRAIN_CASES_FILE || path.resolve(ROOT_DIR, "scripts/benchmarks/second_brain_cases.json");
const BASE_URL = (process.env.BASE_URL || "http://localhost:8080/api/ragent").replace(/\/$/, "");
const LOGIN_USERNAME = process.env.LOGIN_USERNAME || process.env.USERNAME || "admin";
const LOGIN_PASSWORD = process.env.LOGIN_PASSWORD || process.env.PASSWORD || "admin";
const TIMEOUT_MS = Number(process.env.TIMEOUT_SECONDS || "120") * 1000;

const cases = loadCases(CASES_FILE);
const context = createReportContext({
  suite: "api",
  baseUrl: BASE_URL,
  rootDir: ROOT_DIR,
  reportRoot: process.env.SECOND_BRAIN_REPORT_DIR,
  reportTag: process.env.REPORT_TAG,
  casesFile: CASES_FILE
});

function buildAbortController() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  return { controller, timer };
}

async function fetchWithTimeout(url, options = {}) {
  const { controller, timer } = buildAbortController();
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function extractToken(payload) {
  if (!payload || typeof payload !== "object") {
    return "";
  }
  if (typeof payload.token === "string" && payload.token) {
    return payload.token;
  }
  if (payload.data && typeof payload.data === "object" && typeof payload.data.token === "string") {
    return payload.data.token;
  }
  if (typeof payload.data === "string" && payload.data) {
    return payload.data;
  }
  return "";
}

async function login() {
  try {
    const response = await fetchWithTimeout(`${BASE_URL}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: LOGIN_USERNAME, password: LOGIN_PASSWORD })
    });

    const bodyText = await response.text();
    if (response.status >= 500) {
      return { blocked: true, reason: `login endpoint unavailable (${response.status})`, bodyText };
    }

    let payload = {};
    try {
      payload = bodyText ? JSON.parse(bodyText) : {};
    } catch {
      payload = {};
    }

    const token = extractToken(payload);
    if (!response.ok || !token) {
      return { blocked: true, reason: `login failed (${response.status})`, bodyText };
    }

    return { blocked: false, token, status: response.status };
  } catch (error) {
    return { blocked: true, reason: `login request error: ${error instanceof Error ? error.message : String(error)}` };
  }
}

async function runCase(caseDef, token) {
  const url = new URL(`${BASE_URL}/rag/v3/chat`);
  url.searchParams.set("question", caseDef.question);
  url.searchParams.set("deepThinking", "false");

  try {
    const response = await fetchWithTimeout(url, {
      method: "GET",
      headers: {
        Accept: "text/event-stream",
        Authorization: token
      }
    });

    const bodyText = await response.text();
    if (response.status >= 500) {
      return {
        id: caseDef.id,
        status: "BLOCKED",
        reason: `chat endpoint unavailable (${response.status})`,
        blockedDetail: bodyText.slice(0, 200),
        question: caseDef.question,
        expectation: caseDef.expectation,
        referenceRequired: Boolean(caseDef.reference_required),
        safetyCase: Boolean(caseDef.safety_case),
        hitTools: [],
        toolSelectionMatched: null,
        referenceCount: 0,
        confirmCount: 0,
        traceNodeCount: 0,
        fallbackUsed: false,
        safetyBlocked: false,
        events: {},
        httpStatus: response.status,
        artifacts: {}
      };
    }

    const transcript = parseSseTranscript(bodyText);
    const result = evaluateCase(caseDef, {
      responseText: transcript.responseText,
      detectedTools: transcript.detectedTools,
      referenceCount: transcript.eventCounts.references || 0,
      confirmCount: transcript.eventCounts.agent_confirm_required || 0,
      traceNodeCount: transcript.traceNodeCount,
      fallbackUsed: detectFallbackUsed(transcript.responseText, transcript.records),
      events: transcript.eventCounts,
      httpStatus: response.status
    });

    return result;
  } catch (error) {
    return {
      id: caseDef.id,
      question: caseDef.question,
      expectation: caseDef.expectation,
      referenceRequired: Boolean(caseDef.reference_required),
      safetyCase: Boolean(caseDef.safety_case),
      status: "BLOCKED",
      reason: "chat request error",
      blockedDetail: error instanceof Error ? error.message : String(error),
      responsePreview: "",
      hitTools: [],
      toolSelectionMatched: null,
      referenceCount: 0,
      confirmCount: 0,
      traceNodeCount: 0,
      fallbackUsed: false,
      safetyBlocked: false,
      events: {},
      httpStatus: null,
      artifacts: {}
    };
  }
}

async function main() {
  const loginResult = await login();
  let results;
  const notes = [];

  if (loginResult.blocked) {
    notes.push(loginResult.reason);
    results = buildBlockedResults(cases, "service_blocked", loginResult.reason);
  } else {
    notes.push(`login OK (${loginResult.status})`);
    results = [];
    for (const caseDef of cases) {
      const result = await runCase(caseDef, loginResult.token);
      results.push(result);
      const suffix = result.status === "BLOCKED" && result.blockedDetail ? `: ${result.blockedDetail}` : "";
      console.log(`[api-regression] ${result.status} ${caseDef.id} -> ${result.reason}${suffix}`);
    }
  }

  const report = writeReports(context, results, {
    notes,
    casesFile: CASES_FILE
  });

  console.log(`[api-regression] report json: ${context.jsonPath}`);
  console.log(`[api-regression] report md: ${context.markdownPath}`);
  console.log(`[api-regression] suite status: ${report.status}`);
  process.exit(exitCodeForResults(results));
}

main().catch((error) => {
  console.error(`[api-regression] fatal: ${error instanceof Error ? error.stack || error.message : String(error)}`);
  process.exit(1);
});
