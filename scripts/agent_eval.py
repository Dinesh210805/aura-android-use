#!/usr/bin/env python3
"""
agent_eval.py — Phase 0 eval harness for the on-device AURA Koog agent.

The on-device agent already writes a LangSmith-style trace per run to
`files/mcp_logs/<sessionId>/metadata.json` (see aura-android/.../mcp/log/SessionLog.kt).
This script aggregates those traces into the production-readiness metrics that
gate every phase of the agent-improvement plan:

    - VERIFIED success rate        (the human truth check — the only gate metric)
    - false successes              (claimed complete, truth check says otherwise)
    - claimed success rate         (endReason == "completed" — reported, never a gate)
    - median LLM calls per task    (len(llmCalls) over completed runs)
    - rate-limit-rejection rate    (LlmCall whose response body is a 429)
    - peak prompt tokens           (max promptTokens across a run)
    - wall time                    (endedAt - startedAt)
    - blind-acting count           (gestures issued before the first perceive)

## Why two success numbers

`endReason == "completed"` is the agent's claim about itself, and on 2026-07-29 it
lied: the agent tapped Library in Spotify, nothing played, and the run reported
success. Every gate therefore rests on a human watching the device. Supply those
verdicts with `--scores`; without them this tool prints the agent's claim and says
loudly that it must not be used to gate a change.

Sessions are matched to suite tasks by goal string, so the suite definition
(docs/agent-review/EVAL_TASKS.md) is the single source shared with
scripts/run_eval_suite.ps1.

It is read-only: it pulls metadata.json off the device (or reads an already
pulled copy) and computes numbers. It does NOT trigger runs — use
scripts/run_eval_suite.ps1 for that.

## Why --after exists

The app never clears `files/mcp_logs`, so a `--pull` with no window takes every
run the device has ever done (90+ by 2026-08). Folding those into a fresh 40-run
suite drags every metric back toward the historical value — hiding exactly the
improvement you ran the suite to measure. `run_eval_suite.ps1` prints the device
clock at suite start; pass it as `--after`.

Usage:
    # Pull straight off a connected device and print the report:
    python scripts/agent_eval.py --pull

    # Pull + save a scored baseline (the only kind worth comparing against),
    # windowed to the suite run that run_eval_suite.ps1 just finished:
    python scripts/agent_eval.py --pull --after 1786596000000 \
        --scores _gtmp/baseline_step0_scored.md \
        --save _gtmp/baseline_step0.json

    # Read already-pulled metadata and compare against a saved baseline:
    python scripts/agent_eval.py --dir _gtmp/evalsamples \
        --scores _gtmp/post_step0_scored.md \
        --compare _gtmp/baseline_step0.json
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
from dataclasses import dataclass, asdict
from pathlib import Path

# Commands can contain non-Latin text (e.g. Tamil); force UTF-8 stdout so the
# report doesn't die on Windows' cp1252 console.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEFAULT_PACKAGE = "com.aura.aura_ui.feature.debug"
DEVICE_LOG_DIR = "files/mcp_logs"

# Only on-device agent runs carry llmCalls + command. MCP sessions are skipped.
AGENT_SOURCE = "agent"

# Gestures that target on-screen content and therefore REQUIRE a fresh
# perception to be grounded. launch_app / press_* / open_deeplink are global
# navigation, not coordinate-targeted, so issuing them before a perceive is not
# "blind" — matching how docs/agent-learnings.md Part IV counted the baseline.
GROUNDING_REQUIRED_GESTURES = {
    "tap", "double_tap", "long_press", "swipe",
    "scroll_up", "scroll_down", "scroll_left", "scroll_right",
    "scroll_to", "type_text",
}

# v1 grounding signal: only perceive_screen counts. get_ui_tree also returns
# element bounds, but the doc's baseline counted perceive_screen alone, so we
# keep that for before/after comparability.
GROUNDING_TOOLS = {"perceive_screen"}

# The suite definition and the human's verdicts. `endReason == "completed"` is the
# agent's claim ABOUT ITSELF and it has lied in production (2026-07-29, task 2:
# tapped Library in Spotify, nothing played, run reported success). So the gate
# metric is the human truth check, not the trace. Without a scores file this tool
# reports the agent's claim and says loudly that it is not a gate.
#
# The suite definition is the JSON asset that also ships in the APK, so the phone, this script and
# the generated markdown cannot disagree. The markdown is still accepted (older baselines were
# taken against it) but it is now a rendering, not a source — and a rendering whose column order
# this script must not depend on.
DEFAULT_TASKS_FILE = "aura-android/app/src/main/assets/eval/tasks.json"

# Row shapes in the two markdown tables we parse. Both start with an integer task
# number, which is what distinguishes a data row from a header or separator.
_TASK_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|\s*([a-z-]+)\s*\|", re.M)
# The verdict cell is matched as "whatever is between the pipes", not as a
# character class. A class silently changes the meaning of a malformed row: it
# stops matching at all, so the task is dropped from the denominator and NO
# warning fires. `n/a` (an accepted spelling) and `??` (a typo the docs promise
# to warn about) both used to vanish that way. Matching loosely and validating
# afterwards means every numbered row reaches parse_scores() and is either
# scored or complained about.
# The leading integer still distinguishes a data row from a header or a `|---|`
# separator, so those are skipped as before.
_SCORE_ROW = re.compile(r"^\|\s*(\d+)\s*\|([^|]*)\|(.*)$", re.M)

# Forgiving verdict vocabulary — scoring happens by hand, often at speed.
_PASS = {"pass", "p", "yes", "y", "true", "ok"}
_FAIL = {"fail", "f", "no", "n", "false"}
# `refused` and `invalid` used to be the same word, and that made every
# refuse-category task unwinnable: a correct policy block is the PASS condition
# for those tasks, but scoring it "refused" dropped it from the denominator, so
# the task could only ever hurt. They are now two different verdicts:
#
#   refused  the agent correctly blocked a request it should block  -> counts as PASS
#   invalid  a precondition was missing (no Amma contact, Spotify   -> EXCLUDED
#            logged out); the trial never tested the agent
#
# `refused` is counted as a pass unconditionally, but a refusal on a task whose
# category is not `refuse` is tallied separately (refused_off_category) so a
# reader can audit whether any free passes were handed out.
_REFUSED = {"refused", "refuse", "blocked", "r"}
_INVALID = {"invalid", "precondition", "skip", "s", "na", "n/a", "x"}


@dataclass(frozen=True)
class EvalTask:
    """One row of EVAL_TASKS.md — the join key between the runner and the scorer."""
    number: int
    goal: str
    category: str


def parse_tasks(path: Path) -> dict[str, EvalTask]:
    """Read EVAL_TASKS.md into {normalised goal -> task}.

    Keyed by goal rather than number because that is what a trace carries: the
    broadcast sends the goal verbatim, so `metadata.json`'s `command` matches it.
    """
    if not path.exists():
        return {}
    if path.suffix.lower() == ".json":
        tasks = json.loads(path.read_text(encoding="utf-8")).get("tasks", [])
        return {
            _norm(t["goal"]): EvalTask(
                number=int(t["number"]), goal=t["goal"].strip(), category=t.get("category", "").strip()
            )
            for t in tasks
        }
    text = path.read_text(encoding="utf-8")
    return {
        _norm(goal): EvalTask(number=int(num), goal=goal.strip(), category=cat.strip())
        for num, goal, cat in _TASK_ROW.findall(text)
    }


def parse_export_scores(path: Path) -> dict[int, tuple[str, str]]:
    """Read verdicts out of an eval-cockpit export (`export.json` from the on-device runner).

    The cockpit is now where scoring happens — on the phone, while the evidence is still on screen —
    so its export is the primary source of verdicts and the markdown table is the manual fallback.
    The vocabulary is identical on both sides on purpose: `EvalVerdict.wire` in EvalModels.kt emits
    exactly the words `_PASS`/`_FAIL`/`_REFUSED`/`_INVALID` accept.
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    scores: dict[int, tuple[str, str]] = {}
    for row in data.get("results", []):
        verdict = row.get("verdict")
        if not verdict:
            continue  # never attempted, or attempted and not yet judged — not a verdict
        low = str(verdict).lower()
        if low not in {"pass", "fail", "refused", "invalid"}:
            print(f"  warn: task {row.get('number')}: unknown exported verdict {verdict!r}", file=sys.stderr)
            continue
        scores[int(row["number"])] = (low, (row.get("notes") or "").strip())
    return scores


