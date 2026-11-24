package dev.mobsear.client.speech;

import java.nio.file.*;
import java.util.*;

/** Decode only known synthetic cases after a hosted regression. This fixture is outside the production JAR. */
public final class FailureReadings {
    public static void main(String[] args)throws Exception {
        if(!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) throw new IllegalStateException();
        var root=Path.of(args[0]);
        boolean catalog=args[1].equals("catalog");
        var table=root.resolve(catalog?"synthetic-catalog.bin":"registry.bin");
        if(!Files.isRegularFile(table)) return;
        byte[] data=Files.readAllBytes(table);
        var vocab=new CallVocabulary(Arrays.copyOfRange(data,4,data.length));
        var hints=catalog?List.of("enderman","lopunny","hoglin","zoglin","pikachu"):
                List.of("enderman","spider","cave spider","guardian","owl guardian","endermite","hoglin","zoglin","pikachu","lopunny","lanturn","poponee","buneary");
        try(var engine=new Sapi();var decoder=new WordDecoder();var files=Files.list(root)) {
            var worker=new SpeechWorker(vocab,engine);
            if(!catalog) worker.roster(hints,List.of("pikachu","lopunny","lanturn","poponee","buneary"));
            var fld=SpeechWorker.class.getDeclaredField("dictation");fld.setAccessible(true);
            var dict=(Sapi.Grammar)fld.get(worker);
            var cmdField=SpeechWorker.class.getDeclaredField("commands");cmdField.setAccessible(true);
            var commands=(Sapi.Grammar)cmdField.get(worker);
            decoder.warm();
            for(var path:files.filter(p->p.getFileName().toString().matches(catalog?"synthetic-catalog-[0-9]+\\.pcm":"miss-voice-.*\\.pcm")).sorted().limit(70).toList()){
                var pcm=Files.readAllBytes(path);
                var mixed=engine.recognize(pcm);
                commands.enabled(false);var plain=engine.recognize(pcm);commands.enabled(true);
                dict.enabled(false);var restricted=engine.recognize(pcm);dict.enabled(true);
                var word=decoder.recognize(pcm,hints);
                System.out.println("SYNTH_FAILURE_READINGS "+path.getFileName()+" mixed="+mixed+" plain="+plain+" restricted="+restricted+" word="+word);
            }
        }
    }
}
