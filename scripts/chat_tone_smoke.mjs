#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

import { parseSseTranscript } from "./benchmarks/second_brain_report_utils.mjs";

const ROOT_DIR = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const CASES_FILE = process.env.TONE_CASES_FILE || path.resolve(ROOT_DIR, "scripts/benchmarks/chat_tone_cases.json");
const BASE_URL = (process.env.BASE_URL || "http://localhost:8080/api/ragent").replace(/\/$/, "");
const LOGIN_USERNAME = process.env.LOGIN_USERNAME || "admin";
const LOGIN_PASSWORD = process.env.LOGIN_PASSWORD || "admin";
const DEEP_THINKING = (process.env.DEEP_THINKING || "false").toLowerCase() === "true";
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || "120000");

function fail(message) {
  console.error(`[tone-smoke] FAIL: ${message}`);
  process.exit(1);
}

function normalizeText(text) {
  return String(text || "").replace(/\s+/g, " ").trim();
}

function hasAnswerStructure(text) {
  return /[。！？：]/.test(text) || /\n/.test(text) || /(?:^|\s)(?:1\.|2\.|一、|二、|- )/.test(text);
}

function evaluateCase(question, minChars, transcript) {
  const counts = transcript.eventCounts || {};
  const rawResponse = String(transcript.responseText || "");
  const response = normalizeText(rawResponse);
  const normalizedQuestion = normalizeText(question);
  const exactEcho = Boolean(response) && response === normalizedQuestion;
  const startsWithQuestion = Boolean(response) && Boolean(normalizedQuestion) && response.startsWith(normalizedQuestion);
  const shortAfterQuestion = startsWithQuestion && response.length <= normalizedQuestion.length + 20;
  const likelyEcho = exactEcho || shortAfterQuestion;
  const missingProtocol = ["meta", "message", "finish", "done"].filter((key) => !counts[key]);

  if (missingProtocol.length > 0) {
    return {
      status: "FAIL",
      reason: `SSE 事件不完整: ${missingProtocol.join(", ")}`,
      response,
      counts
    };
  }

  if (!response) {
    return {
      status: "FAIL",
      reason: "回答为空",
      response,
      counts
    };
  }

  if (likelyEcho) {
    return {
      status: "FAIL",
      reason: "回答接近原样回显，疑似仍在使用 noop/local profile",
      response,
      counts
    };
  }

  if (response.length < minChars) {
    return {
      status: "WARN",
      reason: `回答偏短（${response.length} < ${minChars}）`,
      response,
      counts
    };
  }

  if (!hasAnswerStructure(rawResponse)) {
    return {
      status: "WARN",
      reason: "回答存在，但缺少明显句式或结构",
      response,
      counts
    };
  }

  return {
    status: "PASS",
    reason: "回答自然，且不是简单回声",
    response,
    counts
  };
}

async function fetchJson(url, init) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      ...init,
      signal: controller.signal
    });
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null;
    }
    return { response, text, data };
  } finally {
    clearTimeout(timer);
  }
}

async function fetchText(url, init) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      ...init,
      signal: controller.signal
    });
    const text = await response.text();
    return { response, text };
  } finally {
    clearTimeout(timer);
  }
}

async function login() {
  const { response, data, text } = await fetchJson(`${BASE_URL}/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      username: LOGIN_USERNAME,
      password: LOGIN_PASSWORD
    })
  });

  if (!response.ok) {
    fail(`登录失败，HTTP ${response.status}: ${text.slice(0, 200)}`);
  }

  const token = data?.data?.token || data?.data || "";
  if (!token || typeof token !== "string") {
    fail(`登录成功但无法提取 token: ${text.slice(0, 200)}`);
  }

  return token;
}

async function requestTranscript(token, question) {
  const url = new URL(`${BASE_URL}/rag/v3/chat`);
  url.searchParams.set("question", question);
  url.searchParams.set("deepThinking", String(DEEP_THINKING));

  const { response, text } = await fetchText(url, {
    headers: {
      Accept: "text/event-stream",
      Authorization: token
    }
  });

  if (!response.ok) {
    fail(`SSE 请求失败，HTTP ${response.status}: ${text.slice(0, 200)}`);
  }

  return parseSseTranscript(text);
}

function printCaseResult(caseDef, result) {
  const preview = result.response.slice(0, 120);
  console.log(`- [${result.status}] ${caseDef.id}: ${result.reason}`);
  console.log(`  问题: ${caseDef.question}`);
  console.log(`  字数: ${result.response.length}`);
  console.log(
    `  事件: meta=${result.counts.meta || 0}, message=${result.counts.message || 0}, finish=${result.counts.finish || 0}, done=${result.counts.done || 0}`
  );
  console.log(`  预览: ${preview}${result.response.length > 120 ? "..." : ""}`);
}

async function main() {
  if (!fs.existsSync(CASES_FILE)) {
    fail(`测试用例不存在: ${CASES_FILE}`);
  }

  const cases = JSON.parse(fs.readFileSync(CASES_FILE, "utf-8"));
  if (!Array.isArray(cases) || cases.length === 0) {
    fail(`测试用例为空: ${CASES_FILE}`);
  }

  console.log(`[tone-smoke] base url: ${BASE_URL}`);
  console.log(`[tone-smoke] cases file: ${CASES_FILE}`);
  console.log(`[tone-smoke] login user: ${LOGIN_USERNAME}`);

  const token = await login();
  console.log("[tone-smoke] login OK");

  let failCount = 0;
  let warnCount = 0;
  let passCount = 0;

  for (const caseDef of cases) {
    const transcript = await requestTranscript(token, caseDef.question);
    const result = evaluateCase(caseDef.question, Number(caseDef.min_chars || 0), transcript);
    printCaseResult(caseDef, result);
    if (result.status === "FAIL") {
      failCount += 1;
    } else if (result.status === "WARN") {
      warnCount += 1;
    } else {
      passCount += 1;
    }
    console.log("");
  }

  console.log(`[tone-smoke] summary: pass=${passCount}, warn=${warnCount}, fail=${failCount}`);
  if (failCount > 0) {
    process.exit(1);
  }
}

main().catch((error) => {
  fail(error instanceof Error ? error.stack || error.message : String(error));
});
