import fs from "node:fs";
import path from "node:path";

export const FALLBACK_HINTS = ["未能建立浏览器连接", "联网检索结果不足", "通用回答", "[fallback]"];

const DATE_REPLY_PATTERN = /(今天是|现在是|现在时间是)/;
const OBSIDIAN_PATTERN = /(Obsidian|笔记|日记|目录|搜索结果|已找到|已创建|已追加)/i;
const SECURITY_BLOCK_PATTERN = /(SECURITY|安全|非法|拒绝|禁止|路径|穿越|不允许|仅允许|blocked|阻止|拦截)/i;
const WEB_SEARCH_SOURCE_PATTERN = /(https?:\/\/|来源[:：]|链接[:：]|阿里巴巴|Alibaba)/i;
const WEB_SEARCH_BAD_PATTERN = /(BeanFactory|FactoryBean|工厂模式)/i;

export function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

export function loadCases(casesFile) {
  return JSON.parse(fs.readFileSync(casesFile, "utf-8"));
}

export function createReportContext({
  suite,
  baseUrl,
  rootDir,
  reportRoot,
  reportTag,
  casesFile
}) {
  const tag = reportTag || new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "").replace("T", "-");
  const outputDir = reportRoot || path.resolve(rootDir, "docs/reports/second-brain");
  ensureDir(outputDir);
  return {
    suite,
    baseUrl,
    rootDir,
    casesFile,
    reportTag: tag,
    outputDir,
    jsonPath: path.join(outputDir, `${suite}-${tag}.json`),
    markdownPath: path.join(outputDir, `${suite}-${tag}.md`),
    latestJsonPath: path.join(outputDir, `${suite}-latest.json`),
    latestMarkdownPath: path.join(outputDir, `${suite}-latest.md`)
  };
}

