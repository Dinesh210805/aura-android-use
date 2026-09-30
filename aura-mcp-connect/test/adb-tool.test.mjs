/**
 * aura-adb unit test — no real adb, no phone.
 *
 * createAdbTool() takes its runner as a dependency precisely so this file can
 * assert on the argv adb *would* have received, and on how the handler shapes
 * adb's output back into an MCP result.
 *
 * Run: node test/adb-tool.test.mjs
 */
import assert from "node:assert/strict";
import { createAdbTool, MAX_OUTPUT_CHARS } from "../src/adb-tool.js";

/** Records every invocation and replays a canned result. */
function makeRunner(impl) {
  const calls = [];
  const run = async (file, args, opts) => {
    calls.push({ file, args, opts });
    return impl ? impl(file, args, opts) : { stdout: "ok", stderr: "" };
  };
  run.calls = calls;
  return run;
}

// ── An empty or missing command must never reach adb ──────────────────
for (const args of [undefined, {}, { command: "" }, { command: "   " }, { command: 42 }]) {
  const run = makeRunner();
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler(args);
  assert.equal(result.isError, true, `must reject ${JSON.stringify(args)}`);
  assert.match(result.content[0].text, /non-empty string/);
  assert.equal(run.calls.length, 0, "adb must not be invoked for a blank command");
}

// ── Command string is split into argv; no serial → no -s ──────────────
{
  const run = makeRunner();
  const tool = createAdbTool({ run, env: {} });
  await tool.handler({ command: "  shell   pm list packages -3 " });
  assert.deepEqual(run.calls[0].args, ["shell", "pm", "list", "packages", "-3"]);
  assert.equal(run.calls[0].file, "adb");
}

// ── AURA_ADB_SERIAL prepends -s <serial> ──────────────────────────────
{
  const run = makeRunner();
  const tool = createAdbTool({ run, env: { AURA_ADB_SERIAL: "ABC123" } });
  await tool.handler({ command: "logcat -d" });
  assert.deepEqual(run.calls[0].args, ["-s", "ABC123", "logcat", "-d"]);
}

// ── Metacharacters stay literal argv — no HOST shell is involved ──────
// Scope note: this proves nothing runs on *this machine*. It does NOT mean the
// phone sees them literally — `adb shell a; b` hands "a; b" to the device's
// shell, which chains (verified on a real device 2026-08-06). The boundary
// execFile defends is the host.
{
  const run = makeRunner();
  const tool = createAdbTool({ run, env: {} });
  await tool.handler({ command: "shell ls; rm -rf /" });
  assert.deepEqual(run.calls[0].args, ["shell", "ls;", "rm", "-rf", "/"]);
}

// ── Quotes are NOT stripped — callers must write pipes unquoted ────────
{
  const run = makeRunner();
  const tool = createAdbTool({ run, env: {} });
  await tool.handler({ command: 'shell "dumpsys window"' });
  assert.deepEqual(run.calls[0].args, ["shell", '"dumpsys', 'window"']);
}

// ── stdout and stderr are combined ────────────────────────────────────
{
  const run = makeRunner(() => ({ stdout: "line-out", stderr: "line-err" }));
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler({ command: "devices" });
  assert.equal(result.isError, undefined);
  assert.equal(result.content[0].text, "line-out\nline-err");
}

// ── Silent success still returns something the model can read ─────────
{
  const run = makeRunner(() => ({ stdout: "", stderr: "" }));
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler({ command: "shell true" });
  assert.equal(result.content[0].text, "(no output)");
}

// ── Output past MAX_OUTPUT_CHARS is truncated, not dumped ─────────────
{
  const overflow = 500;
  const run = makeRunner(() => ({ stdout: "x".repeat(MAX_OUTPUT_CHARS + overflow), stderr: "" }));
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler({ command: "logcat -d" });
  const text = result.content[0].text;
  assert.ok(text.length < MAX_OUTPUT_CHARS + overflow, "must be shorter than the raw output");
  assert.match(text, /…\(truncated, \d+ more chars\)$/);
  assert.equal(text.slice(0, MAX_OUTPUT_CHARS), "x".repeat(MAX_OUTPUT_CHARS));
}

// ── A failing adb returns isError, and does not throw past the boundary ─
{
  const run = makeRunner(() => {
    const err = new Error("Command failed: adb devices");
    err.stdout = "";
    err.stderr = "error: device unauthorized";
    throw err;
  });
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler({ command: "devices" });
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /device unauthorized/);
  assert.match(result.content[0].text, /Command failed/);
}

// ── A non-Error rejection is still reported, not swallowed ────────────
{
  const run = makeRunner(() => {
    throw "adb not found";
  });
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler({ command: "devices" });
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /adb not found/);
}

// ── Truncation applies to the error path too ──────────────────────────
{
  const run = makeRunner(() => {
    const err = new Error("boom");
    err.stderr = "e".repeat(MAX_OUTPUT_CHARS + 100);
    throw err;
  });
  const tool = createAdbTool({ run, env: {} });
  const result = await tool.handler({ command: "logcat" });
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /…\(truncated, \d+ more chars\)$/);
}

console.log("adb-tool.test.mjs: ALL PASS");
