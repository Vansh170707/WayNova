#!/usr/bin/env python3
"""Generate NeuroNav-X Learning Guide PDF."""
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm
from reportlab.lib.colors import HexColor
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table,
                                TableStyle, PageBreak, ListFlowable, ListItem, HRFlowable)
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY

OUT = "/Users/vanshsehrawat/Downloads/sih hackathon project/neuronav-x/NeuroNav-X_Learning_Guide.pdf"

styles = getSampleStyleSheet()
ACCENT = HexColor("#0B3D91")
DARK = HexColor("#1a1a1a")

styles.add(ParagraphStyle("Cover", parent=styles["Title"], fontSize=26, textColor=ACCENT, spaceAfter=6))
styles.add(ParagraphStyle("Sub", parent=styles["Normal"], fontSize=12, alignment=TA_CENTER, textColor=DARK))
styles.add(ParagraphStyle("H1c", parent=styles["Heading1"], fontSize=16, textColor=ACCENT, spaceBefore=14, spaceAfter=6))
styles.add(ParagraphStyle("H2c", parent=styles["Heading2"], fontSize=13, textColor=HexColor("#333333"), spaceBefore=10, spaceAfter=4))
styles.add(ParagraphStyle("Body", parent=styles["Normal"], fontSize=9.5, leading=13.5, alignment=TA_JUSTIFY))
styles.add(ParagraphStyle("Bull", parent=styles["Normal"], fontSize=9.5, leading=13.5, leftIndent=14, bulletIndent=4))
styles.add(ParagraphStyle("CodeB", parent=styles["Code"], fontSize=8, leading=11, backColor=HexColor("#f2f2f2")))
styles.add(ParagraphStyle("Cap", parent=styles["Normal"], fontSize=8, textColor=HexColor("#555555"), alignment=TA_CENTER))

def P(t, s="Body"): return Paragraph(t, styles[s])
def H(t): return Paragraph(t, styles["H1c"])
def H2(t): return Paragraph(t, styles["H2c"])
def tbl(data, widths=None):
    t = Table(data, colWidths=widths, repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), ACCENT),
        ("TEXTCOLOR", (0,0), (-1,0), HexColor("#ffffff")),
        ("FONTSIZE", (0,0), (-1,-1), 8),
        ("LEADING", (0,0), (-1,-1), 10.5),
        ("GRID", (0,0), (-1,-1), 0.4, HexColor("#999999")),
        ("VALIGN", (0,0), (-1,-1), "TOP"),
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [HexColor("#ffffff"), HexColor("#eef3ff")]),
        ("LEFTPADDING", (0,0), (-1,-1), 4), ("RIGHTPADDING", (0,0), (-1,-1), 4),
    ]))
    return t

story = []
# COVER
story += [Spacer(1, 3*cm), P("NeuroNav-X", "Cover"), P("SIH26168 | ISRO | Smart India Hackathon 2026", "Sub"),
    Spacer(1, .3*cm),
    P("AI/ML-based Intelligent Dead Reckoning for Seamless Navigation through GNSS-Denied Conditions", "Sub"),
    Spacer(1, .4*cm), HRFlowable(width="80%"),
    Spacer(1, .4*cm),
    P("Team Learning Guide — hybrid estimator: learned IMU correction feeding an error-state EKF, constrained by vehicle kinematics and a road graph, with calibrated uncertainty. Covers tech stack, dataset quirks, every module, all formulas, baselines B0-B6, Phases 1-16, mobile app, use cases, demo script, and teacher Q&amp;A.", "Sub"),
    Spacer(1, .6*cm), P("How to use: read cover-to-cover once, then use the 1-page cheat-sheet (last page) for revision before judging.", "Cap")]

story += [H("1. Problem & Objectives"), P(
    "<b>Problem.</b> GNSS fails in tunnels, urban canyons, parking basements and intentional denial. A phone's raw inertial navigation (accelerometer + gyro integration) drifts tens of percent within a minute, so navigation stops exactly when it is needed most. <b>Goal:</b> keep a consumer smartphone navigating with <b>drift ratio &lt;10%</b> (final position error / distance travelled) through 10-120 s blackouts, using <b>smartphone-only inputs</b> at deployment."),
    P("<b>Design philosophy (README):</b> 'Hybrid estimator, not a black box.' A tiny learned speed model corrects IMU errors; a classical 6-state error-state EKF (ES-EKF) does the fusion; vehicle kinematics and (optionally) a road graph constrain it; uncertainty is calibrated. Vehicle columns are prefixed <b>ref_</b> and used only as ground truth/supervision — enforced by tests/test_feature_contract.py."),
    H2("Success criteria / Gates"), P("Gate 0: which IO-VNBD sessions are usable (audit). Gates 1-3: baselines + learned speed reproducible end-to-end. Gate 4: map matching (FAILED by design — kept off). Gate 5: runs on a physical phone within latency budget (CLOSED on V2422 ARM64 Android 16, 0.177 ms p95 vs 100 ms budget). Open: a completed below-10% moving field test.")]

