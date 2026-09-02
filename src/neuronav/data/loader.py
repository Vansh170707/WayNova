"""IO-VNBD smartphone (S-*.csv) ingestion.

Field audit findings that this loader encodes (see docs/data_audit.md):

1. Several session files concatenate MULTIPLE drives; `TIME SINCE START (ms)`
   resets to ~0 at each boundary. Sorting such a file by timestamp interleaves
   distinct drives and corrupts the trajectory, so segments are split instead.
2. The `GPS SPEED (Kmh)` header is wrong -- the values are metres per second.
   Validated against GNSS-derived speed (MAE 1.8 m/s as m/s vs 5.6 m/s as km/h).
3. GNSS columns are sample-and-hold at a much lower rate than the 10 Hz IMU
   (as slow as 0.1 Hz on some segments). Rows carrying a genuinely new fix are
   flagged in `gnss_new` so filters update only on real measurements.
4. The ORIENTATION (Yaw/Pitch/Roll) columns are NOT consistent with the gravity
   vector under any standard Android Euler convention (correlation ~0.00 against
   gravity-derived tilt), so they are loaded but excluded from the trusted set.
   The GRAVITY columns ARE consistent with the accelerometer (0.44 deg apart)
   and are the reliable attitude reference.
"""
import re

import numpy as np
import pandas as pd

# Canonical IO-VNBD smartphone CSV column order. The header text mixes UTF-8 and
# Latin-1 byte sequences across sessions, so columns are assigned positionally and
# the file is read as latin-1 to avoid decode errors.
#
# Two logger variants exist in the dataset: an 18-column form ending at the gyro,
# and a 24-column form that additionally carries magnetometer and orientation.
IOVNBD_BASE_COLUMNS = [
    "gps_lat", "gps_lon", "gps_alt", "gps_speed", "gps_accuracy_m",
    "gps_orientation_deg", "gps_satellites", "time_since_start_ms", "date_str",
    "accel_x", "accel_y", "accel_z",
    "gravity_x", "gravity_y", "gravity_z",
    "gyro_yaw", "gyro_pitch", "gyro_roll",
]
IOVNBD_EXTENDED_COLUMNS = [
    "mag_x", "mag_y", "mag_z",
    "orientation_yaw_deg", "orientation_pitch_deg", "orientation_roll_deg",
]
IOVNBD_RAW_COLUMNS = IOVNBD_BASE_COLUMNS + IOVNBD_EXTENDED_COLUMNS

# Smartphone-only production feature contract (blueprint Section 5.1). Vehicle-extracted
# (V-*.csv) channels are never read by this module.
#
# Magnetometer is deliberately NOT in the core set: it is absent from the 18-column
# logger variant and is flagged Medium/High risk in the blueprint's register (in-vehicle
# magnetic disturbance), so it stays optional and quality-gated.
PRODUCTION_FEATURES = [
    "ax", "ay", "az", "gx", "gy", "gz", "grav_x", "grav_y", "grav_z",
]
OPTIONAL_FEATURES = ["mx", "my", "mz"]

MIN_SEGMENT_ROWS = 600  # ~1 min at 10 Hz; shorter fragments are not useful


def _normalize_header(name: str) -> str:
    """Lowercase, strip units/punctuation so mangled encodings still match."""
    s = str(name).lower()
    s = re.sub(r"\(.*?\)", " ", s)        # drop unit parentheticals
    s = re.sub(r"[^a-z0-9]+", " ", s)     # drop mojibake and punctuation
    return " ".join(s.split())


# (canonical name, predicate on the normalized header)
_COLUMN_MATCHERS = [
    ("gps_lat", lambda s: "latitude" in s),
    ("gps_lon", lambda s: "longitude" in s),
    ("gps_alt", lambda s: "altitude" in s),
    ("gps_speed", lambda s: "speed" in s),
    ("gps_accuracy_m", lambda s: "accuracy" in s),
    ("gps_orientation_deg", lambda s: s.startswith("gps orientation")),
    ("gps_satellites", lambda s: "satellites" in s),
    ("time_since_start_ms", lambda s: "time since start" in s),
    ("date_str", lambda s: s.startswith("date")),
    ("accel_x", lambda s: "accelerometer x" in s),
    ("accel_y", lambda s: "accelerometer y" in s),
    ("accel_z", lambda s: "accelerometer z" in s),
    ("gravity_x", lambda s: "gravity x" in s),
    ("gravity_y", lambda s: "gravity y" in s),
    ("gravity_z", lambda s: "gravity z" in s),
    ("gyro_yaw", lambda s: "gyroscope yaw" in s),
    ("gyro_pitch", lambda s: "gyroscope pitch" in s),
    ("gyro_roll", lambda s: "gyroscope roll" in s),
    ("mag_x", lambda s: "magnetic field x" in s),
    ("mag_y", lambda s: "magnetic field y" in s),
    ("mag_z", lambda s: "magnetic field z" in s),
    ("orientation_yaw_deg", lambda s: "orientation yaw" in s and not s.startswith("gps")),
    ("orientation_pitch_deg", lambda s: "orientation pitch" in s and not s.startswith("gps")),
    ("orientation_roll_deg", lambda s: "orientation roll" in s and not s.startswith("gps")),
]

REQUIRED_COLUMNS = [
    "gps_lat", "gps_lon", "time_since_start_ms",
    "accel_x", "accel_y", "accel_z", "gyro_yaw", "gyro_pitch", "gyro_roll",
]


