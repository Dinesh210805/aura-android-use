"""Convert Microsoft OmniParser icon_detect YOLOv8 weights to quantized ONNX.

One-time setup for Phase 5 of the on-device MCP server. We use ONNX
(not TFLite) because:
  1. ultralytics→ONNX export is a single clean step (no TF/tf_keras chain)
  2. ONNX Runtime Mobile (~10MB) is smaller than TFLite (~30MB) on Android
  3. NNAPI/CPU delegates work in onnxruntime-android out of the box

Inputs:
  aura_live_mcp/models/omniparser/icon_detect/best.pt   (~39 MB PT weights)

Outputs:
  aura-android/app/src/main/assets/omniparser_icon_detect.onnx        (~20-25 MB INT8)
  aura-android/app/src/main/assets/omniparser_classes.json            (~1 KB)

Run:
  pip install onnxruntime onnxslim    # if not already
  python scripts/convert_omniparser_onnx.py

Notes:
  - imgsz=640 matches the runtime size the Python pipeline already uses.
  - Dynamic INT8 quantization is "weight-only" — minimal accuracy loss,
    no calibration dataset needed.
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PT_PATH = REPO_ROOT / "aura_live_mcp" / "models" / "omniparser" / "icon_detect" / "best.pt"
ASSETS_DIR = REPO_ROOT / "UI" / "app" / "src" / "main" / "assets"

ONNX_OUT = ASSETS_DIR / "omniparser_icon_detect.onnx"
CLASS_NAMES_OUT = ASSETS_DIR / "omniparser_classes.json"
IMGSZ = 640


def main() -> int:
    if not PT_PATH.exists():
        print(f"ERROR: source weights not found at {PT_PATH}", file=sys.stderr)
        return 1

    try:
        from ultralytics import YOLO
    except ImportError:
        print("ERROR: ultralytics not installed. pip install ultralytics", file=sys.stderr)
        return 2

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    print(f"[1/4] Loading {PT_PATH.name} ({PT_PATH.stat().st_size / 1_048_576:.1f} MB)")
    model = YOLO(str(PT_PATH))

    class_names = {int(k): str(v) for k, v in model.names.items()}
    print(f"[2/4] Class names: {class_names}")
    CLASS_NAMES_OUT.write_text(json.dumps(class_names, indent=2))
    print(f"       Wrote {CLASS_NAMES_OUT.relative_to(REPO_ROOT)}")

    print(f"[3/4] Exporting to ONNX (imgsz={IMGSZ}, opset=12, simplify=True)")
    exported = model.export(format="onnx", imgsz=IMGSZ, opset=12, simplify=True)
    exported_path = Path(exported)
    print(f"       Exported {exported_path.name} ({exported_path.stat().st_size / 1_048_576:.1f} MB)")

    print(f"[4/4] Dynamic INT8 quantization (weight-only, no calibration)")
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        print("ERROR: onnxruntime not installed. pip install onnxruntime", file=sys.stderr)
        return 3

    quantize_dynamic(
        model_input=str(exported_path),
        model_output=str(ONNX_OUT),
        weight_type=QuantType.QUInt8,
    )
    print(f"       Quantized to {ONNX_OUT.name} ({ONNX_OUT.stat().st_size / 1_048_576:.1f} MB)")

    print()
    print("Done.")
    print(f"  Model:  {ONNX_OUT} ({ONNX_OUT.stat().st_size / 1_048_576:.1f} MB)")
    print(f"  Labels: {CLASS_NAMES_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
