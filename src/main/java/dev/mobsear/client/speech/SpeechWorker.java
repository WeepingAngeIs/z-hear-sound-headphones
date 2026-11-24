package dev.mobsear.client.speech;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A private, offline Java worker. The only input is the game's pipe, never a recording device. */
public final class SpeechWorker {
    private final CallVocabulary vocab;
    private final Sapi engine;
    private final Sapi.Grammar commands, dictation;
    private Sapi.Grammar nearCommands, mentions, phonemes;
    private WordDecoder words;
    private List<String> context=List.of();
    private final Map<String,String> pronounced = new LinkedHashMap<>();
    private static final Set<String> REJECT = Set.of("hey","call","pokemon","hey pokemon","call pokemon",
            "all pokemon","every pokemon","hold on","just a moment","low on money","lantern","butterfly",
            "elegant","good dude","geode","slender man","huddling","jogging","logged in");
    private static final int LIMIT = 256000;

    SpeechWorker(CallVocabulary vocab,Sapi engine) {
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
        commandGrammar(commands,choices,vocab.explicit);
        dictation=engine.grammar();
        dictation.dictation();
        if(engine.language==0x409) {
            phonemes=engine.grammar();
            try {
                phonemes.phonetics(vocab.names.keySet());
                phonemes.activate("Pronunciations");
                phonemes.enabled(false);
            } catch(IOException|RuntimeException unsupported) {
                phonemes.close();phonemes=null;syntheticFault(unsupported);
            }
        }
    }

    private static void commandGrammar(Sapi.Grammar grammar, Collection<String> words, Set<String> explicit) {
        grammar.reset();
        var root = grammar.rule("command", 1, true);
        var names = grammar.rule("name", 2, false);
        var greeting = grammar.state(root);
        var call = grammar.state(root);
        grammar.word(root, greeting, "hey");
        grammar.word(root, call, "call");
        grammar.branch(root, null, names);
        grammar.branch(greeting, null, names);
        grammar.branch(call, null, names);
        for (String word : words) {
            // Split spellings cue the recognizer only after the player explicitly says "call".
            grammar.word(explicit.contains(word) ? call : names, null, word);
        }
        grammar.commit();
        grammar.activate("command");
        grammar.priority("command", 1);
    }

    void roster(List<String> hints,List<String> live) {
        vocab.nearby(live);
        var contextNames=new LinkedHashSet<>(live.stream().filter(vocab.confirm::contains).sorted().toList());
        contextNames.addAll(hints);contextNames.addAll(live);
        for(String name:List.of("enderman","lopunny","hoglin","zoglin","pikachu"))
            if(vocab.names.containsKey(name)) contextNames.add(name);
        context=List.copyOf(contextNames);
        if (mentions!=null) { mentions.close(); mentions=null; }
        if (nearCommands!=null) { nearCommands.close(); nearCommands=null; }
        var alternatives=new LinkedHashSet<String>();
        boolean any=live.stream().anyMatch(vocab.spoken::containsValue);
        if(any) alternatives.addAll(vocab.spoken.keySet()); // an absent evolution must still own its complete phrase
        if(!alternatives.isEmpty()) {
            nearCommands=engine.grammar();
            commandGrammar(nearCommands,alternatives,Set.of());
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
        mentions.activate("sentence");
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

    private static void syntheticFault(Throwable error) {
        if("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")) && "1".equals(System.getenv("MOBSEAR_SYNTHETIC_WORKER_FAULTS")))
            error.printStackTrace(System.err);
    }

    private record Reply(int code,String hint) {}

