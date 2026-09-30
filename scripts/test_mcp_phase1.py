"""Phase 1 E2E test for the on-device MCP server.

Verifies the full JSON-RPC over SSE round-trip:
  1. Open SSE connection → receive endpoint event with sessionId
  2. POST `initialize` → read response from SSE stream
  3. POST `tools/list` → confirm `echo` tool is advertised
  4. POST `tools/call` for echo → confirm round-trip response

Prereqs:
  - APK installed on device
  - AssistantForegroundService running (open the AURA app once)
  - `adb forward tcp:8765 tcp:8765`

Run:
  python scripts/test_mcp_phase1.py
"""
from __future__ import annotations

import json
import sys
import threading
import time
import urllib.request
from queue import Empty, Queue

BASE = "http://127.0.0.1:8765"


def sse_reader(url: str, events: Queue) -> None:
    """Read the SSE stream, push every (event, data) tuple onto the queue."""
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    resp = urllib.request.urlopen(req, timeout=10)
    event_name = None
    data_buf: list[str] = []
    for raw in resp:
        line = raw.decode("utf-8").rstrip("\r\n")
        if line == "":
            if data_buf:
                events.put((event_name or "message", "\n".join(data_buf)))
            event_name = None
            data_buf = []
            continue
        if line.startswith("event:"):
            event_name = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data_buf.append(line[len("data:"):].lstrip())


def post_jsonrpc(post_url: str, payload: dict) -> int:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        post_url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    resp = urllib.request.urlopen(req, timeout=5)
    return resp.status


def wait_event(events: Queue, predicate, timeout: float = 5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            event, data = events.get(timeout=0.5)
        except Empty:
            continue
        if predicate(event, data):
            return event, data
    raise TimeoutError(f"No matching event within {timeout}s")


def main() -> int:
    events: Queue = Queue()
    t = threading.Thread(target=sse_reader, args=(BASE + "/", events), daemon=True)
    t.start()

    # 1. Wait for endpoint event with sessionId
    event, data = wait_event(events, lambda e, d: e == "endpoint")
    print(f"[1/4] SSE handshake OK — endpoint={data}")
    post_url = BASE + data if data.startswith("/") else BASE + "/" + data.lstrip("?")
    # The data is "?sessionId=...", so resolve against base
    post_url = BASE + "/" + data if not data.startswith("?") else BASE + "/" + data

    # 2. initialize
    rpc_id = 1
    post_jsonrpc(post_url, {
        "jsonrpc": "2.0",
        "id": rpc_id,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "phase1-e2e", "version": "0.1"},
        },
    })
    event, data = wait_event(events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d)
    init_resp = json.loads(data)
    server_name = init_resp.get("result", {}).get("serverInfo", {}).get("name", "?")
    print(f"[2/4] initialize OK — serverInfo.name={server_name}")

    # 3. tools/list
    rpc_id = 2
    post_jsonrpc(post_url, {
        "jsonrpc": "2.0",
        "id": rpc_id,
        "method": "tools/list",
    })
    event, data = wait_event(events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d)
    list_resp = json.loads(data)
    tool_names = [t["name"] for t in list_resp.get("result", {}).get("tools", [])]
    print(f"[3/4] tools/list OK — tools={tool_names}")
    if "echo" not in tool_names:
        print(f"FAIL: echo tool not advertised. Got: {tool_names}", file=sys.stderr)
        return 2

    # 4. tools/call echo
    rpc_id = 3
    post_jsonrpc(post_url, {
        "jsonrpc": "2.0",
        "id": rpc_id,
        "method": "tools/call",
        "params": {"name": "echo", "arguments": {"text": "phase1-works"}},
    })
    event, data = wait_event(events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d)
    call_resp = json.loads(data)
    content = call_resp.get("result", {}).get("content", [])
    text = content[0].get("text", "") if content else ""
    print(f"[4/4] tools/call echo OK — response={text!r}")
    if text != "echo: phase1-works":
        print(f"FAIL: unexpected echo content {text!r}", file=sys.stderr)
        return 3

    print("\nPhase 1 E2E: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
