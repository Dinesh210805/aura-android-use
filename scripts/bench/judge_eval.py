#!/usr/bin/env python3
"""Replay the completion judge over recorded runs and score it against the human verdicts.

The judge ships in shadow mode (`CompletionJudge.ENFORCE = false`): it records a verdict but
cannot refuse a run. This script is what decides whether that flips. It reads the same prompt
asset the app reads, so the thing measured here IS the thing running on the phone.

    # 1. pull traces + score them by hand as usual (scripts/agent_eval.py --pull --scores ...)
    # 2. replay the judge over those traces
    python scripts/bench/judge_eval.py --dir traces/ --scores docs/agent-review/scores.md \
        --model gemini-3.5-flash-lite

Agreement is the fraction of runs where the judge and the human said the same thing: a human
`pass` needs `success`, a human `fail` needs `fail` or `partial`. At 0.85 or better, set
`CompletionJudge.ENFORCE = true`. Below that, the judge stays advisory — a model that cannot
match a human's verdict has no business blocking a task.

Read-only: it never touches the device and never rewrites a trace.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PROMPT_ASSET = REPO_ROOT / "aura-android/app/src/main/assets/judge/completion_judge.md"

# The same shape agent_eval.py parses out of the scores markdown: "| 3 | pass | ... |".
SCORE_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*(pass|fail|refused|invalid)\b", re.IGNORECASE)

READ_TOOLS = {"read_screen", "perceive_screen", "get_screenshot", "verify_action", "get_ui_tree"}

MAX_SCREEN_CHARS = 4000
MAX_ANSWER_CHARS = 1500


@dataclass
class Replay:
    session_id: str
    goal: str
    claimed: str | None
    answer: str
    findings: list[dict]
    target: int | None
    human: str | None = None
    judged: str | None = None
    why: str | None = None

    @property
    def agrees(self) -> bool | None:
        if self.human is None or self.judged is None:
            return None
        if self.human == "pass":
            return self.judged == "success"
        if self.human == "fail":
            return self.judged in ("fail", "partial")
        return None


def load_sessions(directory: Path) -> list[dict]:
    out = []
    for path in sorted(directory.rglob("metadata.json")):
        try:
            out.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"  skipped {path}: {exc}", file=sys.stderr)
    return out


def final_answer(data: dict) -> str:
    """What the run told the user: end_session's reason, else the last assistant text."""
    for inv in reversed(data.get("invocations") or []):
        if inv.get("toolName") == "end_session":
            args = inv.get("argsJson") or "{}"
            try:
                return str(json.loads(args).get("reason", ""))
            except json.JSONDecodeError:
                return args
    calls = data.get("llmCalls") or []
    return str(calls[-1].get("response", "")) if calls else ""


def claimed_outcome(data: dict) -> str | None:
    for inv in reversed(data.get("invocations") or []):
        if inv.get("toolName") == "end_session":
            try:
                return json.loads(inv.get("argsJson") or "{}").get("outcome")
            except json.JSONDecodeError:
                return None
    return None


def recent_screens(data: dict, limit: int = 2) -> str:
    reads = [
        inv.get("gridPayload") or inv.get("outputSummary") or ""
        for inv in (data.get("invocations") or [])
        if inv.get("toolName") in READ_TOOLS and inv.get("success", True)
    ]
    return "\n---\n".join(reversed(reads[-limit:]))[:MAX_SCREEN_CHARS]


def build_replay(data: dict) -> Replay | None:
    if data.get("source") != "agent":
        return None
    outcome = data.get("outcome") or {}
    return Replay(
        session_id=data.get("sessionId", "?"),
        goal=(data.get("command") or "").strip(),
        claimed=outcome.get("claimed") or claimed_outcome(data),
        answer=final_answer(data)[:MAX_ANSWER_CHARS],
        findings=outcome.get("findings") or [],
        target=outcome.get("target"),
    )


def fill_prompt(template: str, replay: Replay, data: dict) -> str:
    findings = (
        "\n".join(f"- {f.get('item')} | quoted: \"{f.get('quote')}\"" for f in replay.findings)
        or "(none recorded)"
    )
    steps = "\n".join(
        f"- {'ok' if inv.get('success', True) else 'FAILED'} {inv.get('toolName')}"
        for inv in (data.get("invocations") or [])[-20:]
    ) or "(no steps recorded)"
    return (
        template.replace("{{GOAL}}", replay.goal)
        .replace("{{CLAIMED}}", replay.claimed or "never said (the run ended without end_session)")
        .replace("{{ANSWER}}", replay.answer or "(said nothing)")
        .replace("{{FINDING_COUNT}}", str(len(replay.findings)))
        .replace("{{FINDINGS}}", findings)
        .replace("{{STEPS}}", steps)
        .replace("{{SCREENS}}", recent_screens(data) or "(nothing read)")
    )


