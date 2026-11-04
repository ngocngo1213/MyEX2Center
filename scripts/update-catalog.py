#!/usr/bin/env python3
"""
Fill the catalog with the freshly built release APKs and sign it.

    python3 -I scripts/update-catalog.py                 # updates ./apps.json + ./apps.json.sig
    python3 -I scripts/update-catalog.py --catalog X.json --dry-run

For every APK in dist/ (MyEX2Center-<ver>.apk, EX2Store-<ver>.apk) it reads package / versionCode /
versionName with aapt2, computes SHA-256 + size and rewrites the matching catalog entry:
  MyEX2Center -> release tag v<ver>        EX2Store -> release tag store-v<ver>
Then it signs the exact bytes of the catalog (ECDSA P-256 / SHA-256, base64 DER) into <catalog>.sig
with signing/catalog-signing-key.pem. Publish BOTH files together, AFTER the GitHub releases exist.
Use only at release time — the root apps.json is what installed apps read.
"""
import argparse, base64, glob, hashlib, json, os, re, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "ngocngo1213/MyEX2Center"
KEY = os.path.join(ROOT, "signing", "catalog-signing-key.pem")
ASSETS = {  # file prefix -> (package, tag prefix)
    "MyEX2Center": ("com.mipago.ex2center", "v"),
    "EX2Store": ("com.mipago.ex2store", "store-v"),
}


def find_aapt2():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    cands = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    if not cands:
        sys.exit("aapt2 not found (set ANDROID_HOME)")
    return cands[-1]


def badging(aapt2, apk):
    out = subprocess.run([aapt2, "dump", "badging", apk], capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    if not m:
        sys.exit(f"cannot read version from {apk}")
    return m.group(1), int(m.group(2)), m.group(3)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sign(data: bytes) -> str:
    if not os.path.isfile(KEY):
        sys.exit(f"missing signing key {KEY}")
    with tempfile.TemporaryDirectory() as d:
        src, sig = os.path.join(d, "c.json"), os.path.join(d, "c.sig")
        open(src, "wb").write(data)
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", KEY, "-out", sig, src], check=True)
        pub = os.path.join(d, "p.pem")
        subprocess.run(["openssl", "ec", "-in", KEY, "-pubout", "-out", pub], check=True, capture_output=True)
        subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", sig, src],
                       check=True, capture_output=True)
        return base64.b64encode(open(sig, "rb").read()).decode() + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default=os.path.join(ROOT, "apps.json"))
    ap.add_argument("--dist", default=os.path.join(ROOT, "dist"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--sign-only", action="store_true", help="only re-sign the catalog as it is")
    a = ap.parse_args()

    catalog = json.load(open(a.catalog, encoding="utf-8"))
    if not a.sign_only:
        aapt2 = find_aapt2()
        for prefix, (pkg, tag_prefix) in ASSETS.items():
            apks = sorted(glob.glob(os.path.join(a.dist, f"{prefix}-*.apk")), key=os.path.getmtime)
            if not apks:
                print(f"- {prefix}: no APK in dist/, entry unchanged")
                continue
            apk = apks[-1]
            got_pkg, vc, vn = badging(aapt2, apk)
            if got_pkg != pkg:
                sys.exit(f"{apk}: package {got_pkg}, expected {pkg}")
            entry = next((e for e in catalog if e.get("packageName") == pkg), None)
            if entry is None:
                sys.exit(f"catalog has no entry for {pkg}")
            name = os.path.basename(apk)
            entry.update({
                "versionCode": vc,
                "versionName": vn,
                "sizeMb": round(os.path.getsize(apk) / 1048576, 1),
                "apkUrl": f"https://github.com/{REPO}/releases/download/{tag_prefix}{vn}/{name}",
                "sha256": sha256(apk),
            })
            print(f"- {prefix}: {vn} ({vc}) sha256 {entry['sha256'][:12]}… -> {entry['apkUrl']}")

    data = (json.dumps(catalog, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    sig = sign(data)
    if a.dry_run:
        print(f"(dry run) catalog {len(data)} bytes, signature OK — nothing written")
        return
    open(a.catalog, "wb").write(data)
    open(a.catalog + ".sig", "w").write(sig)
    print(f"Wrote {a.catalog} and {a.catalog}.sig — publish both together.")


if __name__ == "__main__":
    main()
