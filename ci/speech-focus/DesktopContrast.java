package dev.mobsear.client.speech;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Evaluate only the named synthetic CI recordings. This fixture never opens a device. */
public final class DesktopContrast {
    private static Sapi.Grammar grammar(SpeechWorker worker,String field) throws Exception {
        var member=SpeechWorker.class.getDeclaredField(field);
        member.setAccessible(true);
        return (Sapi.Grammar)member.get(worker);
    }

    public static void main(String[] args) throws Exception {
        if (!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))) throw new IllegalStateException();
        var data=Files.readAllBytes(Path.of("evidence/registry/public-registry.bin"));
        var vocab=new CallVocabulary(Arrays.copyOfRange(data,4,data.length));
        try (var engine=new Sapi()) {
            var service=new SpeechWorker(vocab,engine);
            service.roster(List.of("pikachu","enderman","zombie"),List.of("pikachu"));
            var command=grammar(service,"commands");
            var open=grammar(service,"dictation");
            var phones=grammar(service,"phonemes");
            var mentions=grammar(service,"mentions");
            var near=grammar(service,"nearCommands");
            var decode=SpeechWorker.class.getDeclaredMethod("decode",byte[].class,boolean.class);
            decode.setAccessible(true);
            for (String name:List.of("pikachu","pikachu-second","pick-a-shoe","peek-at-you","peekaboo","sentence-ordinary","hold-on")) {
                byte[] pcm=Files.readAllBytes(Path.of("evidence/input/"+name+".pcm"));
                var decision=decode.invoke(service,pcm,true);
                System.out.println("DESKTOP_SYNTH "+name+" decision="+decision+" combined="+engine.recognize(pcm));
                command.enabled(false);
                if (near!=null) near.enabled(false);
                System.out.println("DESKTOP_SYNTH "+name+" open="+engine.recognize(pcm));
                phones.enabled(true);
                System.out.println("DESKTOP_SYNTH "+name+" phoneWithOpen="+engine.recognize(pcm));
                open.enabled(false);
                System.out.println("DESKTOP_SYNTH "+name+" phoneCompetition="+engine.recognize(pcm));
                phones.enabled(false);
                open.enabled(true); mentions.enabled(true);
                System.out.println("DESKTOP_SYNTH "+name+" mention="+engine.recognize(pcm));
                mentions.enabled(false);open.enabled(false);
                command.enabled(true);
                if (near!=null) near.enabled(true);
                System.out.println("DESKTOP_SYNTH "+name+" commands="+engine.recognize(pcm));
                open.enabled(true);
            }
        }
    }
}
