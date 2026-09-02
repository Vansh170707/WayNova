"""Fetch and cache OpenStreetMap road geometry for offline map matching.

The blueprint requires the road network to be downloaded ahead of the finale and used
offline, because the venue may have no usable connectivity. So everything here is
fetch-once / cache-forever: an Overpass query per region, stored as a compact .npz that
later runs load without touching the network.

Only drivable highway classes are kept -- footpaths and cycleways would otherwise supply
plausible-looking but impossible candidates to the matcher.
"""
import json
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

DRIVABLE = ("motorway|trunk|primary|secondary|tertiary|unclassified|residential|"
            "living_street|service|motorway_link|trunk_link|primary_link|"
            "secondary_link|tertiary_link")

# Road class -> a nominal free-flow speed (m/s), used to sanity-check transitions.
CLASS_SPEED = {
    "motorway": 31.0, "motorway_link": 22.0, "trunk": 27.0, "trunk_link": 18.0,
    "primary": 22.0, "primary_link": 15.0, "secondary": 18.0, "secondary_link": 13.0,
    "tertiary": 15.0, "tertiary_link": 11.0, "unclassified": 13.0,
    "residential": 9.0, "living_street": 5.0, "service": 7.0,
}
DEFAULT_SPEED = 13.0


@dataclass
class BoundingBox:
    min_lat: float
    min_lon: float
    max_lat: float
    max_lon: float

    def padded(self, metres: float) -> "BoundingBox":
        dlat = metres / 111_000.0
        dlon = metres / (111_000.0 * np.cos(np.radians((self.min_lat + self.max_lat) / 2)))
        return BoundingBox(self.min_lat - dlat, self.min_lon - dlon,
                           self.max_lat + dlat, self.max_lon + dlon)

    def as_overpass(self) -> str:
        return f"{self.min_lat},{self.min_lon},{self.max_lat},{self.max_lon}"

    def contains(self, lat, lon) -> bool:
        return (self.min_lat <= lat <= self.max_lat) and (self.min_lon <= lon <= self.max_lon)

    @staticmethod
    def around(lats, lons, pad_m: float = 500.0) -> "BoundingBox":
        lats = np.asarray(lats)[np.isfinite(lats)]
        lons = np.asarray(lons)[np.isfinite(lons)]
        return BoundingBox(float(lats.min()), float(lons.min()),
                           float(lats.max()), float(lons.max())).padded(pad_m)


def _overpass_query(bbox: BoundingBox) -> str:
    return (f'[out:json][timeout:300];'
            f'way["highway"~"^({DRIVABLE})$"]({bbox.as_overpass()});'
            f'out geom;')


def fetch_overpass(bbox: BoundingBox, retries: int = 3) -> dict:
    """Run the Overpass query with curl (the venv has no CA bundle configured)."""
    query = _overpass_query(bbox)
    last_error = None
    for attempt in range(retries):
        endpoint = OVERPASS_ENDPOINTS[attempt % len(OVERPASS_ENDPOINTS)]
        result = subprocess.run(
            ["curl", "-sS", "--fail", "--max-time", "300", "-X", "POST",
             "-d", query, endpoint],
            capture_output=True)
        if result.returncode == 0 and result.stdout:
            try:
                return json.loads(result.stdout)
            except json.JSONDecodeError as exc:
                last_error = exc
        else:
            last_error = result.stderr.decode()[:200]
        time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"Overpass query failed after {retries} attempts: {last_error}")


def parse_ways(payload: dict):
    """Flatten the Overpass response into per-way coordinate arrays and metadata."""
    ways = []
    for element in payload.get("elements", []):
        if element.get("type") != "way":
            continue
        geometry = element.get("geometry")
        if not geometry or len(geometry) < 2:
            continue
        tags = element.get("tags", {})
        oneway = tags.get("oneway", "no") in ("yes", "true", "1", "-1")
        ways.append({
            "id": element["id"],
            "lat": np.array([p["lat"] for p in geometry], dtype=np.float64),
            "lon": np.array([p["lon"] for p in geometry], dtype=np.float64),
            "nodes": np.array(element.get("nodes", []), dtype=np.int64),
            "highway": tags.get("highway", "unclassified"),
            "oneway": oneway,
        })
    return ways


def road_network_path(name: str, cache_dir: Path) -> Path:
    return Path(cache_dir) / f"roads_{name}.npz"


def download_road_network(name: str, bbox: BoundingBox, cache_dir: Path,
                          force: bool = False) -> Path:
    """Fetch (or reuse) the road network for one region and store it as .npz."""
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    out_path = road_network_path(name, cache_dir)
    if out_path.exists() and not force:
        return out_path

    ways = parse_ways(fetch_overpass(bbox))
    if not ways:
        raise RuntimeError(f"no drivable ways returned for {name}")

    # Store as one flat coordinate array plus per-way offsets, which keeps the file small
    # and loads without any per-way Python work.
    offsets, lats, lons, node_ids = [0], [], [], []
    highways, oneways, way_ids = [], [], []
    for way in ways:
        lats.append(way["lat"])
        lons.append(way["lon"])
        nodes = way["nodes"]
        if len(nodes) != len(way["lat"]):
            nodes = np.full(len(way["lat"]), -1, dtype=np.int64)
        node_ids.append(nodes)
        offsets.append(offsets[-1] + len(way["lat"]))
        highways.append(way["highway"])
        oneways.append(way["oneway"])
        way_ids.append(way["id"])

    np.savez_compressed(
        out_path,
        lat=np.concatenate(lats), lon=np.concatenate(lons),
        node=np.concatenate(node_ids), offset=np.array(offsets, dtype=np.int64),
        highway=np.array(highways), oneway=np.array(oneways, dtype=bool),
        way_id=np.array(way_ids, dtype=np.int64),
        bbox=np.array([bbox.min_lat, bbox.min_lon, bbox.max_lat, bbox.max_lon]),
    )
    return out_path