def ask(prompt: str, model: str, api_key: str, base_url: str) -> str | None:
    """One OpenAI-compatible chat/completions call — the same wire shape BrainChat uses."""
    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.0,
            "extra_body": {"google": {"thinking_config": {"thinking_level": "low"}}},
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        return payload["choices"][0]["message"]["content"]
    except (urllib.error.URLError, KeyError, json.JSONDecodeError, TimeoutError) as exc:
        print(f"  judge call failed: {exc}", file=sys.stderr)
        return None


def parse_verdict(reply: str) -> tuple[str | None, str | None]:
    start, end = reply.find("{"), reply.rfind("}")
    if start < 0 or end <= start:
        return None, None
    try:
        obj = json.loads(reply[start : end + 1])
    except json.JSONDecodeError:
        return None, None
    verdict = str(obj.get("verdict", "")).lower().strip()
    return (verdict if verdict in {"success", "partial", "fail"} else None), obj.get("why")


def load_scores(path: Path) -> dict[int, str]:
    scores: dict[int, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = SCORE_ROW.match(line.strip())
        if match:
            scores[int(match.group(1))] = match.group(2).lower()
    return scores


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", type=Path, required=True, help="Directory of pulled metadata.json traces.")
    ap.add_argument("--scores", type=Path, help="Markdown table of human verdicts (task # | verdict).")
    ap.add_argument("--model", default="gemini-3.5-flash-lite")
    ap.add_argument(
        "--base-url",
        default="https://generativelanguage.googleapis.com/v1beta/openai",
        help="OpenAI-compatible endpoint of the brain being measured.",
    )
    ap.add_argument("--save", type=Path, help="Write the replay results as JSON.")
    args = ap.parse_args()

    api_key = os.environ.get("AURA_JUDGE_KEY") or os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("Set AURA_JUDGE_KEY (or GEMINI_API_KEY) to the provider key.", file=sys.stderr)
        return 2
    if not PROMPT_ASSET.exists():
        print(f"Prompt asset missing: {PROMPT_ASSET}", file=sys.stderr)
        return 2
    template = PROMPT_ASSET.read_text(encoding="utf-8")

    # Human verdicts are joined by ORDER here, not by goal text: this replays a scored suite
    # run, so trace N is task N. A mismatch shows up immediately as nonsense agreement.
    scores = load_scores(args.scores) if args.scores else {}

    replays: list[Replay] = []
    for data in load_sessions(args.dir):
        replay = build_replay(data)
        if replay is None or not replay.goal:
            continue
        prompt = fill_prompt(template, replay, data)
        reply = ask(prompt, args.model, api_key, args.base_url)
        if reply:
            replay.judged, replay.why = parse_verdict(reply)
        replays.append(replay)

    for index, replay in enumerate(replays, start=1):
        replay.human = scores.get(index)

    comparable = [r for r in replays if r.agrees is not None]
    agreed = sum(1 for r in comparable if r.agrees)

    print(f"\n{'#':>2} {'human':<8} {'judge':<8} {'ok':<3} goal")
    for index, replay in enumerate(replays, start=1):
        ok = "-" if replay.agrees is None else ("yes" if replay.agrees else "NO")
        print(f"{index:>2} {replay.human or '-':<8} {replay.judged or '-':<8} {ok:<3} {replay.goal[:50]}")
        if replay.agrees is False:
            print(f"     judge said: {replay.why}")

    if comparable:
        agreement = agreed / len(comparable)
        print(f"\nAgreement: {agreed}/{len(comparable)} = {agreement:.2f}")
        print(
            "→ set CompletionJudge.ENFORCE = true"
            if agreement >= 0.85
            else "→ keep the judge in shadow mode; it does not match human verdicts yet"
        )
    else:
        print("\nNo comparable runs — supply --scores from the same suite run.")

    if args.save:
        args.save.write_text(
            json.dumps([r.__dict__ for r in replays], indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print(f"Saved → {args.save}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
