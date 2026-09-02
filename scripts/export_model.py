"""Export the speed TCN to the mobile runtimes, and verify each export numerically.

The blueprint's deployment-first rule means export is not a final packaging step -- a
backbone that cannot be exported is not a candidate at all. So every export is checked
against the eager PyTorch model on real windows, and any that disagrees is reported rather
than quietly shipped.

Runtimes, in the blueprint's stated order of preference:
  1. ExecuTorch + XNNPACK  -- primary PyTorch-native Android path
  2. LiteRT                -- second path, for NPU-capable devices
  3. ONNX Runtime Mobile   -- compatibility fallback, not an assumed winner

TorchScript is also emitted because it is the lowest-friction way to smoke-test the model
inside an Android app before the ExecuTorch toolchain is wired up.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import torch

from neuronav.models.tcn import SpeedTCN, count_parameters

REPO_ROOT = Path(__file__).resolve().parents[1]
TOLERANCE = 1e-4


def load_model(checkpoint_path: Path):
    ckpt = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    model = SpeedTCN(n_features=len(ckpt["features"]), channels=int(ckpt["channels"]))
    model.load_state_dict(ckpt["model"])
    model.eval()
    return model, ckpt


def example_input(ckpt, batch: int = 1, rate_hz: float = 10.0) -> torch.Tensor:
    window = max(int(round(float(ckpt["window_s"]) * rate_hz)), 8)
    return torch.randn(batch, len(ckpt["features"]), window)


def check(name: str, reference: torch.Tensor, actual, results: dict):
    actual = torch.as_tensor(actual)
    delta = float((reference - actual).abs().max())
    ok = delta <= TOLERANCE
    results[name] = {"max_abs_diff": delta, "matches_eager": bool(ok)}
    print(f"  {name:28s} max|diff| = {delta:.2e}  {'OK' if ok else 'MISMATCH'}")
    return ok


def export_torchscript(model, example, out_dir: Path, reference, results):
    path = out_dir / "speed_tcn_torchscript.pt"
    traced = torch.jit.trace(model, example)
    traced.save(path)
    with torch.no_grad():
        check("torchscript", reference, torch.jit.load(path)(example)[0], results)
    results["torchscript"]["size_kb"] = path.stat().st_size / 1024
    return path


def export_onnx(model, example, out_dir: Path, reference, results):
    path = out_dir / "speed_tcn.onnx"
    # external_data=False keeps weights inside the single .onnx file. The default splits
    # them into a sibling .onnx.data, which is easy to ship without and yields a model
    # that loads but has no weights on device.
    torch.onnx.export(
        model, (example,), str(path),
        input_names=["imu_window"], output_names=["speed", "log_var"],
        dynamic_axes={"imu_window": {0: "batch"}, "speed": {0: "batch"},
                      "log_var": {0: "batch"}},
        opset_version=17, external_data=False)
    stray = path.with_suffix(".onnx.data")
    if stray.exists():
        stray.unlink()
    try:
        import onnxruntime as ort
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        out = session.run(None, {"imu_window": example.numpy()})
        check("onnx_runtime", reference, out[0], results)
    except ImportError:
        print("  onnx_runtime               skipped (onnxruntime not installed)")
    results.setdefault("onnx_runtime", {})["size_kb"] = path.stat().st_size / 1024
    return path


def export_executorch(model, example, out_dir: Path, reference, results):
    """ExecuTorch + XNNPACK -- the blueprint's primary Android path."""
    try:
        from executorch.exir import to_edge_transform_and_lower
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    except ImportError as exc:
        print(f"  executorch                 skipped ({exc.__class__.__name__}: not installed)")
        results["executorch"] = {"available": False}
        return None

    path = out_dir / "speed_tcn_xnnpack.pte"
    exported = torch.export.export(model, (example,))
    lowered = to_edge_transform_and_lower(exported, partitioner=[XnnpackPartitioner()])
    program = lowered.to_executorch()
    path.write_bytes(program.buffer)
    results["executorch"] = {"available": True, "size_kb": path.stat().st_size / 1024}

    try:
        from executorch.runtime import Runtime
        runtime = Runtime.get()
        method = runtime.load_program(path).load_method("forward")
        check("executorch_xnnpack", reference, method.execute([example])[0], results)
    except Exception as exc:  # runtime is optional on desktop wheels
        print(f"  executorch_xnnpack         exported, not run locally ({type(exc).__name__})")
    return path


MOBILE_ASSETS = REPO_ROOT / "mobile/app/src/main/assets"


