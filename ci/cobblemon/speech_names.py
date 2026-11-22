"""Read registry canonical names and English display names from the pinned public CI jar."""
import hashlib
import json
import sys
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


def extract(folder, destination):
    jar, = Path(folder).glob("Cobblemon-neoforge-1.8.4*.jar")
    rows = []
    with ZipFile(jar) as archive:
        data = archive.read("assets/cobblemon/lang/en_us.json")
        assert hashlib.sha256(data).hexdigest() == "e07ca98082ff6737d88579c0de6c43a0b15b7fe2ef6dacaff914375e2c0006fc"
        english = json.loads(data)
        for path in archive.namelist():
            if not path.startswith("data/cobblemon/species/") or not path.endswith(".json"):
                continue
            species = PurePosixPath(path).stem
            name = json.loads(archive.read(path))["name"]
            label = english[f"cobblemon.species.{species}.name"]
            rows.append("\t".join((f"cobblemon:{species}", name, label)))
    assert len(rows) == 1025
    Path(destination).write_text("\n".join(sorted(rows)) + "\n", encoding="utf-8")
    print(f"Prepared {len(rows)} public English species labels for the offline speech fixture.")


if __name__ == "__main__":
    extract(*sys.argv[1:])
