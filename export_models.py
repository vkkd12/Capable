"""
Export ONNX models for the Capable Android app.

Requirements:
    pip install ultralytics transformers torch onnx onnxruntime

Outputs (placed in app/src/main/assets/):
    1. yolov8n_fp16.onnx          — YOLOv8 Nano object detection  (~6 MB)
    2. depth_anything_v2_small_fp16.onnx — Depth Anything V2 Small (~50 MB)
    3. segformer_b0.onnx           — SegFormer B0 ADE20K semantic seg (~15 MB)

Run from the project root:
    python export_models.py
"""

import os, shutil, glob, torch, onnx

OUT = os.path.join("app", "src", "main", "assets")
os.makedirs(OUT, exist_ok=True)


def save_single_file(src_path, dst_path):
    """Load an ONNX model (including any external data files) and
    re-save as a single self-contained .onnx file."""
    model = onnx.load(src_path, load_external_data=True)
    onnx.save(model, dst_path)
    # Clean up any leftover .data files
    for f in glob.glob(src_path + ".data") + glob.glob(src_path + ".*"):
        if f != dst_path:
            try:
                os.remove(f)
            except OSError:
                pass


# --------------------------------------------------------
# 1. YOLOv8 Nano  (opset 17, FP16 weights)
# --------------------------------------------------------
print("=" * 50)
print("Exporting YOLOv8n -> yolov8n_fp16.onnx")
print("=" * 50)
from ultralytics import YOLO

model = YOLO("yolov8n.pt")
model.export(format="onnx", imgsz=640, simplify=True, opset=17)
dst = os.path.join(OUT, "yolov8n_fp16.onnx")
src = "yolov8n.onnx"
if os.path.exists(src):
    shutil.move(src, dst)
print(f"  OK {dst}  ({os.path.getsize(dst)/1e6:.1f} MB)\n")

# --------------------------------------------------------
# 2. Depth Anything V2 Small  (opset 17, FP16)
# --------------------------------------------------------
print("=" * 50)
print("Exporting Depth Anything V2 Small -> depth_anything_v2_small_fp16.onnx")
print("=" * 50)
from transformers import AutoModelForDepthEstimation

depth = AutoModelForDepthEstimation.from_pretrained(
    "depth-anything/Depth-Anything-V2-Small-hf",
    dtype=torch.float16,
)
depth.eval()
dummy = torch.randn(1, 3, 518, 518, dtype=torch.float16)
tmp = os.path.join(OUT, "_depth_tmp.onnx")
torch.onnx.export(
    depth, dummy, tmp,
    input_names=["image"],
    output_names=["depth"],
    opset_version=17,
    do_constant_folding=True,
)
dst = os.path.join(OUT, "depth_anything_v2_small_fp16.onnx")
save_single_file(tmp, dst)
if os.path.exists(tmp):
    os.remove(tmp)
print(f"  OK {dst}  ({os.path.getsize(dst)/1e6:.1f} MB)\n")

# --------------------------------------------------------
# 3. SegFormer B0  (opset 17, FP32)
# --------------------------------------------------------
print("=" * 50)
print("Exporting SegFormer B0 -> segformer_b0.onnx")
print("=" * 50)
from transformers import SegformerForSemanticSegmentation

seg = SegformerForSemanticSegmentation.from_pretrained(
    "nvidia/segformer-b0-finetuned-ade-512-512"
)
seg.eval()
dummy = torch.randn(1, 3, 512, 512)
tmp = os.path.join(OUT, "_seg_tmp.onnx")
torch.onnx.export(
    seg, dummy, tmp,
    input_names=["pixel_values"],
    output_names=["logits"],
    opset_version=17,
    do_constant_folding=True,
    dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}},
)
dst = os.path.join(OUT, "segformer_b0.onnx")
save_single_file(tmp, dst)
if os.path.exists(tmp):
    os.remove(tmp)
print(f"  OK {dst}  ({os.path.getsize(dst)/1e6:.1f} MB)\n")

# --------------------------------------------------------
print("All models exported successfully!")
print("Model sizes:")
for f in sorted(os.listdir(OUT)):
    if f.endswith(".onnx"):
        sz = os.path.getsize(os.path.join(OUT, f)) / (1024 * 1024)
        print(f"  {f}: {sz:.1f} MB")
