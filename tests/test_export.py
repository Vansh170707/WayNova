"""Export guards. The deployment-first rule makes these correctness tests, not packaging
checks: a backbone that cannot export, or that exports to something numerically different,
is not a finale candidate at all.
"""
import json
from pathlib import Path

import numpy as np
import pytest
import torch

from neuronav.models.tcn import SpeedTCN

REPO_ROOT = Path(__file__).resolve().parents[1]
EXPORT_DIR = REPO_ROOT / "outputs/export"
ONNX = EXPORT_DIR / "speed_tcn.onnx"
REPORT = REPO_ROOT / "outputs/results/export_report.json"

pytestmark = pytest.mark.skipif(
    not ONNX.exists(), reason="run scripts/export_model.py first")


def test_onnx_is_self_contained():
    """Weights must live inside the .onnx.

    torch.onnx.export defaults to splitting them into a sibling .onnx.data, which ships as
    a model that loads on device and then has no weights.
    """
    assert not (EXPORT_DIR / "speed_tcn.onnx.data").exists()
    assert ONNX.stat().st_size > 200_000, "suspiciously small — weights may be external"


def test_onnx_matches_eager_output():
    onnxruntime = pytest.importorskip("onnxruntime")
    ckpt = torch.load(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt",
                      map_location="cpu", weights_only=False)
    model = SpeedTCN(n_features=len(ckpt["features"]), channels=int(ckpt["channels"]))
    model.load_state_dict(ckpt["model"])
    model.eval()

    window = int(round(float(ckpt["window_s"]) * 10))
    x = np.random.default_rng(0).standard_normal(
        (1, len(ckpt["features"]), window)).astype(np.float32)

    with torch.no_grad():
        expected = model(torch.from_numpy(x))[0].numpy()
    session = onnxruntime.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
    actual = session.run(None, {"imu_window": x})[0]

    assert np.max(np.abs(expected - actual)) < 1e-4


def test_all_runtimes_verified_at_export():
    """Every exported runtime must have been checked against eager, not just produced."""
    if not REPORT.exists():
        pytest.skip("no export report")
    results = json.loads(REPORT.read_text())["results"]
    checked = {k: v for k, v in results.items() if "matches_eager" in v}
    assert checked, "no runtime was numerically verified"
    for name, entry in checked.items():
        assert entry["matches_eager"], f"{name} diverged from eager output"


def test_model_stays_small_enough_to_ship():
    """A mobile model that grows past a few MB stops being deployable."""
    assert ONNX.stat().st_size < 5_000_000
    pte = EXPORT_DIR / "speed_tcn_xnnpack.pte"
    if pte.exists():
        assert pte.stat().st_size < 5_000_000


def test_android_asset_is_in_sync():
    """The APK ships whatever is in assets/ — it must be the current export."""
    asset = REPO_ROOT / "mobile/app/src/main/assets/speed_tcn.onnx"
    if not asset.exists():
        pytest.skip("android assets not populated")
    assert asset.stat().st_size == ONNX.stat().st_size, (
        "assets/speed_tcn.onnx differs from outputs/export — re-copy after exporting")
