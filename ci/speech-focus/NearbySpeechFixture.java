package dev.mobsear.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

/** The shipped worker, capture gate and segmenter. The only inputs in this directory are synthesized by CI. */
public final class NearbySpeechFixture {
    public static void main(String[] args) throws Exception {
        if (!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) throw new IllegalStateException("Hosted only");
        var root=Path.of(args[0]);
        var process=SpeechCues.launch();
        var result=new ArrayList<String>();
        try (var in=new DataInputStream(process.getInputStream()); var out=new DataOutputStream(process.getOutputStream())) {
            out.write(Files.readAllBytes(root.resolve("registry.bin"))); out.flush();
            if (in.read()!=82) throw new AssertionError("Recognizer did not become ready");
            int row=0;
            for (String line:Files.readAllLines(root.resolve("nearby-input.tsv"))) {
                var fields=line.split("\t");
                var names=("+"+fields[1].replace(" ","\n+")).getBytes(StandardCharsets.US_ASCII);
                out.writeInt(0); out.writeInt(Integer.reverseBytes(names.length)); out.write(names); out.flush();
                var clips=new ArrayList<byte[]>();
                var segmenter=new SpeechCues.Utterances(clips::add);
                var gate=new CaptureGate();
                byte[] pcm=Files.readAllBytes(root.resolve(fields[0]));
                for(int start=0;start<pcm.length;start+=640)
                    FixtureCapture.accept(gate,segmenter,Arrays.copyOfRange(pcm,start,start+640),true);
                segmenter.finish();
                var codes=new ArrayList<String>();
                for(int j=0;j<clips.size();j++){
                    byte[] clip=clips.get(j);
                    Files.write(root.resolve("near-segment-"+row+"-"+j+".pcm"),clip);
                    out.writeInt(Integer.reverseBytes(clip.length)); out.write(clip); out.flush();
                    int code=in.readUnsignedShort();
                    if(code!=0) codes.add(Integer.toString(code));
                }
                result.add(codes.isEmpty()?"0":String.join(",",codes)); row++;
            }
        } finally {process.destroyForcibly();}
        Files.write(root.resolve("nearby-actual.txt"),result);
    }
}