    private Reply decode(byte[] pcm,boolean detail) {
        boolean voiced=false;
        for(int i=0;i+1<pcm.length;i+=2)
            if(Math.abs((int)(short)((pcm[i]&255)|(pcm[i+1]<<8)))>128) { voiced=true;break; }
        if(!voiced) return new Reply(0,"");
        var first=engine.recognize(pcm);
        String hint=detail?first.text():"";
        String plain=first.grammar()==dictation.id ? first.text() : "";
        CallVocabulary.Match exact=match(first.text());
        if(first.accepted() && exact!=null && first.confidence()>=.58 && !vocab.confirm.contains(exact.label())
                && (!plain.isEmpty() || !vocab.compoundPart(exact.label()) || first.text().toLowerCase(Locale.ROOT).startsWith("call "))
                && !vocab.partialCompound(exact.label(), first.text()) && !vocab.partialCompound(exact.label(),plain)
                && !CallVocabulary.rival(exact.label(),plain))
            return new Reply(exact.code(),"");
        if(REJECT.contains(String.join(" ",CallVocabulary.words(plain))) && exact==null) return new Reply(0,hint);
        // Independent open dictation for close registered names and conversational rivals.
        if(plain.isEmpty()) {
            commands.enabled(false);
            if(nearCommands!=null) nearCommands.enabled(false);
            try {
                var open=engine.recognize(pcm);
                plain=open.text();
                if(hint.isEmpty() && detail) hint=plain;
                var free=match(plain);
                if(open.accepted() && free!=null && open.confidence()>=.58 && !vocab.partialCompound(free.label(),plain) && !CallVocabulary.rival(free.label(),plain))
                    return new Reply(free.code(),"");
            } finally {
                commands.enabled(true);
                if(nearCommands!=null) nearCommands.enabled(true);
            }
        }
        if (vocab.unregisteredInflection(plain) && match(plain)==null) return new Reply(0,hint);
        if(phonemes!=null && CallVocabulary.words(plain).size()<=3) {
            phonemes.enabled(true);
            commands.enabled(false);
            if(nearCommands!=null) nearCommands.enabled(false);
            try {
                var pron=engine.recognize(pcm);
                var m=match(pron.text());
                if(pron.accepted() && pron.grammar()==phonemes.id && pron.confidence()>=.83 && m!=null &&
                        !vocab.conflicts(m.label(),plain)) return new Reply(m.code(),"");
            } finally {
                phonemes.enabled(false); commands.enabled(true);
                if(nearCommands!=null) nearCommands.enabled(true);
            }
        }
        if(words!=null) {
            String decoded;
            try { decoded=words.recognize(pcm,context); }
            catch(RuntimeException|LinkageError failed) {
                syntheticFault(failed);
                return new Reply(65534,"");
            }
            if(vocab.unregisteredInflection(decoded) && match(decoded)==null || vocab.splitInflection(decoded,plain))
                return new Reply(0,hint);
            var exactWord=match(decoded);
            if(exactWord!=null) {
                if(vocab.conflicts(exactWord.label(),plain) || vocab.partialCompound(exactWord.label(),decoded))
                    return new Reply(0,hint);
                return new Reply(exactWord.code(),"");
            }
            var pronouncedWord=vocab.spokenRecovery(decoded,plain);
            if(pronouncedWord!=null) return new Reply(pronouncedWord.code(),"");
            var relative=vocab.guess(decoded,plain);
            if(relative!=null) return new Reply(relative.code(),"");
            if(detail && hint.isEmpty()) hint=decoded;
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
                    if(candidate!=null && !vocab.conflicts(candidate.label(),plain)
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
        if(!first.accepted() || first.confidence()<.12 || plain.toLowerCase(Locale.ROOT).startsWith("call ")) {
            dictation.enabled(false);
            try {
                var restricted=engine.recognize(pcm);
                var m=match(restricted.text());
                if (m != null && restricted.accepted() && !vocab.confirm.contains(m.label())
                        && !vocab.partialCompound(m.label(), plain) && !CallVocabulary.rival(m.label(), plain)) {
                    // Both command passes must independently identify the same explicit split spelling.
                    // Free dictation must also hear the "call" prefix; ordinary large-catalog retries stay strict.
                    boolean corroboratedSplit = exact != null && exact.code() == m.code() && vocab.explicit.contains(m.label())
                            && first.grammar() == commands.id && first.confidence() >= .50
                            && first.text().toLowerCase(Locale.ROOT).startsWith("call ")
                            && plain.toLowerCase(Locale.ROOT).startsWith("call ")
                            && restricted.text().toLowerCase(Locale.ROOT).startsWith("call ");
                    double minimum = corroboratedSplit ? .82 : vocab.names.size() > 256 ? .90 : .72;
                    if (restricted.confidence() >= minimum) return new Reply(m.code(), "");
                }
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
                try {
                    service.words=new WordDecoder();
                    service.words.warm();
                } catch(IOException|RuntimeException|LinkageError unavailable) {
                    if(service.words!=null) service.words.close();
                    service.words=null;
                    syntheticFault(unavailable);
                }
                engine.recognize(new byte[16000]);
                out.writeByte(service.words==null ? 68 : 82);out.flush();
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
                if(service.phonemes!=null) service.phonemes.close();
                if(service.words!=null) service.words.close();
                service.dictation.close();service.commands.close();
            }
        } catch(Exception | LinkageError failure) {
            // Never print hypotheses or PCM. Hosted synthetic diagnostics may show a fixed ABI stage and HRESULT.
            syntheticFault(failure);
            System.exit(1);
        }
    }

}