story += [H("2. Tech Stack & Architecture"), tbl([
    ["Layer", "Technology", "Why / where used"],
    ["ML / science", "Python 3.10+, PyTorch, numpy, pandas, scipy, matplotlib", "SpeedTCN training (scripts/train_tcn.py), evaluation, plots"],
    ["Model export", "TorchScript, ONNX Runtime, ExecuTorch/XNNPACK (.pte 123 KB)", "Phone inference; verified max diff 4.8e-06 vs eager"],
    ["Android", "Kotlin 2.1.20, Gradle 9.4.1, AGP 8.13, JDK 25, SDK 36", "Logger + live Navigator + benchmark (mobile/)"],
    ["Maps", "Google Maps SDK + MapLibre Native + PMTiles Protomaps v4 (12 MB Greater Noida)", "Offline basemap; needs MAPS_API_KEY in ignored local.properties"],
    ["Roads", "OSM Overpass cache .npz 8.3 MB, cKDTree + Dijkstra", "src/neuronav/mapmatch/"],
    ["Data", "IO-VNBD via Git LFS into data/raw/IO-VNBD/", "Phone S-*.csv + vehicle V-*.csv paired at 10 Hz"],
], widths=[2.6*cm, 4.6*cm, 8.8*cm]),
    H2("Data flow (read as diagram)"), P("Phone IMU (100 Hz) + GNSS (1 Hz) → <b>Calibration</b> (gravity, forward angle, yaw channel, gyro bias) → <b>Features</b> (6 vehicle-frame signals) → <b>SpeedTCN</b> (speed + sigma, 1 Hz) → <b>6-state ES-EKF</b> (position, heading, speed, gyro bias, yaw scale) → <b>BlackoutManager</b> (AIDED/BLACKOUT/REACQUIRING) → <b>TrackSmoother</b> (display-only slew, no teleport) → Map UI (faint estimate + bright display, orange when unaided, confidence circle).", "CodeB")]

story += [H("3. Dataset IO-VNBD — 9 Quirks You Must Know"), P("Phone logs S-*.csv + vehicle logs V-*.csv (same drive, row-for-row at true 10 Hz: position ~9.27 Hz, velocity, heading, wheel speeds, yaw-rate, longitudinal accel to ±0.59 g). After gating: ~6.2 h reliably paired; 7 segments / 8.9 h trainable across drivers A/B/E. No phone session has BOTH good GNSS and live accel — hence pairing + simulated aiding."),
    tbl([
    ["#", "Quirk", "Handling"],
    ["1", "Files concatenate multiple drives (time resets, e.g. S-S2 rows 1863/35185)", "Split on time-reset; else 305,178 km phantom path"],
    ["2", "GPS SPEED (Kmh) is actually m/s", "No /3.6 (MAE 1.8 vs 5.6); guarded by test"],
    ["3", "Phone GNSS sample-and-hold down to 0.1 Hz (median gap 9.0 s)", "Simulate 1 Hz aiding from vehicle ref: 5 m noise, 0.5 m/s speed sigma"],
    ["4", "ORIENTATION cols contradict gravity (corr ~0.00)", "Never used; gravity is attitude reference"],
    ["5", "accel-minus-gravity is high-pass filtered (brake -2.5 shows -0.18)", "Cannot integrate → TCN learns speed from vibration/motor cues"],
    ["6", "Gyro yaw channel differs per family (gy on S, gz on A)", "Detected from data (select_yaw_channel); Phase 13 uses gravity-projected yaw"],
    ["7", "S-A4 stray empty column shifts sensors", "Header-text matching + drop all-NaN cols"],
    ["8", "V-*.csv is dense truth", "All ref_ prefixed; supervision/eval only"],
    ["9", "Phone/vehicle offsets up to ~9 s (one +115 s)", "2 s-smoothed cross-correlation, gate corr>=0.35; shift(-lag); paired 8→14 sessions"],
    ], widths=[0.8*cm, 5.4*cm, 9.8*cm]),
    P("Simulated aiding (src/neuronav/data/gnss_sim.py): subsample truth to 1 Hz, 5 m horizontal noise, accuracy 5 m, bearing Doppler-style 3 deg withheld below 2 m/s. Differencing noisy fixes would give ~40-70 deg heading error — hence Doppler bearing. Blackout windows require mean speed >=5 m/s, distance >=20 m.", "Bull")]

