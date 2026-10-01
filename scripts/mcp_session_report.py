#!/usr/bin/env python3
"""
mcp_session_report.py — how well do PC agents (Claude Code, Cursor, …) drive the phone?

agent_eval.py measures the on-device agent and skips `source == "mcp"` sessions. This is
its counterpart for those: it reads the same `files/mcp_logs/<id>/metadata.json` traces
and counts the failure signatures that say an agent is wasting turns or fighting a guard.

Signatures (matched on each invocation's `outputSummary`, which holds the reply text, or
"refused: <code>" for a call a gate refused before it ran):

    refused            any gate refusal (scope, policy, control lock, foreground, action guard)
    action_guard       refused by the server's ActionGuard (blind tap, loop, unverified finish)
    stale_som          a som_id that died because the screen changed ("is STALE:")
    policy_blocked     a hard-blocked sensitive action
    already_foreground launch_app into the app that was already open
    usage_guide        calls to get_usage_guide (expected ~0 — pull-only guidance)

Usage:
    python scripts/mcp_session_report.py --pull --after <device-millis> --save _gtmp/mcp_base.json
    python scripts/mcp_session_report.py --dir _gtmp/eval --compare _gtmp/mcp_base.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from agent_eval import DEFAULT_PACKAGE, pull_metadata

MCP_SOURCE = "mcp"

SIGNATURES: dict[str, callable] = {
    "refused": lambda inv: inv["out"].startswith("refused:"),
    "action_guard": lambda inv: inv["out"].startswith("refused: action_guard"),
    "stale_som": lambda inv: "is STALE:" in inv["out"],
    "policy_blocked": lambda inv: "policy_blocked" in inv["out"],
    "already_foreground": lambda inv: '"already_foreground":true' in inv["out"].replace(" ", ""),
    "usage_guide": lambda inv: inv["tool"] == "get_usage_guide",
}


def summarize(data: dict) -> dict | None:
    """One MCP session's tool-call count and signature counts, or None for non-MCP traces."""
    if (data.get("source") or MCP_SOURCE) != MCP_SOURCE:
        return None
    invocations = [
        {"tool": i.get("toolName", ""), "out": i.get("outputSummary") or ""}
        for i in data.get("invocations") or []
    ]
    return {
        "session_id": data.get("sessionId", "?"),
        "agent": data.get("agentLabel") or "?",
        "tool_calls": len(invocations),
        **{name: sum(1 for inv in invocations if match(inv)) for name, match in SIGNATURES.items()},
    }


def load(metadata_dir: Path, after: int | None) -> list[dict]:
    sessions = []
    for f in sorted(metadata_dir.glob("*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except ValueError:
            print(f"  warn: could not parse {f.name}", file=sys.stderr)
            continue
        if after is not None and (data.get("startedAtMillis") or 0) < after:
            continue
        row = summarize(data)
        if row is not None and row["tool_calls"]:
            sessions.append(row)
    return sessions


def aggregate(sessions: list[dict]) -> dict:
    """Totals plus each signature per 100 tool calls, so runs of different length compare."""
    calls = sum(s["tool_calls"] for s in sessions)
    agg = {"sessions": len(sessions), "tool_calls": calls}
    for name in SIGNATURES:
        total = sum(s[name] for s in sessions)
        agg[name] = total
        agg[f"{name}_per_100_calls"] = round(100 * total / calls, 1) if calls else None
    return agg


def main() -> int:
    parser = argparse.ArgumentParser(description="Failure signatures in PC-agent MCP sessions.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--pull", action="store_true", help="Pull metadata off the connected device first.")
    parser.add_argument("--dir", default="_gtmp/eval")
    parser.add_argument("--after", type=int, help="Only sessions started at/after this device epoch millis.")
    parser.add_argument("--save", metavar="FILE", help="Write the aggregate as a baseline JSON.")
    parser.add_argument("--compare", metavar="FILE", help="Print deltas against a saved baseline.")
    args = parser.parse_args()

    out_dir = Path(args.dir)
    if args.pull:
        print(f"pulled {pull_metadata(args.package, out_dir, args.after)} trace(s)", file=sys.stderr)

    sessions = load(out_dir, args.after)
    agg = aggregate(sessions)

    for s in sessions:
        flags = " ".join(f"{k}={s[k]}" for k in SIGNATURES if s[k])
        print(f"{s['session_id']:<28} {s['agent'][:24]:<24} calls={s['tool_calls']:<4} {flags}")
    print(json.dumps(agg, indent=2))

    if args.compare:
        base = json.loads(Path(args.compare).read_text(encoding="utf-8"))
        print("\n=== vs baseline (per 100 calls) ===")
        for name in SIGNATURES:
            key = f"{name}_per_100_calls"
            print(f"{name:<20} {base.get(key)!s:>8} -> {agg.get(key)!s:>8}")
    if args.save:
        Path(args.save).write_text(json.dumps(agg, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
