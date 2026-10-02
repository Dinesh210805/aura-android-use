/**
 * aura-phone skill: the shipped file is a loadable skill and install copies it.
 *
 * Run: node test/skill.test.mjs
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { installSkill, SKILL_SOURCE } from "../src/skill.js";

const source = fs.readFileSync(SKILL_SOURCE, "utf8");
const front = source.match(/^---\r?\n([\s\S]*?)\r?\n---/);
assert.ok(front, "SKILL.md must open with YAML frontmatter");
assert.match(front[1], /^name: aura-phone$/m);
assert.match(front[1], /^description: .{40,}/m, "description tells the agent when to load it");
assert.match(source, /policy_blocked/, "the skill carries the safety stop");

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aura-skill-"));
try {
  const target = installSkill({ dir });
  assert.equal(target, path.join(dir, "aura-phone", "SKILL.md"));
  assert.equal(fs.readFileSync(target, "utf8"), source);
  installSkill({ dir }); // reinstall over an existing copy
} finally {
  fs.rmSync(dir, { recursive: true, force: true });
}

console.log("skill.test: ok");
