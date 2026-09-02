import json

import pandas as pd

from scripts.analyze_live_blackout import analyse_summary


def write_session(tmp_path, phase="COMPLETE", duration_s=3):
    diagnostics = pd.DataFrame({
        "test_phase": ["ARMED", "WITHHOLDING", "WITHHOLDING", "WITHHOLDING",
                       "RECOVERING", "COMPLETE"],
        "test_elapsed_s": [0, 1, 2, 3, 3, 3],
        "test_withheld_fixes": [0, 1, 2, 3, 3, 3],
        "reference_east_m": [None, 0, 10, 20, 30, 30],
        "reference_north_m": [None, 0, 0, 0, 0, 0],
        "east_m": [0, 1, 12, 23, 33, 30],
        "north_m": [0, 0, 0, 0, 0, 0],
        "mode": ["AIDED", "BLACKOUT", "BLACKOUT", "BLACKOUT",
                 "REACQUIRING", "AIDED"],
        "blackout_distance_m": [0, 0, 10, 20, 30, 0],
    })
    diagnostics.to_csv(tmp_path / "nav_diagnostics_1.csv", index=False)
    summary = {
        "session_id": 1,
        "diagnostics_file": "nav_diagnostics_1.csv",
        "raw_writer_error": None,
        "diagnostics_writer_error": None,
        "controlled_blackout": {
            "phase": phase,
            "requested_duration_s": duration_s,
            "actual_duration_s": duration_s,
            "withheld_fixes": 3,
            "end_reference_error_m": 3,
            "max_reference_error_m": 3,
            "recovered": phase == "COMPLETE",
            "reacquire_s": 2,
        },
    }
    path = tmp_path / "nav_summary_1.json"
    path.write_text(json.dumps(summary))
    return path


def test_scores_incremental_drift_and_recovery(tmp_path):
    report = analyse_summary(write_session(tmp_path))
    # Error vector grows from +1 m to +3 m: incremental drift is 2 m over 20 m.
    assert report["incremental_outage_drift_m"] == 2
    assert report["incremental_drift_ratio_pct"] == 10
    assert report["recovered"]
    # The fixture is deliberately only 3 s, below the production 5 s validity floor.
    assert report["status"] == "incomplete"


def test_reports_session_without_controlled_test(tmp_path):
    path = tmp_path / "nav_summary_2.json"
    path.write_text(json.dumps({"session_id": 2, "controlled_blackout": {"phase": "OFF"}}))
    assert analyse_summary(path)["status"] == "not_run"


def test_completed_session_recognises_reacquiring_navigation_mode(tmp_path):
    report = analyse_summary(write_session(tmp_path, duration_s=6))
    assert report["status"] == "complete"
    assert report["completion_checks"]["mode_transitions_ok"]
    # Protocol completion and the accuracy threshold are deliberately independent.
    assert not report["under_10pct_candidate"]
