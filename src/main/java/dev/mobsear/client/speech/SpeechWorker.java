package dev.mobsear.client.speech;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A private, offline Java worker. The only input is the game's pipe, never a recording device. */
public final class SpeechWorker {
    private final CallVocabulary vocab;
    private final Sapi engine;
    private final Sapi.Grammar commands, dictation;
    private Sapi.Grammar nearCommands, mentions;
    private final Map<String,String> pronounced = new LinkedHashMap<>();
    private static final Set<String> REJECT = Set.of("hey","call","pokemon","hey pokemon","call pokemon",
            "all pokemon","every pokemon","hold on","just a moment","low on money","lantern","butterfly",
            "elegant","good dude","geode","slender man","huddling","jogging","logged in");
    private static final int LIMIT = 256000;

    private SpeechWorker(CallVocabulary vocab,Sapi engine) {
        this.vocab=vocab;
        this.engine=engine;
        for (var group : List.of(List.of("enderman","ender man","ender men"),List.of("lopunny","lop bunny","la punny","lapunny","lop punny"),
                List.of("geodude","geo dude","gee oh dude","gio dude"),List.of("pikachu","pika chu"))) {
            for (String word : group.subList(1,group.size()))
                if (vocab.names.containsKey(group.getFirst()) && !vocab.names.containsKey(word)) pronounced.put(word,group.getFirst());
        }
        commands=engine.grammar();
        var choices=new LinkedHashSet<>(vocab.names.keySet());
        choices.addAll(pronounced.keySet());
        choices.addAll(REJECT);
        commandGrammar(commands,choices);
        dictation=engine.grammar();
        dictation.dictation();
    }

    private static void commandGrammar(Sapi.Grammar grammar,Collection<String> words) {
        grammar.reset();
        var root=grammar.rule("command",1,true);
        var names=grammar.rule("name",2,false);
        var prefix=grammar.state(root);
        grammar.word(root,prefix,"hey");
        grammar.word(root,prefix,"call");
        grammar.branch(root,null,names);
        grammar.branch(prefix,null,names);
        for(String word:words) grammar.word(names,null,word);
        grammar.commit();
        grammar.active(true);
    }

    private void roster(List<String> hints,List<String> live) {
        vocab.nearby(live);
        if (mentions!=null) { mentions.close(); mentions=null; }
        if (nearCommands!=null) { nearCommands.close(); nearCommands=null; }
        var alternatives=new LinkedHashSet<String>();
        boolean any=live.stream().anyMatch(vocab.spoken::containsValue);
        if(any) alternatives.addAll(vocab.spoken.keySet()); // an absent evolution must still own its complete phrase
        if(!alternatives.isEmpty()) {
            nearCommands=engine.grammar();
            commandGrammar(nearCommands,alternatives);
        }
        var labels=new LinkedHashSet<>(hints);
        labels.addAll(live);
        labels.addAll(alternatives);
        labels.addAll(pronounced.keySet());
        labels.addAll(vocab.localReadings().keySet());
        // The background dictation path can always return exact registered names. The contextual grammar
        // also recognizes invented nearby names with arbitrary real words on either side.
        labels.addAll(List.of("zombie","creeper","enderman","spider","skeleton","skeletons","mobs"));
        labels.removeIf(w -> !w.matches("[a-z]+(?: [a-z]+)*"));
        if(labels.isEmpty()) return;
        mentions=engine.grammar();
        mentions.reset();
        var root=mentions.rule("sentence",1,true);
        var name=mentions.rule("name",2,false);
        for(String label:labels) mentions.word(name,null,label);
        var before=mentions.state(root); var after=mentions.state(root);
        mentions.freeWords(root,before);
        mentions.branch(root,after,name);
        mentions.branch(before,after,name);
        mentions.branch(before,null,name);
        mentions.freeWords(after,null);
        mentions.commit();
        mentions.active(true);
        mentions.enabled(false);
    }

    private CallVocabulary.Match match(String text) {
        String clean=String.join(" ",CallVocabulary.words(text));
        String bare=clean.replaceFirst("^(?:hey|call) ","");
        String phonetic=pronounced.get(bare);
        if (phonetic!=null) return new CallVocabulary.Match(phonetic,vocab.names.get(phonetic));
        if (REJECT.contains(clean) && !vocab.names.containsKey(clean)) return null;
        return vocab.find(clean,clean.startsWith("call "));
    }

    private record Reply(int code,String hint) {}

