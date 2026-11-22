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


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or len(sys.argv) < 2:
        raise SystemExit("Usage on the hosted runner: python ci/cobblemon/stage.py MOD_DIRECTORY [MOD_DIRECTORY ...]")
    destinations = list(dict.fromkeys(Path(arg).resolve() for arg in sys.argv[1:]))
    for directory in destinations:
        directory.mkdir(parents=True, exist_ok=True)
    dest = destinations[0]
    for name, url, sha in FILES:
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
