"""Phase 3 E2E test for the on-device MCP server.

Verifies the 14 new tools land + work via the bridge:
  Gestures   : swipe, scroll_to, double_tap, long_press,
               scroll_up, scroll_down, scroll_left, scroll_right
  Keys       : press_enter, open_recent_apps
  Apps       : launch_app, lookup_app, type_text
  Policy     : validate_action, connect_device

Prereqs:
  - APK installed on device
  - AssistantForegroundService running (open the AURA app once)
  - Accessibility service enabled
  - `adb forward tcp:8765 tcp:8765`

Run:
  python scripts/test_mcp_phase3.py
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
    req = urllib.request.Request(post_url, data=body, method="POST",
                                  headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=5).status


def wait_for(events: Queue, predicate, timeout: float = 8.0):
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
        _, data = wait_for(self.events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d)
        return json.loads(data)

    def call(self, name: str, arguments: dict | None = None) -> dict:
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}})


def first_text(resp: dict) -> str:
    content = resp.get("result", {}).get("content", [])
    return content[0].get("text", "") if content else ""


def expect_ok(label: str, resp: dict, allow_isError: bool = False) -> dict:
    """Expect a successful tool call. Returns parsed JSON payload."""
    is_error = resp.get("result", {}).get("isError", False)
    text = first_text(resp)
    if is_error and not allow_isError:
        raise AssertionError(f"{label}: isError=true text={text!r}")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        raise AssertionError(f"{label}: response not JSON: {text!r}")
    print(f"  PASS {label}: {payload}")
    return payload


def main() -> int:
    client = Client()

    client.rpc("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "phase3-e2e", "version": "0.1"},
    })

    listing = client.rpc("tools/list")
    tool_names = sorted(t["name"] for t in listing.get("result", {}).get("tools", []))
    print(f"tools/list — {len(tool_names)} tools:")
    for name in tool_names:
        print(f"  - {name}")

    required = {
        # Phase 1
        "echo",
        # Phase 2
        "tap", "press_home", "press_back", "volume_up", "volume_down", "mute", "get_device_status",
        # Phase 3 — gestures
        "swipe", "scroll_to", "double_tap", "long_press",
        "scroll_up", "scroll_down", "scroll_left", "scroll_right",
        # Phase 3 — keys
        "open_recent_apps", "press_enter",
        # Phase 3 — apps
        "launch_app", "lookup_app", "type_text",
        # Phase 3 — policy
        "validate_action", "connect_device",
    }
    missing = required - set(tool_names)
    if missing:
        print(f"FAIL: missing tools {missing}", file=sys.stderr)
        return 2

    # Make sure accessibility is on first
    status = expect_ok("get_device_status", client.call("get_device_status"))
    if not status.get("accessibility_service_running"):
        print("FAIL: accessibility service not running — enable AURA in Settings", file=sys.stderr)
        return 3

    print("\n---Policy stubs---")
    expect_ok("validate_action", client.call("validate_action", {"gesture_type": "tap"}))
    expect_ok("connect_device", client.call("connect_device"))

    print("\n---Apps---")
    lookup = expect_ok("lookup_app(Brave)", client.call("lookup_app", {"app_name": "Brave"}))
    brave_pkg = lookup.get("package_name", "")
    if brave_pkg:
        expect_ok(f"launch_app({brave_pkg})", client.call("launch_app", {"package_name": brave_pkg}))
        time.sleep(1.5)  # let Brave open
    expect_ok("press_home", client.call("press_home"))
    time.sleep(0.5)

    print("\n---Gestures (safe — corner pixels)---")
    expect_ok("swipe (1,1)->(2,2)", client.call("swipe", {"x1": 1, "y1": 1, "x2": 2, "y2": 2, "duration_ms": 200}))
    expect_ok("scroll_to (1,1)->(2,2)", client.call("scroll_to", {"x1": 1, "y1": 1, "x2": 2, "y2": 2, "duration_ms": 200}))
    expect_ok("double_tap (1,1)", client.call("double_tap", {"x": 1, "y": 1}))
    expect_ok("long_press (1,1, 300ms)", client.call("long_press", {"x": 1, "y": 1, "duration_ms": 300}))

    print("\n---Scroll directions---")
    for d in ("scroll_up", "scroll_down", "scroll_left", "scroll_right"):
        expect_ok(d, client.call(d))
        time.sleep(0.3)

    print("\n---Keys---")
    expect_ok("open_recent_apps", client.call("open_recent_apps"))
    time.sleep(0.5)
    expect_ok("press_back", client.call("press_back"))
    time.sleep(0.3)
    # press_enter on the home screen returns false (no editor focused), which is correct.
    expect_ok("press_enter (no focused editor)", client.call("press_enter"), allow_isError=True)

    print("\n---type_text (no focused editor)---")
    # Same situation: on the home screen there's no focused field, so type_text
    # should return false. We allow isError to surface that cleanly.
    expect_ok("type_text", client.call("type_text", {"text": "hello"}), allow_isError=True)

    print("\nPhase 3 E2E: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
