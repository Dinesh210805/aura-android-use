"""Convert Microsoft OmniParser icon_detect YOLOv8 weights to TFLite INT8.

One-time setup for Phase 5 of the on-device MCP server. Reads the existing
.pt file the Python perception pipeline already uses, exports a quantized
TFLite model + a class-names manifest, and copies both into the Android app
assets folder.

After this script runs, the Kotlin TFLiteYoloRunner loads from assets and
no further conversion is needed.

Inputs:
  aura_live_mcp/models/omniparser/icon_detect/best.pt   (~39 MB)

Outputs:
  aura-android/app/src/main/assets/omniparser_icon_detect.tflite  (~10-12 MB INT8)
  aura-android/app/src/main/assets/omniparser_classes.json        (~1 KB)

Run:
  python scripts/convert_omniparser_tflite.py

Notes:
  - Requires `ultralytics` and a TF/TFLite-compatible Python env.
    Same env the Python MCP server uses already has ultralytics; if export
    complains about missing TF, run: pip install tensorflow==2.16.1
  - INT8 quantization needs a small calibration dataset. Ultralytics will
    auto-generate synthetic calibration data — that's fine for icon detection.
  - imgsz=640 matches the runtime size the Python pipeline already uses.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PT_PATH = REPO_ROOT / "aura_live_mcp" / "models" / "omniparser" / "icon_detect" / "best.pt"
ASSETS_DIR = REPO_ROOT / "UI" / "app" / "src" / "main" / "assets"

TFLITE_OUT = ASSETS_DIR / "omniparser_icon_detect.tflite"
CLASS_NAMES_OUT = ASSETS_DIR / "omniparser_classes.json"

IMGSZ = 640  # matches Python runtime image_size


def main() -> int:
    if not PT_PATH.exists():
        print(f"ERROR: source weights not found at {PT_PATH}", file=sys.stderr)
        print("       Download from HuggingFace 'microsoft/OmniParser-v2.0' first.", file=sys.stderr)
        return 1

    try:
        from ultralytics import YOLO
    except ImportError:
        print("ERROR: ultralytics not installed. pip install ultralytics", file=sys.stderr)
        return 2

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    print(f"[1/4] Loading {PT_PATH.name} ({PT_PATH.stat().st_size / 1_048_576:.1f} MB)")
    model = YOLO(str(PT_PATH))

    # Persist class names for the Android runner.
    class_names = {int(k): str(v) for k, v in model.names.items()}
    print(f"[2/4] Class names: {class_names}")
    CLASS_NAMES_OUT.write_text(json.dumps(class_names, indent=2))
    print(f"       Wrote {CLASS_NAMES_OUT.relative_to(REPO_ROOT)}")

    print(f"[3/4] Exporting to TFLite INT8 (imgsz={IMGSZ}) — may take 1-3 minutes")
    # ultralytics returns the exported file path
    exported = model.export(
        format="tflite",
        int8=True,
        imgsz=IMGSZ,
        verbose=False,
    )
    exported_path = Path(exported)
    print(f"       Exported {exported_path.name} ({exported_path.stat().st_size / 1_048_576:.1f} MB)")

    print(f"[4/4] Copying to {TFLITE_OUT.relative_to(REPO_ROOT)}")
    shutil.copyfile(exported_path, TFLITE_OUT)

    print()
    print("Done.")
    print(f"  Model:  {TFLITE_OUT} ({TFLITE_OUT.stat().st_size / 1_048_576:.1f} MB)")
    print(f"  Labels: {CLASS_NAMES_OUT}")
    print()
    print("Next: rebuild the APK. The Kotlin AppPerceptionBridge will load these from assets at first use.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
