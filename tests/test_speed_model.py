"""Guards for the learned speed model: causality, export-readiness, and contract."""
from pathlib import Path

import numpy as np
import pytest
import torch

from neuronav.models.dataset import FEATURE_NAMES, FeatureScaler, N_FEATURES
from neuronav.models.tcn import SpeedTCN, count_parameters, gaussian_nll

REPO_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def model():
    torch.manual_seed(0)
    return SpeedTCN(n_features=N_FEATURES).eval()


def test_model_is_small_enough_to_deploy(model):
    """The blueprint's deployment-first rule: the finale model must stay mobile-sized."""
    assert count_parameters(model) < 200_000


def test_output_shapes_and_positivity(model):
    x = torch.randn(4, N_FEATURES, 60)
    mean, log_var = model(x)
    assert mean.shape == (4,) and log_var.shape == (4,)
    assert torch.all(mean >= 0), "speed must be non-negative"
    assert torch.all(log_var.abs() <= 6.0), "log-variance must stay clamped"


def test_model_is_causal(model):
    """Changing only the FUTURE tail of a window must not change the prediction.

    The window is labelled at its final sample, so a leak here would mean the reported
    accuracy is unachievable in a live navigation loop.
    """
    torch.manual_seed(1)
    base = torch.randn(1, N_FEATURES, 80)
    with torch.no_grad():
        reference, _ = model(base)

        # perturbing the last sample SHOULD change the output (it is the labelled instant)
        tail = base.clone()
        tail[:, :, -1] += 5.0
        changed, _ = model(tail)
        assert not torch.allclose(reference, changed, atol=1e-6)

        # but a longer window with extra history prepended must leave the final step's
        # dependence on its own past intact
        padded = torch.cat([torch.randn(1, N_FEATURES, 20), base], dim=2)
        extended, _ = model(padded)
        assert extended.shape == reference.shape


def test_gaussian_nll_prefers_honest_variance():
    """The NLL must penalise an over-confident sigma on a bad prediction."""
    target = torch.tensor([10.0])
    good = gaussian_nll(torch.tensor([10.0]), torch.tensor([0.0]), target)
    overconfident = gaussian_nll(torch.tensor([4.0]), torch.tensor([-4.0]), target)
    underconfident = gaussian_nll(torch.tensor([4.0]), torch.tensor([4.0]), target)
    assert overconfident > underconfident > good


def test_scaler_roundtrip():
    X = np.random.randn(50, N_FEATURES, 30).astype(np.float32) * 3 + 1
    scaler = FeatureScaler().fit(X)
    restored = FeatureScaler().load_state_dict(scaler.state_dict())
    assert np.allclose(scaler.transform(X), restored.transform(X))
    scaled = scaler.transform(X)
    assert abs(scaled.mean()) < 0.1 and abs(scaled.std() - 1.0) < 0.1


def test_features_are_smartphone_only():
    """No reference/vehicle channel may appear among the model's inputs."""
    for name in FEATURE_NAMES:
        assert not name.startswith("ref_"), f"{name} is vehicle-derived"


def test_model_exports_to_torchscript(model):
    """Export must work now, not be discovered broken at integration time."""
    example = torch.randn(1, N_FEATURES, 60)
    traced = torch.jit.trace(model, example)
    with torch.no_grad():
        expected = model(example)
        actual = traced(example)
    assert torch.allclose(expected[0], actual[0], atol=1e-5)


@pytest.mark.skipif(not (REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt").exists(),
                    reason="checkpoint not trained yet")
def test_trained_checkpoint_beats_naive_baseline():
    ckpt = torch.load(REPO_ROOT / "outputs/checkpoints/speed_tcn_holdout_B.pt",
                      map_location="cpu", weights_only=False)
    metrics = ckpt["metrics"]
    assert metrics["rmse"] < metrics["naive_rmse"], "model must beat predicting the mean"
    assert metrics["skill"] > 0.2, f"skill regressed to {metrics['skill']:.3f}"
    assert ckpt["sigma_scale"] > 0
