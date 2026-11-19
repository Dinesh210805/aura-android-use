@AGENTS.md

# How Claude works in this repo

Every non-trivial task goes **interview → spec → spec review → user approval → small steps, each
proven by its check**. No code is written before the spec is approved.

## This overrides other planning rules
For this repo, this file replaces the global PRD / architecture / system_design / task_list docs,
the planner agent, and the default save paths of brainstorming and plan-writing skills. A task
gets **one spec file in `docs/specs/`** and nothing else. Tests are written for what each step's
**Done when** names, not to reach a coverage number.

## When to skip it
Skip the interview and the spec only for: a typo or wording fix, a one-file change whose check is
obvious, a question that needs no code, or when the user says "just do it". Say in one line that
you are skipping it. Anything else gets the full flow.

## 1. Interview
Goal: understand what the user actually wants to hold in their hand at the end.

- **Read first.** Read the code, folder READMEs and specs the task touches before asking. Never ask
  what the code or the conversation already answers.
- **Ask with `AskUserQuestion`**: at most 4 questions per round, 2–4 options each, your
  recommended option first. Run as many rounds as needed; let each round build on the last.
- **Cover all of these** (skip one only if it is already clear):
  1. The problem, in the user's words. What happens today that is wrong or missing?
  2. Who uses it, and when (voice, chat, overlay, desktop bridge, benchmark)?
  3. The end product: what does the user see, tap or hear when it is done? A concrete example run.
  4. In scope and **out of scope**.
  5. How the user will check it worked: what would convince them?
  6. Constraints: on-device only, offline, privacy and sensitive apps, Android versions, speed.
  7. What happens when it fails: no network, missing permission, wrong screen.
  8. Priority against other open work, and what can wait for a later spec.
- **Stop asking** when you can write every Done-when check without guessing. Then play back a
  summary of at most 5 lines (problem, end product, out of scope, how we check) and get a yes.

## 2. Spec
**Location and name:** `docs/specs/<area>/<kind>-<topic>.md`, kebab-case, no date in the name.

| area | covers |
|---|---|
| `agent` | the on-device agent (`agentcore/`, `agent/`), prompts, safety, run log |
| `mcp` | `:mcp-server` tools, the agent's action space |
| `bridge` | `aura-mcp-connect/`, the desktop bridge |
| `ui` | app screens, overlay, settings (`presentation/`) |
| `voice` | wake word, speech in and out |
| `website` | `aura-android-use-website/` |
| `benchmark` | `benchmark/`, AndroidWorld |
| `release` | APK, npm and site releases, build setup |

Add a new area only when none of these fits, and add it to this table in the same commit.

| kind | use for |
|---|---|
| `feature` | something new the user can do |
| `fix` | a bug: what goes wrong, the root cause, the fix |
| `arch` | how parts fit together; a design other specs build on |
| `refactor` | same behaviour, better code |

Examples: `docs/specs/agent/feature-screen-history.md`, `docs/specs/mcp/fix-scroll-overshoot.md`,
`docs/specs/agent/arch-tool-hub.md`. One spec per task. Before writing a new one, check whether
an open spec already covers it and extend that one instead.

**Header:** `> **Status:** planned | in progress | done | dropped. **Written:** YYYY-MM-DD. **Branch:** name.`
`grep -r "Status:" docs/specs` is the index; there is no separate index file.

**Sections, in order:**
1. **Goal**: the problem and what the user wants, in their words from the interview. The spec
   must make sense to someone who never saw the chat.
2. **End product**: what the user sees when it is done, as one concrete example.
3. **Out of scope**: what we are not doing, and why.
4. **Steps**: numbered, foundation first (data, storage, contracts), then the features on top.
   Each step is small, shippable and committed on its own. Each step has:
   - **What changes**: the files and identifiers involved. Mark new ones `(new)`.
   - **Logic**: plain words, pseudocode, or both. The lasting design, not a workaround.
   - **Done when**: the winning criterion. It must be a named test that passes, a command
     with its expected output, or a result you can see on the device. "Works well",
     "is robust" or "handles errors" is not a criterion.
5. **Left out on purpose**: things considered and rejected, one line each with the reason.

**Writing rules:**
- Plain, short sentences a newcomer understands. Name exact identifiers.
- **Permanent logic only.** No "for now", no temporary flags, no TODO hacks. A deliberate
  shortcut says what it can't handle and what would replace it.
- **No over-engineering.** No interface with one implementation, no config for a fixed value, no
  step for a need nobody has yet. Prefer reusing what the repo already has.
- **No 100% perfection.** Aim for reliable and done; polish can be its own later spec.

## 3. Spec review (subagent)
Before showing the spec to the user, run the `spec-reviewer` subagent on it. The reviewer checks
that every file and identifier the spec names actually exists, that each Done-when can be run,
that the steps are in order, and that nothing is over-engineered or temporary.
- It answers **PASS** or **REVISE** with numbered findings. Fix what is right; push back on what
  is wrong, with evidence.
- **At most 2 revise rounds.** After that, show the user the spec with the points still in
  dispute, and let them decide.
- Then get the user's approval. Only then does implementation start.

## 4. Build in small steps
- Work one step at a time, in spec order. Set the header to `in progress` when you start.
- A step is done only when its **Done when** check has been run and passed. Paste the command
  and its output (or describe what you saw on the device) as the evidence. Never mark a step
  done because the code compiles.
- Tick the step (`- [x]`) in the same commit that finishes it. Set `done` when every step is ticked.
- **If the plan changes while building, edit the spec first,** say what changed and why, and
  get a yes if it changes the end product or the scope.
- Stuck after two tries at the same step: stop, say what failed, and ask. Don't widen the scope
  to get around it.
- Commit only when the user asks, and stage exact paths, never a folder (other sessions edit
  this repo too).
