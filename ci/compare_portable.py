"""Compare the final artifact against the exact 0.3.13 distribution and inspect the actual ONNX graphs."""
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile
import onnx

OLD = "0174acc775e6e33f24ccc21f6707e28576527c63c0ab6694234f3ce36aba7708"
OPS = {"Identity","Cast","Transpose","Conv","Erf","Mul","Add","Sub","ReduceMean","Div","Sqrt",
       "MatMul","Reshape","Softmax","Gather"}
BASE = "dev/mobsear/client/speech/model/"


def main(previous, current):
    raw = previous.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == OLD
    with zipfile.ZipFile(io.BytesIO(raw)) as old, zipfile.ZipFile(current) as new:
        protected = [n for n in old.namelist() if n.startswith("assets/") or
                     n.endswith(".class") and n.startswith("dev/mobsear/") and
                     not n.startswith(tuple("dev/mobsear/client/"+changed for changed in
                                            ("VoiceClient","SpeechCues","WindowsSpeechWorker","OfflineWordModel")))]
        assert protected and any(n.endswith(".png") for n in protected)
        for name in protected:
            if name == "assets/mobsear/lang/en_us.json":
                old_text=json.loads(old.read(name)); new_text=json.loads(new.read(name))
                assert all(new_text[k]==v for k,v in old_text.items()) and "mobsear.speech.desktop_only" in new_text
            else:
                assert new.read(name) == old.read(name), name
        assets = {n[len(BASE):] for n in new.namelist() if n.startswith(BASE) and not n.endswith("/")}
        assert assets == {"encoder.onnx","decoder.onnx","english.vocab","mel-filters.f32","LICENSE-Whisper.txt","LICENSE-whisper.cpp.txt"}
        for filename in ("encoder.onnx","decoder.onnx"):
            data = new.read(BASE+filename)
            model = onnx.load_model_from_string(data)
            onnx.checker.check_model(model)
            assert [(i.domain,i.version) for i in model.opset_import] == [("",11)]
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
