"""Gate 2 deliverable: train the compact speed TCN with a held-out DRIVER.

Splitting by driver rather than by time is the honest test: it withholds an unseen phone,
vehicle and driving style together, which is what deployment actually faces. A time-based
split within one drive would mostly measure memorisation of that route.

Phase 6 additions -- all aimed at one measured defect
----------------------------------------------------
`scripts/ablate_error_sources.py` showed that speed, not heading, still dominates blackout
drift on top of B5, and `docs/phase6_physics_guided.md` traces that to the shape of the
speed error rather than its size: the head predicts v_hat ~= 0.71*v + 2.1 on a held-out
driver. That shrinkage toward the training mean is the RMSE-optimal answer under an
imbalanced speed distribution, but it is a speed-DEPENDENT bias, and bias integrates
straight into along-track position while zero-mean noise averages out.

Three countermeasures, each switchable so its contribution can be measured separately:

* `--balance`      inverse-frequency sample weights over true speed, so the rare fast
                   driving that dominates blackout distance is not drowned out.
* `--phys-weight`  a horizon loss on MEAN speed over ~30 s, which is exactly the quantity
                   that becomes along-track drift. Pointwise NLL is indifferent to a
                   consistent offset; this term is not.
* affine calibration, always fitted on VALIDATION and stored in the checkpoint, inverting
                   whatever shrinkage remains.
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader, TensorDataset

from neuronav.calibration.alignment import estimate_alignment
from neuronav.data.gnss_sim import GNSSSimConfig, add_simulated_gnss
from neuronav.data.reference import load_paired_segments
from neuronav.models.dataset import (DEFAULT_STRIDE_S, DEFAULT_WINDOW_S, FEATURE_NAMES,
                                     FeatureScaler, concat, make_windows)
from neuronav.models.tcn import SpeedTCN, count_parameters, gaussian_nll

REPO_ROOT = Path(__file__).resolve().parents[1]
PAIRED = REPO_ROOT / "data/raw/IO-VNBD/paired"


def load_trainable(inventory_path: Path, seed: int):
    """Load every segment the paired audit marked trainable, with its calibration."""
    inv = pd.read_csv(inventory_path)
    inv = inv[inv["trainable"]]
    loaded = []
    for _, row in inv.iterrows():
        driver, route = row["driver"], row["route_id"]
        session = route.rsplit("_seg", 1)[0]
        phone = PAIRED / driver / f"S-{session}.csv"
        vehicle = PAIRED / driver / f"V-{session}.csv"
        if not phone.exists():
            continue
        for seg in load_paired_segments(str(phone), str(vehicle), session):
            if seg["route_id"].iloc[0] != route:
                continue
            seg, _ = add_simulated_gnss(seg, GNSSSimConfig(seed=seed))
            try:
                align = estimate_alignment(seg)
            except ValueError:
                continue
            loaded.append((driver, route, seg, align))
    return loaded


def speed_bin_weights(y: np.ndarray, n_bins: int = 12, cap: float = 3.0) -> np.ndarray:
    """Inverse-frequency weights over true speed, normalised to mean 1 and clipped.

    Ordinary driving spends most of its samples stopped or crawling, so an unweighted
    objective is dominated by speeds that contribute almost nothing to distance. The cap
    keeps a handful of very fast windows from taking over the gradient entirely.
    """
    edges = np.linspace(0.0, float(max(y.max(), 1.0)), n_bins + 1)
    idx = np.clip(np.digitize(y, edges) - 1, 0, n_bins - 1)
    counts = np.bincount(idx, minlength=n_bins).astype(np.float64)
    inv = np.where(counts > 0, 1.0 / np.maximum(counts, 1.0), 0.0)
    w = inv[idx]
    w = w / max(w.mean(), 1e-12)
    return np.clip(w, 1.0 / cap, cap).astype(np.float32)


def chunk_starts(route: np.ndarray, k: int, stride: int) -> np.ndarray:
    """Start indices of `k` consecutive windows that all belong to one route.

    Windows are generated in time order at a fixed stride within a route, so a run of
    consecutive indices sharing a route id is a contiguous stretch of driving -- which is
    what the horizon loss needs. Chunks are never allowed to straddle a route boundary.
    """
    starts = []
    for r in np.unique(route):
        where = np.flatnonzero(route == r)
        if len(where) < k:
            continue
        lo, hi = where[0], where[-1] - k + 1
        starts.append(np.arange(lo, hi + 1, stride))
    return np.concatenate(starts) if starts else np.zeros(0, dtype=int)


def evaluate(model, X, y, device, batch=1024, affine=(1.0, 0.0)):
    """Held-out metrics. `affine` de-shrinks the head exactly as the predictor does."""
    model.eval()
    preds, variances = [], []
    with torch.no_grad():
        for i in range(0, len(X), batch):
            xb = torch.from_numpy(X[i:i + batch]).to(device)
            mean, log_var = model(xb)
            preds.append(mean.cpu().numpy())
            variances.append(np.exp(log_var.cpu().numpy()))
    slope, offset = affine
    pred = np.maximum((np.concatenate(preds) - offset) / slope, 0.0)
    var = np.concatenate(variances) / (slope ** 2)
    rmse = float(np.sqrt(np.mean((pred - y) ** 2)))
    mae = float(np.mean(np.abs(pred - y)))
    # calibration: standardised residuals should have unit variance if sigma is honest
    z = (pred - y) / np.sqrt(var)
    # How much distance a consistent offset would cost over a 120 s outage -- the metric
    # the navigation loop actually cares about, which RMSE alone does not expose.
    fit_slope = float(np.polyfit(y, pred, 1)[0]) if len(y) > 10 else float("nan")
    return {"rmse": rmse, "mae": mae, "corr": float(np.corrcoef(pred, y)[0, 1]),
            "z_std": float(np.std(z)), "mean_sigma": float(np.mean(np.sqrt(var))),
            "bias": float(np.mean(pred - y)), "fit_slope": fit_slope,
            "dist_err_120s_m": float(np.mean(pred - y) * 120.0)}


def fit_speed_affine(model, X, y, device, batch=1024) -> tuple:
    """Fit v_hat ~= slope*v + offset on validation, so the predictor can invert it.

    Fitted on the validation ROUTE, never on the held-out driver -- the correction has to
    be one a deployed system could have derived without seeing the test set.
    """
    model.eval()
    preds = []
    with torch.no_grad():
        for i in range(0, len(X), batch):
            preds.append(model(torch.from_numpy(X[i:i + batch]).to(device))[0].cpu().numpy())
    pred = np.concatenate(preds)
    slope, offset = np.polyfit(y, pred, 1)
    # A slope outside this range means the fit, not the head, is the thing that broke.
    slope = float(np.clip(slope, 0.3, 1.5))
    return slope, float(offset)


def select_validation_route(route: np.ndarray, y: np.ndarray,
                            requested: str | None = None) -> str:
    """Choose a route-level validation split without depending on name ordering.

    The previous ``sorted(set(route))[-1]`` happened to choose ``Vta2_seg00``: the
    smallest route, from a third vehicle, with unusually weak yaw agreement. Both early
    stopping and post-hoc uncertainty calibration then depended on that accident.

    The automatic policy remains group-honest (an entire route is held out). It prefers a
    route whose 10/25/50/75/90th speed percentiles resemble the *remaining* routes, with a
    small penalty for a route size far from the median route size. Thus neither a tiny
    outlier nor the largest source of training data wins merely because of its filename.
    Only training labels are used; the held-out driver remains untouched.
    """
    route = np.asarray(route)
    y = np.asarray(y)
    if len(route) != len(y) or len(route) == 0:
        raise ValueError("route and speed arrays must be non-empty and have equal length")
    routes, counts = np.unique(route.astype(str), return_counts=True)
    if len(routes) < 2:
        raise ValueError("validation requires at least two training routes")

    if requested:
        if requested not in routes:
            raise ValueError(
                f"validation route {requested!r} is not available; choose one of "
                f"{', '.join(routes)}")
        return requested

    median_count = float(np.median(counts))
    quantiles = (0.10, 0.25, 0.50, 0.75, 0.90)
    scored = []
    route_str = route.astype(str)
    for candidate, count in zip(routes, counts):
        val_mask = route_str == candidate
        val_speed = y[val_mask]
        remaining = y[~val_mask]
        if len(remaining) == 0:
            continue
        val_q = np.quantile(val_speed, quantiles)
        remaining_q = np.quantile(remaining, quantiles)
        scale = max(float(np.quantile(remaining, 0.90) -
                          np.quantile(remaining, 0.10)), 1.0)
        distribution_distance = float(np.sqrt(np.mean(
            ((val_q - remaining_q) / scale) ** 2)))
        size_penalty = abs(float(np.log(count / median_count)))
        score = distribution_distance + 0.15 * size_penalty
        scored.append((score, candidate))
    if not scored:
        raise ValueError("could not construct a validation route")
    return min(scored)[1]


def main(held_out: str, epochs: int, window_s: float, seed: int, channels: int,
         balance: bool = False, phys_weight: float = 0.0, horizon_s: float = 30.0,
         tag: str = "", affine: bool = False, balance_cap: float = 3.0,
         validation_route: str | None = None):
    torch.manual_seed(seed)
    np.random.seed(seed)
    device = "cpu"  # keep parity with the mobile target; the model is tiny

    loaded = load_trainable(REPO_ROOT / "outputs/results/paired_inventory.csv", seed)
    if not loaded:
        raise SystemExit("no trainable segments; run scripts/audit_paired.py first")

    drivers = sorted({d for d, _, _, _ in loaded})
    print(f"trainable segments: {len(loaded)} across drivers {drivers}")
    if held_out not in drivers:
        raise SystemExit(f"held-out driver {held_out!r} not among {drivers}")

    train_sets, test_sets = [], []
    for driver, route, seg, align in loaded:
        windows = make_windows(seg, align, driver, window_s=window_s,
                               stride_s=DEFAULT_STRIDE_S)
        if not len(windows):
            continue
        (test_sets if driver == held_out else train_sets).append(windows)
        print(f"  {route:14s} driver={driver:5s} windows={len(windows):6d} "
              f"{'TEST' if driver == held_out else 'train'}")

    train = concat(train_sets)
    test = concat(test_sets)

    # Hold out one complete route for early stopping and calibration, so the test driver
    # stays untouched. Selection is based on representativeness, not filename ordering.
    try:
        val_route = select_validation_route(train.route, train.y, validation_route)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    val_mask = train.route == val_route
    Xva, yva = train.X[val_mask], train.y[val_mask]
    Xtr, ytr = train.X[~val_mask], train.y[~val_mask]
    route_tr = train.route[~val_mask]
    selection = "explicit" if validation_route else "automatic representative-route"
    print(f"\ntrain={len(ytr)} windows | val={len(yva)} ({val_route}, {selection}) | "
          f"test={len(test)} "
          f"(driver {held_out})")

    scaler = FeatureScaler().fit(Xtr)
    Xtr_s, Xva_s, Xte_s = (scaler.transform(a) for a in (Xtr, Xva, test.X))

    model = SpeedTCN(n_features=len(FEATURE_NAMES), channels=channels).to(device)
    print(f"parameters: {count_parameters(model):,} | receptive field: "
          f"{model.receptive_field} samples ({model.receptive_field/10:.1f} s)")

    weights = (speed_bin_weights(ytr, cap=balance_cap) if balance
               else np.ones(len(ytr), dtype=np.float32))
    if balance:
        print(f"speed-balanced weights: min={weights.min():.2f} max={weights.max():.2f}")

    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    # The horizon loss needs CONSECUTIVE windows, but the NLL needs a well-shuffled batch
    # of independent ones. An earlier attempt built every batch from contiguous chunks and
    # cost 1.1 m/s of held-out RMSE: 4 chunks of 60 windows is 4 independent places in the
    # data, not 240, and the model underfits. So the two terms are sampled separately --
    # the loader is untouched, and the horizon term draws its own small set of chunks.
    loader = DataLoader(
        TensorDataset(torch.from_numpy(Xtr_s), torch.from_numpy(ytr),
                      torch.from_numpy(weights)),
        batch_size=256, shuffle=True, drop_last=True)

    k = max(int(round(horizon_s / DEFAULT_STRIDE_S)), 2)
    starts = chunk_starts(route_tr, k, stride=max(k // 4, 1))
    chunks_per_step = 8
    if phys_weight > 0:
        print(f"horizon loss: {horizon_s:.0f} s = {k} windows, {len(starts)} chunks, "
              f"{chunks_per_step}/step, weight {phys_weight}")

    Xtr_t = torch.from_numpy(Xtr_s)
    ytr_t = torch.from_numpy(ytr)
    rng = np.random.default_rng(seed)

    best = {"rmse": float("inf")}
    best_state = None
    started = time.time()
    for epoch in range(epochs):
        model.train()
        total, n_batches = 0.0, 0
        for xb, yb, wb in loader:
            optimizer.zero_grad()
            mean, log_var = model(xb.to(device))
            inv_var = torch.exp(-log_var)
            yb = yb.to(device)
            wb = wb.to(device)
            loss = (wb * 0.5 * (inv_var * (yb - mean) ** 2 + log_var)).mean()

            if phys_weight > 0 and len(starts):
                # Mean speed over the horizon IS the along-track drift rate: an outage of
                # length T accumulates T * (mean predicted - mean true) metres of
                # along-track error. Pointwise NLL is indifferent to a consistent offset,
                # so this term is what actually penalises the bias that becomes drift.
                sel = rng.choice(starts, size=chunks_per_step, replace=False)
                idx = (sel[:, None] + np.arange(k)[None, :]).reshape(-1)
                cm, _ = model(Xtr_t[idx].to(device))
                ct = ytr_t[idx].to(device)
                loss = loss + phys_weight * torch.nn.functional.smooth_l1_loss(
                    cm.view(chunks_per_step, k).mean(dim=1),
                    ct.view(chunks_per_step, k).mean(dim=1), beta=0.5)

            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            total += float(loss.item())
            n_batches += 1
        schedule.step()

        val = evaluate(model, Xva_s, yva, device)
        if val["rmse"] < best["rmse"]:
            best = {**val, "epoch": epoch}
            best_state = {k_: v.detach().clone() for k_, v in model.state_dict().items()}
        print(f"  epoch {epoch:2d}  loss={total/max(n_batches,1):7.4f}  "
              f"val_rmse={val['rmse']:5.2f}  val_corr={val['corr']:+.3f}  "
              f"val_slope={val['fit_slope']:.3f}")

    model.load_state_dict(best_state)

    # Both calibrations are fitted on VALIDATION, never on the held-out driver.
    # 1. Affine de-shrinking, OFF by default because it was measured to backfire here.
    #    The correction is only as good as the set it is fitted on. Phase 6's historical
    #    runs used a 2,185-window route from a third vehicle: it reported ~0.6 where
    #    the held-out driver's is ~0.86-0.99, so applying it over-corrects and costs
    #    2 m/s of RMSE. Fixing the shrinkage during training (--balance, --phys-weight)
    #    turned out to be both cheaper and safer than correcting it afterwards. The fit
    #    is still computed and reported, since it is the cleanest measure of how much
    #    shrinkage is left.
    fitted_slope, fitted_offset = fit_speed_affine(model, Xva_s, yva, device)
    slope, offset = (fitted_slope, fitted_offset) if affine else (1.0, 0.0)
    print(f"\naffine fit on validation: v_hat = {fitted_slope:.3f}*v {fitted_offset:+.3f} "
          f"({'inverted by the predictor' if affine else 'reported only, not applied'})")
    # 2. Sigma calibration, fitted AFTER the affine correction because the correction
    #    changes the residuals whose spread sigma is supposed to describe. An ES-EKF fed
    #    an over-confident pseudo-measurement trusts it too much during a blackout.
    val_metrics = evaluate(model, Xva_s, yva, device, affine=(slope, offset))
    sigma_scale = float(val_metrics["z_std"])
    print(f"sigma calibration on validation: z_std={sigma_scale:.3f} "
          f"-> scaling predicted sigma by this factor")

    test_metrics = evaluate(model, Xte_s, test.y, device, affine=(slope, offset))
    test_metrics["z_std_after_calibration"] = test_metrics["z_std"] / sigma_scale
    test_raw = evaluate(model, Xte_s, test.y, device)   # uncorrected, for the record

    # the baseline any speed model must beat: predict the training mean
    naive = float(np.sqrt(np.mean((ytr.mean() - test.y) ** 2)))
    skill = 1 - test_metrics["rmse"] / naive

    print(f"\nheld-out driver {held_out}:")
    print(f"  RMSE   = {test_metrics['rmse']:.3f} m/s   (predict-train-mean: {naive:.3f})")
    print(f"  MAE    = {test_metrics['mae']:.3f} m/s")
    print(f"  corr   = {test_metrics['corr']:+.3f}")
    print(f"  skill  = {skill:+.3f}")
    print(f"  sigma  = {test_metrics['mean_sigma']:.2f} m/s, z-std = {test_metrics['z_std']:.2f} "
          f"(1.0 means calibrated)")
    print(f"  bias   = {test_metrics['bias']:+.3f} m/s (was {test_raw['bias']:+.3f} "
          f"uncorrected) -> {test_metrics['dist_err_120s_m']:+.0f} m over a 120 s outage "
          f"(was {test_raw['dist_err_120s_m']:+.0f} m)")
    print(f"  slope  = {test_metrics['fit_slope']:.3f} (was {test_raw['fit_slope']:.3f}); "
          f"1.0 means no shrinkage")
    print(f"  trained in {time.time()-started:.0f}s")

    out_dir = REPO_ROOT / "outputs" / "checkpoints"
    out_dir.mkdir(parents=True, exist_ok=True)
    suffix = f"_{tag}" if tag else ""
    ckpt = out_dir / f"speed_tcn_holdout_{held_out}{suffix}.pt"
    torch.save({"model": model.state_dict(), "scaler": scaler.state_dict(),
                "window_s": window_s, "channels": channels,
                "features": FEATURE_NAMES, "held_out": held_out,
                "sigma_scale": sigma_scale,
                "speed_affine": {"slope": slope, "offset": offset},
                "train_config": {"balance": balance, "phys_weight": phys_weight,
                                 "horizon_s": horizon_s, "affine": affine,
                                 "balance_cap": balance_cap,
                                 "validation_route": val_route},
                "metrics": {**test_metrics, "skill": skill, "naive_rmse": naive}}, ckpt)
    print(f"\ncheckpoint -> {ckpt}")

    results_path = REPO_ROOT / "outputs/results" / f"tcn_holdout_{held_out}{suffix}.json"
    results_path.write_text(json.dumps(
        {"held_out": held_out, "val": best, "test": test_metrics,
         "test_uncalibrated": test_raw, "skill": skill,
         "speed_affine": {"slope": slope, "offset": offset},
         "speed_affine_fitted": {"slope": fitted_slope, "offset": fitted_offset},
         "train_config": {"balance": balance, "phys_weight": phys_weight,
                          "horizon_s": horizon_s, "affine": affine,
                          "balance_cap": balance_cap,
                          "validation_route": val_route},
         "naive_rmse": naive, "n_train": int(len(ytr)), "n_test": int(len(test)),
         "params": count_parameters(model)}, indent=2))
    return test_metrics


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--held-out", default="B")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--window-s", type=float, default=DEFAULT_WINDOW_S)
    ap.add_argument("--channels", type=int, default=32)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--balance", action="store_true",
                    help="inverse-frequency sample weights over true speed")
    ap.add_argument("--phys-weight", type=float, default=0.0,
                    help="weight on the horizon mean-speed (along-track drift) loss")
    ap.add_argument("--horizon-s", type=float, default=30.0)
    ap.add_argument("--tag", default="", help="checkpoint/report suffix, for ablations")
    ap.add_argument("--affine", action="store_true",
                    help="apply the validation-fitted affine de-shrinking (measured to "
                         "backfire on this dataset; see docs/phase6_physics_guided.md)")
    ap.add_argument("--balance-cap", type=float, default=3.0)
    ap.add_argument("--validation-route", default=None,
                    help="complete training route held out for early stopping and "
                         "calibration; default chooses a representative route")
    args = ap.parse_args()
    main(args.held_out, args.epochs, args.window_s, args.seed, args.channels,
         args.balance, args.phys_weight, args.horizon_s, args.tag, args.affine,
         args.balance_cap, args.validation_route)
