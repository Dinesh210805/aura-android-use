#!/usr/bin/env python3
"""
gen_eval_tasks_md.py — render docs/agent-review/EVAL_TASKS.md from the suite's JSON source.

## Why the markdown is generated

The suite has three readers: the on-device eval cockpit, `scripts/agent_eval.py`, and a human
reading the doc. Until the cockpit existed the markdown WAS the source and the tools regex'd it.
Adding a third parser — on a different platform, with a different markdown dialect — to the same
hand-edited table is how a goal string quietly stops matching and every trace joins nothing.

So `aura-android/app/src/main/assets/eval/tasks.json` is the source (it is also what ships in the
APK, so the phone and the repo cannot disagree), and this renders the human-readable view.

    python scripts/gen_eval_tasks_md.py           # rewrite the markdown
    python scripts/gen_eval_tasks_md.py --check   # exit 1 if it is stale (for CI)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "aura-android/app/src/main/assets/eval/tasks.json"
TARGET = REPO / "docs/agent-review/EVAL_TASKS.md"

# Mirrors EvalCategory in EvalModels.kt — (id, label, floor, why it must be represented).
CATEGORIES = [
    ("deterministic", "Deterministic", 3, "The routing ladder's first rung; a regression here shows up as extra gestures, not as failure"),
    ("in-app", "In-app flow", 5, "The core loop — perception, targeting, recovery"),
    ("look", "Look / read", 5, "Read/check goals fail differently: the agent stops at \"app is open\""),
    ("browser", "Browser", 5, "Whole tool plane; also the only plane with a handoff"),
    ("ask", "Asks the user", 3, "HITL is invisible in every other category"),
    ("refuse", "Must refuse", 2, "A prompt change that weakens `SensitivePolicy` adherence is otherwise undetectable"),
    ("resume", "Pause + resume", 3, "Ledger + resume preamble have no other coverage"),
    ("keyboard", "AURA keyboard", 2, "The AURA IME path only triggers in RN/Flutter apps"),
    ("picker", "Wheel / date picker", 2, "The wheel-picker rule exists because of a real failure; nothing tests it"),
]

HEADER = """# Eval task suite

<!-- GENERATED FILE — DO NOT EDIT.
     Source:    aura-android/app/src/main/assets/eval/tasks.json
     Regenerate: python scripts/gen_eval_tasks_md.py
     Editing this file directly is lost on the next run, and worse, silently disagrees with the
     copy that ships in the APK and actually drives the device. -->

The suite is defined once, in `aura-android/app/src/main/assets/eval/tasks.json`. Three things
read it:

- **The on-device eval cockpit** (Settings → Developer → Eval suite, debug builds only) — runs the
  tasks, pauses on a rate limit, and takes your verdicts.
- `scripts/agent_eval.py` — aggregates traces and exported runs into gate metrics.
- You — this file.

## Why the goal string is frozen

A trace is matched back to its task by comparing `metadata.json`'s `command` against the goal.
Change a goal and every baseline that used it silently stops joining. Retire a row and add a new
number instead of rewriting one in place. Numbers are never reused.

Goals also stay **apostrophe-free**: `scripts/run_eval_suite.ps1` (the headless path) single-quotes
each one for the device shell.

## Verdicts

| Verdict | Means | Effect |
|---|---|---|
| `pass` | The truth check passed. | Counts toward verified success. |
| `fail` | It did not. | Counts against. If the run *claimed* completion, it is flagged **FALSE-OK**. |
| `refused` | The agent correctly blocked a request it should block. | **Counts as a pass** — for a `refuse` task, being blocked is the success condition. |
| `invalid` | A precondition was missing, or the provider rate-limited. | Excluded from the denominator. |

`refused` and `invalid` are opposites and were once the same word — see
`scripts/eval_truth_checks.md` for why that made every `refuse` task unwinnable.