def parse_scores(path: Path) -> dict[int, tuple[str, str]]:
    """Read a filled-in scores table into {task number -> (verdict, notes)}.

    Verdict is normalised to pass/fail/refused/invalid. An unrecognised word is
    dropped with a warning rather than silently scored — a typo must not become
    a pass.
    """
    scores: dict[int, tuple[str, str]] = {}
    for num, word, rest in _SCORE_ROW.findall(path.read_text(encoding="utf-8")):
        low = word.strip().lower()
        if low in _PASS:
            verdict = "pass"
        elif low in _FAIL:
            verdict = "fail"
        elif low in _REFUSED:
            verdict = "refused"
        elif low in _INVALID:
            verdict = "invalid"
        else:
            print(f"  warn: task {num}: unrecognised verdict {word.strip()!r} — not scored", file=sys.stderr)
            continue
        scores[int(num)] = (verdict, rest.strip(" |").strip())
    return scores


def _norm(goal: str) -> str:
    """Case- and whitespace-insensitive goal key, so trivial drift still matches."""
    return " ".join(goal.lower().split())


@dataclass
class SessionMetrics:
    """Per-run rollup of one metadata.json."""
    session_id: str
    command: str
    end_reason: str
    completed: bool
    llm_calls: int
    rate_limit_rejections: int
    error_calls: int
    peak_prompt_tokens: int
    total_tokens: int
    wall_seconds: float
    tool_calls: int
    perceive_calls: int
    blind_gestures: int
    # The harness's own verdict (spec 2026-09-15): what the run proved, and what the
    # completion judge made of it. Absent on runs recorded before proof-backed completion,
    # and on tasks that declared no contract.
    harness_verdict: str | None = None   # success | partial | fail | unverified | None
    claimed_outcome: str | None = None   # what the model itself said at end_session
    proven: int = 0
    target: int | None = None
    # Filled in by attach_scores() when a suite + scores file are supplied.
    task_number: int | None = None
    task_category: str | None = None
    verdict: str | None = None          # pass | fail | refused | invalid | None (unscored)

    @property
    def judge_agrees(self) -> bool | None:
        """Whether the harness verdict matches the human's, or None when either is missing.

        This is the number that decides whether the judge may ever block a run: it ships in
        shadow mode (CompletionJudge.ENFORCE = false) precisely so this can be measured on
        hand-scored runs first.
        """
        if self.verdict is None or self.harness_verdict in (None, "unverified"):
            return None
        if self.verdict == "pass":
            return self.harness_verdict == "success"
        if self.verdict == "fail":
            return self.harness_verdict in ("fail", "partial")
        return None

    @property
    def false_success(self) -> bool:
        """The agent claimed the task was done and the world says otherwise.

        This is the number that matters: a harness that cannot tell a real
        completion from a claimed one cannot gate anything.
        """
        return self.completed and self.verdict == "fail"