    private Reply decode(byte[] pcm,boolean detail) {
        var first=engine.recognize(pcm);
        String hint=detail?first.text():"";
        String plain=first.grammar()==dictation.id ? first.text() : "";
        CallVocabulary.Match exact=match(first.text());
        if(first.accepted() && exact!=null && first.confidence()>=.58 && !vocab.confirm.contains(exact.label())
                && !CallVocabulary.rival(exact.label(),plain))
            return new Reply(exact.code(),"");
        if(REJECT.contains(plain.toLowerCase(Locale.ROOT)) && exact==null) return new Reply(0,hint);
        // Independent open dictation for close registered names and conversational rivals.
        if(plain.isEmpty()) {
            commands.enabled(false);
            if(nearCommands!=null) nearCommands.enabled(false);
            try {
                var open=engine.recognize(pcm);
                plain=open.text();
                if(hint.isEmpty() && detail) hint=plain;
                var free=match(plain);
                if(open.accepted() && free!=null && open.confidence()>=.58 && !CallVocabulary.rival(free.label(),plain))
                    return new Reply(free.code(),"");
            } finally {
                commands.enabled(true);
                if(nearCommands!=null) nearCommands.enabled(true);
            }
        }
        var independent=vocab.guess(plain,plain);
        if(mentions!=null) {
            commands.enabled(false);
            if(nearCommands!=null) nearCommands.enabled(false);
            mentions.enabled(true);
            try {
                var sentence=engine.recognize(pcm);
                if(sentence.accepted() && sentence.grammar()==mentions.id && sentence.labelConfidence()>=.58) {
                    var candidate=match(sentence.label());
                    if(candidate==null) candidate=vocab.guess(sentence.label(),plain);
                    if(candidate!=null && !CallVocabulary.rival(candidate.label(),plain)
                            && (!vocab.confirm.contains(candidate.label()) || independent!=null && independent.label().equals(candidate.label())))
                        return new Reply(candidate.code(),"");
                }
            } finally {
                mentions.enabled(false);
                commands.enabled(true);
                if(nearCommands!=null) nearCommands.enabled(true);
            }
        }
        if(independent!=null && first.accepted()) return new Reply(independent.code(),"");
        if(!first.accepted() || first.confidence()<.12) {
            dictation.enabled(false);
            try {
                var restricted=engine.recognize(pcm);
                var m=match(restricted.text());
                if(restricted.accepted() && restricted.confidence()>=(vocab.names.size()>256?.90:.72) && m!=null
                        && !vocab.confirm.contains(m.label()) && !CallVocabulary.rival(m.label(),plain))
                    return new Reply(m.code(),"");
            } finally { dictation.enabled(true); }
        }
        return new Reply(0,hint);
    }

    private static byte[] read(DataInputStream in,int size,int max) throws IOException {
        if(size<0 || size>max) throw new IOException("Invalid length");
        byte[] raw=new byte[size]; in.readFully(raw); return raw;
    }

    public static void main(String[] args) {
        boolean stages=args.length==1 && args[0].equals("stages");
        try (var in=new DataInputStream(new BufferedInputStream(System.in));
             var out=new DataOutputStream(new BufferedOutputStream(System.out))) {
            if(stages){System.err.write(81);System.err.flush();}
            var vocab=new CallVocabulary(read(in,Integer.reverseBytes(in.readInt()),8*1024*1024));
            if(stages){System.err.write(71);System.err.flush();}
            try(var engine=new Sapi()) {
                if(stages){System.err.write(69);System.err.flush();}
                var service=new SpeechWorker(vocab,engine);
                service.roster(List.of(),List.of());
                if(stages){System.err.write(76);System.err.flush();}
                engine.recognize(new byte[16000]);
                out.writeByte(82);out.flush();
                for(;;) {
                    int len;
                    try { len=Integer.reverseBytes(in.readInt()); } catch(EOFException e) { break; }
                    if(len==0) {
                        var names=new ArrayList<String>();var live=new ArrayList<String>();
                        byte[] message=read(in,Integer.reverseBytes(in.readInt()),1024*1024);
                        for(String label:new String(message,StandardCharsets.US_ASCII).split("\n")) {
                            if(label.startsWith("+") && vocab.names.containsKey(label.substring(1))) live.add(label.substring(1));
                            else if(vocab.names.containsKey(label)) names.add(label);
                        }
                        service.roster(names,live);
                        continue;
                    }
                    boolean detail=len<0;
                    byte[] pcm=read(in,Math.abs(len),LIMIT);
                    Reply result;
                    try { result=service.decode(pcm,detail); }
                    finally { Arrays.fill(pcm,(byte)0); }
                    out.writeShort(result.code());
                    if(detail) {
                        String hint=result.hint().replaceAll("[^\\p{L}\\p{M}\\p{Nd}' -]"," ").replaceAll(" +"," ").strip();
                        if(hint.length()>32) hint=hint.substring(0,31).stripTrailing()+"…";
                        byte[] bytes=hint.getBytes(StandardCharsets.UTF_8);
                        out.writeByte(bytes.length);out.write(bytes);Arrays.fill(bytes,(byte)0);
                    }
                    out.flush();
                }
                if(service.mentions!=null) service.mentions.close();
                if(service.nearCommands!=null) service.nearCommands.close();
                service.dictation.close();service.commands.close();
            }
        } catch(Exception | LinkageError failure) {
            // Never print hypotheses or PCM. Hosted synthetic diagnostics may show a fixed ABI stage and HRESULT.
            if("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")) && "1".equals(System.getenv("MOBSEAR_SYNTHETIC_WORKER_FAULTS")))
                failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

}
