"""
perceive_screen benchmark + two-color visualizer.

Connects to the live on-device AURA MCP server over TLS, drives a few
representative screens, and for each one:
  - fetches the RAW screenshot (get_screenshot)
  - fetches the element list (perceive_screen) and times it
  - renders a two-color SoM overlay locally: BLUE = ui_tree, RED = omniparser
    (mirrors the new perceive_screen rendering so the report is accurate even
     before the app is redeployed)

Output: docs/perceive-bench/{raw,annotated}_<screen>.png + timings.json
Run:  venv/Scripts/python.exe scripts/perceive_bench.py
"""
import asyncio, base64, json, os, time, statistics
from pathlib import Path
import httpx
from PIL import Image, ImageDraw, ImageFont
from mcp.client.sse import sse_client
from mcp import ClientSession

# Connection details come from the environment, never from this file:
#   AURA_MCP_URL    the phone's MCP server, e.g. https://192.168.1.23:8765
#   AURA_MCP_TOKEN  its bearer token
#   AURA_MCP_CERT   path to its self-signed certificate (PEM)
#   AURA_BENCH_OUT  output folder (default: docs/perceive-bench next to this repo)
BASE = os.environ.get("AURA_MCP_URL", "")
TOKEN = os.environ.get("AURA_MCP_TOKEN", "")
CERT = os.environ.get("AURA_MCP_CERT", "")
if not (BASE and TOKEN and CERT):
    raise SystemExit("Set AURA_MCP_URL, AURA_MCP_TOKEN and AURA_MCP_CERT first.")
OUT = Path(os.environ.get("AURA_BENCH_OUT", Path(__file__).resolve().parents[1] / "docs" / "perceive-bench"))
OUT.mkdir(parents=True, exist_ok=True)

BLUE = (33, 150, 243)   # ui_tree
RED = (244, 67, 54)     # omniparser


def factory(headers=None, timeout=None, auth=None):
    h = dict(headers or {})
    h["Authorization"] = f"Bearer {TOKEN}"
    return httpx.AsyncClient(headers=h, timeout=timeout, auth=auth, verify=CERT, follow_redirects=True)


def _texts(res):
    return [c.text for c in res.content if getattr(c, "type", None) == "text" or c.__class__.__name__ == "TextContent"]


def _images(res):
    return [c.data for c in res.content if getattr(c, "type", None) == "image" or c.__class__.__name__ == "ImageContent"]


def _json_payload(res):
    for t in reversed(_texts(res)):
        try:
            obj = json.loads(t)
            if isinstance(obj, dict) and "elements" in obj:
                return obj
        except Exception:
            pass
    return None


def render(raw_png_b64, elements, path):
    im = Image.open(__import__("io").BytesIO(base64.b64decode(raw_png_b64))).convert("RGB")
    d = ImageDraw.Draw(im)
    w = max(3, im.width // 300)
    fsize = max(22, im.width // 55)
    try:
        font = ImageFont.truetype("arial.ttf", fsize)
    except Exception:
        font = ImageFont.load_default()
    counts = {"ui_tree": 0, "omniparser": 0}
    for el in elements:
        b = el["bbox"]
        src = el.get("source", "ui_tree")
        counts[src] = counts.get(src, 0) + 1
        col = BLUE if src == "ui_tree" else RED
        d.rectangle([b["x1"], b["y1"], b["x2"], b["y2"]], outline=col, width=w)
        lab = str(el["som_id"])
        tb = d.textbbox((0, 0), lab, font=font)
        tw, th = tb[2] - tb[0], tb[3] - tb[1]
        ly = max(0, b["y1"] - th - 8)
        d.rectangle([b["x1"], ly, b["x1"] + tw + 10, ly + th + 8], fill=col)
        d.text((b["x1"] + 5, ly + 2), lab, fill=(255, 255, 255), font=font)
    im.save(path)
    return counts


async def call(session, name, args, timings, key=None):
    t0 = time.perf_counter()
    res = await session.call_tool(name, args)
    dt = (time.perf_counter() - t0) * 1000
    timings.setdefault(key or name, []).append(round(dt, 1))
    return res


async def screen(session, label, timings):
    # one shared raw frame + one perceive, repeated for a small latency sample
    raw = await call(session, "get_screenshot", {}, timings, key="get_screenshot")
    raw_b64 = _images(raw)[0]
    (OUT / f"raw_{label}.png").write_bytes(base64.b64decode(raw_b64))
    per = await call(session, "perceive_screen", {"description": f"all interactive elements on {label}"}, timings, key="perceive_screen")
    payload = _json_payload(per)
    elements = payload["elements"] if payload else []
    timing = payload.get("timing_ms", {}) if payload else {}
    status = payload.get("omniparser_status") if payload else None
    counts = render(raw_b64, elements, OUT / f"annotated_{label}.png")
    # extra latency samples for the cheap calls, for comparison
    await call(session, "get_ui_tree", {}, timings, key="get_ui_tree")
    print(f"[{label}] elements={len(elements)} ui_tree={counts.get('ui_tree',0)} "
          f"omniparser={counts.get('omniparser',0)} status={status} on_device_timing_ms={timing}")
    return {"label": label, "elements": len(elements), **counts,
            "omniparser_status": status, "on_device_timing_ms": timing,
            "size": [payload.get("source_width_px"), payload.get("source_height_px")] if payload else None}


async def main():
    timings = {}
    summary = []
    for url in (f"{BASE}/", f"{BASE}/sse"):
        try:
            async with sse_client(url, headers={"Authorization": f"Bearer {TOKEN}"}, httpx_client_factory=factory) as (r, w):
                async with ClientSession(r, w) as s:
                    await s.initialize()
                    print(f"connected via {url}")
                    # warm up + repeat perceive a few times on the current screen for a stable median
                    summary.append(await screen(s, "home_a", timings))
                    await call(s, "press_home", {}, timings)
                    await call(s, "wait_for", {"condition": "home", "timeout_ms": 2000}, timings)
                    summary.append(await screen(s, "home_b", timings))
                    await call(s, "launch_app", {"package_name": "com.brave.browser"}, timings)
                    await call(s, "wait_for", {"condition": "brave loaded", "timeout_ms": 6000}, timings)
                    summary.append(await screen(s, "browser", timings))
                    # a couple extra perceive samples for the median
                    for _ in range(2):
                        await call(s, "perceive_screen", {"description": "latency sample"}, timings, key="perceive_screen")
                    await call(s, "end_session", {"reason": "perceive_screen benchmark"}, timings)
            break
        except Exception as e:
            print(f"connect via {url} failed: {type(e).__name__}: {str(e)[:160]}")
            continue
    stats = {k: {"n": len(v), "median_ms": round(statistics.median(v), 1),
                 "min_ms": min(v), "max_ms": max(v)} for k, v in timings.items()}
    (OUT / "timings.json").write_text(json.dumps({"per_tool_ms": stats, "raw": timings, "screens": summary}, indent=2))
    print("\n=== latency (wall-clock incl. Tailscale RTT) ===")
    for k, v in stats.items():
        print(f"  {k:24s} n={v['n']:<3} median={v['median_ms']:>7} ms  (min {v['min_ms']}, max {v['max_ms']})")
    print(f"\nimages + timings written to {OUT}")


if __name__ == "__main__":
    asyncio.run(main())
