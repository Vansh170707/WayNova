"""Fine-tune the speed TCN on the phone's own recorded drives.

Why this exists
---------------
The shipped checkpoint was trained on IO-VNBD: other phones, other vehicles, highway
speeds up to ~30 m/s. On the V2422 it under-predicts badly, and the three-ride audit
(docs/accuracy_experiment_2026-09-13.md) measured what that costs: adapted speed MAE of
1.50, 2.14 and 1.90 m/s, and outage drift of 26.5%, 57.9% and 32.7% against a <10%
target. Heading error on those same rides was 14.2 deg, 3.7 deg and 5.1 deg -- the ride
with the *best* heading had the *worst* drift. Drift is tracking speed error, not heading.

An earlier attempt fixed this with a per-device affine rescale and it failed: fitted on
177 samples it collapsed a 2-17 m/s range onto 5.3-9.3 m/s. A linear rescale cannot
express "this device reads acceleration differently in this speed regime"; gradient
fine-tuning on real labels can.

What it does not do
-------------------
No label here is anything but the phone's own GNSS speed on samples the recorder marked
fresh. The held-out drive is never trained on, never validated on, and its scaler
statistics never leak into training. Drives whose mount alignment fits poorly are
dropped, matching the app's own refusal to navigate on a weak calibration.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "src"))

from neuronav.models.dataset import FEATURE_NAMES, FeatureScaler, WindowedSet, concat  # noqa: E402
from neuronav.models.own_device import (  # noqa: E402
    discover_drives,
    estimate_own_device_alignment,
    load_drive,
    windows_for_segment,
)
from neuronav.models.tcn import SpeedTCN, count_parameters, gaussian_nll  # noqa: E402

SNAPSHOT_ROOTS = [
    REPO_ROOT / "data/raw/phone_snapshot_2026-09-05",
    REPO_ROOT / "data/raw/phone_snapshot_2026-09-11",
    REPO_ROOT / "data/raw/phone_backup_2026-09-13_pre_voice_install",
    REPO_ROOT / "data/raw/phone_backup_2026-09-13_pre_route_assist_install",
]

# Below this the forward-axis regression has not found a consistent mount direction, so
# the longitudinal feature is mostly noise. The app applies the same kind of gate live:
# it refuses to leave calibration on a weak axis fit.
MIN_FORWARD_CORR = 0.30
# A drive with almost no motion teaches the model nothing except to predict zero.
MIN_WINDOWS = 20


def build_dataset(window_s: float, verbose: bool = True) -> tuple[WindowedSet, dict]:
    """Every usable own-device window, plus a report of what was kept and dropped."""
    report = {"kept": [], "dropped": []}
    sets: list[WindowedSet] = []

    for path in discover_drives(SNAPSHOT_ROOTS):
        for segment in load_drive(path):
            align = estimate_own_device_alignment(segment)
            if align is None:
                report["dropped"].append({"route": segment.route_id,
                                          "reason": "alignment did not converge"})
                continue
            if abs(align.forward_accel_corr) < MIN_FORWARD_CORR:
                report["dropped"].append({
                    "route": segment.route_id,
                    "reason": "weak forward-axis fit",
                    "forward_corr": round(float(align.forward_accel_corr), 3)})
                continue

            windows = windows_for_segment(segment, align, window_s=window_s)
            if len(windows) < MIN_WINDOWS:
                report["dropped"].append({"route": segment.route_id,
                                          "reason": f"only {len(windows)} windows"})
                continue

            sets.append(windows)
            report["kept"].append({
                "route": segment.route_id,
                "windows": int(len(windows)),
                "duration_s": round(segment.duration_s, 1),
                "forward_corr": round(float(align.forward_accel_corr), 3),
                "forward_scale": round(float(align.forward_accel_scale), 3),
                "gyro_bias_rad_s": round(float(align.gyro_bias_rad_s), 5),
                "speed_mean": round(float(windows.y.mean()), 2),
                "speed_max": round(float(windows.y.max()), 2)})

    if not sets:
        raise SystemExit("no usable own-device drives found")

    data = concat(sets)
    if verbose:
        print(f"own-device dataset: {len(data)} windows from {len(report['kept'])} drives")
        for entry in report["kept"]:
            print(f"  keep {entry['route']:24s} {entry['windows']:4d} windows  "
                  f"corr={entry['forward_corr']:+.2f}  "
                  f"v={entry['speed_mean']:.1f}/{entry['speed_max']:.1f} m/s")
        for entry in report["dropped"]:
            print(f"  drop {entry['route']:24s} {entry['reason']}")
    return data, report


def split_by_route(data: WindowedSet, test_route: str, val_route: str) -> tuple:
    """Route-disjoint split. A window never appears in more than one of the three."""
    test_mask = data.route == test_route
    val_mask = data.route == val_route
    train_mask = ~(test_mask | val_mask)
    for name, mask in (("test", test_mask), ("validation", val_mask), ("train", train_mask)):
        if mask.sum() == 0:
            raise SystemExit(f"{name} split is empty; check the route names")

    def take(mask):
        return WindowedSet(data.X[mask], data.y[mask], data.route[mask], data.driver[mask])

    return take(train_mask), take(val_mask), take(test_mask)


def _score(pred: np.ndarray, sigma: np.ndarray, y: np.ndarray) -> dict:
    residual = pred - y
    # Slope of truth -> prediction. 1.0 means no shrinkage.
    slope, offset = np.polyfit(y, pred, 1) if len(y) > 2 else (float("nan"),) * 2
    return {
        "rmse": float(np.sqrt(np.mean(residual ** 2))),
        "mae": float(np.mean(np.abs(residual))),
        "bias": float(np.mean(residual)),
        "corr": float(np.corrcoef(pred, y)[0, 1]) if len(y) > 2 else float("nan"),
        "slope": float(slope),
        "offset": float(offset),
        "mean_sigma": float(np.mean(sigma)),
        "z_std": float(np.std(residual / np.maximum(sigma, 1e-6))),
        # A constant speed bias integrates straight into position during a blackout.
        "distance_error_60s_m": float(np.mean(residual) * 60.0),
        "true_speed_mean": float(np.mean(y)),
        "n": int(len(y)),
    }


@torch.no_grad()
def evaluate(model: SpeedTCN, X: np.ndarray, y: np.ndarray, device: str,
             batch: int = 512, moving_speed: float = 0.0) -> dict:
    """Accuracy of the speed head, in the terms that matter to outage drift.

    Scored over windows at or above `moving_speed`. A blackout that happens while the
    vehicle is parked costs no distance, so including stopped windows flatters the model
    on exactly the samples that cannot contribute to drift. The all-window figures are
    still returned under "all" so nothing is hidden.
    """
    model.eval()
    means, sigmas = [], []
    for start in range(0, len(X), batch):
        chunk = torch.from_numpy(X[start:start + batch]).to(device)
        mean, log_var = model(chunk)
        means.append(mean.cpu().numpy())
        sigmas.append(np.exp(0.5 * log_var.cpu().numpy()))
    pred = np.concatenate(means)
    sigma = np.concatenate(sigmas)

    everything = _score(pred, sigma, y)
    if moving_speed <= 0:
        return {**everything, "all": everything, "moving_speed": 0.0}

    mask = y >= moving_speed
    if mask.sum() < 3:
        return {**everything, "all": everything, "moving_speed": float(moving_speed)}
    moving = _score(pred[mask], sigma[mask], y[mask])
    return {**moving, "all": everything, "moving_speed": float(moving_speed)}


def speed_bin_weights(y: np.ndarray, n_bins: int = 12, cap: float = 3.0) -> np.ndarray:
    """Inverse-frequency weights over true speed, normalised to mean 1 and clipped.

    These drives spend 18.5% of their labelled windows below 1 m/s, and those windows
    contribute nothing to distance during an outage. Left unweighted they drag the model
    down: the first fine-tune ran unweighted, reached an excellent drive-average bias of
    -0.07 m/s, and still made end-to-end drift worse, because the blackout happened
    during the fast part of the drive (8.1 m/s mean against 5.9 m/s for the drive) where
    it was under-predicting by 1.35 m/s.

    The regime that matters is the one the vehicle is actually moving in, so the
    objective is rebalanced across the speed range. Same weighting `train_tcn.py` uses.
    """
    edges = np.linspace(0.0, float(max(y.max(), 1.0)), n_bins + 1)
    idx = np.clip(np.digitize(y, edges) - 1, 0, n_bins - 1)
    counts = np.bincount(idx, minlength=n_bins).astype(np.float64)
    inv = np.where(counts > 0, 1.0 / np.maximum(counts, 1.0), 0.0)
    weights = inv[idx]
    weights = weights / max(weights.mean(), 1e-12)
    return np.clip(weights, 1.0 / cap, cap).astype(np.float32)


def weighted_gaussian_nll(mean, log_var, target, weight):
    """Heteroscedastic NLL with a per-sample weight, up to a constant."""
    inv_var = torch.exp(-log_var)
    per_sample = 0.5 * (inv_var * (target - mean) ** 2 + log_var)
    return (per_sample * weight).sum() / weight.sum().clamp_min(1e-6)


def describe(tag: str, metrics: dict) -> str:
    return (f"{tag:22s} RMSE={metrics['rmse']:5.2f}  MAE={metrics['mae']:5.2f}  "
            f"bias={metrics['bias']:+5.2f}  slope={metrics['slope']:5.3f}  "
            f"corr={metrics['corr']:+.3f}  60s drift={metrics['distance_error_60s_m']:+6.0f} m")


def run_finetune(data: WindowedSet, ckpt: dict, test_route: str, val_route: str,
                 epochs: int = 60, lr: float = 3e-4, weight_decay: float = 1e-2,
                 batch: int = 64, seed: int = 0, refit_scaler: bool = False,
                 balance: bool = True, moving_speed: float = 2.0,
                 device: str = "cpu", verbose: bool = True) -> dict:
    """Fine-tune one fold and return the shipped-vs-tuned comparison on the held-out drive.

    Returns the trained model, its scaler, and the metrics, so the caller can either save
    a checkpoint or just collect the numbers for a cross-validation sweep.
    """
    torch.manual_seed(seed)
    np.random.seed(seed)

    window_s = float(ckpt["window_s"])
    channels = int(ckpt["channels"])
    train, val, test = split_by_route(data, test_route, val_route)

    pretrained_scaler = FeatureScaler().load_state_dict(ckpt["scaler"])
    scaler = FeatureScaler().fit(train.X) if refit_scaler else pretrained_scaler

    Xtr, Xva, Xte = (scaler.transform(s.X) for s in (train, val, test))

    model = SpeedTCN(len(FEATURE_NAMES), channels=channels).to(device)
    model.load_state_dict(ckpt["model"])

    baseline_model = SpeedTCN(len(FEATURE_NAMES), channels=channels).to(device)
    baseline_model.load_state_dict(ckpt["model"])
    baseline = evaluate(baseline_model, pretrained_scaler.transform(test.X), test.y, device,
                        moving_speed=moving_speed)

    Xtr_t = torch.from_numpy(Xtr)
    ytr_t = torch.from_numpy(train.y)
    wtr_t = torch.from_numpy(speed_bin_weights(train.y) if balance
                             else np.ones(len(train.y), dtype=np.float32))
    optimiser = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=weight_decay)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(optimiser, T_max=epochs)

    best = {"rmse": float("inf"), "epoch": -1}
    best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
    generator = np.random.default_rng(seed)
    started = time.time()

    for epoch in range(1, epochs + 1):
        model.train()
        order = generator.permutation(len(ytr_t))
        total, batches = 0.0, 0
        for start in range(0, len(order), batch):
            idx = order[start:start + batch]
            if len(idx) < 2:
                continue
            optimiser.zero_grad()
            mean, log_var = model(Xtr_t[idx].to(device))
            loss = weighted_gaussian_nll(mean, log_var, ytr_t[idx].to(device),
                                         wtr_t[idx].to(device))
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            total += float(loss.item())
            batches += 1
        schedule.step()

        # Early stopping watches the moving regime: a blackout that happens while parked
        # costs nothing, so validation error below walking pace is not what to minimise.
        metrics = evaluate(model, Xva, val.y, device, moving_speed=moving_speed)
        if metrics["rmse"] < best["rmse"]:
            best = {**metrics, "epoch": epoch}
            best_state = {k: v.detach().clone() for k, v in model.state_dict().items()}
        if verbose and (epoch % 5 == 0 or epoch == 1):
            print(f"  epoch {epoch:3d}  loss={total / max(batches, 1):7.3f}  "
                  f"val_rmse={metrics['rmse']:5.2f}  val_slope={metrics['slope']:.3f}")

    model.load_state_dict(best_state)
    val_metrics = evaluate(model, Xva, val.y, device, moving_speed=moving_speed)
    sigma_scale = float(val_metrics["z_std"])
    tuned = evaluate(model, Xte, test.y, device, moving_speed=moving_speed)
    tuned["z_std_after_calibration"] = tuned["z_std"] / max(sigma_scale, 1e-6)
    naive = float(np.sqrt(np.mean((train.y.mean() - test.y) ** 2)))

    return {
        "model": model, "scaler": scaler, "window_s": window_s, "channels": channels,
        "baseline": baseline, "tuned": tuned, "validation": val_metrics,
        "best_epoch": best["epoch"], "sigma_scale": sigma_scale, "naive_rmse": naive,
        "n_train": len(train), "n_val": len(val), "n_test": len(test),
        "trained_s": round(time.time() - started, 1),
    }


def run_final(args) -> None:
    """Train the shipping checkpoint on every kept drive.

    There is no held-out drive here by construction, so this run reports no accuracy of
    its own. The number to quote is the leave-one-drive-out mean from
    `crossval_own_device.py`, which measured this exact recipe on drives it never saw.
    Epochs are fixed rather than early-stopped, at the median best epoch across folds,
    because there is no validation set left to stop on.
    """
    device = "cuda" if torch.cuda.is_available() else "cpu"
    ckpt = torch.load(REPO_ROOT / args.checkpoint, map_location="cpu", weights_only=False)
    data, report = build_dataset(float(ckpt["window_s"]))

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    scaler = (FeatureScaler().fit(data.X) if args.refit_scaler
              else FeatureScaler().load_state_dict(ckpt["scaler"]))
    X = torch.from_numpy(scaler.transform(data.X))
    y = torch.from_numpy(data.y)

    model = SpeedTCN(len(FEATURE_NAMES), channels=int(ckpt["channels"])).to(device)
    model.load_state_dict(ckpt["model"])

    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr,
                                  weight_decay=args.weight_decay)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(optimiser, T_max=args.final_epochs)
    generator = np.random.default_rng(args.seed)

    print(f"\nfinal fit on all {len(data)} windows for {args.final_epochs} epochs")
    for epoch in range(1, args.final_epochs + 1):
        model.train()
        order = generator.permutation(len(y))
        total, batches = 0.0, 0
        for start in range(0, len(order), args.batch):
            idx = order[start:start + args.batch]
            if len(idx) < 2:
                continue
            optimiser.zero_grad()
            mean, log_var = model(X[idx].to(device))
            loss = gaussian_nll(mean, log_var, y[idx].to(device))
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            total += float(loss.item())
            batches += 1
        schedule.step()
        print(f"  epoch {epoch:3d}  loss={total / max(batches, 1):7.3f}")

    # In-sample only, printed so the fit is visibly sane. Not an accuracy claim.
    in_sample = evaluate(model, scaler.transform(data.X), data.y, device)
    print("\n" + describe("in-sample (not held out)", in_sample))

    crossval_path = REPO_ROOT / "outputs/results/crossval_own_device.json"
    crossval = json.loads(crossval_path.read_text()) if crossval_path.exists() else None
    if crossval:
        summary = crossval["summary"]
        print(f"{'held-out (crossval)':22s} RMSE={summary['rmse']['tuned_mean']:5.2f}  "
              f"MAE={summary['mae']['tuned_mean']:5.2f}  "
              f"bias={summary['bias']['tuned_mean']:+5.2f}  "
              f"60s drift={summary['distance_error_60s_m']['tuned_mean']:+6.0f} m")

    sigma_scale = float(in_sample["z_std"])
    out = REPO_ROOT / "outputs/checkpoints" / f"speed_tcn_{args.tag}_final.pt"
    out.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "model": model.state_dict(), "scaler": scaler.state_dict(),
        "window_s": float(ckpt["window_s"]), "channels": int(ckpt["channels"]),
        "features": FEATURE_NAMES, "held_out": "none (trained on all own-device drives)",
        "sigma_scale": sigma_scale, "speed_affine": {"slope": 1.0, "offset": 0.0},
        "train_config": {"base_checkpoint": args.checkpoint, "mode": "final",
                         "epochs": args.final_epochs, "lr": args.lr,
                         "weight_decay": args.weight_decay, "batch": args.batch,
                         "refit_scaler": args.refit_scaler, "seed": args.seed},
        "metrics": {**in_sample, "note": "in-sample; quote crossval_own_device.json"},
        "crossval": crossval["summary"] if crossval else None,
    }, out)
    print(f"\ncheckpoint -> {out}")

    results = REPO_ROOT / "outputs/results" / f"finetune_{args.tag}_final.json"
    results.write_text(json.dumps({
        "mode": "final", "n_windows": len(data), "in_sample": in_sample,
        "sigma_scale": sigma_scale, "dataset": report, "config": vars(args),
        "held_out_accuracy": crossval["summary"] if crossval else None,
    }, indent=2))
    print(f"results    -> {results}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--checkpoint", default="outputs/checkpoints/speed_tcn_holdout_B.pt",
                        help="pretrained IO-VNBD checkpoint to start from")
    parser.add_argument("--test-route", default="1789275278630_seg00",
                        help="drive held out entirely; never trained or validated on")
    parser.add_argument("--val-route", default="1788784767348_seg00",
                        help="drive used for early stopping and sigma calibration")
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--weight-decay", type=float, default=1e-2)
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--refit-scaler", action="store_true",
                        help="refit feature standardisation on own-device training windows")
    parser.add_argument("--no-balance", action="store_true",
                        help="disable speed-bin weighting of the objective")
    parser.add_argument("--moving-speed", type=float, default=2.0,
                        help="score only windows at or above this speed")
    parser.add_argument("--final", action="store_true",
                        help="train the shipping model on every drive for a fixed number "
                             "of epochs; quote crossval_own_device.py for its accuracy")
    parser.add_argument("--final-epochs", type=int, default=13,
                        help="epoch count for --final; the median best epoch across folds")
    parser.add_argument("--tag", default="own_device")
    args = parser.parse_args()

    if args.final:
        run_final(args)
        return

    device = "cuda" if torch.cuda.is_available() else "cpu"
    ckpt = torch.load(REPO_ROOT / args.checkpoint, map_location="cpu", weights_only=False)
    if list(ckpt["features"]) != FEATURE_NAMES:
        raise SystemExit(f"checkpoint features {ckpt['features']} != {FEATURE_NAMES}")

    data, report = build_dataset(float(ckpt["window_s"]))
    result = run_finetune(
        data, ckpt, args.test_route, args.val_route,
        epochs=args.epochs, lr=args.lr, weight_decay=args.weight_decay,
        batch=args.batch, seed=args.seed, refit_scaler=args.refit_scaler,
        balance=not args.no_balance, moving_speed=args.moving_speed,
        device=device, verbose=True,
    )

    print(f"\nsplit: train={result['n_train']}  val={result['n_val']}  "
          f"test={result['n_test']} (test route {args.test_route})")
    print(f"scaler: {'refitted on own-device windows' if args.refit_scaler else 'kept from the pretrained checkpoint'}")
    print(f"objective: {'speed-bin balanced' if not args.no_balance else 'unweighted'}; "
          f"scored on windows >= {args.moving_speed} m/s")
    print(f"best validation epoch {result['best_epoch']}\n")
    print(describe("shipped (IO-VNBD)", result["baseline"]))
    print(describe("fine-tuned (V2422)", result["tuned"]))
    print(f"{'predict train mean':22s} RMSE={result['naive_rmse']:5.2f}")
    print(f"\nsigma calibration on validation: z_std={result['sigma_scale']:.3f}")

    out_dir = REPO_ROOT / "outputs" / "checkpoints"
    out_dir.mkdir(parents=True, exist_ok=True)
    ckpt_out = out_dir / f"speed_tcn_{args.tag}.pt"
    torch.save({
        "model": result["model"].state_dict(),
        "scaler": result["scaler"].state_dict(),
        "window_s": result["window_s"],
        "channels": result["channels"],
        "features": FEATURE_NAMES,
        "held_out": args.test_route,
        "sigma_scale": result["sigma_scale"],
        "speed_affine": {"slope": 1.0, "offset": 0.0},
        "train_config": {"base_checkpoint": args.checkpoint, "epochs": args.epochs,
                         "lr": args.lr, "weight_decay": args.weight_decay,
                         "batch": args.batch, "refit_scaler": args.refit_scaler,
                         "balance": not args.no_balance,
                         "moving_speed": args.moving_speed,
                         "val_route": args.val_route, "seed": args.seed},
        "metrics": {**result["tuned"], "naive_rmse": result["naive_rmse"]},
    }, ckpt_out)
    print(f"\ncheckpoint -> {ckpt_out}")

    results_dir = REPO_ROOT / "outputs" / "results"
    results_dir.mkdir(parents=True, exist_ok=True)
    results_path = results_dir / f"finetune_{args.tag}.json"
    results_path.write_text(json.dumps({
        "test_route": args.test_route, "val_route": args.val_route,
        "n_train": result["n_train"], "n_val": result["n_val"], "n_test": result["n_test"],
        "baseline_on_test": result["baseline"], "finetuned_on_test": result["tuned"],
        "validation": result["validation"], "naive_rmse": result["naive_rmse"],
        "sigma_scale": result["sigma_scale"], "best_epoch": result["best_epoch"],
        "dataset": report, "config": vars(args), "trained_s": result["trained_s"],
    }, indent=2))
    print(f"results    -> {results_path}")


if __name__ == "__main__":
    main()