def attach_scores(
    sessions: list[SessionMetrics],
    tasks: dict[str, EvalTask],
    scores: dict[int, tuple[str, str]],
) -> None:
    """Join sessions to suite tasks by goal text, then to the human's verdict.

    Mutates in place. A session whose command is not in the suite (an ad-hoc run
    sharing the log directory) stays unscored and is excluded from verified
    metrics rather than counted as a failure.
    """
    for s in sessions:
        task = tasks.get(_norm(s.command))
        if task is None:
            continue
        s.task_number = task.number
        s.task_category = task.category
        scored = scores.get(task.number)
        if scored is not None:
            s.verdict = scored[0]


def _is_rate_limit(response: str) -> bool:
    """A 429 is logged as the raw provider error body (verified against real
    Groq traces: {"error":{"message":"Rate limit reached for model ..."}})."""
    low = response.lower()
    return "rate limit reached" in low or "too many requests" in low


def _is_error_response(response: str) -> bool:
    """Provider returned a structured error body rather than a completion."""
    stripped = response.lstrip()
    if not stripped.startswith("{"):
        return False
    try:
        obj = json.loads(stripped)
    except (json.JSONDecodeError, ValueError):
        return False
    return isinstance(obj, dict) and "error" in obj


def _count_blind_gestures(invocations: list[dict]) -> int:
    """Gestures issued before the first perception. The screen is unread until
    the first perceive_screen, so any coordinate-targeted gesture before it is a
    blind guess — the root cause the plan's Phase 1 attacks."""
    grounded = False
    blind = 0
    for inv in invocations:
        name = inv.get("toolName", "")
        if name in GROUNDING_TOOLS:
            grounded = True
        elif name in GROUNDING_REQUIRED_GESTURES and not grounded:
            blind += 1
    return blind


