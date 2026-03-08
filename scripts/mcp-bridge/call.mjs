#!/usr/bin/env node
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

function parseArgs(argv) {
  const result = {
    serverCommand: "",
    tool: "",
    argsJson: "{}",
    timeoutMs: 30000,
    cwd: undefined,
    env: {}
  };

  for (let i = 2; i < argv.length; i++) {
    const key = argv[i];
    if (key === "--serverCommand") {
      result.serverCommand = argv[++i] || "";
    } else if (key === "--tool") {
      result.tool = argv[++i] || "";
    } else if (key === "--args") {
      result.argsJson = argv[++i] || "{}";
    } else if (key === "--timeoutMs") {
      const parsed = Number(argv[++i]);
      result.timeoutMs = Number.isFinite(parsed) && parsed > 0 ? parsed : 30000;
    } else if (key === "--cwd") {
      result.cwd = argv[++i] || undefined;
    } else if (key === "--env") {
      const kv = argv[++i] || "";
      const idx = kv.indexOf("=");
      if (idx > 0) {
        const name = kv.slice(0, idx);
        const value = kv.slice(idx + 1);
        result.env[name] = value;
      }
    }
  }

  return result;
}

function splitCommand(commandLine) {
  const parts = [];
  let current = "";
  let inQuote = false;
  let quoteChar = "";

  for (const ch of commandLine) {
    if ((ch === '"' || ch === "'") && !inQuote) {
      inQuote = true;
      quoteChar = ch;
      continue;
    }
    if (inQuote && ch === quoteChar) {
      inQuote = false;
      quoteChar = "";
      continue;
    }
    if (!inQuote && /\s/.test(ch)) {
      if (current.length > 0) {
        parts.push(current);
        current = "";
      }
      continue;
    }
    current += ch;
  }
  if (current.length > 0) {
    parts.push(current);
  }

  return {
    command: parts[0] || "",
    args: parts.slice(1)
  };
}

function printResult(payload) {
  process.stdout.write(JSON.stringify(payload));
}

function fail(errorCode, errorMessage, details) {
  printResult({
    success: false,
    errorCode,
    errorMessage,
    details: details || null
  });
}

async function closeQuietly(client) {
  if (!client) {
    return;
  }
  try {
    await Promise.race([
      client.close(),
      new Promise((resolve) => setTimeout(resolve, 400))
    ]);
  } catch {
    // Ignore close failures from buggy servers.
  }
}

async function main() {
  const options = parseArgs(process.argv);
  if (!options.serverCommand) {
    fail("INVALID_ARGS", "missing --serverCommand");
    process.exit(1);
    return;
  }
  if (!options.tool) {
    fail("INVALID_ARGS", "missing --tool");
    process.exit(1);
    return;
  }

  let toolArgs = {};
  try {
    toolArgs = JSON.parse(options.argsJson || "{}");
  } catch {
    fail("INVALID_ARGS", "--args must be valid JSON");
    process.exit(1);
    return;
  }

  const { command, args } = splitCommand(options.serverCommand);
  if (!command) {
    fail("INVALID_ARGS", "server command is empty");
    process.exit(1);
    return;
  }

  const mergedEnv = {
    ...process.env,
    ...options.env
  };

  const transport = new StdioClientTransport({
    command,
    args,
    cwd: options.cwd,
    env: mergedEnv,
    stderr: "ignore"
  });

  const client = new Client(
    { name: "ragent-mcp-bridge", version: "0.1.0" },
    { capabilities: {} }
  );

  let timeoutId = null;
  try {
    timeoutId = setTimeout(() => {
      fail("TIMEOUT", `MCP call timed out after ${options.timeoutMs}ms`);
      process.exit(124);
    }, options.timeoutMs + 1000);

    await client.connect(transport);
    const result = await client.callTool({
      name: options.tool,
      arguments: toolArgs
    });

    clearTimeout(timeoutId);
    printResult({
      success: true,
      result
    });
    process.exit(0);
  } catch (error) {
    if (timeoutId) {
      clearTimeout(timeoutId);
    }
    fail("CALL_ERROR", String(error));
    process.exit(1);
  } finally {
    await closeQuietly(client);
  }
}

main();