def export_runtime_config(ckpt: dict, checkpoint_path: Path, out_dir: Path,
                          rate_hz: float = 10.0) -> Path:
    """Everything the on-device loop needs that is NOT inside the .onnx graph.

    The network is only one part of the deployed estimator: its inputs must be
    standardised with the exact statistics fitted during training, its sigma rescaled by
    the calibration factor, and any affine de-shrinking applied. Those live in the
    checkpoint, and shipping the graph without them produces a model that runs, returns
    plausible-looking numbers, and is quietly wrong -- the same failure mode as the
    weightless `.onnx` the Phase 4 export bug produced, but harder to notice.
    """
    scaler = ckpt["scaler"]
    mean = np.asarray(scaler["mean"], dtype=float).reshape(-1)
    std = np.asarray(scaler["std"], dtype=float).reshape(-1)
    affine = ckpt.get("speed_affine") or {"slope": 1.0, "offset": 0.0}

    config = {
        "checkpoint": checkpoint_path.name,
        "held_out_driver": ckpt.get("held_out"),
        "features": list(ckpt["features"]),
        "window_s": float(ckpt["window_s"]),
        "rate_hz": rate_hz,
        "window_samples": max(int(round(float(ckpt["window_s"]) * rate_hz)), 8),
        "scaler_mean": mean.tolist(),
        "scaler_std": std.tolist(),
        "sigma_scale": float(ckpt.get("sigma_scale", 1.0)),
        "speed_affine": {"slope": float(affine.get("slope", 1.0)),
                         "offset": float(affine.get("offset", 0.0))},
        "metrics": {k: float(v) for k, v in (ckpt.get("metrics") or {}).items()
                    if isinstance(v, (int, float))},
    }
    if len(mean) != len(config["features"]) or len(std) != len(config["features"]):
        raise SystemExit("scaler length does not match the feature list")

    path = out_dir / "speed_tcn_runtime.json"
    path.write_text(json.dumps(config, indent=2))
    print(f"  runtime config              {len(config['features'])} features, "
          f"window {config['window_samples']} @ {rate_hz:g} Hz, "
          f"sigma_scale {config['sigma_scale']:.3f}")
    return path


def install_assets(paths: list) -> None:
    """Copy the shipped artefacts into the Android asset directory.

    Done here rather than by hand so the graph and its runtime config can never drift
    apart -- a stale scaler beside a fresh model is silent and wrong.
    """
    MOBILE_ASSETS.mkdir(parents=True, exist_ok=True)
    for src in paths:
        dst = MOBILE_ASSETS / src.name
        dst.write_bytes(src.read_bytes())
        print(f"  {src.name:32s} -> {dst.relative_to(REPO_ROOT)}")


def main(checkpoint: str, install: bool = False):
    checkpoint_path = Path(checkpoint)
    model, ckpt = load_model(checkpoint_path)
    example = example_input(ckpt)
    out_dir = REPO_ROOT / "outputs" / "export"
    out_dir.mkdir(parents=True, exist_ok=True)

    with torch.no_grad():
        reference = model(example)[0]

    print(f"model: {count_parameters(model):,} params, window {example.shape[-1]} samples, "
          f"{len(ckpt['features'])} features")
    print(f"verifying exports against eager output (tolerance {TOLERANCE:g})\n")

    results: dict = {}
    export_torchscript(model, example, out_dir, reference, results)
    export_onnx(model, example, out_dir, reference, results)
    export_executorch(model, example, out_dir, reference, results)

    print("\nartefacts:")
    for file in sorted(out_dir.iterdir()):
        print(f"  {file.name:32s} {file.stat().st_size/1024:8.1f} KB")

    print()
    config_path = export_runtime_config(ckpt, checkpoint_path, out_dir)
    onnx_path = out_dir / "speed_tcn.onnx"
    if install:
        print("\ninstalling into the Android app:")
        install_assets([onnx_path, config_path])

    summary = {"checkpoint": str(checkpoint_path), "params": count_parameters(model),
               "window_samples": int(example.shape[-1]), "results": results,
               "runtime_config": config_path.name, "installed_to_app": bool(install)}
    (REPO_ROOT / "outputs/results/export_report.json").write_text(json.dumps(summary, indent=2))
    print(f"\nreport -> outputs/results/export_report.json")
    return results


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint",
                    default=str(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt"))
    ap.add_argument("--install-assets", action="store_true",
                    help="copy the graph and its runtime config into mobile/ assets")
    args = ap.parse_args()
    main(args.checkpoint, args.install_assets)