def summarize_session(data: dict) -> SessionMetrics | None:
    """Roll one parsed metadata.json into metrics, or None if not an agent run."""
    if data.get("source") != AGENT_SOURCE:
        return None

    llm_calls = data.get("llmCalls", []) or []
    invocations = data.get("invocations", []) or []

    started = data.get("startedAtMillis") or 0
    ended = data.get("endedAtMillis") or started
    end_reason = data.get("endReason") or "unknown"

    prompt_tokens = [c.get("promptTokens") or 0 for c in llm_calls]
    total_tokens = sum(c.get("totalTokens") or 0 for c in llm_calls)

    rate_limits = sum(1 for c in llm_calls if _is_rate_limit(c.get("response", "")))
    # Errors that are NOT rate limits (auth, malformed, 5xx) — tracked separately
    # so a provider outage doesn't masquerade as a rate-limit problem.
    errors = sum(
        1 for c in llm_calls
        if _is_error_response(c.get("response", "")) and not _is_rate_limit(c.get("response", ""))
    )

    outcome = data.get("outcome") or {}

    return SessionMetrics(
        session_id=data.get("sessionId", "?"),
        command=(data.get("command") or "").strip(),
        end_reason=end_reason,
        completed=end_reason == "completed",
        harness_verdict=outcome.get("verdict"),
        claimed_outcome=outcome.get("claimed"),
        proven=outcome.get("proven") or 0,
        target=outcome.get("target"),
        llm_calls=len(llm_calls),
        rate_limit_rejections=rate_limits,
        error_calls=errors,
        peak_prompt_tokens=max(prompt_tokens, default=0),
        total_tokens=total_tokens,
        wall_seconds=round((ended - started) / 1000.0, 1),
        tool_calls=len(invocations),
        perceive_calls=sum(1 for i in invocations if i.get("toolName") == "perceive_screen"),
        blind_gestures=_count_blind_gestures(invocations),
    )


# ── device I/O ──────────────────────────────────────────────────────────────

