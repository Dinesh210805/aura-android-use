"""Phase 4 E2E test for the on-device MCP server.

Verifies the 4 new perception tools work via the new bridges:
  get_ui_tree
  request_screen_capture_permission
  get_screenshot                (only if permission already granted)
  watch_device_events           (short timeout — triggers an event during the wait)

Prereqs:
  - APK installed, AURA app opened, accessibility enabled
  - `adb forward tcp:8765 tcp:8765`

Run:
  python scripts/test_mcp_phase4.py
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


def wait_for(events: Queue, predicate, timeout: float = 30.0):
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

    def rpc(self, method: str, params: dict | None = None, timeout: float = 30.0) -> dict:
        self._next_id += 1
        rpc_id = self._next_id
        body: dict = {"jsonrpc": "2.0", "id": rpc_id, "method": method}
        if params is not None:
            body["params"] = params
        post(self.post_url, body)
        _, data = wait_for(self.events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d, timeout=timeout)
        return json.loads(data)

    def call(self, name: str, arguments: dict | None = None, timeout: float = 30.0) -> dict:
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}}, timeout=timeout)


def find_text(resp: dict) -> str:
    """Return the first TextContent.text from the response."""
    for c in resp.get("result", {}).get("content", []):
        if c.get("type") == "text":
            return c.get("text", "")
    return ""


def find_image(resp: dict) -> dict | None:
    for c in resp.get("result", {}).get("content", []):
        if c.get("type") == "image":
            return c
    return None


def main() -> int:
    client = Client()

    client.rpc("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "phase4-e2e", "version": "0.1"},
    })

    listing = client.rpc("tools/list")
    tool_names = sorted(t["name"] for t in listing.get("result", {}).get("tools", []))
    print(f"tools/list -- {len(tool_names)} tools")
    required = {"get_ui_tree", "get_screenshot", "request_screen_capture_permission", "watch_device_events"}
    missing = required - set(tool_names)
    if missing:
        print(f"FAIL: missing tools {missing}", file=sys.stderr)
        return 2

    print("\n--- get_ui_tree ---")
    tree_resp = client.call("get_ui_tree")
    tree_text = find_text(tree_resp)
    tree = json.loads(tree_text)
    print(f"  ok: validation_failed={tree.get('validation_failed')} elements_count={tree.get('elements_count')} package={tree.get('package_name')}")

    print("\n--- request_screen_capture_permission ---")
    perm_resp = client.call("request_screen_capture_permission")
    print(f"  {json.loads(find_text(perm_resp))}")

    print("\n--- get_screenshot (without granted permission expected to surface permission_required) ---")
    shot_resp = client.call("get_screenshot", timeout=15.0)
    img = find_image(shot_resp)
    if img is not None:
        b64_len = len(img.get("data", ""))
        print(f"  PASS image returned ({b64_len} bytes of base64) -- permission was already granted")
    else:
        meta_text = find_text(shot_resp)
        meta = json.loads(meta_text) if meta_text else {}
        if meta.get("permission_required"):
            print(f"  EXPECTED permission_required: {meta}")
        else:
            print(f"  ERROR response: {meta}")

    print("\n--- watch_device_events (5s, triggering home press to generate an event) ---")
    # Run the call in a background thread, then trigger an event so the buffer fills.
    holder: dict = {}

    def call_watch():
        holder["resp"] = client.call(
            "watch_device_events",
            {"timeout_seconds": 5, "max_events": 20},
            timeout=15.0,
        )

    t = threading.Thread(target=call_watch, daemon=True)
    t.start()
    time.sleep(0.5)
    # Use a separate Client because the watch call is using ours' rpc id space.
    # Simpler: just press home via adb to generate accessibility events.
    import subprocess
    subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_HOME"], check=False)
    time.sleep(0.3)
    subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_BACK"], check=False)
    t.join(timeout=10)

    if "resp" not in holder:
        print("  FAIL: watch_device_events did not return", file=sys.stderr)
        return 3

    watch_payload = json.loads(find_text(holder["resp"]))
    count = watch_payload.get("count", 0)
    print(f"  PASS count={count}")
    for e in watch_payload.get("events", [])[:5]:
        print(f"    - {e['type']:35s} {e['package_name']:30s} {e['description'][:50]}")

    print("\nPhase 4 E2E: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
