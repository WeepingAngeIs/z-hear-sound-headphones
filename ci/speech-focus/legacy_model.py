"""Compatibility check on hosted Windows. Neither the runtime nor Python is distributed with the mod."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

import numpy as np


def main():
    assert os.name == "nt" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        with urllib.request.urlopen("https://pypi.org/pypi/onnxruntime/0.3.0/json") as response:
            wheels = json.load(response)["urls"]
        name = "onnxruntime-0.3.0-cp37-cp37m-win_amd64.whl"
        wheel = root / name
        url = next(row["url"] for row in wheels if row["filename"] == name)
        urllib.request.urlretrieve(url, str(wheel))
        assert hashlib.sha256(wheel.read_bytes()).hexdigest() == "0e7d9f13a5bf028094431c31e02624b9f21f91bce02382d77ffaac0a69dd992f"
        subprocess.run([sys.executable,"-m","pip","install","--no-deps",str(wheel)],check=True)
        import onnxruntime as ort
        for prior, source in [(True, "prior"), (False, "dist")]:
            jar, = Path(source).glob("*.jar")
            sessions = []
            for part in ["encoder","decoder"]:
                target = root / (source + "-" + part + ".onnx")
                with zipfile.ZipFile(str(jar)) as archive:
                    target.write_bytes(archive.read("dev/mobsear/client/speech/model/"+part+".onnx"))
                try:
                    session = ort.InferenceSession(str(target))
                except Exception as exc:
                    if not prior: raise
                    print("Legacy inference rejected 0.3.14", part, str(exc)[:300], flush=True)
                else:
                    if prior: raise AssertionError("Expected opset 11 graph rejection: " + part)
                    sessions.append(session)
            if prior: continue
            encoder, decoder = sessions
            outputs = encoder.run([], {"mel": np.zeros((1,80,1024), dtype=np.float32)})
            data = {o.name: value for o,value in zip(encoder.get_outputs(),outputs)}
            assert len(data) == 8 and all(np.isfinite(a).all() for a in data.values())
            for beams,n in [(1,2),(3,3)]:
                data.update(tokens=np.full((beams,n),50257,dtype=np.int64), positions=np.arange(n,dtype=np.int64),
                            last=np.array([n-1],dtype=np.int64),mask=np.triu(np.full((1,1,n,n),-10000,dtype=np.float32),1))
                logits, = decoder.run([],data)
                assert logits.shape == (beams,1,51864) and np.isfinite(logits).all()
                print("LEGACY_MODEL_PASS encoder and decoder, beams =", beams, "tokens =", n, flush=True)


if __name__ == "__main__":
    main()
