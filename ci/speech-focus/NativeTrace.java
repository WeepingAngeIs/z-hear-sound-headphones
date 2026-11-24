package dev.mobsear.client.speech;
import java.nio.file.*;
import java.util.*;
/** Inspection of predetermined, CI-generated clips only. All code printing hypotheses lives in this fixture. */
public final class NativeTrace {
    public static void main(String[] args) throws Exception {
        if (!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) throw new IllegalStateException();
        Path root=Path.of(args[0]);
        for (int frequency : new int[]{60, 100, 180}) {
            byte[] hum = new byte[38400], voiced = new byte[38400];
            for (int i = 0; i < 16000; i++) {
                double phase = 2 * Math.PI * frequency * i / 16000;
                int pure = (int) Math.round(300 * Math.sin(phase));
                int harmonic = pure + (int) Math.round(130 * Math.sin(2 * phase));
                hum[2*i] = (byte)pure; hum[2*i+1] = (byte)(pure >> 8);
                voiced[2*i] = (byte)harmonic; voiced[2*i+1] = (byte)(harmonic >> 8);
            }
            if (!SpeechWorker.steadyHum(hum) || SpeechWorker.steadyHum(voiced))
                throw new AssertionError("Stationary tone and harmonic voice distinction");
        }
        System.out.println("HUM_NO_GUESS_PASS three low tones and harmonic controls");
        var rows=new StringBuilder();
        int n=1;
        for (String name:List.of("zombie","mobs","mob","enderman","ender man","spider","skeleton","creeper","pikachu","lopunny","hoglin"))
            rows.append(n++).append(":0:").append(name).append('\n');
        var check=new CallVocabulary("1:0:nidorina\n2:0:nidorino\n3:0:wattrel\n4:0:kilowattrel\n5:0:vivillon\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for(var ordered:List.of(List.of("nidorina","nidorino"),List.of("nidorino","nidorina"))) {
            check.nearby(ordered);
            if(!check.guess("I saw a Nidorine tonight","").label().equals(ordered.getFirst())
                    || !check.guess("The Nidorino is beside me","").label().equals("nidorino")
                    || check.guess("Nobody noticed the new notebook","")!=null) throw new AssertionError("Live spelling rank");
        }
        check.nearby(List.of("wattrel","vivillon"));
        if(check.find("look there is a watch and roll",false).code()!=(CallVocabulary.NEAR|3)
                || check.find("kilo watch and roll",false).code()!=0 || check.find("Vivian",false).code()!=(CallVocabulary.NEAR|5))
            throw new AssertionError("Spoken names and absent evolution");
        System.out.println("LIVE_NAME_SELECTION_PASS distance ties, stronger match, ordinary negatives, absence and whole alternatives");
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
