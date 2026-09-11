"""Score one Phase 12 controlled field outage from its saved Android artifacts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd


def _finite(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if np.isfinite(number) else None


def analyse_summary(summary_path: Path) -> dict:
    summary = json.loads(summary_path.read_text())
    test = summary.get("controlled_blackout") or {}
    phase = str(test.get("phase", "OFF"))
    result = {
        "session_id": summary.get("session_id"),
        "summary": str(summary_path.resolve()),
        "phase": phase,
        "status": "not_run" if phase == "OFF" else "incomplete",
    }
    if phase == "OFF":
        return result

    diagnostics_name = summary.get("diagnostics_file")
    if not diagnostics_name:
        result["problem"] = "summary has no diagnostics_file"
        return result
    diagnostics_path = summary_path.parent / diagnostics_name
    result["diagnostics"] = str(diagnostics_path.resolve())
    if not diagnostics_path.exists():
        result["problem"] = f"missing diagnostics: {diagnostics_path}"
        return result

    diagnostics = pd.read_csv(diagnostics_path)
    required = {
        "test_phase", "test_elapsed_s", "test_withheld_fixes",
        "reference_east_m", "reference_north_m", "east_m", "north_m",
        "mode", "blackout_distance_m",
    }
    missing = sorted(required - set(diagnostics.columns))
    if missing:
        result["problem"] = f"diagnostics missing columns {missing}"
        return result

    withheld = diagnostics.loc[
        diagnostics["test_phase"].eq("WITHHOLDING")
        & diagnostics["reference_east_m"].notna()
        & diagnostics["reference_north_m"].notna()
        & diagnostics["east_m"].notna()
        & diagnostics["north_m"].notna()
    ].copy()
    # A reference callback is held across ~10 diagnostic rows. Count each new coordinate
    # once so reference rates and path length describe phone fixes, not held values.
    if len(withheld):
        changed = withheld[["reference_east_m", "reference_north_m"]].diff().abs().sum(axis=1)
        changed.iloc[0] = np.inf
        references = withheld.loc[changed > 1e-6].copy()
    else:
        references = withheld

    requested = _finite(test.get("requested_duration_s")) or 0.0
    actual = _finite(test.get("actual_duration_s")) or 0.0
    error_end = _finite(test.get("end_reference_error_m"))
    error_max = _finite(test.get("max_reference_error_m"))
    reference_distance = None
    incremental_drift = None
    drift_ratio = None
    if len(references) >= 2:
        reference_xy = references[["reference_east_m", "reference_north_m"]].to_numpy(float)
        estimate_xy = references[["east_m", "north_m"]].to_numpy(float)
        reference_distance = float(np.linalg.norm(np.diff(reference_xy, axis=0), axis=1).sum())
        error_vectors = estimate_xy - reference_xy
        incremental_drift = float(np.linalg.norm(error_vectors[-1] - error_vectors[0]))
        if reference_distance > 1.0:
            drift_ratio = 100.0 * incremental_drift / reference_distance

    # Phase 15 live builds expose both sides of speed adaptation. Keep this optional so
    # every pre-Phase-15 field artifact remains analysable by the same command.
    speed_adapter = None
    speed_columns = {
        "learned_speed_ms", "adapted_speed_ms", "adapted_speed_sigma_ms",
        "speed_model_bias_ms", "speed_model_calibration_samples",
    }
    if speed_columns.issubset(diagnostics.columns):
        dark = diagnostics.loc[diagnostics["test_phase"].eq("WITHHOLDING")]
        if len(dark):
            def finite_mean(column):
                values = pd.to_numeric(dark[column], errors="coerce")
                values = values[np.isfinite(values)]
                return float(values.mean()) if len(values) else None

            samples = pd.to_numeric(
                dark["speed_model_calibration_samples"], errors="coerce"
            ).dropna()
            speed_adapter = {
                "ready": bool(len(samples) and samples.max() >= 3),
                "calibration_samples": int(samples.max()) if len(samples) else 0,
                "bias_ms": finite_mean("speed_model_bias_ms"),
                "raw_model_speed_mean_ms": finite_mean("learned_speed_ms"),
                "adapted_speed_mean_ms": finite_mean("adapted_speed_ms"),
                "adapted_sigma_mean_ms": finite_mean("adapted_speed_sigma_ms"),
            }

    modes = diagnostics["mode"].dropna().astype(str)
    phases = diagnostics["test_phase"].dropna().astype(str)
    writer_ok = summary.get("raw_writer_error") is None and \
        summary.get("diagnostics_writer_error") is None
    recovered = bool(test.get("recovered", False))
    duration_ok = requested >= 5.0 and actual >= 0.95 * requested
    # RECOVERING is a FieldBlackoutPhase; the navigation state is REACQUIRING.
    transition_ok = "BLACKOUT" in set(modes) and "REACQUIRING" in set(modes)
    reference_ok = len(references) >= max(int(actual * 0.5), 3)
    completion_checks = {
        "phase_complete": phase == "COMPLETE",
        "recovered": recovered,
        "duration_ok": duration_ok,
        "mode_transitions_ok": transition_ok,
        "reference_ok": reference_ok,
        "writers_ok": writer_ok,
    }
    complete = all(completion_checks.values())

    result.update({
        "status": "complete" if complete else "incomplete",
        "requested_duration_s": requested,
        "actual_duration_s": actual,
        "withheld_fixes": int(test.get("withheld_fixes", 0)),
        "reference_fixes": int(len(references)),
        "reference_distance_m": reference_distance,
        "absolute_end_error_m": error_end,
        "max_absolute_error_m": error_max,
        "incremental_outage_drift_m": incremental_drift,
        "incremental_drift_ratio_pct": drift_ratio,
        "under_10pct_candidate": bool(drift_ratio is not None and drift_ratio < 10.0),
        "completed_under_10pct": bool(complete and drift_ratio is not None and drift_ratio < 10.0),
        "sensor_gap_s": _finite(summary.get("sensor_gap_s")),
        "recovered": recovered,
        "reacquire_s": _finite(test.get("reacquire_s")),
        "saw_blackout": "BLACKOUT" in set(modes),
        "saw_reacquiring": "REACQUIRING" in set(modes),
        "writer_ok": writer_ok,
        "completion_checks": completion_checks,
        "diagnostic_test_phases": list(dict.fromkeys(phases)),
    })
    if speed_adapter is not None:
        result["speed_model_adapter"] = speed_adapter
    return result


def print_report(report: dict) -> None:
    print(f"session {report.get('session_id')}: {report['status']}")
    if report["status"] == "not_run":
        print("  no controlled blackout was armed")
        return
    if "problem" in report:
        print(f"  {report['problem']}")
        return
    print(
        "  outage %.1f/%.1f s | %d fixes withheld | %d hidden references"
        % (
            report["actual_duration_s"], report["requested_duration_s"],
            report["withheld_fixes"], report["reference_fixes"],
        )
    )
    if report["incremental_outage_drift_m"] is not None:
        print(
            "  incremental drift %.1f m over %.1f m reference travel (%.1f%%)"
            % (
                report["incremental_outage_drift_m"],
                report["reference_distance_m"],
                report["incremental_drift_ratio_pct"],
            )
        )
    print(
        "  absolute end error %s m | max %s m | recovered %s in %s s"
        % (
            "n/a" if report["absolute_end_error_m"] is None else
                f"{report['absolute_end_error_m']:.1f}",
            "n/a" if report["max_absolute_error_m"] is None else
                f"{report['max_absolute_error_m']:.1f}",
            report["recovered"],
            "n/a" if report["reacquire_s"] is None else f"{report['reacquire_s']:.1f}",
        )
    )
    gate = report["incremental_drift_ratio_pct"]
    if gate is not None:
        verdict = "ABOVE TARGET" if gate >= 10 else (
            "CANDIDATE PASS" if report["completed_under_10pct"] else "PARTIAL ONLY (test incomplete)"
        )
        print("  <10% phone-reference target: " + verdict)
    adapter = report.get("speed_model_adapter")
    if adapter:
        print(
            "  speed adapter %s (%d pairs): bias %s m/s | raw %s -> adapted %s m/s"
            % (
                "ready" if adapter["ready"] else "not ready",
                adapter["calibration_samples"],
                "n/a" if adapter["bias_ms"] is None else f"{adapter['bias_ms']:.2f}",
                "n/a" if adapter["raw_model_speed_mean_ms"] is None else
                    f"{adapter['raw_model_speed_mean_ms']:.2f}",
                "n/a" if adapter["adapted_speed_mean_ms"] is None else
                    f"{adapter['adapted_speed_mean_ms']:.2f}",
            )
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("summaries", nargs="+", type=Path)
    parser.add_argument("--json", action="store_true", help="emit JSON instead of text")
    args = parser.parse_args()
    reports = [analyse_summary(path) for path in args.summaries]
    if args.json:
        print(json.dumps(reports, indent=2))
    else:
        for index, report in enumerate(reports):
            if index:
                print()
            print_report(report)
