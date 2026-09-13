"""Compare speed and heading evidence across three completed Waynova field rides."""
from __future__ import annotations

import argparse
import bisect
import csv
import json
import math
from pathlib import Path
from statistics import mean

def number(value: str | None) -> float:
    try:
        return float(value) if value not in (None, "") else math.nan
    except ValueError:
        return math.nan


def rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = fraction * (len(ordered) - 1)
    lower = int(math.floor(index))
    upper = int(math.ceil(index))
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def one(summary_path: Path) -> dict:
    summary = json.loads(summary_path.read_text())
    directory = summary_path.parent
    raw_rows = rows(directory / summary["raw_file"])
    diagnostic_rows = rows(directory / summary["diagnostics_file"])
    fresh = sorted(
        (r for r in raw_rows if number(r.get("gps_fresh")) == 1 and math.isfinite(number(r.get("speed")))),
        key=lambda r: number(r.get("timestamp_ms")),
    )
    fresh_times = [number(r.get("timestamp_ms")) for r in fresh]
    aligned: list[dict[str, str]] = []
    for row in sorted((r for r in diagnostic_rows if r.get("phase") == "NAVIGATING"),
                      key=lambda r: number(r.get("timestamp_ms"))):
        timestamp = number(row.get("timestamp_ms"))
        insertion = bisect.bisect_left(fresh_times, timestamp)
        choices = [i for i in (insertion - 1, insertion) if 0 <= i < len(fresh)]
        if not choices:
            continue
        nearest = min(choices, key=lambda i: abs(fresh_times[i] - timestamp))
        if abs(fresh_times[nearest] - timestamp) <= 1500:
            aligned.append({**row, "gps_speed": fresh[nearest]["speed"],
                            "gps_bearing": fresh[nearest].get("bearing", "")})
    withholding = [r for r in aligned if r.get("test_phase") == "WITHHOLDING"]
    aided = [r for r in aligned if r.get("mode") == "AIDED" and r.get("test_phase") != "WITHHOLDING"]

    def speed_stats(frame: list[dict[str, str]]) -> dict:
        result: dict[str, float | int | None] = {"samples": len(frame)}
        for column in ("learned_speed_ms", "adapted_speed_ms", "speed_ms"):
            valid = [(number(r.get(column)), number(r.get("gps_speed"))) for r in frame]
            valid = [(prediction, gps) for prediction, gps in valid if math.isfinite(prediction) and math.isfinite(gps)]
            errors = [abs(prediction - gps) for prediction, gps in valid]
            result[f"{column}_mean"] = mean(prediction for prediction, _ in valid) if valid else None
            result[f"{column}_mae"] = mean(errors) if errors else None
            result[f"{column}_rmse"] = math.sqrt(mean(error * error for error in errors)) if errors else None
            result[f"{column}_p90_abs_error"] = percentile(errors, 0.9)
        for column in ("learned_sigma_ms", "adapted_speed_sigma_ms", "speed_model_residual_sigma_ms"):
            valid = [number(r.get(column)) for r in frame]
            valid = [value for value in valid if math.isfinite(value)]
            result[f"{column}_mean"] = mean(valid) if valid else None
        gps_speeds = [number(r.get("gps_speed")) for r in frame]
        gps_speeds = [value for value in gps_speeds if math.isfinite(value)]
        result["gps_speed_mean"] = mean(gps_speeds) if gps_speeds else None
        heading_errors = []
        for row in frame:
            gps_speed = number(row.get("gps_speed"))
            bearing = number(row.get("gps_bearing"))
            heading = number(row.get("heading_deg"))
            if gps_speed >= 2 and math.isfinite(bearing) and math.isfinite(heading):
                heading_errors.append(abs((heading - bearing + 180) % 360 - 180))
        result["heading_mae_deg"] = mean(heading_errors) if heading_errors else None
        return result

    return {
        "session_id": summary["session_id"],
        "aided": speed_stats(aided),
        "withholding": speed_stats(withholding),
        "recorded_end_error_m": summary["controlled_blackout"].get("end_reference_error_m"),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("summaries", nargs="+", type=Path)
    args = parser.parse_args()
    payload = json.dumps([one(path) for path in args.summaries], indent=2, allow_nan=False)
    if args.output:
        args.output.write_text(payload + "\n")
    print(payload)
