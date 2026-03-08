import fs from "node:fs";
import path from "node:path";
import process from "node:process";

import {
  buildBlockedResults,
  createReportContext,
  detectFallbackUsed,
  evaluateCase,
  exitCodeForResults,
  loadCases,
  writeReports
} from "../benchmarks/second_brain_report_utils.mjs";

const ROOT_DIR = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..", "..");
const CASES_FILE = process.env.SECOND_BRAIN_CASES_FILE || path.resolve(ROOT_DIR, "scripts/benchmarks/second_brain_cases.json");
const FRONTEND_URL = (process.env.UI_BASE_URL || "http://127.0.0.1:5173").replace(/\/$/, "");
const LOGIN_USERNAME = process.env.LOGIN_USERNAME || process.env.USERNAME || "admin";
const LOGIN_PASSWORD = process.env.LOGIN_PASSWORD || process.env.PASSWORD || "admin";
const OUTPUT_DIR = process.env.UI_OUTPUT_DIR || path.resolve(ROOT_DIR, "output/playwright");
const HEADLESS = (process.env.PLAYWRIGHT_HEADLESS || "true").toLowerCase() !== "false";
const STEP_TIMEOUT_MS = Number(process.env.UI_STEP_TIMEOUT_MS || "120000");
const BLOCK_REASON = process.env.UI_BLOCK_REASON || "";

const cases = loadCases(CASES_FILE);
const context = createReportContext({
  suite: "ui",
  baseUrl: FRONTEND_URL,
  rootDir: ROOT_DIR,
  reportRoot: process.env.SECOND_BRAIN_REPORT_DIR,
  reportTag: process.env.REPORT_TAG,
  casesFile: CASES_FILE
});

async function probeUi() {
  if (BLOCK_REASON) {
    return { blocked: true, reason: BLOCK_REASON };
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(STEP_TIMEOUT_MS, 15000));
  try {
    const response = await fetch(`${FRONTEND_URL}/login`, {
      method: "GET",
      signal: controller.signal
    });
    if (response.status >= 500) {
      return { blocked: true, reason: `login page unavailable (${response.status})` };
    }
    return { blocked: false };
  } catch (error) {
    return { blocked: true, reason: `login page request error: ${error instanceof Error ? error.message : String(error)}` };
  } finally {
    clearTimeout(timer);
  }
}

async function waitForChatSettled(page) {
  await page.waitForFunction(
    () => !document.body?.innerText?.includes("生成中..."),
    { timeout: STEP_TIMEOUT_MS }
  );
  await page.waitForTimeout(1200);
}

async function getComposer(page) {
  const locator = page.locator('textarea[aria-label="聊天输入框"], textarea[aria-label="发送消息"]').first();
  await locator.waitFor({ state: "visible", timeout: STEP_TIMEOUT_MS });
  return locator;
}

async function sendQuestion(page, question) {
  const beforeCount = await page.locator("div.group.flex").count();
  const composer = await getComposer(page);
  await composer.fill(question);
  await composer.press("Enter");

  await page.waitForFunction(
    (prev) => document.querySelectorAll("div.group.flex").length > prev,
    beforeCount,
    { timeout: STEP_TIMEOUT_MS }
  );

  await waitForChatSettled(page);
  return page.locator("div.group.flex").last();
}

async function takeCaseScreenshot(page, caseName) {
  await page.evaluate(() => {
    window.scrollTo(0, document.body.scrollHeight);
  });
  await page.waitForTimeout(300);
  const target = path.join(OUTPUT_DIR, `ui-regression-${caseName}.png`);
  await page.screenshot({ path: target, fullPage: true });
  return target;
}

async function collectObservation(assistant) {
  const text = ((await assistant.textContent()) || "").trim();
  const referenceButton = assistant.getByRole("button", { name: /参考文档/ }).first();
  const confirmButton = assistant.getByRole("button", { name: "确认执行" }).first();

  let referenceCount = 0;
  if ((await referenceButton.count()) > 0) {
    const buttonText = ((await referenceButton.textContent()) || "").trim();
    const match = buttonText.match(/(\d+)篇/);
    referenceCount = match ? Number(match[1]) : 1;
  }

  const traceMatch = text.match(/Agent 时间线[\s\S]*?(\d+)条/);
  const toolMatch = text.match(/tool:\s*([a-zA-Z0-9_:-]+)/);

  return {
    responseText: text,
    detectedTools: toolMatch ? [toolMatch[1]] : [],
    referenceCount,
    confirmCount: (await confirmButton.count()) > 0 || text.includes("待确认写操作") ? 1 : 0,
    traceNodeCount: traceMatch ? Number(traceMatch[1]) : 0,
    fallbackUsed: detectFallbackUsed(text),
    events: {
      references: referenceCount > 0 ? 1 : 0,
      agent_confirm_required: (await confirmButton.count()) > 0 || text.includes("待确认写操作") ? 1 : 0
    }
  };
}