story += [H("4. Code Tour (what lives where)"),
    H2("src/neuronav/data/"), P("<b>loader.py</b> — 18+6 columns, PRODUCTION_FEATURES=[ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z], mag optional/excluded. <b>reference.py</b> — SI scales (km→m ×1000, km/h→m/s /3.6, g→9.80665), estimate_sync_lag coarse-to-fine. <b>gnss_sim.py</b> — aiding + apply_blackout + pick_blackout_windows. <b>own_drive.py</b> — own-drive CSV schema + gap splits + summarise.", "Bull"),
    H2("src/neuronav/calibration/"), P("<b>signals.py</b> — gravity_unit, angular_rate, heading_rate=-sum(omega·ghat) (clockwise positive), horizontal basis e1/e2, tilt. <b>alignment.py</b> — Alignment dataclass, robust_line (MAD trim), select_yaw_channel, estimate_gyro_bias, estimate_alignment (joint regress h1,h2 vs dv/dt → angle=atan2).", "Bull"),
    H2("src/neuronav/fusion/"), P("<b>es_ekf.py</b> — PlanarESEKF (see formulas). <b>blackout.py</b> — BlackoutManager + TrackSmoother. <b>run.py</b> — run_es_ekf / run_raw_ins / constant_velocity_baseline, SPEED_MODE_ACCEL/HOLD/TCN.", "Bull"),
    H2("src/neuronav/models/"), P("<b>dataset.py</b> — 6 features, 6 s windows stride 0.5 s causal label, FeatureScaler (train-only fit). <b>tcn.py</b> — CausalConv1d, TemporalBlock, SpeedTCN, gaussian_nll. <b>predictor.py</b> — batched 4096 sliding windows, affine invert, sigma scaling.", "Bull"),
    H2("src/neuronav/mapmatch/ + evaluation + scripts + mobile"), P("<b>mapmatch:</b> osm.py fetch/cache, graph.py KD-tree + Dijkstra, hmm.py offline Viterbi, online.py causal multi-hypothesis, apply.py 2 Hz post-hoc. <b>evaluation/metrics.py:</b> path_length, drift_ratio, ate_rmse, final_position_error. <b>scripts/:</b> download_data, audit_paired (Gate 0), train_tcn (Gate 2, --held-out B), run_baselines (Gates 1/3), export_model, profile_edge, eval_recovery, demo_replay (finale fallback), analyze_own_drive / analyze_live_blackout. <b>mobile/:</b> nav/*.kt ports (Signals, Calibration, Features, SpeedModel, EsEkf, Blackout, Navigator, LiveSource/ReplaySource, HeadingRateAccumulator, LiveSpeedAdapter, StationaryHold, FieldBlackoutTest), dual-track UI, FullNavigationLoopBenchmark.", "Bull")]

