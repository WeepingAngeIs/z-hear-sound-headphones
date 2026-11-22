"""Exercise TEST-matched Lunavee data from the published 1.1.8 jar in disposable runners."""
import argparse
from hashlib import sha256
from io import BytesIO
import json
import os
from pathlib import Path
import re
from urllib.request import urlopen
from zipfile import ZipFile
from global_species import collect, species_rows
from speech_names import extract


URL = "https://edge.forgecdn.net/files/9554/625/Unyielding-1.1.8.jar"
ARCHIVE_SHA = "638ded0f08d806939ed3ce8efafae841386907ec4a33687568a1b00565acc518"
SPECIES = "data/cobblemon/species/custom/lunavee.json"
ENGLISH = "assets/cobblemon/lang/en_us.json"
LABEL = "cobblemon.species.lunavee.name"
ID = "cobblemon:lunavee"
DATA = {
    SPECIES: "3abe61a6a802b614e4be2aa2cf90f5a6c60d0e107ee89b13f28f5232b2c9a3a4",
    "data/cobblemon/dex_entries/pokemon/custom/lunavee.json":
        "c9d652e1388dc7621607b1cd27d7dfce1c1d4ca42a01b5b224d82701c711b8f9",
    "data/cu_rideable/species_additions/custom_lunavee.json":
        "5fdc0396447008999dba7767930061dd88a9e2367606ea0d44278e1a2c8ccac2",
}
VISUAL_SHA = "40ed4793878d49a8dea62f4e1b0fb2a1f1a915ea1f89e544aad8751a1e7ac8f8"


def write(folder, name, content):
    path = folder / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def stage(world_root=None, vocabulary=None):
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("This data fixture runs on the hosted runner.")
    with urlopen(URL, timeout=90) as source:
        content = source.read()
    assert sha256(content).hexdigest() == ARCHIVE_SHA
    with ZipFile(BytesIO(content)) as jar:
        data = {name: jar.read(name) for name in DATA}
        for name, digest in DATA.items():
            assert sha256(data[name]).hexdigest() == digest
        language = jar.read(ENGLISH)
        assert sha256(language).hexdigest() == "a77544d8f4c472e2745c031aefb18a5237b89357f3cfff467fac80e7052ba5c3"
        canonical, label = json.loads(data[SPECIES])["name"], json.loads(language)[LABEL]
        assert (canonical, label) == ("Lunavee", "Lunavee")
        definitions, custom = species_rows(jar)
        assert len(custom) == 10 and custom[ID] == (canonical, label)
        # The public 1.1.8 pack misspells the filename as covislight. Its English
        # species.name/translation already say Corvislight. TEST corrected that ID.
        old_path, old_data, name = definitions.pop("cobblemon:covislight")
        assert custom.pop("cobblemon:covislight") == ("Corvislight", "Corvislight")
        definitions["cobblemon:corvislight"] = (old_path.replace("covislight.json", "corvislight.json"), old_data, name)
        custom["cobblemon:corvislight"] = (name, name)
        if world_root is not None:
            pack = world_root / "server/PokemonFixture/datapacks/pack-lunavee"
            write(pack, "pack.mcmeta", json.dumps({"pack": {"pack_format": 48,
                "description": "Lunavee data from public Unyielding 1.1.8"}}).encode())
            for name, blob in data.items():
                write(pack, name, blob)
            # Nine public raw definitions match TEST; Corvislight is a public identity-equivalent fixture.
            for name, blob, _ in definitions.values():
                write(pack, name, blob)
            # Inkyeon, Mawonna and Substitute reference abilities registered through
            # Mega Showdown's normal reload-time ability data loader.
            abilities = [name for name in jar.namelist()
                         if name.startswith("data/") and "/mega_showdown/showdown/abilities/" in name and name.endswith(".js")]
            assert {Path(name).stem for name in abilities} == {
                "substitute", "futakuchionna", "futakuchionnaplayer", "futakuchisgrasp", "inkylove", "taintedcharm"}
            for name in abilities:
                write(pack, name, jar.read(name))
            assets = world_root / "lunavee-resources"
            render_files = sorted(n for n in jar.namelist() if
                                  n.startswith(("assets/lunavee/bedrock/", "assets/lunavee/textures/"))
                                  and n.endswith((".json", ".png")))
            digest = sha256()
            for name in render_files:
                blob = jar.read(name)
                digest.update(name.encode() + b"\0" + sha256(blob).digest())
                write(assets, name, blob)
            assert len(render_files) == 22 and digest.hexdigest() == VISUAL_SHA
            staged_labels = {}
            for lang_path in sorted(n for n in jar.namelist() if re.fullmatch(r"assets/[^/]+/lang/en_us\.json", n)):
                lang = json.loads(jar.read(lang_path))
                labels = {k: v for k, v in lang.items() if ".species." in k and k.endswith(".name")}
                if labels:
                    write(assets, lang_path, json.dumps(labels).encode())
                    staged_labels.update(labels)
            assert staged_labels["cobblemon.species.fairysliggoo.name"] == "Frilligoo"
            for filename in ("CREDITS.txt", "LICENSE-Cobblemon-Eevee.txt"):
                entry = "META-INF/licenses/imported-pokemon/lunavee/" + filename
                write(assets, entry, jar.read(entry))
    if world_root is not None:
        vocabulary = world_root / "test-species.tsv"
        extract(world_root / "server/mods", vocabulary)
    global_rows = collect(world_root)
    lines = vocabulary.read_text(encoding="utf8").splitlines()
    existing = {line.partition("\t")[0] for line in lines}
    assert len(existing) == 1036
    assert not (existing & custom.keys() or existing & global_rows.keys() or custom.keys() & global_rows.keys())
    lines.extend("\t".join((key, *names)) for key, names in {**custom, **global_rows}.items())
    assert len(lines) == 1119
    catalog = ("\n".join(sorted(lines)) + "\n").encode()
    # Digest of TEST's read-only effective canonical IDs, registry names and English labels.
    assert sha256(catalog).hexdigest() == "f9635d5514968552418aade2b8e5330424b0215cd2f749c3dbdac184fd37260f"
    vocabulary.write_bytes(catalog)
    print("TEST_SPECIES_CATALOG 1119 canonical identifiers and English labels", flush=True)
    print(f"PACK_SPECIES_DATA {ID} sha256={DATA[SPECIES]} public=Unyielding-1.1.8 data-only", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vocabulary", type=Path, required=True)
    stage(vocabulary=parser.parse_args().vocabulary)
