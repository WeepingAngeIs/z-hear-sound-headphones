"""Read registry canonical names and English display names from the pinned public CI jar."""
import hashlib
import json
import sys
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


def read_names(jar, expected_hash):
    rows = []
    with ZipFile(jar) as archive:
        data = archive.read("assets/cobblemon/lang/en_us.json")
        assert hashlib.sha256(data).hexdigest() == expected_hash
        english = json.loads(data)
        for path in archive.namelist():
            if not path.startswith("data/cobblemon/species/") or not path.endswith(".json"):
                continue
            species = PurePosixPath(path).stem
            name = json.loads(archive.read(path))["name"]
            label = english[f"cobblemon.species.{species}.name"]
            rows.append("\t".join((f"cobblemon:{species}", name, label)))
    return rows


def extract(folder, destination):
    jar, = Path(folder).glob("Cobblemon-neoforge-1.8.4*.jar")
    rows = read_names(jar, "e07ca98082ff6737d88579c0de6c43a0b15b7fe2ef6dacaff914375e2c0006fc")
    assert len(rows) == 1025
    addon = read_names(Path(folder) / "extra-eeveelutions-1.2.jar",
                       "b0ff99edbff3e78bfa6bc42e7b5af1fe052886a6507c0b48ed865f471f9a8084")
    assert len(addon) == 11
    rows.extend(addon)
    Path(destination).write_text("\n".join(sorted(rows)) + "\n", encoding="utf-8")
    print(f"Prepared {len(rows)} public English species labels for the offline speech fixture.")


if __name__ == "__main__":
    extract(*sys.argv[1:])