story += [H("5. Math & Formulas (the heart of the project)"),
    H2("5.1 SpeedTCN"), P("Causal conv pads LEFT only: y[t] depends on ≤t. Block: h=Drop(GELU(GN(Conv1(x)))), h=Drop(GELU(GN(Conv2(h)))), out=GELU(h+Res(x)) with GroupNorm(1,C). SpeedTCN: 4 levels, 32 channels, k=3, dropout 0.1, dilations 1,2,4,8. Head: Linear(C,C)→GELU→Linear(C,2); mean=softplus(out0)≥0, logvar=clamp(out1,-6,6). Receptive field RF=1+2·(k-1)·(2^L-1)=1+2·2·15=<b>61 samples = 6.1 s @10 Hz</b>, 24,194 params. Loss Gaussian NLL: <b>0.5·(exp(-logvar)·(y-mean)^2+logvar)</b>. Features: [a_forward, a_lateral, a_vertical=sum(a·up)-9.80665, yaw_rate, a_horiz_mag, jerk]. Skill=<b>1-RMSE/RMSE_mean</b>: B 1-3.23/5.92=0.455, A 1-3.05/5.75=0.470, corr ~0.83. Fused at ~1 Hz (10 Hz double-counts the correlated 6 s window).", "Bull"),
    H2("5.2 Six-state ES-EKF"), P("Nominal <b>x=[pE,pN,psi,v,b_w,s_w]</b> (east, north, heading clockwise from north, speed, gyro bias, yaw-scale error). Error dx with covariance P, inject-then-reset, wrap(a)=(a+π)%2π-π. <b>Propagate:</b> w_deb=yaw_meas-b_w; w=(1+s_w)·w_deb; pE+=v·sin(psi)·dt+0.5·a·sin(psi)·dt²; pN+=v·cos(psi)·dt+0.5·a·cos(psi)·dt²; psi=wrap(psi+w·dt); v+=a·dt. Jacobian F: F[pE,psi]=v·cos·dt, F[pE,v]=sin·dt, F[pN,psi]=-v·sin·dt, F[pN,v]=cos·dt, F[psi,b]=-(1+s_w)·dt, F[psi,s]=w_deb·dt. Q: pos (0.5·0.6·dt²)², heading (0.02²+(0.01·|w|)²)·dt, speed (0.6·dt)²+(0.4²)·dt, bias (2e-4)²·dt, scale (1e-5)²·dt. <b>Update</b> _apply(H,y,R): S=HPH'+R, NIS=y'S⁻¹y rejected beyond 5σ (streak inflates P for re-acquire), K=PH'S⁻¹, Joseph form P=(I-KH)P(I-KH)'+KRK'. Measurements: position σ=max(acc,3.0); speed σ=0.7; Doppler bearing σ=bearing_acc else 0.15 gated speed≥2 m/s (5 native); learned speed σ=max(net_sigma,0.8) only when dark+valid at 1 Hz — never writes position/heading directly (graceful); map anisotropic R=Rot·diag(along²,cross²)·Rot'; ZUPT y=0-v σ=0.05 below 0.3 m/s.", "Bull"),
    H2("5.3 Metrics / calibration / map / blackout"), P("<b>Metrics:</b> path_length=Σ||ref[i]-ref[i-1]||; <b>drift=||est_end-ref_end||/path_length</b> (target &lt;10%); ATE=sqrt(mean||est-ref||²); FPE=||est_end-ref_end||. <b>Calibration:</b> ghat=g/||g||; heading_rate=-omega·ghat; forward angle=atan2(c1,c0) from lstsq([h1,h2], dv/dt); yaw channel = max corr of ∫gyro vs d_bearing. <b>Map HMM:</b> emission -0.5·(d/s)² + heading -w·(1-cos), transition -|route-travelled|/12, Viterbi offline; online multi-hypothesis needs conf≥0.60 for 4 steps and σ≤18 m. <b>Blackout:</b> loss if gap&gt;3 s or acc&gt;30 m; reacquire 3 consistent fixes in 6 s within 40 m; display slew rate=clip(dist/2,40,200) m/s — estimate untouched (snap is Bayes-optimal).", "Bull")]

story += [H("6. Baselines B0-B6 — Results on Held-Out Driver B"), P("Median drift (p90 in brackets) vs 10 Hz vehicle truth. B0 const-velocity floor; B1 raw INS open-loop; B3 ES-EKF classical; <b>B5 TCN+ES-EKF shipped</b>; B4/B4b map off unless --map; B6 adds yaw-scale (deployment_config enables scale only)."),
    tbl([["Blackout","B0 const-vel","B1 raw INS","B3 ES-EKF","B5 TCN+ES-EKF"],
        ["10 s","15.7%","14.8%","9.7% (55.7)","10.5% (34.7)"],
        ["30 s","38.5%","10.0%","10.8% (56.1)","16.3% (32.0)"],
        ["60 s","69.2%","17.9%","15.0% (61.1)","14.0% (34.6)"],
        ["120 s","89.1%","28.9%","26.0% (91.3)","14.7% (51.2)"]],
        widths=[2*cm, 3.4*cm, 3.4*cm, 3.6*cm, 3.6*cm]),
    P("Reading: learned speed <b>halves median drift at 120 s and cuts p90 35-45% at every duration</b>. Speed model RMSE 3.2 vs 5.9 naive (skill 0.46), 24k params. Oracle ablations: on B5, true speed cuts 30/60/120 s 16.3→5.6, 14.0→8.5, 14.7→10.8; both speed+heading ≤0.8% (mechanization sound — remaining error is speed). Map Gate 4 FAILS: needs error &lt; ~17 m road spacing; beyond that confident wrong-road match drags solution (naive online 60 s 27.4→39.7%); gated still loses on B. Recovery jump drops 91-95% (120 s 173.7→8.8 m) with accuracy 10 s later unchanged ±0.3 m.", "Bull")]

