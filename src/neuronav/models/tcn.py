"""Compact causal TCN predicting forward speed, with heteroscedastic uncertainty.

TCN is the blueprint's deployment-first backbone: causal dilated convolutions are small,
parallel, quantisation-friendly and supported by mobile runtimes, so the model that gets
trained is the model that can ship. Everything here is deliberately kept exportable --
no custom operators, no dynamic control flow, fixed-length input.

The head emits both a mean speed and a log-variance. The variance is not decoration: the
ES-EKF consumes it as the measurement noise for the learned speed pseudo-measurement, so
the network can widen its own error bars over road types it finds unfamiliar (blueprint
section 9.1, learned uncertainty).
"""
import torch
import torch.nn as nn


class CausalConv1d(nn.Module):
    """Left-padded convolution: output t depends only on inputs <= t."""

    def __init__(self, in_ch: int, out_ch: int, kernel_size: int, dilation: int):
        super().__init__()
        self.pad = (kernel_size - 1) * dilation
        self.conv = nn.Conv1d(in_ch, out_ch, kernel_size, dilation=dilation)

    def forward(self, x):
        return self.conv(nn.functional.pad(x, (self.pad, 0)))


class TemporalBlock(nn.Module):
    def __init__(self, in_ch: int, out_ch: int, kernel_size: int, dilation: int,
                 dropout: float):
        super().__init__()
        self.conv1 = CausalConv1d(in_ch, out_ch, kernel_size, dilation)
        self.norm1 = nn.GroupNorm(1, out_ch)
        self.conv2 = CausalConv1d(out_ch, out_ch, kernel_size, dilation)
        self.norm2 = nn.GroupNorm(1, out_ch)
        self.act = nn.GELU()
        self.drop = nn.Dropout(dropout)
        self.residual = nn.Conv1d(in_ch, out_ch, 1) if in_ch != out_ch else nn.Identity()

    def forward(self, x):
        h = self.drop(self.act(self.norm1(self.conv1(x))))
        h = self.drop(self.act(self.norm2(self.conv2(h))))
        return self.act(h + self.residual(x))


class SpeedTCN(nn.Module):
    """Predicts forward speed (m/s) and its log-variance from an IMU window."""

    def __init__(self, n_features: int, channels: int = 32, levels: int = 4,
                 kernel_size: int = 3, dropout: float = 0.1):
        super().__init__()
        blocks, in_ch = [], n_features
        for level in range(levels):
            blocks.append(TemporalBlock(in_ch, channels, kernel_size, 2 ** level, dropout))
            in_ch = channels
        self.tcn = nn.Sequential(*blocks)
        self.head = nn.Sequential(
            nn.Linear(channels, channels), nn.GELU(), nn.Linear(channels, 2))
        self.receptive_field = 1 + 2 * (kernel_size - 1) * (2 ** levels - 1)

    def forward(self, x):
        """x: (batch, n_features, window). Returns (mean_speed, log_var)."""
        h = self.tcn(x)[:, :, -1]          # causal: last step summarises the window
        out = self.head(h)
        mean = nn.functional.softplus(out[:, 0])          # speed is non-negative
        log_var = out[:, 1].clamp(-6.0, 6.0)
        return mean, log_var


def gaussian_nll(mean, log_var, target):
    """Heteroscedastic negative log-likelihood, up to a constant."""
    inv_var = torch.exp(-log_var)
    return (0.5 * (inv_var * (target - mean) ** 2 + log_var)).mean()


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)