def _read_and_resolve_columns(path: str) -> pd.DataFrame:
    """Read a session CSV and map its headers to canonical names.

    Header text varies across logger variants: 18- vs 24-column layouts, inconsistent
    spacing, mixed UTF-8/Latin-1 unit symbols, and at least one session (S-A4) with a
    stray empty column that makes positional slicing silently misalign every sensor.
    Matching on normalized header text handles all of these uniformly.
    """
    header_names = [c for c in pd.read_csv(path, nrows=0, encoding="latin-1").columns
                    if not str(c).startswith("Unnamed:")]
    data = pd.read_csv(path, header=None, skiprows=1, encoding="latin-1", low_memory=False)

    # Some sessions (e.g. S-A4) emit an extra empty field per data row that the header
    # does not name, which shifts every subsequent sensor one column to the right.
    # Drop surplus all-empty columns so names line up with the values again.
    if data.shape[1] > len(header_names):
        surplus = data.shape[1] - len(header_names)
        empty_cols = [c for c in data.columns if data[c].isna().all()]
        data = data.drop(columns=empty_cols[:surplus])
    if data.shape[1] < len(header_names):
        header_names = header_names[:data.shape[1]]
    data = data.iloc[:, :len(header_names)]
    data.columns = header_names

    resolved, used = {}, set()
    for canonical, matches in _COLUMN_MATCHERS:
        for original in header_names:
            if original in used:
                continue
            if matches(_normalize_header(original)):
                resolved[canonical] = original
                used.add(original)
                break

    missing = [c for c in REQUIRED_COLUMNS if c not in resolved]
    if missing:
        raise ValueError(f"{path}: could not resolve required columns {missing}")

    raw = data[list(resolved.values())].rename(columns={v: k for k, v in resolved.items()})

    for col in IOVNBD_RAW_COLUMNS:
        if col not in raw.columns:
            raw[col] = np.nan
        elif col not in ("gps_satellites", "date_str"):
            raw[col] = pd.to_numeric(raw[col], errors="coerce")
    return raw


def _split_indices_on_time_reset(t_ms: np.ndarray) -> list[tuple[int, int]]:
    """Return [start, end) row ranges, split wherever the timestamp goes backwards."""
    resets = np.flatnonzero(np.diff(t_ms) < 0) + 1
    bounds = np.concatenate([[0], resets, [len(t_ms)]])
    return [(int(a), int(b)) for a, b in zip(bounds[:-1], bounds[1:])]


def _mark_new_gnss(df: pd.DataFrame) -> pd.Series:
    """True on rows where the GNSS fix differs from the previous row (a real update)."""
    changed = (df["lat"].diff() != 0) | (df["lon"].diff() != 0)
    changed.iloc[0] = True
    return changed & df["lat"].notna() & df["lon"].notna()


def load_iovnbd_segments(path: str, session_id: str) -> list[pd.DataFrame]:
    """Load one IO-VNBD smartphone CSV into a list of contiguous, single-drive segments.

    Each returned frame is one uninterrupted recording with a monotonic `timestamp`
    (seconds) and its own `route_id` -- the unit used for train/val/test split hygiene.
    """
    raw = _read_and_resolve_columns(path)

    t_ms = raw["time_since_start_ms"].to_numpy()
    segments = []
    for seg_i, (a, b) in enumerate(_split_indices_on_time_reset(t_ms)):
        chunk = raw.iloc[a:b]
        if len(chunk) < MIN_SEGMENT_ROWS:
            continue

        df = pd.DataFrame({
            "timestamp": chunk["time_since_start_ms"].astype(float) / 1000.0,
            "ax": chunk["accel_x"], "ay": chunk["accel_y"], "az": chunk["accel_z"],
            "gx": chunk["gyro_yaw"], "gy": chunk["gyro_pitch"], "gz": chunk["gyro_roll"],
            "mx": chunk["mag_x"], "my": chunk["mag_y"], "mz": chunk["mag_z"],
            "grav_x": chunk["gravity_x"], "grav_y": chunk["gravity_y"], "grav_z": chunk["gravity_z"],
            # retained for auditing only -- see note 4 above
            "yaw_deg": chunk["orientation_yaw_deg"],
            "pitch_deg": chunk["orientation_pitch_deg"],
            "roll_deg": chunk["orientation_roll_deg"],
            "lat": chunk["gps_lat"], "lon": chunk["gps_lon"], "alt": chunk["gps_alt"],
            "speed": chunk["gps_speed"],          # already m/s despite the header
            "accuracy": chunk["gps_accuracy_m"],
            "gps_heading_deg": chunk["gps_orientation_deg"],
        }).reset_index(drop=True)

        df = df[df["timestamp"].diff().fillna(1) > 0].reset_index(drop=True)
        if len(df) < MIN_SEGMENT_ROWS:
            continue

        df["gnss_new"] = _mark_new_gnss(df)
        df["route_id"] = f"{session_id}_seg{seg_i:02d}"
        df["blackout_mask"] = False
        segments.append(df)

    return segments


def load_iovnbd_session(path: str, route_id: str = None) -> pd.DataFrame:
    """Convenience wrapper returning the single longest segment of a session file."""
    session_id = route_id or path.split("/")[-1].replace(".csv", "")
    segments = load_iovnbd_segments(path, session_id)
    if not segments:
        raise ValueError(f"No usable segment found in {path}")
    return max(segments, key=len)