def _adb(args: list[str]) -> str:
    """Run an adb command, returning stdout (raises on non-zero exit)."""
    result = subprocess.run(
        ["adb", *args], capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def _session_dir_millis(sid: str) -> int | None:
    """The device names a session dir `<startedAtMillis>-<hash>`. Reading the
    prefix lets --after skip a stale session without paying an `adb cat` for it.
    Purely a speed optimisation: load_dir() re-checks the authoritative field."""
    head = sid.split("-", 1)[0]
    return int(head) if head.isdigit() else None


def pull_metadata(package: str, out_dir: Path, after: int | None = None) -> int:
    """Copy every session's metadata.json off the device into out_dir.
    Returns the number of files pulled. Screenshots are intentionally skipped —
    the harness only needs the JSON trace."""
    out_dir.mkdir(parents=True, exist_ok=True)
    listing = _adb(["shell", "run-as", package, "ls", DEVICE_LOG_DIR])
    session_ids = [line.strip() for line in listing.splitlines() if line.strip()]
    pulled = 0
    for sid in session_ids:
        if after is not None:
            millis = _session_dir_millis(sid)
            if millis is not None and millis < after:
                continue
        try:
            content = _adb(["shell", "run-as", package, "cat", f"{DEVICE_LOG_DIR}/{sid}/metadata.json"])
        except RuntimeError:
            continue  # session dir without metadata.json yet — skip
        if content.strip():
            (out_dir / f"{sid}.json").write_text(content, encoding="utf-8")
            pulled += 1
    return pulled


def load_dir(metadata_dir: Path, after: int | None = None) -> list[SessionMetrics]:
    """Parse every *.json in a directory into agent-run metrics (skips MCP runs).

    `after` (epoch millis, device clock) drops sessions that started before it.
    This is the load-bearing half of the window filter, not the one in
    pull_metadata(): the app never clears `files/mcp_logs`, and pull_metadata()
    never clears `out_dir` either, so a directory that already holds 90 stale
    pulls keeps feeding all 90 to the aggregate no matter what the pull skipped.
    Diluting a 40-run suite with 90 historical runs moves every metric toward the
    old value — i.e. in the direction that hides a real improvement.
    """
    sessions: list[SessionMetrics] = []
    skipped = 0
    for f in sorted(metadata_dir.glob("*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, ValueError):
            print(f"  warn: could not parse {f.name}", file=sys.stderr)
            continue
        if after is not None and (data.get("startedAtMillis") or 0) < after:
            skipped += 1
            continue
        metrics = summarize_session(data)
        if metrics is not None:
            sessions.append(metrics)
    if skipped:
        # Counts every trace, agent and MCP alike — it is a window report, not a
        # sample size. The aggregate's "Agent sessions" is the number that counts.
        print(f"  --after {after}: skipped {skipped} trace(s) started earlier", file=sys.stderr)
    return sessions


# ── aggregation & reporting ───────────────────────────────────────────────────

def _ratio(part: int, whole: int) -> float | None:
    """Rounded share, or None when there is nothing to divide by — never a fake 0.0."""
    return round(part / whole, 3) if whole else None


def aggregate(sessions: list[SessionMetrics]) -> dict:
    """Compute the gate metrics across all agent runs.

    Two success numbers, deliberately reported side by side:

      success_rate           the agent's claim (endReason == "completed")
      verified_success_rate  the human truth check — the only one that gates

    They are kept separate rather than reconciled because their disagreement IS a
    metric: `false_successes` counts runs that claimed completion the world does
    not support, and a rise there means the agent got better at lying, not at
    working.

    Only `invalid` trials (a missing precondition) leave the denominator. A
    correct `refused` is a pass: for a refuse-category task, being blocked IS the
    success condition, and excluding it made those tasks unwinnable.
    """
    total = len(sessions)
    completed = [s for s in sessions if s.completed]
    calls_completed = [s.llm_calls for s in completed]

    scored = [s for s in sessions if s.verdict is not None]
    judged = [s for s in scored if s.verdict != "invalid"]        # pass/fail/refused
    verified = [s for s in judged if s.verdict in ("pass", "refused")]
    refused = [s for s in scored if s.verdict == "refused"]

    return {
        "agent_sessions": total,
        "completed": len(completed),
        "success_rate": round(len(completed) / total, 3) if total else 0.0,
        "scored_sessions": len(scored),
        "verified_completed": len(verified),
        "verified_success_rate": round(len(verified) / len(judged), 3) if judged else None,
        "false_successes": sum(1 for s in sessions if s.false_success),
        "refused": len(refused),
        # A refusal on a task that was not supposed to be refused is a free pass
        # handed to the agent. Counted, not warned about, so it stays auditable.
        "refused_off_category": sum(1 for s in refused if s.task_category != "refuse"),
        "invalid": sum(1 for s in scored if s.verdict == "invalid"),
        # Proof-backed completion (spec 2026-09-15). The judge ships in shadow mode; these two
        # numbers are what decide whether it is trusted to refuse a run. 85% is the bar.
        "judge_comparable": sum(1 for s in sessions if s.judge_agrees is not None),
        "judge_agreement": _ratio(
            sum(1 for s in sessions if s.judge_agrees is True),
            sum(1 for s in sessions if s.judge_agrees is not None),
        ),
        "proof_short_runs": sum(
            1 for s in sessions if s.target is not None and s.proven < s.target
        ),
        "unmatched_sessions": sum(1 for s in sessions if s.task_number is None),
        "median_llm_calls_completed": statistics.median(calls_completed) if calls_completed else 0,
        "total_rate_limit_rejections": sum(s.rate_limit_rejections for s in sessions),
        "sessions_with_rate_limit": sum(1 for s in sessions if s.rate_limit_rejections),
        "total_error_calls": sum(s.error_calls for s in sessions),
        "sessions_with_blind_gestures": sum(1 for s in sessions if s.blind_gestures),
        "total_blind_gestures": sum(s.blind_gestures for s in sessions),
        "median_peak_prompt_tokens": statistics.median([s.peak_prompt_tokens for s in sessions]) if sessions else 0,
        "max_peak_prompt_tokens": max((s.peak_prompt_tokens for s in sessions), default=0),
        "median_wall_seconds": statistics.median([s.wall_seconds for s in sessions]) if sessions else 0,
    }


def print_report(sessions: list[SessionMetrics], agg: dict, scores_supplied: bool) -> None:
    print("\n=== Per-session (agent runs, newest first) ===")
    header = (
        f"{'#':>2} {'end':<10} {'true':<8} {'harness':<9} {'proof':>7} {'LLM':>3} {'tool':>4} "
        f"{'RL':>3} {'err':>3} {'perc':>4} {'blind':>5} {'pkTok':>6} {'sec':>6}  command"
    )
    print(header)
    print("-" * len(header))
    for s in sorted(sessions, key=lambda x: x.session_id, reverse=True):
        num = str(s.task_number) if s.task_number is not None else "-"
        # A false success is the one row a reader must not skim past.
        truth = "FALSE-OK" if s.false_success else (s.verdict or "-")
        # The harness's own verdict beside the human's: where they disagree is where the
        # judge needs work, and where they agree is the case for letting it block a run.
        harness = s.harness_verdict or "-"
        proof = f"{s.proven}/{s.target}" if s.target is not None else "-"
        print(
            f"{num:>2} {s.end_reason:<10} {truth:<8} {harness:<9} {proof:>7} "
            f"{s.llm_calls:>3} {s.tool_calls:>4} "
            f"{s.rate_limit_rejections:>3} {s.error_calls:>3} {s.perceive_calls:>4} "
            f"{s.blind_gestures:>5} {s.peak_prompt_tokens:>6} {s.wall_seconds:>6.1f}  {s.command[:46]}"
        )

    # There are three different reasons the gate metric can be missing, and they
    # need three different actions. Saying "you forgot --scores" when the real
    # problem is that no session joined the suite is wrong in exactly the case
    # that matters: a goal string mangled on its way through PowerShell ->
    # adb shell -> device shell makes every run unscorable, and the old banner
    # sent the reader off to fix a flag that was already correct.
    joined = agg["agent_sessions"] - agg["unmatched_sessions"]
    if agg["verified_success_rate"] is None:
        if not scores_supplied:
            print(
                "\n  !! UNSCORED — no truth checks supplied (--scores).\n"
                "     'Success rate' below is the agent's claim about itself and has been\n"
                "     wrong in production. Do NOT gate a change on it. See\n"
                "     scripts/eval_truth_checks.md.",
                file=sys.stderr,
            )
        elif joined == 0:
            print(
                "\n  !! JOIN FAILED — --scores WAS supplied, but not one session matched a\n"
                "     suite task, so no verdict could be attached. This is not a scoring\n"
                "     problem: the goal strings in the traces do not match the goals in the\n"
                "     suite file. Compare a trace's `command` against its row in\n"
                "     docs/agent-review/EVAL_TASKS.md — quoting through PowerShell ->\n"
                "     adb shell -> device shell is the usual culprit. Also check that\n"
                "     --tasks points at the right file.",
                file=sys.stderr,
            )
        elif agg["scored_sessions"] == 0:
            print(
                f"\n  !! NOTHING SCORED — {joined} session(s) joined the suite, but the scores\n"
                "     file covers a different set of task numbers, so not one of them got a\n"
                "     verdict. See the 'scored task(s) ... have no matching run' warning above.",
                file=sys.stderr,
            )
        else:
            print(
                f"\n  !! NO JUDGED TRIALS — {joined} session(s) joined the suite, but every\n"
                "     scored one was `invalid` (a missing precondition), so the denominator\n"
                "     is empty. Fix the device state and re-run; nothing here gates a change.",
                file=sys.stderr,
            )
    elif agg["false_successes"]:
        print(
            f"\n  !! {agg['false_successes']} FALSE SUCCESS(ES) — runs that claimed completion\n"
            "     the truth check does not support. Fix these before reading any other number.",
            file=sys.stderr,
        )

    print("\n=== Aggregate (gate metrics) ===")
    labels = {
        "agent_sessions": "Agent sessions",
        "scored_sessions": "  of which truth-checked",
        "verified_success_rate": "VERIFIED success rate (the gate)",
        "verified_completed": "  verified completions",
        "false_successes": "  false successes (claimed, untrue)",
        "refused": "  correctly refused (counts as pass)",
        "refused_off_category": "    of which NOT a refuse task",
        "invalid": "  invalid trials (excluded)",
        "judge_agreement": "Judge agreement with human (≥0.85 to enforce)",
        "judge_comparable": "  runs the judge and human both scored",
        "proof_short_runs": "  runs that ended short of their proof target",
        "unmatched_sessions": "Sessions matching no suite task",
        "completed": "Claimed completed (endReason)",
        "success_rate": "Claimed success rate (NOT a gate)",
        "median_llm_calls_completed": "Median LLM calls / completed task",
        "total_rate_limit_rejections": "Rate-limit rejections (total)",
        "sessions_with_rate_limit": "Sessions hitting a rate limit",
        "total_error_calls": "Non-RL error calls (total)",
        "sessions_with_blind_gestures": "Sessions with blind gestures",
        "total_blind_gestures": "Blind gestures (total)",
        "median_peak_prompt_tokens": "Median peak prompt tokens",
        "max_peak_prompt_tokens": "Max peak prompt tokens",
        "median_wall_seconds": "Median wall seconds",
    }
    for key, label in labels.items():
        print(f"  {label:<38} {agg[key]}")


def print_comparison(current: dict, baseline_path: Path) -> None:
    base = json.loads(baseline_path.read_text(encoding="utf-8")).get("aggregate", {})
    print(f"\n=== Comparison vs {baseline_path.name} ===")
    # Metrics where lower is better get an inverted arrow.
    lower_better = {
        "median_llm_calls_completed", "total_rate_limit_rejections",
        "sessions_with_rate_limit", "total_error_calls",
        "sessions_with_blind_gestures", "total_blind_gestures",
        "median_peak_prompt_tokens", "max_peak_prompt_tokens", "median_wall_seconds",
        "false_successes",
    }
    # Informational: a change here reflects which tasks ran, not whether the agent
    # got better, so labelling it improved/REGRESSED would be misleading.
    neutral = {
        "agent_sessions", "scored_sessions", "refused", "refused_off_category",
        "invalid", "unmatched_sessions",
    }

    for key in current:
        if key not in base:
            continue
        old, new = base[key], current[key]
        # verified_success_rate is None until someone scores the run. Comparing a
        # scored run against an unscored baseline (or vice versa) is meaningless,
        # so say so rather than printing a bogus arrow.
        if old is None or new is None:
            if key == "verified_success_rate":
                print(f"  {key:<38} {old} -> {new}  [NOT COMPARABLE — one side unscored]")
            continue
        if old == new:
            arrow = "="
        elif key in neutral:
            arrow = "changed"
        elif key in lower_better:
            arrow = "improved" if new < old else "REGRESSED"
        else:
            arrow = "improved" if new > old else "REGRESSED"
        print(f"  {key:<38} {old} -> {new}  [{arrow}]")

    # The gate, restated so a skimmer cannot miss it.
    old_v, new_v = base.get("verified_success_rate"), current.get("verified_success_rate")
    if old_v is not None and new_v is not None:
        if new_v < old_v:
            print("\n  !! VERIFIED SUCCESS REGRESSED — revert this change, do not tune it.", file=sys.stderr)
        elif current.get("false_successes", 0) > base.get("false_successes", 0):
            print("\n  !! FALSE SUCCESSES ROSE — the agent got better at claiming, not at working.", file=sys.stderr)
    else:
        print(
            "\n  !! No verified comparison — score both runs with --scores before gating on this.",
            file=sys.stderr,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Aggregate on-device agent traces into gate metrics.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="App package id (default: debug variant).")
    parser.add_argument("--pull", action="store_true", help="Pull metadata off the connected device first.")
    parser.add_argument("--dir", default="_gtmp/eval", help="Directory of metadata.json files to read.")
    parser.add_argument(
        "--after", type=int, metavar="EPOCH_MS",
        help="Only consider sessions whose startedAtMillis is >= this (DEVICE clock). "
             "run_eval_suite.ps1 prints the value to use; without it every historical "
             "run on the device is folded into the numbers.",
    )
    parser.add_argument("--save", metavar="FILE", help="Save the aggregate + per-session rollup as a baseline JSON.")
    parser.add_argument("--compare", metavar="FILE", help="Compare current aggregate against a saved baseline JSON.")
    parser.add_argument(
        "--tasks", default=DEFAULT_TASKS_FILE,
        help=f"Suite definition to match sessions against (default: {DEFAULT_TASKS_FILE}).",
    )
    parser.add_argument(
        "--scores", metavar="FILE",
        help="Filled-in truth-check table. Without it, only the agent's own (unreliable) "
             "success claim is available and nothing can be gated.",
    )
    args = parser.parse_args()

    # A seconds-resolution value here is January 1970 in millis: the filter would
    # match every session and --after would silently become a no-op, which is the
    # exact bug it was added to fix. Refuse it rather than proceed.
    if args.after is not None and args.after < 1_000_000_000_000:
        print(
            f"error: --after {args.after} looks like seconds, not milliseconds. "
            "startedAtMillis is epoch millis (13 digits); a seconds value would match "
            "every session and filter nothing.",
            file=sys.stderr,
        )
        return 1

    metadata_dir = Path(args.dir)

    if args.pull:
        print(f"Pulling metadata for {args.package} -> {metadata_dir} ...")
        try:
            n = pull_metadata(args.package, metadata_dir, after=args.after)
        except RuntimeError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
        print(f"  pulled {n} session(s)")

    if not metadata_dir.exists():
        print(f"error: {metadata_dir} does not exist (use --pull or point --dir at metadata).", file=sys.stderr)
        return 1

    sessions = load_dir(metadata_dir, after=args.after)
    if not sessions:
        where = f"{metadata_dir}"
        if args.after is not None:
            where += f" at or after {args.after}"
        print(f"No agent runs (source='agent') found in {where}.", file=sys.stderr)
        return 1

    tasks = parse_tasks(Path(args.tasks))
    if not tasks:
        print(f"  warn: no suite tasks parsed from {args.tasks} — sessions cannot be scored.", file=sys.stderr)
    # A cockpit export and a hand-written markdown table are both accepted; the extension picks.
    # The export is the normal path now — the phone is where scoring happens.
    scores = {}
    if args.scores:
        scores_path = Path(args.scores)
        scores = (
            parse_export_scores(scores_path) if scores_path.suffix.lower() == ".json"
            else parse_scores(scores_path)
        )
    attach_scores(sessions, tasks, scores)

    if scores:
        unmatched = sorted(scores.keys() - {s.task_number for s in sessions if s.task_number})
        if unmatched:
            # Scored a task whose run is missing: usually the task timed out and
            # never wrote a trace. Silently dropping it would inflate the rate.
            print(f"  warn: scored task(s) {unmatched} have no matching run in {metadata_dir}", file=sys.stderr)

    # The mirror image of the warning above, and the one that was missing: runs
    # on disk that matched no suite task at all. A handful is normal — ad-hoc runs
    # share the log directory. All of them is a broken join, not a dirty
    # directory; see the JOIN FAILED banner in print_report().
    orphans = [s for s in sessions if s.task_number is None]
    if orphans:
        print(
            f"  warn: {len(orphans)} of {len(sessions)} session(s) matched no suite task "
            "— excluded from verified metrics",
            file=sys.stderr,
        )

    agg = aggregate(sessions)
    print_report(sessions, agg, scores_supplied=bool(scores))

    if args.compare:
        print_comparison(agg, Path(args.compare))

    if args.save:
        out = Path(args.save)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps({"aggregate": agg, "sessions": [asdict(s) for s in sessions]}, indent=2),
            encoding="utf-8",
        )
        print(f"\nSaved baseline -> {out}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
