"""Extract drivable road centrelines from the bundled offline PMTiles into a compact asset.

The Android estimator reads this file (``RoadNetwork.kt``) for road-heading aiding. It
needs road geometry on its own sensor thread, independent of whether a MapLibre map is
on screen, so it cannot reuse the renderer's tile cache.

Format (little-endian), version 1:
    b"WNRD" | u8 version | i32 base_lat_e6 | i32 base_lon_e6 | u32 line_count
    per line: u8 flags (bit0 = one-way) | varint point_count |
              point_count x (zigzag varint dlat_e6, zigzag varint dlon_e6),
              deltas from the previous point (the first from the base).

Usage (needs ``pip install pmtiles mapbox-vector-tile``):
    python scripts/extract_offline_roads.py \
        mobile/app/src/main/assets/offline_map/greater_noida.pmtiles \
        mobile/app/src/main/assets/offline_roads_greater_noida.bin
"""
import gzip
import math
import struct
import sys

import mapbox_vector_tile
from pmtiles.reader import MmapSource, Reader

DRIVABLE = {"highway", "major_road", "minor_road"}
ZOOM = 15


def tile_xy(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return x, y


def to_lat_lon(z, x, y, px, py, extent):
    n = 2 ** z
    lon = (x + px / extent) / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / extent) / n))))
    return lat, lon


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def zigzag(value):
    return (value << 1) ^ (value >> 63)


def main(src, dst):
    with open(src, "rb") as handle:
        reader = Reader(MmapSource(handle))
        header = reader.header()
        south, north = header["min_lat_e7"] / 1e7, header["max_lat_e7"] / 1e7
        west, east = header["min_lon_e7"] / 1e7, header["max_lon_e7"] / 1e7
        x0, y1 = tile_xy(south, west, ZOOM)
        x1, y0 = tile_xy(north, east, ZOOM)
        lines = []
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                data = reader.get(ZOOM, x, y)
                if not data:
                    continue
                if data[:2] == b"\x1f\x8b":
                    data = gzip.decompress(data)
                layer = mapbox_vector_tile.decode(
                    data, default_options={"y_coord_down": True}).get("roads")
                if not layer:
                    continue
                extent = layer.get("extent", 4096)
                for feature in layer["features"]:
                    props = feature["properties"]
                    if props.get("kind") not in DRIVABLE:
                        continue
                    oneway = props.get("oneway") == "yes"
                    geometry = feature["geometry"]
                    parts = ([geometry["coordinates"]] if geometry["type"] == "LineString"
                             else geometry["coordinates"] if geometry["type"] == "MultiLineString"
                             else [])
                    for part in parts:
                        # Tile buffers duplicate short pieces of neighbouring tiles. Duplicates
                        # share direction, so the heading consensus is unaffected.
                        points = [to_lat_lon(ZOOM, x, y, px, py, extent) for px, py in part]
                        if len(points) >= 2:
                            lines.append((oneway, points))
    base_lat = round((south + north) / 2 * 1e6)
    base_lon = round((west + east) / 2 * 1e6)
    body = bytearray()
    for oneway, points in lines:
        body.append(1 if oneway else 0)
        body += varint(len(points))
        prev_lat, prev_lon = base_lat, base_lon
        for lat, lon in points:
            lat_e6, lon_e6 = round(lat * 1e6), round(lon * 1e6)
            body += varint(zigzag(lat_e6 - prev_lat)) + varint(zigzag(lon_e6 - prev_lon))
            prev_lat, prev_lon = lat_e6, lon_e6
    with open(dst, "wb") as out:
        out.write(b"WNRD" + struct.pack("<BiiI", 1, base_lat, base_lon, len(lines)))
        out.write(body)
    print(f"{len(lines)} lines, {sum(len(p) for _, p in lines)} points, "
          f"{len(body) / 1e6:.2f} MB -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
