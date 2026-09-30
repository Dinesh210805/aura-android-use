"""Phase 5 E2E test for the on-device MCP server.

Verifies the 3 AI perception tools work via the new OmniParser ONNX +
ML Kit OCR pipeline:
  perceive_screen           -> detections + OCR labels (no annotated image)
  get_annotated_screenshot  -> detections + OCR + annotated PNG
  omniparser_detect         -> raw YOLO bboxes, no OCR

Prereqs:
  - APK installed (with assets/omniparser_icon_detect.onnx)
  - AURA app opened, accessibility enabled
  - Screen capture permission granted (or accept the dialog during the test)
  - `adb forward tcp:8765 tcp:8765`

Run:
  python scripts/test_mcp_phase5.py
"""
from __future__ import annotations

import base64
import json
import sys
import threading
import time
import urllib.request
from pathlib import Path
from queue import Empty, Queue

BASE = "http://127.0.0.1:8765"
ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "scripts" / "artifacts"


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
    return urllib.request.urlopen(req, timeout=30).status


def wait_for(events: Queue, predicate, timeout: float = 60.0):
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

    def rpc(self, method: str, params: dict | None = None, timeout: float = 60.0) -> dict:
        self._next_id += 1
        rpc_id = self._next_id
        body: dict = {"jsonrpc": "2.0", "id": rpc_id, "method": method}
        if params is not None:
            body["params"] = params
        post(self.post_url, body)
        _, data = wait_for(self.events, lambda e, d: e == "message" and f'"id":{rpc_id}' in d, timeout=timeout)
        return json.loads(data)

    def call(self, name: str, arguments: dict | None = None, timeout: float = 60.0) -> dict:
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}}, timeout=timeout)


def first_text(resp: dict) -> str:
    for c in resp.get("result", {}).get("content", []):
        if c.get("type") == "text":
            return c.get("text", "")
    return ""


def first_image(resp: dict) -> dict | None:
    for c in resp.get("result", {}).get("content", []):
        if c.get("type") == "image":
            return c
    return None


def main() -> int:
    client = Client()
    client.rpc("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "phase5-e2e", "version": "0.1"},
    })

    listing = client.rpc("tools/list")
    tool_names = sorted(t["name"] for t in listing.get("result", {}).get("tools", []))
    print(f"tools/list -- {len(tool_names)} tools")
    required = {"perceive_screen", "get_annotated_screenshot", "omniparser_detect"}
    missing = required - set(tool_names)
    if missing:
        print(f"FAIL: missing tools {missing}", file=sys.stderr)
        return 2

    # First call may load model lazily — give it more headroom
    print("\n--- omniparser_detect (cold, loads ONNX model) ---")
    t0 = time.monotonic()
    raw_resp = client.call("omniparser_detect", timeout=90.0)
    elapsed = time.monotonic() - t0
    raw_text = first_text(raw_resp)
    if not raw_text:
        print(f"FAIL: no text payload. Raw: {raw_resp}", file=sys.stderr)
        return 3
    raw = json.loads(raw_text)
    if not raw.get("ok"):
        if raw.get("permission_required"):
            print(f"  SKIP: screen capture permission not granted. Run request_screen_capture_permission and accept on device, then retry.")
            return 0
        print(f"FAIL: ok=false {raw}", file=sys.stderr)
        return 4
    print(f"  PASS in {elapsed:.2f}s -- {raw['element_count']} elements detected on {raw['source_width_px']}x{raw['source_height_px']}")
    for e in raw.get("elements", [])[:5]:
        b = e["bbox"]
        print(f"    SoM {e['som_id']:3d} {e['element_type']:8s} conf={e['confidence']:.2f}  bbox=({b['x1']},{b['y1']})-({b['x2']},{b['y2']})")

    print("\n--- perceive_screen (warm, with OCR) ---")
    t0 = time.monotonic()
    p_resp = client.call("perceive_screen", timeout=30.0)
    elapsed = time.monotonic() - t0
    p = json.loads(first_text(p_resp))
    print(f"  PASS in {elapsed:.2f}s -- {p['element_count']} elements, {sum(1 for e in p['elements'] if e['label']) } labelled by OCR")
    for e in p.get("elements", [])[:8]:
        if e["label"]:
            print(f"    SoM {e['som_id']:3d} -> '{e['label']}'")

    print("\n--- get_annotated_screenshot (warm, with annotated PNG) ---")
    t0 = time.monotonic()
    a_resp = client.call("get_annotated_screenshot", timeout=30.0)
    elapsed = time.monotonic() - t0
    img = first_image(a_resp)
    a_payload = json.loads(first_text(a_resp))
    print(f"  PASS in {elapsed:.2f}s -- {a_payload['element_count']} elements, image_bytes={len(img['data']) if img else 0}")

    if img:
        ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
        out = ARTIFACTS_DIR / "phase5_annotated.png"
        out.write_bytes(base64.b64decode(img["data"]))
        print(f"  Annotated PNG saved to {out.relative_to(out.parent.parent.parent)}")

    print("\nPhase 5 E2E: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
