"""Phase 2 E2E test for the on-device MCP server.

Verifies the 5 new device-control tools land + work via the bridge:
  - tap (no-op coordinate to avoid disturbing the screen)
  - press_home
  - press_back
  - volume_up
  - get_device_status

Prereqs:
  - APK installed on device
  - AssistantForegroundService running (open the AURA app once)
  - Accessibility service enabled
  - `adb forward tcp:8765 tcp:8765`

Run:
  python scripts/test_mcp_phase2.py
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


def post(post_url: str, payload: dict) -> int:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        post_url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    return urllib.request.urlopen(req, timeout=5).status


def wait_for(events: Queue, predicate, timeout: float = 5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            event, data = events.get(timeout=0.5)
        except Empty:
            continue
        if predicate(event, data):
            return event, data
    raise TimeoutError(f"No matching event within {timeout}s")


class Client:
    def __init__(self) -> None:
        self.events: Queue = Queue()
        self._next_id = 0
        t = threading.Thread(target=sse_reader, args=(BASE + "/", self.events), daemon=True)
        t.start()
        _, data = wait_for(self.events, lambda e, _d: e == "endpoint")
        self.post_url = BASE + "/" + data

    def rpc(self, method: str, params: dict | None = None) -> dict:
        self._next_id += 1
        rpc_id = self._next_id
        body: dict = {"jsonrpc": "2.0", "id": rpc_id, "method": method}
        if params is not None:
            body["params"] = params
        post(self.post_url, body)
        _, data = wait_for(self.events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d, timeout=8)
        return json.loads(data)

    def call_tool(self, name: str, arguments: dict | None = None) -> dict:
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}})


def first_text(resp: dict) -> str:
    content = resp.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else ""


def assert_success(label: str, resp: dict) -> None:
    is_error = resp.get("result", {}).get("isError", False)
    text = first_text(resp)
    if is_error:
        raise AssertionError(f"{label}: isError=true text={text!r}")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        raise AssertionError(f"{label}: response not JSON: {text!r}")
    if not payload.get("success", False) and "accessibility_service_running" not in payload:
        raise AssertionError(f"{label}: success=false payload={payload}")
    print(f"  PASS {label}: {payload}")


def main() -> int:
    client = Client()

    init = client.rpc("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "phase2-e2e", "version": "0.1"},
    })
    print(f"initialize OK — serverInfo={init.get('result', {}).get('serverInfo')}")

    listing = client.rpc("tools/list")
    tool_names = sorted(t["name"] for t in listing.get("result", {}).get("tools", []))
    print(f"tools/list — {len(tool_names)} tools: {tool_names}")
    required = {"echo", "tap", "press_home", "press_back", "volume_up", "volume_down", "mute", "get_device_status"}
    missing = required - set(tool_names)
    if missing:
        print(f"FAIL: missing tools {missing}", file=sys.stderr)
        return 2

    print("\nExercising bridge-backed tools:")
    assert_success("get_device_status", client.call_tool("get_device_status"))
    # Tap (1, 1) — a safe corner pixel that won't disturb visible UI
    assert_success("tap (1,1)", client.call_tool("tap", {"x": 1, "y": 1}))
    assert_success("volume_up", client.call_tool("volume_up"))
    assert_success("press_back", client.call_tool("press_back"))
    assert_success("press_home", client.call_tool("press_home"))

    print("\nPhase 2 E2E: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
