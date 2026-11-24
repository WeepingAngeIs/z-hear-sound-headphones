package dev.mobsear.client.speech;
import java.nio.file.*;
import java.util.*;
/** Inspection of predetermined, CI-generated clips only. All code printing hypotheses lives in this fixture. */
public final class NativeTrace {
    public static void main(String[] args) throws Exception {
        if (!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) throw new IllegalStateException();
        Path root=Path.of(args[0]);
        var rows=new StringBuilder();
        int n=1;
        for (String name:List.of("zombie","mobs","mob","enderman","ender man","spider","skeleton","creeper","pikachu","lopunny","hoglin"))
            rows.append(n++).append(":0:").append(name).append('\n');
        try (var engine=new Sapi();var words=new WordDecoder()) {
            words.warm();
            new SpeechWorker(new CallVocabulary(rows.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)),engine)
                    .roster(List.of("zombie","enderman","pikachu"),List.of("pikachu","lopunny"));
            var hints=List.of("enderman","spider","cave spider","guardian","owl guardian","endermite","hoglin","zoglin","pikachu","lopunny","lanturn","poponee","buneary");
            for(String label:List.of("enderman","lopunny","pikachu","sentence-lopunny-middle","sentence-pikachu-middle","sentence-creeper-start","sentence-partial-spider")) {
                var path=root.resolve("voice-0-"+label+"-0-clean.pcm");
                if(Files.isRegularFile(path)) {
                    var result=engine.recognize(Files.readAllBytes(path));
                    System.out.println("SYNTH_FROM_DESKTOP "+label+" "+result);
                    var started=System.nanoTime();
                    String resolved=words.recognize(Files.readAllBytes(path),hints);
                    System.out.println("SYNTH_FROM_MODEL "+label+" "+resolved+" millis="+((System.nanoTime()-started)/1000000));
                }
            }
        }
    }
}
