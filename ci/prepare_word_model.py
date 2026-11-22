"""Bundle checksum-pinned, redistributable offline speech assets when building the mod."""
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from urllib.request import Request, urlopen
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import sys
import tempfile
import time

CPP = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.3/whisper-bin-x64.zip"
MODEL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.en-q5_1.bin"
FILES = ("whisper-cli.exe", "whisper.dll", "ggml.dll", "ggml-cpu.dll", "ggml-base.dll")

def fetch(url, digest):
    for attempt in range(3):
        try:
            request = Request(url, headers={"User-Agent":"Mobs-Hear-U-build"})
            with urlopen(request, timeout=90) as source:
                data = source.read(50_000_001)
            break
        except OSError:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    if sha256(data).hexdigest() != digest:
        raise ValueError("Unexpected public dependency digest for " + url.split("/")[-1])
    return data

def bundle(directory):
    dest = directory / "dev/mobsear/client/words-win-x64.zip"
    dest.parent.mkdir(parents=True, exist_ok=True)
    release = fetch(CPP, "d824b1e37599f882b396e73f1ee0bfd5d0529f700314c48311dcbd00b803321d")
    with ZipFile(BytesIO(release)) as archive:
        files = {name: archive.read("Release/" + name) for name in FILES}
    files["tiny.en.bin"] = fetch(MODEL, "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b")
    for filename, path, digest in (
        ("LICENSE-whisper.cpp.txt", "ggml-org/whisper.cpp/v1.8.3/LICENSE", "e562a2ddfaf8280537795ac5ecd34e3012b6582a147ef69ba6a6a5c08c84757d"),
        ("LICENSE-Whisper.txt", "openai/whisper/v20250625/LICENSE", "b5d65a59060e68c4ff940e1eddfa6f94b2d68fdf58ed7f4dd57721c997e35e9d"),
    ):
        files[filename] = fetch("https://raw.githubusercontent.com/" + path, digest)
    with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as temp:
        tmp = Path(temp.name)
    try:
        with ZipFile(tmp, "w") as output:
            for filename, data in files.items():
                output.writestr(ZipInfo(filename), data, compress_type=ZIP_DEFLATED)
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)

if __name__ == "__main__":
    bundle(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/generated/offline-speech"))