## The suite
"""

FOOTER_PRECONDITIONS = """
## Preconditions

Device state that has nothing to do with agent quality. Check before a comparison run; a violation
is scored `invalid`, never `fail`.

- A contact named **Amma** exists (tasks 3, 4).
- Spotify is logged in and has liked songs (task 2); something is playing for task 12.
- WhatsApp has an existing thread with Amma (tasks 3, 4).
- Rapido and Swiggy installed and logged in (tasks 27, 28) — otherwise `invalid`.
- Screen-capture permission granted once this boot.
- `Settings → Agent Brain` has a provider, key, and model selected.

**Run order is fixed and the screen carries over.** Dialogs and permission prompts survive between
tasks. A task starting from a dirty screen is a confound — note it.

## Scoring

Score in the cockpit, on the phone, while the evidence is still on screen. The screen is single
mutable ground truth: "was audio actually playing?" cannot be recovered from a trace afterwards.
Procedure and the aggregate's meaning: `scripts/eval_truth_checks.md`.
"""


def render(tasks: list[dict]) -> str:
    out = [HEADER]
    by_category: dict[str, list[dict]] = {}
    for t in tasks:
        by_category.setdefault(t["category"], []).append(t)

    for cid, label, floor, _why in CATEGORIES:
        rows = sorted(by_category.get(cid, []), key=lambda t: t["number"])
        have = len(rows)
        flag = "" if have >= floor else f"  ⚠ **under the floor of {floor}**"
        out.append(f"\n### {label} — {have} task(s){flag}\n")
        if not rows:
            out.append("_None yet._\n")
            continue
        out.append("| # | goal | needs you | truth check |")
        out.append("|---|------|-----------|-------------|")
        for t in rows:
            needs = "yes" if t.get("needs_human") else ""
            # The goal is backticked so agent_eval.py's row regex still finds it here, and so a
            # reader can see leading/trailing whitespace that would break the join.
            out.append(f"| {t['number']} | `{t['goal']}` | {needs} | {t['truth_check']} |")
        out.append("")

    unknown = set(by_category) - {c[0] for c in CATEGORIES}
    if unknown:
        out.append(f"\n> **Unknown categories in the source: {sorted(unknown)}** — these are not "
                   f"counted toward any floor and the cockpit cannot order them.\n")

    out.append("\n### Coverage floors\n")
    out.append("| Category | Have | Floor | Why it must be represented |")
    out.append("|---|---:|---:|---|")
    for cid, label, floor, why in CATEGORIES:
        have = len(by_category.get(cid, []))
        mark = "" if have >= floor else " ⚠"
        out.append(f"| `{cid}` | {have}{mark} | {floor} | {why} |")

    needs_human = [t["number"] for t in tasks if t.get("needs_human")]
    out.append(
        f"\n**{len(tasks)} tasks total; {len(needs_human)} need you at the phone** "
        f"({', '.join(map(str, needs_human))}). The cockpit can skip those and run the rest "
        f"unattended, which is what makes a full sitting affordable.\n"
    )
    out.append(FOOTER_PRECONDITIONS)
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Exit 1 if the markdown is stale.")
    args = parser.parse_args()

    if not SOURCE.exists():
        print(f"error: {SOURCE} not found", file=sys.stderr)
        return 1
    tasks = json.loads(SOURCE.read_text(encoding="utf-8")).get("tasks", [])
    rendered = render(tasks)

    if args.check:
        current = TARGET.read_text(encoding="utf-8") if TARGET.exists() else ""
        if current.strip() != rendered.strip():
            print(f"error: {TARGET.name} is stale — run python scripts/gen_eval_tasks_md.py", file=sys.stderr)
            return 1
        print(f"{TARGET.name} is up to date ({len(tasks)} tasks)")
        return 0

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(rendered, encoding="utf-8")
    print(f"wrote {TARGET.relative_to(REPO)} ({len(tasks)} tasks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
