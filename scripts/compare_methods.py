"""Compare two methods on the SAME blackout windows, with uncertainty.

Why this exists
---------------
Every phase up to now compared methods by the median of each method's drift
distribution. On 50 windows that statistic is far noisier than it looks: two methods
whose per-window difference is essentially zero can still show medians 3 points apart,
because the marginal median only has to move past a few windows to jump. Reading such a
gap as an improvement is how a project talks itself into a result it does not have.

Every method here is run through identical windows, so the windows can be PAIRED and the
per-window difference examined directly. A paired median with a bootstrap interval, plus
the fraction of windows actually improved, says whether a change did anything.
"""
import argparse
from pathlib import Path

import numpy as np
import pandas as pd

REPO_ROOT = Path(__file__).resolve().parents[1]
N_BOOT = 4000


def load(tag: str) -> pd.DataFrame:
    suffix = f"_{tag}" if tag else ""
    path = REPO_ROOT / f"outputs/results/baseline_windows{suffix}.csv"
    if not path.exists():
        raise SystemExit(f"missing {path}; run scripts/run_baselines.py --tag {tag}")
    d = pd.read_csv(path)
    d["key"] = (d["route_id"] + "@" + d["start_s"].astype(str)
                + "@" + d["blackout_s"].astype(str))
    return d


def boot_ci(sample: np.ndarray, statistic, rng, alpha: float = 0.05):
    idx = rng.integers(0, len(sample), (N_BOOT, len(sample)))
    stats = statistic(sample[idx], axis=1)
    return np.quantile(stats, [alpha / 2, 1 - alpha / 2])


def sign_test_p(wins: int, n: int) -> float:
    """Two-sided probability of a split this lopsided under a fair coin."""
    from math import comb
    k = min(wins, n - wins)
    tail = sum(comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def parse_spec(spec: str) -> tuple:
    tag, _, method = spec.partition(":")
    if not method:
        raise SystemExit(f"expected TAG:METHOD, got {spec!r}")
    return tag, method


def main(spec_a: str, spec_b: str, metric: str):
    tag_a, method_a = parse_spec(spec_a)
    tag_b, method_b = parse_spec(spec_b)
    rng = np.random.default_rng(0)

    a = load(tag_a); b = load(tag_b)
    A = a[a["method"] == method_a].set_index("key")[metric]
    B = b[b["method"] == method_b].set_index("key")[metric]
    shared = A.index.intersection(B.index)
    if not len(shared):
        raise SystemExit("no windows in common -- were both runs given the same seed?")

    scale = 100.0 if metric == "drift_ratio" else 1.0
    unit = "%" if metric == "drift_ratio" else "m"
    print(f"A = {method_a} @ {tag_a or 'default'}")
    print(f"B = {method_b} @ {tag_b or 'default'}")
    print(f"metric = {metric}; negative delta means B is better\n")

    durations = sorted({float(k.rsplit("@", 1)[1]) for k in shared})
    rows = []
    for dur in durations:
        keys = [k for k in shared if k.endswith(f"@{dur}")]
        x, y = A[keys].to_numpy(), B[keys].to_numpy()
        d = y - x
        lo, hi = boot_ci(d, np.median, rng)
        p90_lo, p90_hi = boot_ci(d, lambda s, axis: np.quantile(s, 0.90, axis=axis), rng)
        wins = int((d < 0).sum())
        rows.append({
            "blackout_s": dur, "n": len(d),
            f"A_median{unit}": round(float(np.median(x)) * scale, 2),
            f"B_median{unit}": round(float(np.median(y)) * scale, 2),
            f"A_p90{unit}": round(float(np.quantile(x, 0.9)) * scale, 2),
            f"B_p90{unit}": round(float(np.quantile(y, 0.9)) * scale, 2),
            "paired_median_delta": round(float(np.median(d)) * scale, 2),
            "ci95": f"[{lo*scale:+.2f}, {hi*scale:+.2f}]",
            "tail_delta_p90": round(float(np.quantile(d, 0.9)) * scale, 2),
            "tail_ci95": f"[{p90_lo*scale:+.2f}, {p90_hi*scale:+.2f}]",
            "B_better": f"{wins}/{len(d)}",
            "sign_p": round(sign_test_p(wins, len(d)), 3),
        })

    out = pd.DataFrame(rows)
    with pd.option_context("display.width", 250):
        print(out.to_string(index=False))
    print("\nci95 excluding 0 is the only column that licenses the word 'improves'.")
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="TAG:METHOD, e.g. orig:B5_tcn_es_ekf")
    ap.add_argument("--b", required=True, help="TAG:METHOD, e.g. p6hb:B6_phys_es_ekf")
    ap.add_argument("--metric", default="drift_ratio",
                    choices=["drift_ratio", "final_error_m", "ate_m"])
    args = ap.parse_args()
    main(args.a, args.b, args.metric)
