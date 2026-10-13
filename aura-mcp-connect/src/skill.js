/**
 * The optional `aura-phone` agent skill that ships with this package.
 *
 * - Contract: installSkill() copies skills/aura-phone/SKILL.md into
 *   <dir>/aura-phone/SKILL.md (default ~/.claude/skills, where Claude Code
 *   looks), overwriting an older copy, and returns the written path.
 * - Why optional: the phone's MCP server works without it — rules are enforced
 *   on the phone and next steps ride in tool replies. The skill only gives an
 *   agent the whole map up front.
 * - Change together: the skill names phone tools, so a tool rename on the phone
 *   means editing SKILL.md (`SkillToolNamesTest` on the Kotlin side checks it).
 */

import fs from "fs";
import os from "os";
import path from "path";
import { fileURLToPath } from "url";

export const SKILL_SOURCE = fileURLToPath(new URL("../skills/aura-phone/SKILL.md", import.meta.url));

export function defaultSkillsDir() {
  return path.join(os.homedir(), ".claude", "skills");
}

export function installSkill({ dir = defaultSkillsDir() } = {}) {
  const target = path.join(dir, "aura-phone", "SKILL.md");
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.copyFileSync(SKILL_SOURCE, target);
  return target;
}
