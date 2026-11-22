"""Make one disposable namespaced species using the public Cobblemon release installed on the runner."""
import json
from pathlib import Path
import sys
from zipfile import ZipFile

def create(source, packs):
    target = packs / "hearing-species"
    directory = target / "data/hearfixture/species"
    directory.mkdir(parents=True, exist_ok=True)
    with ZipFile(source) as jar:
        species = json.loads(jar.read("data/cobblemon/species/generation1/pikachu.json"))
    species.update(name="Chimekin", nationalPokedexNumber=16025, evolutions=[], forms=[], features=[])
    species.pop("preEvolution", None)
    (directory / "chimekin.json").write_text(json.dumps(species), encoding="utf8")
    (target / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48,
        "description": "Disposable species registry fixture"}}), encoding="utf8")

if __name__ == "__main__":
    create(Path(sys.argv[1]), Path(sys.argv[2]))
