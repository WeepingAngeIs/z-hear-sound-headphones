"""Replay a failed generated example with the same vocabulary, audio frames and nearby names."""
import os
from io import BytesIO
from pathlib import Path
import struct
import subprocess
import wave
from zipfile import ZipFile


def show(root, inputs, failed):
    assert os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" and os.name == "nt"
    source = Path("src/main/resources/dev/mobsear/client")
    script = (source / "recognize.ps1").read_text(encoding="utf8")
    hooks = {
        "$pcm = $reader.ReadBytes($length)": '"SYNTHETIC_CLIP"',
        "$plain = if ($null": '"desktop=$($result.Text) grammar=$($result.Grammar.Name) score=$($result.Confidence)"',
        "if ($text.Length -le 500)": '"sentence=$($text.Trim()) independent=$($original -join \' \')"',
        "$answer=Read-WordAudio": '"compare shortCode=$shortCode context=$hasContext"',
        "$output.WriteByte([byte]($decision -shr 8))": '"decision=$decision"'
    }
    for hook, message in hooks.items():
        assert script.count(hook) == 1
        script = script.replace(hook, "[Console]::Error.WriteLine(" + message + ")\n        " + hook)
    model = root / "nearby-diagnostic-model"
    with ZipFile("build/generated/offline-speech/dev/mobsear/client/words-win-x64.zip") as archive:
        archive.extractall(model)
    worker = root / "generated-trace.ps1"
    worker.write_text(script, encoding="utf-8-sig")
    data = bytearray((root / "registry.bin").read_bytes())
    labels = []
    examples = []
    for index in failed:
        voice_file, roster = inputs[index].split("\t")
        assert voice_file.startswith("near-Microsoft") and voice_file.endswith(".pcm")
        hints = "\n".join("+" + name for name in roster.split()).encode("ascii")
        data.extend(struct.pack("<ii", 0, len(hints)) + hints)
        for path in sorted(root.glob(f"near-segment-{index}-*.pcm")):
            clip = path.read_bytes()
            data.extend(struct.pack("<i", len(clip)) + clip)
            labels.append(voice_file)
            examples.append((voice_file, roster, clip))
    shell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    run = subprocess.run([str(shell), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(worker)],
                         input=data, capture_output=True, timeout=120, check=True,
                         env=dict(os.environ, MOBSEAR_LOCAL_WORD_MODEL=str(model),
                                  MOBSEAR_NEARBY_MATCHER=(source / "NearbyPokemonWords.cs").read_text()))
    for clip, decision in zip(labels, run.stderr.decode("utf8", "replace").split("SYNTHETIC_CLIP")[1:], strict=True):
        print("GENERATED_NAME_TRACE", clip, " / ".join(decision.strip().splitlines()), flush=True)
    for name, roster, samples in examples:
        audio = BytesIO()
        with wave.open(audio, "wb") as dest:
            dest.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            dest.writeframes(samples)
        for prompt in ("", "Minecraft, Cobblemon, " + ", ".join(roster.split()) + "."):
            command = [str(model / "whisper-cli.exe"), "-m", str(model / "tiny.en.bin"), "-f", "-", "-of", "pipe-only",
                       "-t", "3", "-nt", "-np", "-l", "en", "-bs", "5", "-ac", "512"]
            if prompt:
                command.extend(["--prompt", prompt])
            direct = subprocess.run(command, input=audio.getvalue(), capture_output=True, timeout=15, check=True)
            print("GENERATED_WORDS", name, "nearbyHint="+str(bool(prompt)), direct.stdout.decode("utf8","replace").strip(),flush=True)