async function login(page) {
  await page.goto(`${FRONTEND_URL}/login`, { waitUntil: "domcontentloaded", timeout: STEP_TIMEOUT_MS });
  const loginButton = page.getByRole("button", { name: "登录" });
  if ((await loginButton.count()) > 0) {
    await page.getByPlaceholder("请输入用户名").fill(LOGIN_USERNAME);
    await page.getByPlaceholder("请输入密码").fill(LOGIN_PASSWORD);
    await loginButton.click();
  }
  try {
    await page.waitForURL(/\/chat/, { timeout: STEP_TIMEOUT_MS });
    return { blocked: false };
  } catch (error) {
    const bodyText = ((await page.locator("body").textContent()) || "").trim();
    if (/502|Bad Gateway|Network Error|服务不可用|请求失败/.test(bodyText)) {
      return { blocked: true, reason: `login flow blocked: ${bodyText.slice(0, 160)}` };
    }
    throw error;
  }
}

async function main() {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  const probe = await probeUi();
  if (probe.blocked) {
    const results = buildBlockedResults(cases, "ui_blocked", probe.reason);
    const report = writeReports(context, results, {
      notes: [probe.reason],
      casesFile: CASES_FILE
    });
    console.log(`[ui-regression] report json: ${context.jsonPath}`);
    console.log(`[ui-regression] report md: ${context.markdownPath}`);
    console.log(`[ui-regression] suite status: ${report.status}`);
    process.exit(exitCodeForResults(results));
  }

  const { chromium } = await import("playwright");
  const browser = await chromium.launch({ headless: HEADLESS });
  const contextBrowser = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  const page = await contextBrowser.newPage();
  let results = [];
  const notes = [];

  try {
    const loginResult = await login(page);
    if (loginResult.blocked) {
      notes.push(loginResult.reason);
      results = buildBlockedResults(cases, "ui_blocked", loginResult.reason);
    } else {
      notes.push("UI benchmark completed");
      await page.goto(`${FRONTEND_URL}/chat`, { waitUntil: "domcontentloaded", timeout: STEP_TIMEOUT_MS });
      await getComposer(page);

      for (const caseDef of cases) {
        try {
          const assistant = await sendQuestion(page, caseDef.question);
          const observation = await collectObservation(assistant);
          const screenshot = await takeCaseScreenshot(page, caseDef.id);
          const result = evaluateCase(caseDef, {
            ...observation,
            artifacts: { screenshot }
          });
          results.push(result);
          console.log(`[ui-regression] ${result.status} ${caseDef.id} -> ${result.reason}`);
        } catch (error) {
          const screenshot = await takeCaseScreenshot(page, `${caseDef.id}-failed`);
          const reason = error instanceof Error ? error.message : String(error);
          results.push({
            id: caseDef.id,
            question: caseDef.question,
            expectation: caseDef.expectation,
            referenceRequired: Boolean(caseDef.reference_required),
            safetyCase: Boolean(caseDef.safety_case),
            status: "FAIL",
            reason,
            responsePreview: "",
            hitTools: [],
            toolSelectionMatched: false,
            referenceCount: 0,
            confirmCount: 0,
            traceNodeCount: 0,
            fallbackUsed: false,
            safetyBlocked: false,
            events: {},
            httpStatus: null,
            artifacts: { screenshot }
          });
          console.error(`[ui-regression] FAIL ${caseDef.id}: ${reason}`);
        }
      }
    }
  } finally {
    await contextBrowser.close();
    await browser.close();
  }

  const report = writeReports(context, results, {
    notes,
    casesFile: CASES_FILE
  });
  console.log(`[ui-regression] report json: ${context.jsonPath}`);
  console.log(`[ui-regression] report md: ${context.markdownPath}`);
  console.log(`[ui-regression] suite status: ${report.status}`);
  process.exit(exitCodeForResults(results));
}

main().catch((error) => {
  console.error(`[ui-regression] fatal: ${error instanceof Error ? error.stack || error.message : String(error)}`);
  process.exit(1);
});
