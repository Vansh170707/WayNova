"""Leave-one-drive-out cross-validation of the own-device fine-tune.

One train/test split over ten short city drives proves very little: the single split in
`finetune_own_device.py` had its validation drive prefer a refitted feature scaler while
the held-out drive preferred the pretrained one. With this little data that disagreement
is noise, and picking the winner by looking at the test drive would be cheating.

So every kept drive takes a turn as the held-out test drive, with the next drive in the
rotation acting as its validation set. The result is a per-drive table plus a mean, which
is what should be quoted -- and it is measured the same way for the shipped checkpoint, so
the comparison is like for like.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "src"))
sys.path.insert(0, str(REPO_ROOT / "scripts"))

from finetune_own_device import build_dataset, describe, run_finetune  # noqa: E402


# What "better" means differs per metric: error metrics should approach zero, the
# truth-to-prediction slope should approach 1, and correlation should grow.
METRIC_TARGET = {
    "rmse": 0.0, "mae": 0.0, "bias": 0.0, "distance_error_60s_m": 0.0,
    "slope": 1.0, "corr": None,
}


def summarise(rows: list[dict], key: str) -> dict:
    """Mean and spread of one metric across folds, for both the shipped and tuned model."""
    before = np.array([row["baseline"][key] for row in rows], dtype=float)
    after = np.array([row["tuned"][key] for row in rows], dtype=float)

    target = METRIC_TARGET.get(key, 0.0)
    if target is None:                       # larger is better
        improved = after > before
    else:
        improved = np.abs(after - target) < np.abs(before - target)

    return {
        "shipped_mean": float(before.mean()), "shipped_std": float(before.std()),
        "tuned_mean": float(after.mean()), "tuned_std": float(after.std()),
        "folds_improved": int(improved.sum()),
        "folds": len(rows),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--checkpoint", default="outputs/checkpoints/speed_tcn_holdout_B.pt")
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--weight-decay", type=float, default=1e-2)
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--refit-scaler", action="store_true")
    parser.add_argument("--no-balance", action="store_true",
                        help="disable speed-bin weighting of the objective")
    parser.add_argument("--moving-speed", type=float, default=2.0,
                        help="score only windows at or above this speed")
    parser.add_argument("--min-test-windows", type=int, default=100,
                        help="skip folds whose held-out drive is too short to score")
    parser.add_argument("--save-folds", action="store_true",
                        help="write each fold's checkpoint, so a held-out drive can be "
                             "re-scored end to end by a model that never saw it")
    parser.add_argument("--tag", default="own_device")
    args = parser.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    ckpt = torch.load(REPO_ROOT / args.checkpoint, map_location="cpu", weights_only=False)
    data, report = build_dataset(float(ckpt["window_s"]))

    routes = sorted(set(data.route.tolist()))
    scorable = [r for r in routes if (data.route == r).sum() >= args.min_test_windows]
    print(f"\ncross-validating over {len(scorable)} of {len(routes)} drives "
          f"(a drive needs >= {args.min_test_windows} windows to be scored)\n")

    rows = []
    for index, test_route in enumerate(scorable):
        # The validation drive is the next one in the rotation, so it is never the drive
        # being scored and every drive gets used for early stopping exactly once.
        val_route = scorable[(index + 1) % len(scorable)]
        result = run_finetune(
            data, ckpt, test_route, val_route,
            epochs=args.epochs, lr=args.lr, weight_decay=args.weight_decay,
            batch=args.batch, seed=args.seed, refit_scaler=args.refit_scaler,
            balance=not args.no_balance, moving_speed=args.moving_speed,
            device=device, verbose=False,
        )
        if args.save_folds:
            fold_path = (REPO_ROOT / "outputs/checkpoints" /
                         f"speed_tcn_fold_{test_route}.pt")
            fold_path.parent.mkdir(parents=True, exist_ok=True)
            torch.save({
                "model": result["model"].state_dict(),
                "scaler": result["scaler"].state_dict(),
                "window_s": result["window_s"], "channels": result["channels"],
                "held_out": test_route, "sigma_scale": result["sigma_scale"],
                "speed_affine": {"slope": 1.0, "offset": 0.0},
                "features": list(result["scaler"].mean.shape[:0]) or None,
                "metrics": result["tuned"],
            }, fold_path)

        rows.append({"test_route": test_route, "val_route": val_route,
                     "baseline": result["baseline"], "tuned": result["tuned"],
                     "best_epoch": result["best_epoch"],
                     "n_train": result["n_train"], "n_test": result["n_test"]})
        print(f"fold {index + 1}/{len(scorable)}  held out {test_route}  "
              f"({result['n_test']} windows, train {result['n_train']})")
        print("   " + describe("shipped", result["baseline"]))
        print("   " + describe("fine-tuned", result["tuned"]))

    print("\n" + "=" * 78)
    print(f"leave-one-drive-out mean over {len(rows)} drives"
          f"{' (scaler refitted)' if args.refit_scaler else ''}")
    print("=" * 78)
    summary = {key: summarise(rows, key) for key in
               ("rmse", "mae", "bias", "slope", "corr", "distance_error_60s_m")}
    for key, stats in summary.items():
        print(f"  {key:22s} shipped {stats['shipped_mean']:+8.2f} "
              f"-> tuned {stats['tuned_mean']:+8.2f}   "
              f"improved on {stats['folds_improved']}/{stats['folds']} drives")

    out = REPO_ROOT / "outputs" / "results" / f"crossval_{args.tag}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"folds": rows, "summary": summary,
                               "config": vars(args), "dataset": report}, indent=2))
    print(f"\nresults -> {out}")


if __name__ == "__main__":
    main()
