#!/usr/bin/env python3
"""Measure what every MCP tool description costs on every single request.

### Why this exists

Turn-0 prompts on the 2026-08-26 eval suite were 16,290-16,500 tokens, and roughly
12,500 of that is tool descriptions -- about 77%, and the only part of the prompt
nobody had ever budgeted. `docs/superpowers/specs/2026-08-25-agent-brain-instructions-design.md`
Step 3 sets per-plane targets; this is what says whether they were hit.

The number that matters is **description + inputSchema text together**. They ride the
same `tools` field of the same request, so a "cut" that moves prose from one into the
other is not a cut. Measuring only the description is how a tool ends up documenting
one parameter twice (`perceive_screen`'s `detail`, 2026-08-26) and reading as if it
had been trimmed.

### Known blind spot — read this before trusting the total

This reads Kotlin source with a regex, so a schema **assembled by a helper** is invisible to it.
That is not hypothetical: `browserSchema()` adds three shared properties to all eleven browser
tools, and this script counted none of them — about 600 tokens the model paid on every request
and no measurement here could see.

`ToolDescriptionBudgetTest` measures what registration actually produced and is the one that
gates. Use this script for the fast per-tool loop while editing; believe the test.

### Method, and its one deliberate imprecision

Tokens are estimated as `chars / 4` -- the same `PromptTokenEstimate.CHARS_PER_TOKEN`
the on-device budget uses. That is wrong in absolute terms for every tokenizer, and
right for the only thing this is for: comparing the same text before and after an edit
under an estimator that does not move. Absolute truth comes from a device run's
`llmCalls[0].promptTokens`, which is a real provider figure; this is for deciding what
to cut without one.

Usage:
    python scripts/tool_desc_budget.py                 # every tool, by plane
    python scripts/tool_desc_budget.py --tool perceive_screen
    python scripts/tool_desc_budget.py --save _gtmp/desc_baseline.json
    python scripts/tool_desc_budget.py --compare _gtmp/desc_baseline.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CHARS_PER_TOKEN = 4

TOOLS_DIR = Path("aura-android/mcp-server/src/main/kotlin/com/aura/mcp/tools")

# Plane assignment mirrors the budget table in the design doc. A tool that matches no
# prefix lands in "other" and is reported -- an unassigned tool is an unbudgeted tool,
# which is the state this script exists to end.
PLANES: dict[str, tuple[str, ...]] = {
    "browser": ("browser_",),
    "perception": (
        "perceive_screen", "read_screen", "get_screenshot", "verify_action",
        "wait_for", "get_device_status", "find_text", "get_ui_tree",
    ),
    "apps": ("launch_app", "open_deeplink", "list_app", "resolve_deeplink", "list_installed"),
    "assistant": (
        "system_intent", "read_notifications", "media_", "resolve_contact",
        "send_", "read_files", "list_files", "notification_",
    ),
    "session": ("end_session", "set_plan", "mark_step", "ask_user", "use_skill", "get_usage_guide"),
    "gestures": (
        "tap", "double_tap", "long_press", "swipe", "scroll", "type_text",
        "press_", "key_", "drag", "pinch", "select_", "clear_text", "back", "home",
    ),
}

# name = "x", then anything, then description = """...""" or description = "..." + "..."
_TOOL_BLOCK = re.compile(
    r'name\s*=\s*"(?P<name>[a-z_0-9]+)"(?P<body>.*?)(?=\n\s*(?:\}|internal fun|private fun|fun )\s)',
    re.S,
)
_TRIPLE = re.compile(r'description\s*=\s*"""(?P<text>.*?)"""', re.S)
_CONCAT = re.compile(r'description\s*=\s*(?P<text>"(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*)', re.S)
_SCHEMA = re.compile(r'inputSchema\s*=\s*(?P<text>.*?)(?=\n\s*\)\s*\{|\n\s*\}\s*\n)', re.S)
_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def trim_indent(text: str) -> str:
    """Kotlin `String.trimIndent()`, because the raw literal is not what ships.

    A raw triple-quoted block in this codebase carries 12 spaces of source indentation
    on every line. Counting those charges a tool ~25% for whitespace the model never
    sees, and — worse — makes a description look like it shrank when someone only
    re-indented it. Blank first/last lines go too, matching Kotlin.
    """
    lines = text.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    indents = [len(ln) - len(ln.lstrip()) for ln in lines if ln.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(ln[cut:] if ln.strip() else "" for ln in lines)


def tokens(text: str) -> int:
    return len(text) // CHARS_PER_TOKEN


def plane_of(name: str) -> str:
    for plane, prefixes in PLANES.items():
        if any(name == p or name.startswith(p) for p in prefixes):
            return plane
    return "other"


def string_chars(blob: str) -> str:
    """Every Kotlin string literal in a fragment, concatenated.

    Schema fragments are code, not prose: only the quoted parts reach the model, so
    counting the fragment verbatim would charge the tool for its own property names
    and helper calls.
    """
    return "".join(_STRING.findall(blob))


def scan(tools_dir: Path) -> list[dict]:
    found: list[dict] = []
    for path in sorted(tools_dir.glob("*.kt")):
        src = path.read_text(encoding="utf-8")
        for block in _TOOL_BLOCK.finditer(src):
            name, body = block.group("name"), block.group("body")
            triple = _TRIPLE.search(body)
            if triple:
                desc = trim_indent(triple.group("text"))
            else:
                concat = _CONCAT.search(body)
                if not concat:
                    continue
                desc = string_chars(concat.group("text"))
            schema = _SCHEMA.search(body)
            schema_text = string_chars(schema.group("text")) if schema else ""
            found.append(
                {
                    "name": name,
                    "file": path.name,
                    "plane": plane_of(name),
                    "desc_tokens": tokens(desc),
                    "schema_tokens": tokens(schema_text),
                    "total_tokens": tokens(desc) + tokens(schema_text),
                }
            )
    return found


def report(rows: list[dict], only: str | None) -> None:
    if only:
        rows = [r for r in rows if r["name"] == only]
        if not rows:
            print(f"no tool named {only!r} found under {TOOLS_DIR}", file=sys.stderr)
            raise SystemExit(2)

    by_plane: dict[str, list[dict]] = {}
    for row in rows:
        by_plane.setdefault(row["plane"], []).append(row)

    grand = 0
    for plane in sorted(by_plane):
        items = sorted(by_plane[plane], key=lambda r: -r["total_tokens"])
        subtotal = sum(r["total_tokens"] for r in items)
        grand += subtotal
        print(f"\n{plane.upper()}  ({len(items)} tools, {subtotal} tok)")
        for r in items:
            print(
                f"  {r['total_tokens']:>5}  {r['name']:<24}"
                f"  desc {r['desc_tokens']:>4}  schema {r['schema_tokens']:>4}"
            )
    print(f"\nTOTAL  {len(rows)} tools, {grand} tok on every request "
          f"(~{CHARS_PER_TOKEN} chars/tok estimate)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tool", help="report a single tool by name")
    ap.add_argument("--dir", default=str(TOOLS_DIR), help="tools source directory")
    ap.add_argument("--save", help="write the measurement to a JSON file")
    ap.add_argument("--compare", help="diff against a previously saved JSON file")
    args = ap.parse_args()

    rows = scan(Path(args.dir))
    if not rows:
        print(f"no tools parsed under {args.dir} — did the registration shape change?", file=sys.stderr)
        raise SystemExit(2)

    if args.compare:
        before = {r["name"]: r for r in json.loads(Path(args.compare).read_text())}
        now = {r["name"]: r for r in rows}
        delta_total = 0
        for name in sorted(set(before) | set(now)):
            b = before.get(name, {}).get("total_tokens", 0)
            a = now.get(name, {}).get("total_tokens", 0)
            if b != a:
                delta_total += a - b
                print(f"  {a - b:+6}  {name:<24} {b} -> {a}")
        print(f"\nNET {delta_total:+} tok on every request")
        return

    report(rows, args.tool)
    if args.save:
        Path(args.save).parent.mkdir(parents=True, exist_ok=True)
        Path(args.save).write_text(json.dumps(rows, indent=2))
        print(f"saved -> {args.save}")


if __name__ == "__main__":
    main()
