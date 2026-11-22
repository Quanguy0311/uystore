"""Build the GitHub Pages site: the website, catalog.json, its signature and version.json.

Usage: CATALOG_KEY_FILE=key.pem python3 -I tools/publish.py <catalog.json> <out-dir>
The key is an EC P-256 private key in PEM. Without CATALOG_KEY_FILE the site is built unsigned,
which is only useful for previewing it.
"""
import base64
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    catalog, out = Path(sys.argv[1]), Path(sys.argv[2])
    data = catalog.read_bytes()
    meta = json.loads(data)
    out.mkdir(parents=True, exist_ok=True)
    for f in (ROOT / "tools/site").iterdir():
        shutil.copy(f, out / f.name)
    for name in ("mark.png", "mark_dark.png"):
        shutil.copy(ROOT / "app/src/main/res/drawable-nodpi" / name, out / name)
    (out / "catalog.json").write_bytes(data)
    key = os.environ.get("CATALOG_KEY_FILE")
    if key:
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=data, capture_output=True, check=True).stdout
        pub = subprocess.run(["openssl", "ec", "-in", key, "-pubout"], capture_output=True, check=True).stdout
        (out / "pub.pem").write_bytes(pub)
        (out / "raw.sig").write_bytes(sig)
        subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(out / "pub.pem"), "-signature", str(out / "raw.sig"), str(out / "catalog.json")],
                       capture_output=True, check=True)
        (out / "pub.pem").unlink()
        (out / "raw.sig").unlink()
        (out / "catalog.sig").write_text(base64.b64encode(sig).decode() + "\n")
    (out / "version.json").write_text(json.dumps({"generated": meta["generated"], "apps": len(meta["apps"])}) + "\n")
    (out / ".nojekyll").write_text("")
    print(f"site: {len(meta['apps'])} apps, generated {meta['generated']}, {'signed' if key else 'unsigned'} -> {out}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