story += [H("7. Phases 1-16 (one line each)"), P(
    "1 Screening: classical ladder + 300 s warmup. 2 Learned speed (B5): 24k TCN, 1 Hz fusion, halves 120 s drift. 3 Map: HMM+online, Gate 4 fails → display-snapping only. 4 Edge: TorchScript/ONNX/XNNPACK verified, desktop 0.74 ms (0.7% of 100 ms), INT8 rejected (+7.8% error). 5 Blackout machine + demo_replay.py fallback (no GNSS/vehicle/internet). 6 Physics B6: yaw-scale state (bias∝time, scale∝turn; M_seg00 49.9°=4.4+44.1); horizon/balance off. 7 Kotlin port: parity &lt;0.1 m after fixing bearing_acc (171 m lesson) + decimation. 8 Real-device: V2422 full-loop 0.024/0.177 ms → Gate 5 closed; field protocol. 9 Own-drive evidence pipeline. 10 Retrain with representative validation REGRESSED → deliberately not deployed. 11 One-button raw+diagnostics+summary recorder. 12 Controlled 60 s estimator-only withholding + calibration gate. 13 Native full-rate gravity-projected yaw (fixes 51.5% field blowup → 7.2% counterfactual). 14 Crash/bias/scale fixes; live TCN +4.5-5 m/s bias found. 15 LiveSpeedAdapter learns additive bias from aided pairs. 16 Motion-aware calibration + stationary hold + gap reset (12:04 replay 55.7→25.0%); <b>below-10% completed moving field test still open</b>.", "Bull")]

story += [H("8. Mobile App & Edge"), P("Logger schema: timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,grav_x,grav_y,grav_z,lat,lon,alt,speed,accuracy,bearing,bearing_acc,gps_fresh (monotonic elapsedRealtimeNanos; gps_fresh disambiguates hold; Doppler bearing not differenced). Live Navigator: 100 Hz callbacks → integer decimation → CALIBRATING (200 angles+100 baselines, ~350 s demo) → 6-feat ring → TCN 1 Hz → ES-EKF → manager → smoother. Arm 60 s test: parked → 15 s lead-in → TEST WITHHOLDING (raw kept as hidden ref) → ordinary recovery. UI: Google/MapLibre GN-offline/PMTiles/canvas fallback; faint estimate + bright display (orange unaided); confidence circle; status shows 1σ/distance never 'drift' (no truth live). Benchmark screen: bundled drive 5× through callbacks/cal/feats/TCN/EKF/manager/smoother; run 2× cool+hot. Artifacts per session: nav_drive_100Hz.csv + nav_diagnostics_10Hz.csv + nav_summary.json.", "Bull"),
    H("9. Use Cases & Demo Script"), P("<b>Use cases:</b> tunnels/urban canyons/parking/denial on consumer phones; fleet continuity; offline Greater Noida maps; field data collection (unfiltered accel + 1 Hz GNSS); teaching hybrid-vs-black-box and audit-first ML. <b>Demo (5 min):</b> (1) show aided stable + status; (2) trigger blackout — overlay truth vs raw INS (diverges) vs NeuroNav-X (holds), orange unaided + growing sigma; (3) recovery — estimate snaps, display slews in ~2-4 s, no teleport, sigma collapses; (4) fallback: demo_replay.py replays real sensor CSV through the real estimator with no GNSS/vehicle/connectivity (B 120 s/1236 m: 24.6% vs 48.8% raw, jump 15.5 m, heading 16.6°).", "Bull")]

