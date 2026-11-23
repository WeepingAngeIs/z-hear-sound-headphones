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
cases = []
inputs = []
for file in sorted(root.glob("near-Microsoft*.pcm")):
    voice, label = file.stem[5:].split("_")
    near = {"exact": "raichu pikachu", "nidorine": "nidorina nidorino",
            "bulbasaur": "ivysaur bulbasaur", "charmander": "charmeleon charmander"}
    trials = [(label, near.get(targets.get(label, label), crowded))]
    if label == "nidorine":
        trials.append(("reverse", "nidorino nidorina"))
    if label in targets:
        trials.append(("out-" + label, "nidorina lanturn"))
    for test, roster in trials:
        cases.append((voice, test, roster.split()))
        inputs.append(file.name + "\t" + roster)
(root / "nearby-input.tsv").write_text("\n".join(inputs), encoding="ascii")
subprocess.run(["javac", "--release", "21", "-cp", "ci-classes", "-d", "ci-classes",
                "ci/speech-focus/NearbySpeechFixture.java"], check=True)
subprocess.run(["java", "-cp", "ci-classes;src/main/resources;build/generated/offline-speech",
                "dev.mobsear.client.NearbySpeechFixture", str(root)], check=True, timeout=180)
actual = (root / "nearby-actual.txt").read_text().splitlines()
hits, similar, guesses, errors = set(), set(), [], []
for (voice, test, roster), reply in zip(cases, actual, strict=True):
    assert "," not in reply, ("multiple decisions", test, reply)
    wire = int(reply)
    result = codes.get(wire & 32767, "neutral") if wire else "neutral"
    guessed = bool(wire & 32768)
    print("NEARBY_GENERATED", voice, test, ",".join(roster), "result="+result, "approximate="+str(guessed), flush=True)
    if wire >= 65534 or (guessed and result not in roster):
        errors.append((test, "invalid guess or worker failure", wire))
    if test == "exact" and (result != "pikachu" or guessed):
        errors.append((test, "exact name did not win", result))
    if test in ("ordinary", "money", "lantern") and result != "neutral":
        errors.append((test, result))
    if test.startswith("out-") and (guessed or result not in ("neutral", targets[test[4:]])):
        errors.append((test, "redirected out-of-range speech", result))
    if test in targets:
        if result == targets[test]:
            hits.add(voice)
        elif result != "neutral":
            errors.append((test, "wrong species", result))
    if test in ("nidorine", "reverse"):
        if result in roster:
            similar.add(voice)
        elif result != "neutral":
            errors.append((test, "wrong relative", result))
    if guessed and (test in targets or test in ("nidorine", "reverse")):
        guesses.append((voice, test, result))
assert not errors and guesses and len(hits) >= 2 and similar, (errors, guesses, hits, similar)
print("NEARBY_SPEECH_PASS voices="+str(len(hits)), "imperfect="+str(guesses), "similar="+str(sorted(similar)), flush=True)
