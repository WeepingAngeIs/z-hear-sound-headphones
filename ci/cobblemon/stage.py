"""Stage checksum-matched public TEST dependencies into disposable Actions directories."""
from hashlib import file_digest
import os
from pathlib import Path
from shutil import copyfile, copyfileobj
import sys
from tempfile import NamedTemporaryFile
from urllib.request import urlopen


FILES = (
    ("Cobblemon-neoforge-1.8.4+1.21.1.jar",
     "https://cdn.modrinth.com/data/MdwFAVRL/versions/xsVHrAsY/Cobblemon-neoforge-1.8.4%2B1.21.1.jar",
     "89306baca7b4332fc4dcc448507b45ca0133254c70f77990a63c500ed9a7b863"),
    ("kotlinforforge-5.12.0-all.jar",
     "https://edge.forgecdn.net/files/8335/665/kotlinforforge-5.12.0-all.jar",
     "095aed94f21b4e55895b21ea957750ae3ab829fbcfc6d3c66a3489f5f1128d46"),
    ("extra-eeveelutions-1.2.jar",
     "https://edge.forgecdn.net/files/7296/662/extra-eeveelutions-1.2.jar",
     "a40f2cdce076b709cd14ac5d2a5d660f32512115528ed6d5d7b80c1a071b637a"),
)

MEGA_FILES = (
    ("mega_showdown-neoforge-1.3.3+1.8.4+1.21.1-release.jar",
     "https://edge.forgecdn.net/files/9343/427/mega_showdown-neoforge-1.3.3%2B1.8.4%2B1.21.1-release.jar",
     "58f00a27068bcdfdb2c7866b54ef818e7f7812911346b6e9b59b0ff0bb1956ac"),
    ("architectury-13.0.11-neoforge.jar",
     "https://edge.forgecdn.net/files/8492/726/architectury-13.0.11-neoforge.jar",
     "9cc92f2c09533fc5482c60f993bd891c655a0e32b637037cdcb7c2b23adedeeb"),
    ("accessories-neoforge-1.1.0-beta.53+1.21.1.jar",
     "https://edge.forgecdn.net/files/7583/320/accessories-neoforge-1.1.0-beta.53%2B1.21.1.jar",
     "10017a3da78ea63e9ece27a1ca32f8cf490362f348778cf8cb759e7282f3beb0"),
    ("owo-lib-neoforge-0.12.15.5-beta.1+1.21.jar",
     "https://edge.forgecdn.net/files/6785/734/owo-lib-neoforge-0.12.15.5-beta.1%2B1.21.jar",
     "de6ed336bd80154b7241a7b3276694befc1c94550add8bcdfe7f82e5172fd13d"),
)


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or len(sys.argv) < 2:
        raise SystemExit("Usage on the hosted runner: python ci/cobblemon/stage.py MOD_DIRECTORY [MOD_DIRECTORY ...]")
    destinations = list(dict.fromkeys(Path(arg).resolve() for arg in sys.argv[1:]))
    for directory in destinations:
        directory.mkdir(parents=True, exist_ok=True)
    dest = destinations[0]
    files = FILES + MEGA_FILES if os.getenv("MOBSEAR_POKEMON_PACK") == "1" else FILES
    for name, url, sha in files:
        with NamedTemporaryFile(dir=dest, delete=False) as handle:
            temp = Path(handle.name)
        try:
            with urlopen(url, timeout=120) as source, temp.open("wb") as out:
                copyfileobj(source, out)
            with temp.open("rb") as data:
                if file_digest(data, "sha256").hexdigest() != sha:
                    raise ValueError(f"Checksum mismatch: {name}")
            temp.replace(dest / name)
            for directory in destinations[1:]:
                with NamedTemporaryFile(dir=directory, delete=False) as handle:
                    copy = Path(handle.name)
                try:
                    copyfile(dest / name, copy)
                    copy.replace(directory / name)
                finally:
                    copy.unlink(missing_ok=True)
            print(f"COBBLEMON_FIXTURE_FILE {name} {sha}", flush=True)
        finally:
            temp.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
