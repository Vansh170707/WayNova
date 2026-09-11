# COPY-PASTE PROMPT FOR CHATGPT — NeuroNav-X Learning PDF Generator

Copy everything below the line and paste into ChatGPT (plus upload your project files).

---

You are a senior technical writer + ML engineer + hackathon mentor. Your task is to create a COMPREHENSIVE LEARNING PDF for my team on our project **NeuroNav-X — SIH26168**.

**Context you must assume (even if I don't upload every file):**
Project = "AI/ML-based intelligent dead reckoning for seamless navigation through GNSS-denied conditions (ISRO, Smart India Hackathon 2026)."
Core idea = "Hybrid estimator, not a black box: learned IMU error correction feeding an error-state EKF, constrained by vehicle kinematics and (later) a road graph, with calibrated uncertainty."
Stack = Python 3.10+, PyTorch (SpeedTCN), numpy, pandas, matplotlib, scipy, pyyaml, tqdm | Android Kotlin 2.1.20, Gradle 9.4.1, ONNX Runtime Android, MapLibre Native + PMTiles offline maps, Google Maps SDK | Dataset = IO-VNBD phone (S-*.csv) + vehicle (V-*.csv) paired at 10Hz | Metrics = drift ratio, ATE, FPE | Baselines B0 const-velocity → B1 raw INS → B2 calibrated INS → B3 ES-EKF → B4/B4b + map → B5 TCN+ES-EKF (shipped) → B6 + physics/yaw-scale | Phases 1-16 implemented, Gates 0-5, physical test device V2422 ARM64 Android 16.

**Files I will upload (use all of them if present, otherwise infer from context + ask me for missing):**
1. README.md
2. docs/data_audit.md, docs/phase2_learned_speed.md, docs/phase3_map_matching.md, docs/phase4_edge_deployment.md, docs/phase5_blackout_state_machine.md, docs/phase6_physics_guided.md, docs/phase7_navigation_ui.md, docs/phase8_real_device_validation.md, docs/phase9_own_drive_evidence.md, docs/phase10_representative_validation.md, docs/phase11_live_field_recording.md, docs/phase12_controlled_field_blackout.md, docs/phase13_native_heading.md, docs/phase14_field_robustness.md, docs/phase15_live_speed_adaptation.md, docs/phase16_motion_and_interruptions.md
3. src/neuronav/data/ (loader.py, reference.py, gnss_sim.py, blackout.py, own_drive.py)
4. src/neuronav/calibration/ (signals.py, alignment.py)
5. src/neuronav/fusion/ (es_ekf.py, blackout.py, run.py, raw_ins.py)
6. src/neuronav/models/ (dataset.py, tcn.py, predictor.py)
7. src/neuronav/mapmatch/ (osm.py, graph.py, hmm.py, online.py, apply.py)
8. src/neuronav/evaluation/metrics.py
9. scripts/ (download_data.py, audit_paired.py, train_tcn.py, run_baselines.py, plot_baselines.py, export_model.py, eval_recovery.py, demo_replay.py)
10. mobile/README.md, mobile/OFFLINE_MAPS.md
11. requirements.txt, pyproject.toml

**What to produce:**
A structured learning PDF (12-15 pages equivalent, ~5000-7000 words) with table of contents, headings, tables, formulas, diagrams described in text, and a 1-page cheat-sheet at the end. Tone = simple enough for a beginner teammate, but technically correct enough to answer a strict hackathon judge/teacher. Define every jargon term on first use.

**Mandatory chapters (do not skip any):**

1. Cover + One-paragraph elevator pitch + Problem statement (why GNSS fails: tunnels, urban canyons, denial; why phone IMU alone drifts)
2. Objectives, success criteria (smartphone-only inputs, vehicle columns `ref_` only for supervision, drift ratio <10% target, Gates 0-5) + Design philosophy (hybrid vs black-box)
3. Tech stack table (language, library, why it was chosen, where used) + System architecture diagram (describe in words + ASCII flow: Phone IMU/GNSS → Calibration → Features → SpeedTCN → ES-EKF → Blackout Manager → Display Smoother → Map UI)
4. Dataset IO-VNBD deep-dive + all 9 quirks table (time-reset concatenation, GPS SPEED actually m/s, 0.1Hz GNSS hold, bad ORIENTATION, filtered accel, per-family gyro yaw channel gy vs gz, S-A4 stray column, V-*.csv row-aligned truth, sync lags up to 115s) + how each is handled in code + simulated 1Hz GNSS aiding (5m noise, 0.5 m/s speed sigma, 3-deg Doppler bearing)
5. Module-by-module code tour (for each file in src/, scripts/, mobile/: purpose, key functions, inputs/outputs)
6. Math & Formulas section (render clearly):
   - TCN: causal conv y=Conv1d(pad_left), TemporalBlock with GroupNorm+GELU+residual, SpeedTCN 4 levels ch=32 k=3 dilations 1,2,4,8, RF=1+2*(k-1)*(2^L-1)=61 samples=6.1s, head Linear→GELU→Linear→2 (mean=softplus, logvar clamp [-6,6]), loss Gaussian NLL 0.5*(exp(-logvar)*(y-mean)^2+logvar), 24,194 params, 6 features [a_forward,a_lateral,a_vertical,yaw_rate,a_horiz_mag,jerk], 6s window stride 0.5s causal label, FeatureScaler, sigma calibration, skill=1-RMSE/RMSE_mean
   - ES-EKF: nominal x=[pE,pN,psi,v,b_w,s_w], error dx, propagate pE+=v*sin(psi)*dt+0.5*a*sin(psi)*dt^2 etc., psi=wrap(psi+w*dt), w=(1+s_w)*(yaw_meas-b_w), F Jacobian entries, Q with sigma_a=0.6 sigma_g=0.02 sigma_rate=0.01 sigma_vproc=0.4, updates for position/speed/bearing/learned-speed/map/ZUPT with H,y,R, NIS 5-sigma gating + Joseph form, init P=diag(9,9,0.2^2,1^2,1e-4,...)
   - Metrics: path_length=sum||ref[i]-ref[i-1]||, drift=||est_end-ref_end||/path_length, ATE=sqrt(mean||est-ref||^2), FPE
   - Calibration: ghat=g/||g||, heading_rate=-omega·ghat, horizontal basis e1,e2, forward angle atan2(c1,c0) via lstsq, yaw channel selection via integrated gyro vs d_bearing
   - Map HMM: emission -0.5*(d/s)^2, heading -w*(1-cos), transition -|route-travelled|/12, Viterbi + online multi-hypothesis
   - Blackout machine: NavMode AIDED/BLACKOUT/REACQUIRING, loss if gap>3s or acc>30m, reacquire 3 fixes/6s/40m, TrackSmoother rate=clip(dist/2.0,40,200)
7. Baseline ladder B0-B6 results table (held-out driver B median/p90: 10s B5 10.5%/34.7%, 30s 16.3%/32.0%, 60s 14.0%/34.6%, 120s 14.7%/51.2% vs B3/B1/B0; speed RMSE 3.2 vs 5.9 naive skill 0.46) + what each baseline proves + oracle ablations (true speed vs true heading) + why map Gate 4 fails (needs error<17m road spacing)
8. Phases 1-16 timeline (one paragraph each: what was built, what broke, key number, lesson)
9. Edge deployment (TorchScript/ONNX/ExecuTorch diffs 0.00/1.91e-06/4.77e-06, desktop 0.74ms 0.7% of 100ms, phone full-loop 0.024ms median 0.177ms p95, why INT8 rejected +7.8%) + Mobile app (logger schema, live Navigator loop, CALIBRATING gate, Arm 60s test, dual-track UI, benchmark screen, offline maps)
10. Use cases (tunnels, urban canyons, fleet, offline, data collection) + Live demo script step-by-step (aided → blackout → 3 tracks → recovery slew no teleport → fallback demo_replay.py with no GNSS/vehicle/internet, numbers 120s/1236m 24.6% vs 48.8% raw)
11. Teacher/Judge Q&A — 15 hard questions + 3-4 line model answers (Why not integrate accel? Why speed not position? Why 1Hz fusion? Why map fails? Why smooth display not filter? Why yaw-scale? Why not INT8/Transformer? Why paired stats? Biggest field lessons? What is open?)
12. Key numbers dashboard (single table of all headline metrics) + Glossary (GNSS, INS, dead reckoning, ES-EKF, TCN, HMM, ATE, drift ratio, Doppler bearing, ZUPT, etc.) + 1-page cheat-sheet (formulas + commands: download_data, audit_paired, train_tcn --held-out B, run_baselines, pytest)
13. Limitations & future work (below-10% field test open, disturbed stops, difficult sessions) + References (file paths for every claim)

**Formatting rules:**
- Use Markdown headings, tables, bullet lists, code blocks for commands and formulas.
- Every metric must have units and context (e.g. "14.7% median drift at 120s on held-out driver B").
- Flag negative results explicitly (map fails Gate 4, Phase 10 retrain not deployed, INT8 rejected).
- End with "How to explain this project in 2 minutes" script for hackathon presentation.

Now generate the full document content in Markdown so I can export to PDF. If any uploaded file is missing, state your assumption clearly and continue — do not stop and ask.
