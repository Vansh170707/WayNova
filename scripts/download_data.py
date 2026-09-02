"""Fetch IO-VNBD phone/vehicle session pairs from the dataset's Git LFS media endpoint.

The repository stores CSVs via Git LFS, so a plain clone yields pointer files. Rather than
require git-lfs, this pulls the resolved blobs directly from media.githubusercontent.com.

Session folders are inconsistently cased (`V-vta2.csv` vs `V-Vw1.csv`), so each vehicle file
is attempted in a few spellings.
"""
import argparse
import shutil
import subprocess
import urllib.parse
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MEDIA = "https://media.githubusercontent.com/media/onyekpeu/IO-VNBD/master"
CATEGORISED = "Synchronised V abd S datasets/Categorised IOVNB Dataset"

# driver -> (folder, [(session_dir, session_name)])
SESSIONS = {
    "A": ("S (Driver A)", [("S1", "S1"), ("S2", "S2"), ("S3a", "S3a"),
                           ("S3b", "S3b"), ("S3c", "S3c"), ("S4", "S4")]),
    "B": ("M (Driver B)", [("", "M")]),
    "D": ("Y (Driver D)", [("Y1", "Y1")]),
    "E_ta": ("Vta (Driver E)", [("Vta02", "Vta2"), ("Vta05", "Vta5"), ("Vta10", "Vta10"),
                                ("Vta15", "Vta15"), ("Vta20", "Vta20")]),
    "E_w": ("Vw (Driver E)", [("Vw01", "Vw1"), ("Vw05", "Vw5"), ("Vw10", "Vw10")]),
}


MIN_CSV_BYTES = 10_000  # an LFS pointer is a few hundred bytes; a real CSV is far larger


def fetch(url: str, dest: Path) -> bool:
    """Download via curl, which uses the system trust store.

    urllib is avoided here deliberately: a python.org interpreter on macOS commonly has no
    CA bundle configured, so every HTTPS fetch fails with CERTIFICATE_VERIFY_FAILED.
    """
    if dest.exists() and dest.stat().st_size > MIN_CSV_BYTES:
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["curl", "-sL", "--fail", "--max-time", "300", "-o", str(dest), url],
        capture_output=True)
    if result.returncode != 0 or not dest.exists() or dest.stat().st_size < MIN_CSV_BYTES:
        dest.unlink(missing_ok=True)
        return False
    return True


def main(drivers: list[str]):
    if shutil.which("curl") is None:
        raise SystemExit("curl is required to fetch the dataset")
    out_root = REPO_ROOT / "data/raw/IO-VNBD/paired"
    ok, failed = [], []

    for driver in drivers:
        folder, sessions = SESSIONS[driver]
        for session_dir, name in sessions:
            parts = [CATEGORISED, folder] + ([session_dir] if session_dir else [])
            base = "/".join(urllib.parse.quote(p) for p in parts)

            phone_dest = out_root / driver / f"S-{name}.csv"
            vehicle_dest = out_root / driver / f"V-{name}.csv"

            got_phone = fetch(f"{MEDIA}/{base}/{urllib.parse.quote(f'S-{name}.csv')}", phone_dest)
            got_vehicle = False
            for spelling in (f"V-{name}.csv", f"V-{name.lower()}.csv", f"v-{name}.csv"):
                if fetch(f"{MEDIA}/{base}/{urllib.parse.quote(spelling)}", vehicle_dest):
                    got_vehicle = True
                    break

            label = f"{driver}/{name}"
            if got_phone and got_vehicle:
                ok.append(label)
                print(f"  ok      {label:14s} phone={phone_dest.stat().st_size/1e6:6.1f} MB "
                      f"vehicle={vehicle_dest.stat().st_size/1e6:6.1f} MB")
            else:
                failed.append(label)
                print(f"  MISSING {label:14s} phone={got_phone} vehicle={got_vehicle}")

    print(f"\npaired sessions retrieved: {len(ok)}")
    if failed:
        print(f"failed: {', '.join(failed)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--drivers", default=",".join(SESSIONS))
    args = ap.parse_args()
    main([d for d in args.drivers.split(",") if d in SESSIONS])
