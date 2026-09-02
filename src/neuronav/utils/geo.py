import numpy as np

WGS84_A = 6378137.0  # semi-major axis, meters


def latlon_to_enu(lat, lon, alt, lat0, lon0, alt0):
    """Local-tangent-plane East-North-Up projection (equirectangular approximation).

    Adequate for the sub-few-km spans used in blackout-window evaluation; not a
    substitute for a proper geodetic library on trips spanning large latitude ranges.
    """
    lat = np.asarray(lat, dtype=np.float64)
    lon = np.asarray(lon, dtype=np.float64)
    alt = np.asarray(alt, dtype=np.float64)

    lat0_rad = np.radians(lat0)
    east = np.radians(lon - lon0) * WGS84_A * np.cos(lat0_rad)
    north = np.radians(lat - lat0) * WGS84_A
    up = alt - alt0
    return east, north, up


def enu_to_latlon(east, north, up, lat0, lon0, alt0):
    """Inverse of `latlon_to_enu`, exact for that projection.

    Needed to write simulated aiding back out as lat/lon: the Android logger schema and
    the phone's own Location API speak degrees, so a replay log that fed ENU straight in
    would not exercise the same code path the live app runs.
    """
    east = np.asarray(east, dtype=np.float64)
    north = np.asarray(north, dtype=np.float64)
    up = np.asarray(up, dtype=np.float64)

    lat = lat0 + np.degrees(north / WGS84_A)
    lon = lon0 + np.degrees(east / (WGS84_A * np.cos(np.radians(lat0))))
    return lat, lon, alt0 + up
