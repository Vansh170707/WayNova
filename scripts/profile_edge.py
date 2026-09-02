"""Edge profiling: latency budget and INT8 accuracy cost for the deployed loop.

The blueprint asks for float32 first, then INT8, with trajectory accuracy, latency and
memory measured together -- quantising a model that then drifts further is not a win.

IMPORTANT CAVEAT, stated up front because the blueprint is explicit about it: this runs on
a desktop CPU. The Edge acceptance gate requires the target rate on the actual Android
device with the whole sensor/fusion loop live, and desktop speed does not count. What this
script establishes is the *shape* of the budget -- which stage dominates, and whether INT8
is worth its accuracy cost -- so the on-device run has something to check against.
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
import torch

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import add_simulated_gnss
from neuronav.data.reference import load_paired_segments
from neuronav.fusion.es_ekf import PlanarESEKF
from neuronav.models.dataset import compute_features, make_windows
from neuronav.models.tcn import SpeedTCN, count_parameters

REPO_ROOT = Path(__file__).resolve().parents[1]
TARGET_HZ = 10.0
BUDGET_MS = 1000.0 / TARGET_HZ
QUANT_BATCH = 64


def timeit(fn, repeats: int = 200, warmup: int = 20) -> dict:
    for _ in range(warmup):
        fn()
    samples = []
    for _ in range(repeats):
        t0 = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - t0) * 1000.0)
    a = np.array(samples)
    return {"median_ms": float(np.median(a)), "p95_ms": float(np.percentile(a, 95)),
            "mean_ms": float(a.mean())}


def quantize_int8(model, example, calibration: torch.Tensor):
    """PT2E dynamic-range INT8 via the XNNPACK quantizer -- the ExecuTorch mobile path."""
    try:
        # PT2E moved out of torch.ao into torchao in recent PyTorch releases; accept both
        # so this keeps working across the versions a team might have installed.
        try:
            from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
        except ImportError:
            from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e
        from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (
            XNNPACKQuantizer, get_symmetric_quantization_config)
    except ImportError as exc:
        return None, f"unavailable ({exc.__class__.__name__}: {exc})"

    try:
        # Export at the calibration batch size. A batch-1 program carries a guard that
        # rejects batched calibration, and marking the batch dynamic does not help here
        # because GroupNorm specialises on it. Calibration is what sets the activation
        # ranges INT8 accuracy depends on, so it is worth exporting at batch N for this
        # measurement and re-exporting at batch 1 for deployment.
        batch_size = min(QUANT_BATCH, len(calibration))
        exported = torch.export.export(model, (calibration[:batch_size],)).module()
        quantizer = XNNPACKQuantizer().set_global(get_symmetric_quantization_config())
        prepared = prepare_pt2e(exported, quantizer)
        with torch.no_grad():
            for i in range(0, len(calibration) - batch_size + 1, batch_size):
                prepared(calibration[i:i + batch_size])
        return convert_pt2e(prepared), None
    except Exception as exc:
        return None, f"failed ({type(exc).__name__}: {exc})"


def evaluate_speed(model, X: np.ndarray, y: np.ndarray, batch: int = 512) -> dict:
    preds = []
    with torch.no_grad():
        for i in range(0, len(X), batch):
            out = model(torch.from_numpy(X[i:i + batch]))
            preds.append((out[0] if isinstance(out, tuple) else out).numpy())
    pred = np.concatenate(preds)
    return {"rmse": float(np.sqrt(np.mean((pred - y) ** 2))),
            "corr": float(np.corrcoef(pred, y)[0, 1])}


def main(checkpoint: str, driver: str):
    ckpt = torch.load(checkpoint, map_location="cpu", weights_only=False)
    model = SpeedTCN(n_features=len(ckpt["features"]), channels=int(ckpt["channels"]))
    model.load_state_dict(ckpt["model"])
    model.eval()

    from neuronav.models.dataset import FeatureScaler
    scaler = FeatureScaler().load_state_dict(ckpt["scaler"])

    # --- held-out data for the accuracy half of the trade-off ---------------------
    inventory = pd.read_csv(REPO_ROOT / "outputs/results/paired_inventory.csv")
    rows = inventory[inventory["trainable"] & (inventory["driver"] == driver)]
    windows = []
    segment = None
    for _, row in rows.iterrows():
        session = row["route_id"].rsplit("_seg", 1)[0]
        for seg in load_paired_segments(
                str(REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/S-{session}.csv"),
                str(REPO_ROOT / f"data/raw/IO-VNBD/paired/{driver}/V-{session}.csv"), session):
            if seg["route_id"].iloc[0] != row["route_id"]:
                continue
            seg, _ = add_simulated_gnss(seg)
            align = estimate_alignment(seg)
            segment = (seg, align)
            windows.append(make_windows(seg, align, driver, window_s=float(ckpt["window_s"])))
    from neuronav.models.dataset import concat
    data = concat(windows)
    X = scaler.transform(data.X)
    y = data.y
    print(f"held-out driver {driver}: {len(y)} windows\n")

    example = torch.from_numpy(X[:1])
    calibration = torch.from_numpy(X[:512])
    report = {"target_hz": TARGET_HZ, "budget_ms": BUDGET_MS,
              "params": count_parameters(model)}

    # --- stage 1: model latency ---------------------------------------------------
    print("=== model latency, single window (batch=1) ===")
    fp32 = evaluate_speed(model, X, y)
    with torch.no_grad():
        stats = timeit(lambda: model(example))
    print(f"  {'eager fp32':22s} {stats['median_ms']:7.3f} ms  p95 {stats['p95_ms']:6.3f}  "
          f"RMSE {fp32['rmse']:.3f} m/s")
    report["eager_fp32"] = {**stats, **fp32}

    traced = torch.jit.load(REPO_ROOT / "outputs/export/speed_tcn_torchscript.pt")
    with torch.no_grad():
        stats = timeit(lambda: traced(example))
    print(f"  {'torchscript fp32':22s} {stats['median_ms']:7.3f} ms  p95 {stats['p95_ms']:6.3f}")
    report["torchscript_fp32"] = stats

    onnx_path = REPO_ROOT / "outputs/export/speed_tcn.onnx"
    if onnx_path.exists():
        import onnxruntime as ort
        session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        numpy_example = X[:1]
        stats = timeit(lambda: session.run(None, {"imu_window": numpy_example}))
        print(f"  {'onnxruntime fp32':22s} {stats['median_ms']:7.3f} ms  "
              f"p95 {stats['p95_ms']:6.3f}")
        report["onnxruntime_fp32"] = stats

    # --- stage 2: INT8 ------------------------------------------------------------
    print("\n=== INT8 quantisation (accuracy and latency together) ===")
    quantized, error = quantize_int8(model, example, calibration)
    if quantized is None:
        print(f"  int8 {error}")
        report["int8"] = {"available": False, "reason": error}
    else:
        # the quantised program is specialised to QUANT_BATCH, so score it in that shape
        usable = (len(X) // QUANT_BATCH) * QUANT_BATCH
        int8_metrics = evaluate_speed(quantized, X[:usable], y[:usable], batch=QUANT_BATCH)
        batched = torch.from_numpy(X[:QUANT_BATCH])
        with torch.no_grad():
            stats = timeit(lambda: quantized(batched), repeats=50)
        per_sample = stats["median_ms"] / QUANT_BATCH
        delta = int8_metrics["rmse"] - fp32["rmse"]
        print(f"  {'int8 (pt2e/xnnpack)':22s} {per_sample:7.3f} ms/window (batch "
              f"{QUANT_BATCH})  RMSE {int8_metrics['rmse']:.3f} m/s  ({delta:+.3f} vs fp32)")
        report["int8"] = {"available": True, "median_ms_per_window": per_sample,
                          "batch": QUANT_BATCH, **int8_metrics,
                          "rmse_delta_vs_fp32": delta}

    # --- stage 3: the rest of the per-update loop ---------------------------------
    print("\n=== the rest of the loop, per update ===")
    seg, align = segment
    window_samples = int(round(float(ckpt["window_s"]) * TARGET_HZ))
    recent = seg.iloc[:window_samples * 2].reset_index(drop=True)

    stats_feat = timeit(lambda: compute_features(recent, align), repeats=100)
    print(f"  {'feature computation':22s} {stats_feat['median_ms']:7.3f} ms  "
          f"(over a {len(recent)}-sample buffer)")
    report["features"] = stats_feat

    ekf = PlanarESEKF()
    ekf.initialize(0.0, 0.0, 0.0, 12.0)

    def ekf_update():
        ekf.propagate(0.1, 0.01, 0.2)
        ekf.update_gnss_position(1.0, 1.0, 5.0)
        ekf.update_gnss_speed(12.0)

    stats_ekf = timeit(ekf_update, repeats=500)
    print(f"  {'ES-EKF step':22s} {stats_ekf['median_ms']:7.3f} ms")
    report["ekf"] = stats_ekf

    # --- budget -------------------------------------------------------------------
    # budget uses the fp32 path actually recommended for deployment
    model_ms = report["torchscript_fp32"]["median_ms"]
    total = model_ms + stats_feat["median_ms"] + stats_ekf["median_ms"]
    print(f"\n=== budget at {TARGET_HZ:.0f} Hz ({BUDGET_MS:.0f} ms per update) ===")
    print(f"  model + features + filter = {total:.2f} ms  "
          f"({100*total/BUDGET_MS:.1f}% of budget)")
    print(f"  headroom for sensors, map, UI and logging = {BUDGET_MS - total:.2f} ms")
    report["total_ms"] = total
    report["budget_used_pct"] = 100 * total / BUDGET_MS

    print("\nNOTE: desktop CPU. The blueprint's Edge acceptance gate requires this on the")
    print("      real device with the full sensor loop live; Gate 5 stays OPEN until then.")

    (REPO_ROOT / "outputs/results/edge_profile.json").write_text(json.dumps(report, indent=2))
    print("\nreport -> outputs/results/edge_profile.json")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint",
                    default=str(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt"))
    ap.add_argument("--driver", default="B")
    args = ap.parse_args()
    main(args.checkpoint, args.driver)
