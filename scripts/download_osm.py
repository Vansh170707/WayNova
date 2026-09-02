"""Download and cache the OSM road network covering every trainable segment.

Run once with connectivity; every later run (including the finale demo) reads the cached
.npz files and never touches the network.
"""
import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from neuronav.data.reference import load_paired_segments
from neuronav.mapmatch.osm import BoundingBox, download_road_network, road_network_path

REPO_ROOT = Path(__file__).resolve().parents[1]
CACHE = REPO_ROOT / "data/external/osm"
PAIRED = REPO_ROOT / "data/raw/IO-VNBD/paired"


def main(force: bool):
    inventory = pd.read_csv(REPO_ROOT / "outputs/results/paired_inventory.csv")
    inventory = inventory[inventory["trainable"]]
    total = 0

    for _, row in inventory.iterrows():
        driver, route = row["driver"], row["route_id"]
        session = route.rsplit("_seg", 1)[0]
        phone = PAIRED / driver / f"S-{session}.csv"
        vehicle = PAIRED / driver / f"V-{session}.csv"
        if not phone.exists():
            continue

        for seg in load_paired_segments(str(phone), str(vehicle), session):
            if seg["route_id"].iloc[0] != route:
                continue
            bbox = BoundingBox.around(seg["ref_lat"], seg["ref_lon"], pad_m=800)
            path = road_network_path(route, CACHE)
            if path.exists() and not force:
                print(f"  cached  {route:14s} {path.stat().st_size/1e6:5.2f} MB")
                total += path.stat().st_size
                continue
            print(f"  fetching {route:14s} bbox={bbox.as_overpass()}")
            path = download_road_network(route, bbox, CACHE, force=force)
            size = path.stat().st_size
            total += size
            data = np.load(path, allow_pickle=False)
            print(f"           -> {size/1e6:5.2f} MB, {len(data['offset'])-1} ways, "
                  f"{len(data['lat'])} points")

    print(f"\ncached road networks: {CACHE}  ({total/1e6:.1f} MB total)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    main(args.force)
