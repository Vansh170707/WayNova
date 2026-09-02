"""Run the trained speed TCN over a segment, producing a speed + sigma track.

Predictions are computed for the whole segment in one batched pass and then read by the
filter, which keeps evaluation fast. The computation is still strictly causal -- every
window ends at the sample it labels -- so nothing here depends on future data and the same
model can be driven sample-by-sample from a ring buffer on-device.
"""
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import torch

from neuronav.calibration.alignment import Alignment
from neuronav.models.dataset import FeatureScaler, compute_features
from neuronav.models.tcn import SpeedTCN


@dataclass
class SpeedPrediction:
    speed: np.ndarray   # (N,) predicted forward speed, m/s
    sigma: np.ndarray   # (N,) calibrated 1-sigma uncertainty, m/s
    valid: np.ndarray   # (N,) False before a full window is available


class SpeedPredictor:
    def __init__(self, checkpoint_path: str | Path, device: str = "cpu"):
        ckpt = torch.load(checkpoint_path, map_location=device, weights_only=False)
        self.device = device
        self.window_s = float(ckpt["window_s"])
        self.sigma_scale = float(ckpt.get("sigma_scale", 1.0))
        # Affine de-shrinking, fitted on validation (see train_tcn.fit_speed_affine).
        # Absent in older checkpoints, where it is the identity.
        affine = ckpt.get("speed_affine") or {}
        self.speed_slope = float(affine.get("slope", 1.0))
        self.speed_offset = float(affine.get("offset", 0.0))
        self.held_out = ckpt.get("held_out")
        self.metrics = ckpt.get("metrics", {})
        self.scaler = FeatureScaler().load_state_dict(ckpt["scaler"])
        self.model = SpeedTCN(n_features=len(ckpt["features"]),
                              channels=int(ckpt["channels"])).to(device)
        self.model.load_state_dict(ckpt["model"])
        self.model.eval()

    def predict_segment(self, seg: pd.DataFrame, align: Alignment,
                        batch: int = 4096) -> SpeedPrediction:
        t = seg["timestamp"].to_numpy()
        rate = len(seg) / max(t[-1] - t[0], 1e-6)
        win = max(int(round(self.window_s * rate)), 8)

        feats = compute_features(seg, align)          # (N, F)
        n = len(seg)
        speed = np.zeros(n, dtype=np.float64)
        sigma = np.full(n, np.inf, dtype=np.float64)
        valid = np.zeros(n, dtype=bool)
        if n <= win:
            return SpeedPrediction(speed, sigma, valid)

        # window ending at index i covers [i-win+1, i]
        ends = np.arange(win - 1, n)
        strided = np.lib.stride_tricks.sliding_window_view(feats, win, axis=0)
        # sliding_window_view -> (n-win+1, F, win), already the layout the model wants
        X = np.ascontiguousarray(strided, dtype=np.float32)
        X = self.scaler.transform(X)

        means, variances = [], []
        with torch.no_grad():
            for i in range(0, len(X), batch):
                xb = torch.from_numpy(X[i:i + batch]).to(self.device)
                mean, log_var = self.model(xb)
                means.append(mean.cpu().numpy())
                variances.append(np.exp(log_var.cpu().numpy()))

        raw = np.concatenate(means)
        # Undo the head's shrinkage toward the training mean. A regression head trained on
        # a squared-error objective under an imbalanced speed distribution predicts
        # v_hat ~= a*v + b with a < 1, which is optimal for RMSE but not for navigation:
        # the resulting error is a speed-DEPENDENT bias, and a bias integrates straight
        # into along-track position while zero-mean noise averages out. Inverting the fit
        # trades a little variance for a large reduction in accumulated distance error.
        speed[ends] = np.maximum((raw - self.speed_offset) / self.speed_slope, 0.0)
        sigma[ends] = (np.sqrt(np.concatenate(variances)) * self.sigma_scale
                       / abs(self.speed_slope))
        valid[ends] = True
        return SpeedPrediction(speed, sigma, valid)
