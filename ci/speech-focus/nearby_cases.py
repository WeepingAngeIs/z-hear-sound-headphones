"""Only generated audio is accepted in this hosted test of the shipped capture gate and recognizer."""
import os
from pathlib import Path
import subprocess
import sys

assert os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and os.name == "nt"
root = Path(sys.argv[1])
registry = (root / "registry.bin").read_bytes()[4:].decode().splitlines()
codes = {int(code): name for code, mode, name in (line.split(":") for line in registry)
         if mode != "2" and " " not in name}
targets = {"bulbazore": "bulbasaur", "bulbazar": "bulbasaur",
           "charmandor": "charmander", "charmendar": "charmander"}
crowded = "lopunny lanturn nidorina nidorino charmander pikachu"
examples = {"butterfree": "beautifly butterfree", "lilligant": "lillipup lilligant", "geodude": "graveler geodude"}
birds = "kilowattrel wattrel"
spoken = {"wattrel": birds, "kilowattrel": birds, "vivillon": "vibrava vivillon"}
cases = []
inputs = []
for file in sorted(root.glob("near-Microsoft*.pcm")):
    voice, label = file.stem[5:].split("_")
    near = {"exact": "raichu pikachu", "pause-name": "raichu pikachu", "nidorine": "nidorina nidorino",
            "bulbasaur": "ivysaur bulbasaur", "charmander": "charmeleon charmander"}
    family = label.removesuffix("-sentence").removesuffix("-phonetic").removesuffix("-stock")
    roster = examples.get(family, near.get(targets.get(label, label), crowded))
    if label == "butterfly":
        roster = examples["butterfree"]
    if label == "elegant":
        roster = examples["lilligant"]
    if label in ("geode", "good-dude", "going"):
        roster = examples["geodude"]
    species = label.split("-")[0]
    if species in spoken:
        roster = spoken[species]
    if label.startswith("usual-"):
        roster = birds + " vivillon"
    trials = [(label, roster)]
    if label in ("wattrel-and", "kilowattrel-split", "kilowattrel-base", "vivillon-vivian"):
        other = "kilowattrel" if species == "wattrel" else "wattrel" if species == "kilowattrel" else "vibrava"
        trials.append(("absent-"+label, other))
        if label == "wattrel-and":
            trials.append(("absent-both", "eevee"))
    if label == "nidorine":
        trials.append(("reverse", "nidorino nidorina"))
        trials.append(("crowded", "pikachu lopunny bulbasaur nidorina nidorino ivysaur lanturn"))
    if label in targets:
        trials.append(("out-" + label, "nidorina lanturn"))
    for test, roster in trials:
        cases.append((voice, test, roster.split()))
        inputs.append(file.name + "\t" + roster)
(root / "nearby-input.tsv").write_text("\n".join(inputs), encoding="ascii")
classpath = os.environ["MOBSEAR_FIXTURE_CP"]
subprocess.run(["java", "-cp", classpath, "dev.mobsear.client.NearbySpeechFixture", str(root)], check=True, timeout=420)
actual = (root / "nearby-actual.txt").read_text().splitlines()
hits, similar, guesses, errors = set(), set(), [], []
failed_examples = []
for index, ((voice, test, roster), reply) in enumerate(zip(cases, actual, strict=True)):
    assert "," not in reply, ("multiple decisions", test, reply)
    wire = int(reply)
    result = codes.get(wire & 32767, "neutral") if wire else "neutral"
    guessed = bool(wire & 32768)
    print("NEARBY_GENERATED", voice, test, ",".join(roster), "result="+result, "approximate="+str(guessed), flush=True)
    if wire >= 65534 or (guessed and result not in roster):
        errors.append((test, "invalid guess or worker failure", wire))
    if test in ("exact","pause-name") and (result != "pikachu" or guessed):
        errors.append((test, "exact name did not win", result))
    base = test.removesuffix("-sentence").removesuffix("-phonetic").removesuffix("-stock")
    wrong_stock_phones = "-stock" in test and (root / f"stock-{voice}.txt").read_text() == "False"
    if base in examples and result != base and not (wrong_stock_phones and result == "neutral"):
        errors.append((voice, test, "wrong or missing base species", result))
        failed_examples.append(index)
    if test in ("ordinary", "money", "lantern", "notebook", "needle", "pause", "moment", "butterfly", "elegant", "geode", "good-dude", "going") and result != "neutral":
        errors.append((test, result))
        failed_examples.append(index)
    if test.startswith("out-") and (guessed or result not in ("neutral", targets[test[4:]])):
        errors.append((test, "redirected out-of-range speech", result))
    species = test.split("-")[0]
    if species in spoken and result != species or test.startswith(("usual-", "absent-")) and result != "neutral":
        errors.append((voice, test, "spoken alternative", result))
        failed_examples.append(index)
    if test in targets:
        if result == targets[test]:
            hits.add(voice)
        elif result != "neutral":
            errors.append((test, "wrong species", result))
    if test in ("nidorine", "reverse", "crowded"):
        if result in ("nidorina","nidorino"):
            similar.add(voice)
        elif result != "neutral" or test=="crowded":
            errors.append((test, "wrong relative", result))
    if guessed and (test in targets or test in ("nidorine", "reverse", "crowded")):
        guesses.append((voice, test, result))
if failed_examples:
    print("Failed generated fixtures:", failed_examples)
assert not errors and guesses and len(hits) >= 2 and similar, (errors, guesses, hits, similar)
print("NEARBY_SPEECH_PASS voices="+str(len(hits)), "imperfect="+str(guesses), "similar="+str(sorted(similar)), flush=True)
