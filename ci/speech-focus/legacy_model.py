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
        for folder in ("prior", "dist"):
            # Legacy libraries may return an error at load time or first evaluation.
            # Running them separately lets the corrected model execute even if the baseline aborts.
            process = subprocess.run([sys.executable,__file__,"evaluate",folder,str(root)],timeout=180)
            if folder == "dist": process.check_returncode()
            else: print("LEGACY_BASELINE exitCode =", process.returncode, flush=True)
        if (root/"prior.npz").exists():
            with np.load(str(root/"prior.npz")) as reference, np.load(str(root/"dist.npz")) as result:
                for name in reference.files:
                    np.testing.assert_allclose(reference[name],result[name],rtol=1e-4,atol=1e-4)
            print("LEGACY_COMPARISON finite baseline and candidate token scores agree",flush=True)


def evaluate(folder, root):
    import onnxruntime as ort
    sessions = []
    jar = Path(folder)/"Mic Voice Chat - Mobs & Cobblemon Hear You.jar"
    expected = (Path(folder)/"SHA256SUMS.txt").read_text().split()[0]
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == expected
    if folder=="prior": assert expected=="a85bb6aa9666b9ca39f98c66f42ab9f9bc0c1619b025a621ca91bad8231ee4ca"
    print("LEGACY_ARTIFACT",folder,expected,flush=True)
    for part in ["encoder","decoder"]:
        target = root / (folder + "-" + part + ".onnx")
        with zipfile.ZipFile(str(jar)) as archive:
            target.write_bytes(archive.read("dev/mobsear/client/speech/model/"+part+".onnx"))
        sessions.append(ort.InferenceSession(str(target)))
        print("LEGACY_LOAD",folder,part,flush=True)
    encoder, decoder = sessions
    outputs = encoder.run([], {"mel": np.zeros((1,80,1024), dtype=np.float32)})
    data = {o.name: value for o,value in zip(encoder.get_outputs(),outputs)}
    assert len(data) == 8 and all(np.isfinite(a).all() for a in data.values())
    result = {}
    for beams,n in [(1,2),(3,3)]:
        data.update(tokens=np.full((beams,n),50257,dtype=np.int64), positions=np.arange(n,dtype=np.int64),
                    mask=np.triu(np.full((1,1,n,n),-10000,dtype=np.float32),1))
        if folder=="dist": data["last"]=np.array([n-1],dtype=np.int64)
        logits, = decoder.run([],data)
        assert logits.shape == (beams,1,51864) and np.isfinite(logits).all()
        result["beams"+str(beams)] = logits
        print("LEGACY_MODEL_PASS",folder,"encoder and decoder, beams =",beams,"tokens =",n,flush=True)
    np.savez(str(root/(folder+".npz")),**result)


if __name__ == "__main__":
    if len(sys.argv)==4 and sys.argv[1]=="evaluate": evaluate(sys.argv[2],Path(sys.argv[3]))
    else: main()
