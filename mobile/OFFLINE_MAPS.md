# NeuroNav-X offline maps

The navigation screen uses this renderer order:

1. Google Maps, when a valid Google key and Play services are available.
2. MapLibre Native with a local PMTiles archive for Greater Noida.
3. NeuroNav's dependency-free local trajectory canvas.

The bundled `greater_noida.pmtiles` is a Protomaps v4 vector-basemap extract derived from
OpenStreetMap. Its bounds are `77.30,28.34,77.70,28.68`, and it contains zoom levels 0–15;
MapLibre overzooms level 15 for the navigation camera. The current archive is approximately
12 MB. It is copied from APK assets to private app storage on first use because MapLibre needs
file byte-range access for local PMTiles.

The style, sprites, Latin/Devanagari glyph ranges, and map archive are all local. Runtime map
rendering therefore makes no network request. The source badge switches renderers. Long-press
it to replace the archive through Android's document picker; imported files must use PMTiles v3
and the Protomaps v4 basemap layer schema.

## Refresh the bundled map

Install the official `pmtiles` CLI, then run:

```bash
./update_greater_noida_map.sh
```

The script resolves the newest official Protomaps daily build, extracts the configured bounds,
verifies the result, and atomically replaces the bundled archive. Protomaps discourages hotlinking
the complete planet archive in applications; the app distributes and reads its own regional copy.

Map tiles require visible OpenStreetMap attribution. The navigation shell shows
`MapLibre · © OpenStreetMap · Protomaps` whenever the offline renderer is active. Corresponding
basemap, data, font, and sprite license texts ship under `assets/offline_map/licenses/`.

