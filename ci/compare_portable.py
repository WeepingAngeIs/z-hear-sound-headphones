"""Compare the final artifact against the exact 0.3.14 distribution and inspect the actual ONNX graphs."""
import hashlib
import io
import json
from pathlib import Path
import sys
import urllib.request
import zipfile
import onnx
from audit_archive import audit

OLD = "a85bb6aa9666b9ca39f98c66f42ab9f9bc0c1619b025a621ca91bad8231ee4ca"
OPS = {"Identity","Cast","Transpose","Conv","Erf","Mul","Add","Sub","ReduceMean","Div","Sqrt",
       "MatMul","Reshape","Softmax","Gather"}
BASE = "dev/mobsear/client/speech/model/"


def platform_libraries():
    # Mojang's 1.21.1 manifest provides both libraries. These remain launcher-owned, outside the mod.
    pinned = {"jna": "67bf3eaea4f0718cb376a181a629e5f88fa1c9dd",
              "jna-platform": "28934d48aed814f11e4c584da55c49fa7032b31b"}
    inventory = {}
    for name, expected in pinned.items():
        url = f"https://libraries.minecraft.net/net/java/dev/jna/{name}/5.14.0/{name}-5.14.0.jar"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(3_000_001)
        assert hashlib.sha1(data).hexdigest() == expected
        result = audit(data)
        pe = [f["entry"] for f in result["findings"] if f["reason"].startswith("Windows PE")]
        assert all(entry.lower().endswith("/jnidispatch.dll") for entry in pe)
        assert bool(pe) == (name == "jna")
        assert not any(f["entry"].lower().endswith((".exe",".bat",".cmd")) for f in result["findings"])
        inventory[name] = {"version":"5.14.0","origin":url,"sha256":result["sha256"],"windows_native_entries":pe}
        print("External Minecraft launcher library", name, "sha256", result["sha256"], "native Windows entries", pe)
    return inventory


def main(previous, current):
    raw = previous.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == OLD
    assert not audit(raw, "0.3.14")["findings"]
    external = platform_libraries()
    (current.parent/"launcher-libraries.json").write_text(json.dumps(external,indent=2)+"\n",encoding="utf8")
    with zipfile.ZipFile(io.BytesIO(raw)) as old, zipfile.ZipFile(current) as new:
        protected = [n for n in old.namelist() if n.startswith("assets/") or
                     n.endswith(".class") and n.startswith("dev/mobsear/") and n!="dev/mobsear/MobsEar.class" and
                     not n.startswith(tuple("dev/mobsear/client/"+changed for changed in
                                            ("VoiceClient","SpeechCues","CallDebugLog","speech/")))]
        assert protected and any(n.endswith(".png") for n in protected)
        for name in protected:
            if name == "assets/mobsear/lang/en_us.json":
                old_text=json.loads(old.read(name)); new_text=json.loads(new.read(name))
                assert all(new_text[k]==v for k,v in old_text.items()) and "mobsear.speech.desktop_reason" in new_text
            else:
                assert new.read(name) == old.read(name), name
        assert b"DeferredRegister" in old.read("dev/mobsear/MobsEar.class")
        assert b"DeferredRegister" not in new.read("dev/mobsear/MobsEar.class")
        assert new.read("dev/mobsear/server/VisitInteractions.class") == old.read("dev/mobsear/server/VisitInteractions.class")
        assets = {n[len(BASE):] for n in new.namelist() if n.startswith(BASE) and not n.endswith("/")}
        assert assets == {"encoder.onnx","decoder.onnx","english.vocab","mel-filters.f32","LICENSE-Whisper.txt","LICENSE-whisper.cpp.txt"}
        for asset in assets - {"encoder.onnx","decoder.onnx"}:
            assert old.read(BASE+asset) == new.read(BASE+asset), asset
        for filename in ("encoder.onnx","decoder.onnx"):
            data = new.read(BASE+filename)
            model = onnx.load_model_from_string(data)
            onnx.checker.check_model(model)
            baseline = onnx.load_model_from_string(old.read(BASE+filename))
            assert baseline.ir_version == 6 and [(i.domain,i.version) for i in baseline.opset_import] == [("",11)]
            assert model.ir_version == 3 and [(i.domain,i.version) for i in model.opset_import] == [("",9)]
            weights = lambda graph: {t.name: t.SerializeToString() for t in graph.initializer}
            before, after = weights(baseline.graph), weights(model.graph)
            assert all(before.get(k) == v for k,v in after.items()), filename
            removed = list(before.keys()-after.keys())
            assert len(removed) == (1 if filename == "decoder.onnx" else 0)
            if removed:
                tensor = next(t for t in baseline.graph.initializer if t.name == removed[0])
                assert onnx.numpy_helper.to_array(tensor).tolist() == [-1]
            inputs = {i.name for i in model.graph.input}
            assert inputs >= after.keys()
            assert ("last" in inputs) == (filename == "decoder.onnx")
            assert not model.functions and not model.training_info
            assert not model.graph.sparse_initializer
            assert all(not tensor.external_data and tensor.data_location==0 for tensor in model.graph.initializer)
            assert all(n.domain=="" and n.op_type in OPS and all(a.type not in (5,10) for a in n.attribute)
                       for n in model.graph.node)
            print("Windows ML data",filename,"nodes",len(model.graph.node),"sha256",hashlib.sha256(data).hexdigest())
        print("Preserved baseline game/network classes, game assets and translations:",len(protected))
        print("Exact corrected JAR SHA-256:",hashlib.sha256(current.read_bytes()).hexdigest())


if __name__=="__main__":
    main(Path(sys.argv[1]),Path(sys.argv[2]))
