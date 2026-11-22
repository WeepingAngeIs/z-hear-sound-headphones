"""Stage only published inputs on a disposable GitHub-hosted machine."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import sys
import time
from zipfile import ZipFile

import requests

ROOT = Path(__file__).resolve().parent
BASE = "https://raw.githubusercontent.com/WeepingAngeIs/Server-Jars/411857009b8e05f606bb0b5b70648dcce8a9623b/.github/pack249/"
ARTIFACTS = [
    ("z-hear-sound-headphones", 44989599476, "mobs-hear-u", "mobsear-0.3.1.jar",
     "850e97492f40e7dc206dcc56decaab81c0c4bf129f063dffd90a638b2d414174"),
    ("zSortange", 44991463192, "anya-storage-plus-sharpcam.3", "anya-storage-plus-0.3.0-beta.7-sharpcam.3.jar",
     "8ef8d26343d30dc9ab2cda1fa7bdf93b71f7ec9ac1922ad89c16c908e87ae68a"),
    ("z-UwU_dm", 44991338625, "uwu-guardians-playtest-08bba3838d89b0ff23c74a082837061112bd4ce4",
     "UwU-Dimension-1.21.1-1.5.0-beta.13-combined-rc.2-playtest.9-integrated.2-weapon-settings.1-villages.1-guardians.1.jar",
     "0fdec5aaba8a4ce77fed7e9ba67b7d9a3f20bf5f066c621a9c60804e096d0346"),
]
OLD = {"UwU-Dimension-1.21.1-1.4.9.jar", "voicechat-neoforge-1.21.1-2.6.23.jar", "Unyielding-1.2.0.jar"}
CONFIGTOPIA = {
    "name": "Unyielding-1.2.0-Configtopia.jar", "size": 17203146,
    "url": "https://mediafilez.forgecdn.net/files/9708/806/Unyielding-1.2.0-Configtopia.jar",
    "sha512": "03007b8c82545aece68bfcf1fb2e3825f2496bfb50fe46ebf1c3d249aeb7e0e3c76a6bffe04c88df0fe86778c91a881b44fe2a5071b79a1b80ed8f61366d11f4",
}
ROOTS = {"config", "datapacks", "resourcepacks"}


def read_url(url):
    for attempt in range(4):
        try:
            result = requests.get(url, timeout=(15, 150))
            result.raise_for_status()
            return result.content
        except requests.RequestException:
            if attempt == 3:
                raise RuntimeError("Public download unavailable: " + url.split("?")[0]) from None
            time.sleep(2 ** attempt)


def public_json(name):
    return json.loads(read_url(BASE + name))


def checked_file(row, dest):
    expected = "sha512" if "sha512" in row else "sha1"
    for url in dict.fromkeys([row.get("modrinth", row["url"]), row["url"], row["url"].replace("mediafilez.", "edge.")]):
        try:
            data = read_url(url)
        except RuntimeError:
            continue
        if len(data) == row["size"] and hashlib.new(expected, data).hexdigest() == row[expected]:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            return
    raise RuntimeError("No hash-matching public download for " + row["name"])


def action_jar(row, mods):
    repo, run, label, name, expected = row
    url = f"https://api.github.com/repos/WeepingAngeIs/{repo}/actions/runs/{run}/artifacts?per_page=100"
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "MobsHearU-public-tests"}
    if os.getenv("ACTIONS_READ_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["ACTIONS_READ_TOKEN"]
    # An Actions token is supplied only to the official API. Signed storage receives no authorization header.
    data = None
    listing = requests.get(url, headers=headers, timeout=25)
    if listing.ok:
        found = [a for a in listing.json()["artifacts"] if a["name"] == label and not a["expired"]]
        if len(found) == 1:
            item = found[0]
            response = requests.get(item["archive_download_url"], headers=headers, allow_redirects=False, timeout=25)
            if response.is_redirect:
                data = read_url(response.headers["Location"])
                if item.get("digest") != "sha256:" + hashlib.sha256(data).hexdigest():
                    raise RuntimeError("Public artifact checksum mismatch: " + label)
    if data is None:
        data = read_url(f"https://nightly.link/WeepingAngeIs/{repo}/actions/runs/{run}/{label}.zip")
    with ZipFile(io.BytesIO(data)) as archive:
        entries = [f for f in archive.infolist() if PurePosixPath(f.filename).name == name]
        if len(entries) != 1:
            raise RuntimeError("Ambiguous or missing public artifact member: " + name)
        jar = archive.read(entries[0])
    if hashlib.sha256(jar).hexdigest() != expected:
        raise RuntimeError("Public artifact differs from the TEST binary: " + name)
    (mods / name).write_bytes(jar)
    print("EXACT_PUBLIC_ARTIFACT", repo, run, name, expected, flush=True)


def safe_name(name):
    p = PurePosixPath(name)
    return not p.is_absolute() and ".." not in p.parts and "\\" not in name and ":" not in name


def main():
    if os.getenv("GITHUB_ACTIONS") != "true" or os.getenv("RUNNER_ENVIRONMENT") != "github-hosted":
        raise SystemExit("GitHub-hosted disposable machine required")
    parser = argparse.ArgumentParser()
    parser.add_argument("game", type=Path)
    parser.add_argument("--focused", action="store_true")
    args = parser.parse_args()
    mods = args.game / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    profile = public_json("profile.json")
    rows = [row for row in profile["mods"] if row["name"] not in OLD]
    assert len(rows) == 362
    rows.append(CONFIGTOPIA)
    if args.focused:
        # These are the same two production dependencies shipped in the pack.
        rows = [r for r in rows if r["name"].lower().startswith(("cobblemon-neoforge-", "kotlinforforge-"))]
        assert len(rows) == 2
    with ThreadPoolExecutor(max_workers=6) as pool:
        for row in rows:
            assert safe_name(row["name"]) and len(PurePosixPath(row["name"]).parts) == 1
        list(pool.map(lambda r: checked_file(r, mods / r["name"]), rows))
        list(pool.map(lambda r: action_jar(r, mods), ARTIFACTS[:1] if args.focused else ARTIFACTS))
    if args.focused:
        print("FOCUSED: 3 exact production JARs", flush=True)
        return
    for row in profile["additional_packs"]:
        assert safe_name(row["path"]) and PurePosixPath(row["path"]).parts[0] in ROOTS
        checked_file(row, args.game / row["path"])
    pack = Path(os.environ["RUNNER_TEMP"]) / "public-pack-1.0.14.zip"
    checked_file(profile["pack"], pack)
    approved = public_json("overrides.json")
    with ZipFile(pack) as archive:
        for name, digest in approved.items():
            assert safe_name(name) and PurePosixPath(name).parts[0] in ROOTS
            body = archive.read("overrides/" + name)
            if hashlib.sha256(body).hexdigest() != digest:
                raise ValueError("Official pack override differs: " + name)
            target = args.game / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        # Three published preference-independent deltas match the installed file digests.
        variants = public_json("configured-hashes.json")
        changes = {
            "config/cubalance-common.toml": (b"armor_percent = 100.0", b"armor_percent = 50.0"),
            "config/defaultoptions-common.toml": (b', "file/CobblemonMoreCosmetics Release v1.1.7-1.21.x.zip"', b""),
            "config/defaultoptions/options.txt": (b',"file/CobblemonMoreCosmetics Release v1.1.7-1.21.x.zip"', b""),
        }
        for name, (old, new) in changes.items():
            body = archive.read("overrides/" + name)
            assert body.count(old) == 1
            body = body.replace(old, new)
            assert hashlib.sha256(body).hexdigest() == variants[name]
            target = args.game / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        for name in variants.keys() - changes.keys():
            target = args.game / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read("overrides/" + name))
    assert len(list(mods.glob("*.jar"))) == 366
    print("PUBLIC RECONSTRUCTION: 365 exact JARs, one public Configtopia release (CurseForge 9708806);",
          len(approved), "published override files and", len(profile["additional_packs"]), "pinned add-on packs", flush=True)


if __name__ == "__main__":
    main()
