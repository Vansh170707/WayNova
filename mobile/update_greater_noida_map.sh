#!/usr/bin/env bash
set -euo pipefail

# Refresh the bundled Greater Noida Protomaps v4 extract from the newest official daily build.
# Requirements: curl, jq, and the `pmtiles` CLI (https://docs.protomaps.com/pmtiles/cli).

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output="$script_dir/app/src/main/assets/offline_map/greater_noida.pmtiles"
metadata_url="https://build-metadata.protomaps.dev/builds.json"
bounds="77.30,28.34,77.70,28.68"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v pmtiles >/dev/null || { echo "pmtiles is required" >&2; exit 1; }

latest="$(curl --fail --silent --show-error "$metadata_url" \
    | jq -r 'sort_by(.key) | last | .key')"
[[ "$latest" == *.pmtiles ]] || { echo "Could not resolve the latest build" >&2; exit 1; }

temporary="$(mktemp "${TMPDIR:-/tmp}/neuronavx-greater-noida.XXXXXX.pmtiles")"
trap 'rm -f "$temporary"' EXIT

pmtiles extract "https://build.protomaps.com/$latest" "$temporary" \
    --bbox="$bounds" --maxzoom=15 --download-threads=4
pmtiles verify "$temporary"
mv "$temporary" "$output"

echo "Updated $output from $latest ($(du -h "$output" | cut -f1))."