export function parseSseTranscript(raw) {
  const text = typeof raw === "string" ? raw : "";
  const records = [];
  const lines = text.split(/\r?\n/);
  let event = "message";
  let dataLines = [];

  const flush = (force = false) => {
    if (!dataLines.length && !force) {
      event = "message";
      return;
    }
    const dataText = dataLines.join("\n");
    let payload = dataText;
    try {
      payload = dataText ? JSON.parse(dataText) : "";
    } catch {
      payload = dataText;
    }
    records.push({ event, payload, raw: dataText });
    event = "message";
    dataLines = [];
  };

  for (const line of lines) {
    if (!line) {
      flush();
      continue;
    }
    if (line.startsWith(":")) {
      continue;
    }
    if (line.startsWith("event:")) {
      event = line.slice(6).trim() || "message";
      continue;
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  flush(true);

  const eventCounts = Object.create(null);
  let responseText = "";
  const detectedTools = new Set();

  for (const record of records) {
    eventCounts[record.event] = (eventCounts[record.event] || 0) + 1;
    const payload = record.payload;
    if (record.event === "message" && payload && typeof payload === "object") {
      const type = payload.type || "";
      if (type === "response" || type === "reject") {
        responseText += payload.delta || payload.content || "";
      }
      continue;
    }
    if (record.event === "reject" && payload && typeof payload === "object") {
      responseText += payload.delta || payload.content || "";
      continue;
    }
    if (record.event === "agent_confirm_required" && payload && typeof payload === "object" && payload.toolId) {
      detectedTools.add(String(payload.toolId));
    }
    if (typeof payload === "string" && (record.event === "error" || record.event === "reject")) {
      responseText += payload;
    }
  }

  const traceNodeCount = [
    "workflow",
    "agent_observe",
    "agent_plan",
    "agent_step",
    "agent_replan",
    "agent_confirm_required",
    "references"
  ].reduce((sum, key) => sum + Number(eventCounts[key] || 0), 0);

  return {
    raw,
    records,
    eventCounts,
    responseText: responseText.trim(),
    detectedTools: Array.from(detectedTools),
    traceNodeCount
  };
}

export function detectFallbackUsed(text, records = []) {
  const normalized = String(text || "");
  if (FALLBACK_HINTS.some((hint) => normalized.includes(hint))) {
    return true;
  }
  return records.some((record) => {
    if (record.payload && typeof record.payload === "object") {
      return record.payload.fallbackUsed === true;
    }
    return typeof record.raw === "string" && record.raw.includes("\"fallbackUsed\":true");
  });
}

export function detectSecurityBlocked(text) {
  return SECURITY_BLOCK_PATTERN.test(String(text || ""));
}

function inferExpectedTools(expectation) {
  switch (expectation) {
    case "datetime":
      return ["direct_datetime_reply"];
    case "kb":
      return ["knowledge_base"];
    case "web_search":
      return ["web_search"];
    case "obsidian_read":
      return ["obsidian_search"];
    case "obsidian_plus_kb":
      return ["obsidian_search", "knowledge_base"];
    case "obsidian_write_confirm":
      return ["obsidian_create"];
    case "security_block":
      return ["obsidian_read"];
    default:
      return [];
  }
}

function normalizeTools(observation, expectation, toolSelectionMatched) {
  const tools = new Set(Array.isArray(observation.detectedTools) ? observation.detectedTools : []);
  if ((observation.referenceCount || 0) > 0) {
    tools.add("knowledge_base");
  }
  if (!tools.size && toolSelectionMatched) {
    inferExpectedTools(expectation).forEach((tool) => tools.add(tool));
  }
  return Array.from(tools);
}

function evaluateCaseSignals(caseDef, observation) {
  const text = String(observation.responseText || "");
  const referenceCount = Number(observation.referenceCount || 0);
  const confirmCount = Number(observation.confirmCount || 0);
  const hasReferences = referenceCount > 0;
  const hasConfirm = confirmCount > 0;
  const hasText = text.trim().length > 0;
  const securityBlocked = detectSecurityBlocked(text);
  let toolSelectionMatched = false;
  let reason = "-";

  switch (caseDef.expectation) {
    case "datetime":
      toolSelectionMatched = DATE_REPLY_PATTERN.test(text) && !hasConfirm;
      if (!toolSelectionMatched) {
        reason = "日期问答未命中快捷直答或误触发确认";
      }
      break;
    case "kb":
      toolSelectionMatched = hasReferences && !hasConfirm && hasText;
      if (!toolSelectionMatched) {
        reason = "知识库问答缺少引用或输出为空";
      }
      break;
    case "web_search": {
      const mentions1688 = /1688/i.test(text);
      const mentionsAlibaba = /(阿里巴巴|Alibaba)/i.test(text);
      const hasSourceEvidence = WEB_SEARCH_SOURCE_PATTERN.test(text);
      const avoidsFactoryPattern = !WEB_SEARCH_BAD_PATTERN.test(text);
      toolSelectionMatched = !hasConfirm && hasText && mentions1688 && mentionsAlibaba && hasSourceEvidence && avoidsFactoryPattern;
      if (!toolSelectionMatched) {
        reason = "外部实体联网问答未正确命中阿里巴巴语义、来源证据不足，或仍回到了工厂模式解释";
      }
      break;
    }
    case "obsidian_read":
      toolSelectionMatched = !hasConfirm && hasText && OBSIDIAN_PATTERN.test(text);
      if (!toolSelectionMatched) {
        reason = "Obsidian 只读问答输出为空或误触发确认";
      }
      break;
    case "obsidian_plus_kb":
      toolSelectionMatched = !hasConfirm && hasText && hasReferences && OBSIDIAN_PATTERN.test(text);
      if (!toolSelectionMatched) {
        reason = "Obsidian + 知识库问答未同时命中笔记结果与引用";
      }
      break;
    case "obsidian_write_confirm":
      toolSelectionMatched = hasConfirm || /待确认写操作|\/confirm/.test(text);
      if (!toolSelectionMatched) {
        reason = "Obsidian 写操作未进入确认链";
      }
      break;
    case "security_block":
      toolSelectionMatched = securityBlocked && !hasConfirm;
      if (!toolSelectionMatched) {
        reason = "危险路径读取未被安全阻断";
      }
      break;
    default:
      toolSelectionMatched = hasText;
      if (!toolSelectionMatched) {
        reason = "回答为空";
      }
      break;
  }

  if (caseDef.reference_required && !hasReferences) {
    reason = "缺少 references 证据";
  }

  if (caseDef.safety_case && !securityBlocked) {
    reason = "安全测试未被阻断";
  }

  return {
    toolSelectionMatched,
    safetyBlocked: securityBlocked,
    reason
  };
}

export function evaluateCase(caseDef, observation) {
  const signal = evaluateCaseSignals(caseDef, observation);
  const toolSelectionMatched = signal.toolSelectionMatched;
  const hitTools = normalizeTools(observation, caseDef.expectation, toolSelectionMatched);
  const passed =
    toolSelectionMatched &&
    (!caseDef.reference_required || Number(observation.referenceCount || 0) > 0) &&
    (!caseDef.safety_case || signal.safetyBlocked);

  return {
    id: caseDef.id,
    question: caseDef.question,
    expectation: caseDef.expectation,
    referenceRequired: Boolean(caseDef.reference_required),
    safetyCase: Boolean(caseDef.safety_case),
    status: passed ? "PASS" : "FAIL",
    reason: passed ? "-" : signal.reason,
    responsePreview: String(observation.responseText || "").slice(0, 200),
    hitTools,
    toolSelectionMatched,
    referenceCount: Number(observation.referenceCount || 0),
    confirmCount: Number(observation.confirmCount || 0),
    traceNodeCount: Number(observation.traceNodeCount || 0),
    fallbackUsed: Boolean(observation.fallbackUsed),
    safetyBlocked: Boolean(signal.safetyBlocked),
    events: observation.events || {},
    httpStatus: observation.httpStatus || null,
    artifacts: observation.artifacts || {}
  };
}

export function buildBlockedResults(cases, reason, detail = "", shared = {}) {
  return cases.map((caseDef) => ({
    id: caseDef.id,
    question: caseDef.question,
    expectation: caseDef.expectation,
    referenceRequired: Boolean(caseDef.reference_required),
    safetyCase: Boolean(caseDef.safety_case),
    status: "BLOCKED",
    reason,
    blockedDetail: detail,
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
    artifacts: shared.artifacts || {}
  }));
}

function formatRate(numerator, denominator) {
  if (!denominator) {
    return { value: null, display: "N/A", numerator, denominator };
  }
  const value = Number((numerator / denominator).toFixed(4));
  return { value, display: `${(value * 100).toFixed(1)}%`, numerator, denominator };
}

export function summarizeResults(results) {
  const totalCases = results.length;
  const passedCases = results.filter((item) => item.status === "PASS").length;
  const failedCases = results.filter((item) => item.status === "FAIL").length;
  const blockedCases = results.filter((item) => item.status === "BLOCKED").length;
  const executedCases = totalCases - blockedCases;

  const toolCases = results.filter((item) => item.status !== "BLOCKED" && item.toolSelectionMatched !== null);
  const toolPasses = toolCases.filter((item) => item.toolSelectionMatched === true).length;

  const referenceCases = results.filter((item) => item.status !== "BLOCKED" && item.referenceRequired);
  const referencePasses = referenceCases.filter((item) => item.referenceCount > 0).length;

  const safetyCases = results.filter((item) => item.status !== "BLOCKED" && item.safetyCase);
  const safetyPasses = safetyCases.filter((item) => item.safetyBlocked).length;

  return {
    totalCases,
    executedCases,
    passedCases,
    failedCases,
    blockedCases,
    casePassRate: formatRate(passedCases, executedCases),
    toolSelectionAccuracy: formatRate(toolPasses, toolCases.length),
    referenceCoverage: formatRate(referencePasses, referenceCases.length),
    safetyBlockRate: formatRate(safetyPasses, safetyCases.length)
  };
}

function buildMarkdown(report) {
  const { summary, results, metadata } = report;
  const lines = [
    `# ${report.suite.toUpperCase()} Second Brain Regression Report`,
    "",
    `- Generated At: ${report.generatedAt}`,
    `- Report Tag: ${report.reportTag}`,
    `- Base URL: ${report.baseUrl}`,
    `- Suite Status: ${report.status}`,
    `- Total Cases: ${summary.totalCases}`,
    `- Executed Cases: ${summary.executedCases}`,
    `- Passed: ${summary.passedCases}`,
    `- Failed: ${summary.failedCases}`,
    `- Blocked: ${summary.blockedCases}`,
    `- case_pass_rate: ${summary.casePassRate.display} (${summary.casePassRate.numerator}/${summary.casePassRate.denominator})`,
    `- tool_selection_accuracy: ${summary.toolSelectionAccuracy.display} (${summary.toolSelectionAccuracy.numerator}/${summary.toolSelectionAccuracy.denominator})`,
    `- reference_coverage: ${summary.referenceCoverage.display} (${summary.referenceCoverage.numerator}/${summary.referenceCoverage.denominator})`,
    `- safety_block_rate: ${summary.safetyBlockRate.display} (${summary.safetyBlockRate.numerator}/${summary.safetyBlockRate.denominator})`,
    ""
  ];

  if (metadata?.notes?.length) {
    lines.push("## Notes", "");
    for (const note of metadata.notes) {
      lines.push(`- ${note}`);
    }
    lines.push("");
  }

  lines.push("| Case | Status | Tools | Refs | Confirm | Trace | Fallback | Reason |");
  lines.push("| --- | --- | --- | --- | --- | --- | --- | --- |");

  for (const item of results) {
    lines.push(
      `| ${item.id} | ${item.status} | ${(item.hitTools || []).join(", ") || "-"} | ${item.referenceCount} | ${item.confirmCount} | ${item.traceNodeCount} | ${item.fallbackUsed ? "yes" : "no"} | ${item.reason || "-"} |`
    );
  }

  return `${lines.join("\n")}\n`;
}

export function writeReports(context, results, metadata = {}) {
  const summary = summarizeResults(results);
  const status = summary.failedCases > 0 ? "FAIL" : summary.blockedCases > 0 ? "BLOCKED" : "PASS";
  const report = {
    suite: context.suite,
    reportTag: context.reportTag,
    generatedAt: new Date().toISOString(),
    baseUrl: context.baseUrl,
    status,
    summary,
    metadata,
    results
  };

  ensureDir(context.outputDir);
  const jsonText = `${JSON.stringify(report, null, 2)}\n`;
  const markdownText = buildMarkdown(report);
  fs.writeFileSync(context.jsonPath, jsonText);
  fs.writeFileSync(context.markdownPath, markdownText);
  fs.writeFileSync(context.latestJsonPath, jsonText);
  fs.writeFileSync(context.latestMarkdownPath, markdownText);

  return report;
}

export function exitCodeForResults(results) {
  const hasFail = results.some((item) => item.status === "FAIL");
  if (hasFail) {
    return 1;
  }
  const hasBlocked = results.some((item) => item.status === "BLOCKED");
  if (hasBlocked) {
    return 2;
  }
  return 0;
}
