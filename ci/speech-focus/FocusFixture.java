package dev.mobsear.client;

import dev.mobsear.net.Cue;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Feed shared generated recordings through each revision's own gate, segmenter and Windows worker. */
public final class FocusFixture {
    private static final int FRAME = SpeechCues.Utterances.FRAME_BYTES;

    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))) throw new IllegalStateException("Hosted fixture only");
        Path dir = Path.of(args[0]);
        var names = new TreeMap<String,List<String>>();
        for (String row : Files.readAllLines(Path.of(System.getenv("MOBSEAR_SPEECH_CATALOG")))) {
            var f = row.split("\\t");
            names.put(f[0], List.of(f[1], f[2]));
        }
        if (names.size() != 1119) throw new AssertionError("Missing pack vocabulary");
        var entities = Map.of("minecraft:enderman", List.of("Enderman"), "minecraft:endermite", List.of("Endermite"),
                "minecraft:ender_dragon", List.of("Ender Dragon"), "cataclysm:ender_guardian", List.of("Ender Guardian"),
                "minecraft:rabbit", List.of("Rabbit"), "uwudimension:blossom_warden", List.of("Owl Guardian", "UwU Guardian"));
        var grammar = new SpeechGrammar(null, SpeechGrammar.registries(names,entities));
        boolean mentions = args[1].equals("mentions");
        if (mentions) try (var out = new DataOutputStream(Files.newOutputStream(dir.resolve("registry.bin")))) {
            grammar.writeTo(out);
        }
        if (grammar.code("enderman") != Cue.ENDERMAN.ordinal()+1
                || !id(grammar, grammar.code("lopunny")).equals("cobblemon:lopunny")) throw new AssertionError("Incorrect names");
        var rows = new ArrayList<String>();
        rows.add("fixture\tvoice\tphrase\tprofile\tmode\texpected\tactual\tsegments\tmaxMillis");
        Process helper = SpeechCues.launch();
        helper.onExit().orTimeout(420,TimeUnit.SECONDS).exceptionally(e -> {helper.destroyForcibly(); return null;});
        try (var input=new DataInputStream(helper.getInputStream()); var output=new DataOutputStream(helper.getOutputStream())) {
            grammar.writeTo(output);
            if (input.read()!=82) throw new AssertionError("English recognizer unavailable");
            if (!args[1].equals("baseline")) {
                // The same crowded local roster for every utterance, including unrelated speech.
                byte[] nearbyLabels = "+pikachu\n+lopunny\nenderman\n+lanturn\nspider\ncave spider\n+poponee\n+buneary\nguardian\nowl guardian\nendermite\nhoglin\nzoglin"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                output.writeInt(0);
                output.writeInt(Integer.reverseBytes(nearbyLabels.length));
                output.write(nearbyLabels);
                output.flush();
            }
            for (String row : Files.readAllLines(dir.resolve("manifest.tsv"))) {
                var f=row.split("\\t",-1);
                if (mentions && !f[2].startsWith("sentence-") && !List.of("enderman","lopunny","pikachu","ordinary-chat","low-money").contains(f[2])) continue;
                byte[] pcm=Files.readAllBytes(dir.resolve(f[0]+".pcm"));
                for (boolean automatic : new boolean[]{false,true}) {
                    var clips=new ArrayList<byte[]>();
                    var segments=new SpeechCues.Utterances(clips::add);
                    var gate=new CaptureGate();
                    for (int pos=0;pos<pcm.length;pos+=FRAME) {
                        byte[] frame=Arrays.copyOfRange(pcm,pos,pos+FRAME);
                        FixtureCapture.accept(gate,segments,frame,automatic);
                    }
                    if (!automatic) segments.finish();
                    else if (f[3].equals("tight-release")) {
                        // Open mic hears the silence after the player stops. PTT releases immediately.
                        for (int n=0;n<34;n++) FixtureCapture.accept(gate,segments,new byte[FRAME],true);
                    }
                    if (f[3].equals("pack")) {
                        var source=voicedFrames(List.of(pcm));
                        var actual=voicedFrames(clips);
                        boolean intact=java.util.Arrays.deepEquals(source.toArray(),actual.toArray());
                        System.out.println("PIKACHU_CAPTURE "+args[1]+" "+f[1]+" automatic="+automatic+
                                " sourceFrames="+source.size()+" analyzed="+actual.size()+" identical="+intact);
                        if (!args[1].equals("baseline") && !intact) throw new AssertionError("Lost an original Pikachu frame");
                    }
                    var replies=new ArrayList<String>();
                    long maxMillis = 0;
                    for (var clip : clips) {
                        long started = System.nanoTime();
                        output.writeInt(Integer.reverseBytes(clip.length));
                        output.write(clip);
                        output.flush();
                        String reply=id(grammar,input.readUnsignedShort());
                        if (mentions && !automatic && (f[2].startsWith("sentence-") || f[2].equals("low-money")) && !reply.equals(f[4]))
                            Files.write(dir.resolve("miss-" + f[0] + "-" + clips.indexOf(clip) + ".pcm"), clip);
                        maxMillis = Math.max(maxMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        if (!reply.equals("neutral")) replies.add(reply);
                    }
                    rows.add(String.join("\t",f[0],f[1],f[2],f[3],automatic?"activation":"ptt",f[4],
                            replies.isEmpty()?"neutral":String.join(",",replies),String.valueOf(clips.size()),String.valueOf(maxMillis)));
                }
            }
        } finally { helper.destroyForcibly(); }
        Files.write(dir.resolve(args[1]+".tsv"),rows);
    }

    private static List<byte[]> voicedFrames(List<byte[]> clips) {
        var frames = new ArrayList<byte[]>();
        byte[] silent = new byte[FRAME];
        for (byte[] clip : clips) for (int pos=0; pos<clip.length; pos+=FRAME) {
            byte[] frame=Arrays.copyOfRange(clip,pos,pos+FRAME);
            if (!Arrays.equals(frame,silent)) frames.add(frame);
        }
        return frames;
    }

    private static String id(SpeechGrammar grammar,int result) {
        if (result==0) return "neutral";
        var ids=new ArrayList<String>();
        if (!grammar.dispatch(result,c->ids.add(c.name()),ids::add,ids::add) || ids.size()!=1)
            throw new AssertionError("Malformed speech decision");
        return ids.getFirst();
    }
}
