"""Read the two published species packs required by TEST's global data-pack loader."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import PurePosixPath
import re
from urllib.request import urlopen
from zipfile import ZipFile


PACKS = (
    ("lasers-fakemon.zip",
     "https://cdn.modrinth.com/data/MkY2yZlo/versions/nnb5553z/Laser%27s%20Fakemon%20Pack%20v1.6.2.zip",
     "e11bfa5115a78c7717eaeb042ca778ae1e53463c47afc879007f28f7cd105dea", 70),
    ("triple-bakalutions.zip",
     "https://cdn.modrinth.com/data/mv8ODnvK/versions/zv0SRZb5/Triple%20Bakalutions%20-%201.1.1.zip",
     "6e96afa78a9701a586ee8705a0c31795f32e94528ed07fc90eaa9dfc4aaf24a3", 3),
)


def species_rows(jar):
    definitions = {}
    language = {}
    for name in jar.namelist():
        path = PurePosixPath(name)
        if re.fullmatch(r"data/[^/]+/species/.+\.json", name):
            namespace = path.parts[1]
            key = f"{namespace}:{path.stem}"
            data = jar.read(name)
            definitions[key] = (name, data, json.loads(data.decode("utf-8-sig"))["name"])
        elif re.fullmatch(r"assets/[^/]+/lang/en_us\.json", name):
            data = jar.read(name).decode("utf-8-sig")
            # The released Laser resource pack uses Gson's whole-line # comments.
            data = re.sub(r"(?m)^[ \t]*#[^\r\n]*", "", data)
            language.update(json.loads(data))
    rows = {}
    for key, (_, _, canonical) in definitions.items():
        namespace = key.partition(":")[0]
        showdown_name = re.sub("[^a-z0-9]", "", canonical.lower())
        label_key = f"{namespace}.species.{showdown_name}.name"
        rows[key] = (canonical, language.get(label_key, canonical))
    return definitions, rows


def collect(world_root=None):
    rows = {}
    for filename, url, digest, count in PACKS:
        with urlopen(url, timeout=90) as resource:
            data = resource.read()
        assert sha256(data).hexdigest() == digest
        with ZipFile(BytesIO(data)) as jar:
            _, found = species_rows(jar)
        assert len(found) == count and not rows.keys() & found.keys()
        rows.update(found)
        if world_root is not None:
            (world_root / "server/PokemonFixture/datapacks" / filename).write_bytes(data)
            for options in world_root.glob("*/options.txt"):
                resourcepacks = options.parent / "resourcepacks"
                resourcepacks.mkdir(exist_ok=True)
                (resourcepacks / filename).write_bytes(data)
        print(f"PUBLIC_GLOBAL_PACK {filename} sha256={digest} species={count}", flush=True)
    if world_root is not None:
        selected = ["vanilla", "mod_resources", *["file/" + p[0] for p in PACKS]]
        for options in world_root.glob("*/options.txt"):
            with options.open("a", encoding="utf8") as out:
                out.write("resourcePacks:" + json.dumps(selected) + "\n")
    return rows
