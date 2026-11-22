"""Stage checksum-matched public TEST dependencies into disposable Actions directories."""
from hashlib import file_digest, sha256
from io import BytesIO
import os
from pathlib import Path
from shutil import copyfile, copyfileobj
import sys
import tarfile
from tempfile import NamedTemporaryFile
from urllib.request import urlopen
from zipfile import ZipFile


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

OWL_JAR = "UwU-Dimension-1.21.1-1.5.0-beta.13-combined-rc.2-playtest.9-integrated.1.jar"
OWL_INPUTS = ("https://nightly.link/WeepingAngeIs/z-UwU_dm/actions/runs/44973577287/"
              "cumulative-ci-inputs-eb534710706ec9c495d1e84071715e7f9d87d640.zip")
OWL_FILES = (
    (OWL_JAR, OWL_INPUTS, "0e865bbe4cf695144b70889138d82ad36ed8288a019bcb0e6b8b4851a6f1a04f"),
    ("lootr-neoforge-1.21.1-1.11.38.125.jar",
     "https://edge.forgecdn.net/files/8811/105/lootr-neoforge-1.21.1-1.11.38.125.jar",
     "091878598fa5cb51cd304bf9324afe46420d328b6f77a9ba38acc15e26945e9e"),
    ("another_furniture-neoforge-4.0.2.jar",
     "https://edge.forgecdn.net/files/7355/747/another_furniture-neoforge-4.0.2.jar",
     "c61a66c73bcc5147d9983dc72c485493a612d2dbc078fcb86e2024155e34b7dd"),
)
TWILIGHT = (
    "twilightforest-1.21.1-4.8.3345-universal.jar",
    "https://edge.forgecdn.net/files/7797/302/twilightforest-1.21.1-4.8.3345-universal.jar",
    "493da16d10210f9f53a3c33b3d8fcb4ae9b14016ee420d753c894562bd9aba35",
)
CONNECTIVITY_FILES = (
    ("connectivity-1.21.1-7.6.jar",
     "https://edge.forgecdn.net/files/7367/221/connectivity-1.21.1-7.6.jar",
     "2366973ddae5ba00b9ba15a26e5b610a6648038b9f2549bf81b9ae61342b281a"),
    ("cupboard-1.21.1-4.2.jar",
     "https://edge.forgecdn.net/files/8889/50/cupboard-1.21.1-4.2.jar",
     "5162922647fe07c7b9f446870915b57ddd0ae9dcf56b243611130fea30f2f77a"),
)

MIMIC_JAR = "upsidedown-1.2.0-beta.5.jar"
MIMIC_FILES = (
    (MIMIC_JAR, "https://nightly.link/WeepingAngeIs/unyielddungrilssidedown/actions/runs/43351006827/"
     "upside-down-dungeons-dad1ad7bff12430cef0dfb9dda09cec214ba7a9c.zip",
     "4bb26fc8116f8e666855a3845128d955bdd9a4782972ce719559dd5379b83317"),
    ("simplyswords-neoforge-1.70.2-1.21.1.jar",
     "https://edge.forgecdn.net/files/8746/1/simplyswords-neoforge-1.70.2-1.21.1.jar",
     "5e75bd66ea489079d60c9c1ed98762550fbc0bee88cd7af399f17bbeb56608c3"),
    ("fzzy_config-0.7.7+1.21+neoforge.jar",
     "https://edge.forgecdn.net/files/8883/390/fzzy_config-0.7.7%2B1.21%2Bneoforge.jar",
     "26af871cacd134ce52610384bbdd362a018707444eb2d498e4833063f378cf85"),
    ("SimplyTooltips-neoforge-0.1.5.jar",
     "https://edge.forgecdn.net/files/8715/141/SimplyTooltips-neoforge-0.1.5.jar",
     "fdac367da1c5243ac5225f00f831b786817452ee9fa6a7f4acd99ec819b0f2b7"),
)


def copy_guardian_baseline(source, output):
    # This publicly accessible test archive includes the exact previous production baseline.
    # Read only the known member, verifying the outer published archive and the inner JAR.
    data = source.read(16_981_919)
    assert len(data) == 16_981_918 and sha256(data).hexdigest() == "7f619ad21a052277cc410f9f138ac7868acf35ae4117efbdfb7c31c03ea0f744"
    with ZipFile(BytesIO(data)) as archive, archive.open("cumulative-inputs.tar.gz") as compressed:
        with tarfile.open(fileobj=compressed, mode="r:gz") as tar:
            member = tar.getmember("build/cumulative/input/integrated-baseline.jar")
            assert member.isfile() and member.size == 6_365_448
            with tar.extractfile(member) as jar:
                copyfileobj(jar, output)


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or len(sys.argv) < 2:
        raise SystemExit("Usage on the hosted runner: python ci/cobblemon/stage.py MOD_DIRECTORY [MOD_DIRECTORY ...]")
    destinations = list(dict.fromkeys(Path(arg).resolve() for arg in sys.argv[1:]))
    for directory in destinations:
        directory.mkdir(parents=True, exist_ok=True)
    dest = destinations[0]
    files = FILES + MEGA_FILES if os.getenv("MOBSEAR_POKEMON_PACK") == "1" else FILES
    if os.getenv("MOBSEAR_ENTITY_PACK") == "1":
        files += OWL_FILES + (TWILIGHT,) + MIMIC_FILES + CONNECTIVITY_FILES
    elif os.getenv("MOBSEAR_OWL_ACOUSTIC") == "1":
        files += OWL_FILES
    for name, url, sha in files:
        with NamedTemporaryFile(dir=dest, delete=False) as handle:
            temp = Path(handle.name)
        try:
            with urlopen(url, timeout=120) as source, temp.open("wb") as out:
                if name == OWL_JAR:
                    copy_guardian_baseline(source, out)
                elif name == MIMIC_JAR:
                    archive_data = source.read(914_224)
                    assert sha256(archive_data).hexdigest() == "a88d9eb24f737fb24fcf70c9712492fb55c32428d46fd432e4f12b39561f7f1e"
                    with ZipFile(BytesIO(archive_data)) as archive, archive.open("build/libs/" + MIMIC_JAR) as jar:
                        copyfileobj(jar, out)
                else:
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
