# Phase 10 — Representative Validation Retraining

Phase 8 removed an accidental validation policy: sorting route names and taking the last
one always selected `Vta2_seg00`, a small third-vehicle route already known to be a poor
calibration source. Phase 10 asks the necessary follow-up question: **does retraining the
same deployable model with the corrected route selection improve blackout navigation?**

The answer is **not consistently**. Driver B's median improves at longer outages, but a
subset of windows becomes significantly worse; driver A regresses at both the median and
tail. The Android model is therefore unchanged.

---

## 1. Controlled change

Two checkpoints were trained with the standard Phase 2 objective:

```bash
./envs/bin/python scripts/train_tcn.py --held-out A --epochs 12 --tag p10val
./envs/bin/python scripts/train_tcn.py --held-out B --epochs 12 --tag p10val
```

No balancing, horizon loss or affine correction was enabled. Architecture, seed, feature
contract, optimizer and epoch count stayed fixed. The intended methodological change was
the validation route:

| Held-out driver | Historical accidental route | Phase 10 route |
|---|---|---|
| A | `Vta2_seg00` | `M_seg00` |
| B | `Vta2_seg00` | `S4_seg00` |

Because the historical checkpoints predate `train_config`, they do not record the old
route internally; the Phase 6 training logs and documentation establish that both used
`Vta2_seg00`. New checkpoints record their validation route.

---

## 2. Speed-model results

| Held-out | Model | RMSE | Correlation | Skill | z-std after calibration |
|---|---|---:|---:|---:|---:|
| A | shipped | **3.050 m/s** | **0.832** | **0.470** | 1.801 |
| A | representative validation | 3.242 m/s | 0.807 | 0.461 | **1.348** |
| B | shipped | **3.228 m/s** | 0.831 | **0.455** | 1.060 |
| B | representative validation | 3.357 m/s | 0.830 | 0.429 | **1.021** |

The new validation set improves uncertainty calibration, especially for driver A, but
worsens RMSE and skill on both held-out drivers. Driver B's new fit has slope 0.844 and
bias +0.440 m/s; driver A's has slope 0.671 and bias −0.127 m/s. A better validation metric
is not enough if the actual unseen-driver prediction becomes worse.

---

## 3. Paired blackout comparison

Both new checkpoints were evaluated on the same seeds and window counts as the published
`orig` and `origA` runs: 200 matched windows per outage and driver. Both sides use
`B6_phys_es_ekf`, so only the speed checkpoint changes.

```bash
./envs/bin/python scripts/run_baselines.py --drivers B --n-windows 100 --seed 0 \
  --checkpoint outputs/checkpoints/speed_tcn_holdout_B_p10val.pt --tag p10val
./envs/bin/python scripts/run_baselines.py --drivers A --n-windows 50 --seed 0 \
  --checkpoint outputs/checkpoints/speed_tcn_holdout_A_p10val.pt --tag p10valA

./envs/bin/python scripts/compare_methods.py \
  --a orig:B6_phys_es_ekf --b p10val:B6_phys_es_ekf
./envs/bin/python scripts/compare_methods.py \
  --a origA:B6_phys_es_ekf --b p10valA:B6_phys_es_ekf
```

### Driver B

Negative paired median delta means the new checkpoint is better.

| Outage | Old median | New median | Paired median delta, 95% CI | Old p90 | New p90 | p90 of paired deltas, 95% CI |
|---:|---:|---:|---:|---:|---:|---:|
| 10 s | 11.51% | 11.89% | −0.05 [−0.40, +0.30] | 32.12% | 28.15% | +4.47 [+3.29, +5.32] |
| 30 s | 14.27% | 13.17% | **−0.64 [−1.21, −0.15]** | 31.84% | 30.22% | +3.75 [+3.05, +5.05] |
| 60 s | 15.88% | 14.83% | **−1.39 [−1.92, −0.86]** | 31.53% | 29.38% | +2.74 [+1.29, +3.71] |
| 120 s | 14.30% | 12.33% | **−1.42 [−1.67, −1.10]** | 32.99% | 31.54% | +1.72 [+1.02, +3.09] |

The distinction between the last two columns matters. The marginal p90 happens to fall,
but the 90th percentile of *per-window changes* is significantly positive. The new model
helps most windows while making a smaller subset materially worse. A live demonstration
experiences one window, not the marginal distribution in retrospect, so this is not a safe
tail trade.

### Driver A

| Outage | Old median | New median | Paired median delta, 95% CI | Old p90 | New p90 | p90 of paired deltas, 95% CI |
|---:|---:|---:|---:|---:|---:|---:|
| 10 s | 13.69% | 13.94% | +0.63 [−0.25, +1.44] | 30.97% | 33.23% | +13.11 [+8.22, +16.85] |
| 30 s | 15.14% | 14.82% | **+0.86 [+0.25, +1.58]** | 28.40% | 33.90% | +9.25 [+7.06, +12.06] |
| 60 s | 14.36% | 15.56% | **+1.34 [+0.71, +2.14]** | 28.34% | 33.08% | +7.80 [+6.59, +9.14] |
| 120 s | 14.20% | 16.27% | **+1.43 [+0.72, +2.09]** | 28.95% | 31.31% | +5.12 [+3.87, +7.15] |

At 30–120 seconds, the paired median and paired upper tail both significantly regress.
This failure to generalize across held-out drivers is stronger evidence than driver B's
median gain alone.

---

## 4. Decision

**Do not deploy either Phase 10 checkpoint.** The representative selector remains the
correct default for future experiments because it removes accidental name ordering, but a
more defensible split policy does not guarantee a better model on this small heterogeneous
dataset.

The Android assets were not re-exported. Their SHA-256 values after Phase 10 remain:

```text
speed_tcn.onnx          7ffd26455c8f95d17272a3dc50f20372b1ad08ca666f1051fda3cc4546a7f82b
speed_tcn_runtime.json  4609f82553bb355d23ec8b51f11b3d385b9540f7e87ce5086ad82f36e094f9a8
```

The `p10val` checkpoints and window tables stay under ignored `outputs/` as negative-result
evidence. They should not be passed to `export_model.py --install-assets`.

---

## 5. What changes the next attempt

Do not repeat this retraining with another single route chosen by intuition. The next
model-calibration attempt needs at least one new precondition:

- tomorrow's audited own-vehicle data adds actual device/speed diversity; or
- route-grouped cross-validation estimates calibration parameters from multiple folds
  rather than betting them on one route.

Any candidate must again pass paired per-window blackout comparison on both held-out
drivers before it can replace the Android asset.
