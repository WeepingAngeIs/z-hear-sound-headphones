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
        try (var engine=new Sapi()) {
            new SpeechWorker(new CallVocabulary(rows.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)),engine)
                    .roster(List.of("zombie","enderman","pikachu"),List.of("pikachu","lopunny"));
            for(String label:List.of("enderman","lopunny","pikachu","sentence-lopunny-middle","sentence-pikachu-middle","sentence-creeper-start")) {
                var path=root.resolve("voice-0-"+label+"-0-clean.pcm");
                if(Files.isRegularFile(path)) {
                    var result=engine.recognize(Files.readAllBytes(path));
                    System.out.println("SYNTH_FROM_DESKTOP "+label+" "+result);
                }
            }
        }
    }
}