story += [H("10. Teacher / Judge Q&A (memorize these)"), tbl([
    ["Question", "Model answer (3-4 lines)"],
    ["Why not integrate accel?", "Phone accel is high-pass filtered (brake -2.5 shows -0.18, corr 0.02). TCN learns speed from vibration/motor cues instead."],
    ["Why speed, not position?", "Oracle: true speed fixes most error at all durations; network never writes pos/heading, so failures degrade gracefully via gating."],
    ["Why fuse at 1 Hz?", "6 s window is correlated; 10 Hz double-counts and lost short outages. 1 Hz fixed it."],
    ["Why does map fail?", "Needs error < ~17 m road spacing; beyond, confident wrong-road match drags solution + sigma miscalibrated 0.56-2.15x. Use for display-snapping."],
    ["Why smooth display, not filter?", "Snap is Bayes-optimal; inflating R for cosmetics worsens the estimate. Smoother is display-only."],
    ["Why yaw-scale state?", "Heading error = bias·T + scaleErr·turn; M_seg00 44/50 deg is scale. Helps B p90 -6-7 pts."],
    ["Why not INT8 / Transformer?", "fp32 already 0.7% of budget; INT8 costs 7.8% accuracy. Transformer stays desktop until Android export+latency gates."],
    ["Biggest field lessons?", "bearing_acc missing (171 m), 17% decimation drop, double gyro bias, frozen gz, live TCN +5 bias, 1479 s gap → 929 km sigma (now reset)."],
    ["What is still open?", "A completed below-10% moving field test; disturbed stops + difficult sessions remain."],
    ], widths=[4.2*cm, 11.8*cm])]

story += [H("11. Key Numbers, 2-Minute Pitch, Cheat-Sheet"),
    tbl([["Metric", "Value"],
        ["Speed RMSE", "3.2 m/s vs 5.9 naive (skill 0.46), corr 0.83, 24k params, RF 6.1 s"],
        ["Drift B5 (med/p90)", "10 s 10.5/34.7, 30 s 16.3/32.0, 60 s 14.0/34.6, 120 s 14.7/51.2%"],
        ["Recovery", "Jump -91-95% (120 s 173.7→8.8 m); +10 s accuracy ±0.3 m"],
        ["Latency", "Desktop 0.74 ms; V2422 full-loop 0.024 med / 0.177 p95 ms (0.18% of 100 ms)"],
        ["Parity", "Desktop vs Android <0.1 m on 3 routes × 2 drivers"],
        ["Data", "14 paired (12.1 h), 7 trainable (8.9 h), drivers A/B/E"],
        ["Field", "First controlled 19.2%, regressed 114% → fixed; 3 Sep heading 2.9-4.8 deg; Phase16 replay 55.7→25.0%"],
    ], widths=[4.2*cm, 11.8*cm]),
    H2("2-minute pitch"), P("\"GNSS dies in tunnels and urban canyons. Phone inertial navigation drifts away in a minute because the accelerometer is filtered and the gyro has bias and scale errors. NeuroNav-X is a hybrid: a tiny 24k-parameter causal TCN learns speed from phone vibration with calibrated uncertainty, and a 6-state error-state EKF fuses it with Doppler bearing — never writing position directly so failures degrade gracefully. On a held-out driver it halves 120-second drift from 26% to 14.7% and cuts the worst cases 35-45%; recovery jumps shrink 95% with no teleport thanks to a display-only smoother. It runs in 0.18 milliseconds on a real phone and works fully offline. Map matching was tried and deliberately turned off because it fails beyond 17-metre road spacing — that negative result is documented. What remains is closing the sub-10% moving field test.\"", "Bull"),
    H2("Commands"), P("./envs/bin/python scripts/download_data.py # fetch &nbsp;&nbsp; ./envs/bin/python scripts/audit_paired.py # Gate 0 &nbsp;&nbsp; ./envs/bin/python scripts/train_tcn.py --held-out B # Gate 2 &nbsp;&nbsp; ./envs/bin/python scripts/run_baselines.py --drivers B --checkpoint outputs/checkpoints/speed_tcn_holdout_B.pt # Gates 1/3 &nbsp;&nbsp; ./envs/bin/python -m pytest tests/ # guards", "CodeB"),
    H2("Glossary"), P("GNSS= satellite positioning; INS= inertial navigation; dead reckoning= integrate motion from last fix; ES-EKF= error-state extended Kalman filter (estimates errors, inject-then-reset); TCN= temporal convolutional network (causal dilated conv); HMM= hidden Markov model (map matching); ATE= absolute trajectory error; Doppler bearing= velocity-derived heading; ZUPT= zero-velocity update; NIS= normalized innovation squared (outlier gate); LODO= leave-one-driver-out.", "Bull")]

doc = SimpleDocTemplate(OUT, pagesize=A4, topMargin=1.6*cm, bottomMargin=1.6*cm, leftMargin=1.8*cm, rightMargin=1.8*cm, title="NeuroNav-X Learning Guide", author="Muse Spark")
doc.build(story)
print(f"Wrote {OUT}")
