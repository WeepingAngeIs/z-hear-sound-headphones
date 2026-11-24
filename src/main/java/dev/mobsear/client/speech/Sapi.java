package dev.mobsear.client.speech;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Windows SDK sapi.h interfaces. All calls, resources and COM references belong to the one speech thread. */
final class Sapi implements AutoCloseable {
    private static final int PTR = Native.POINTER_SIZE;
    private static final int END = 34, RECOGNIZED = 38, REJECTED = 43;
    private static final int END_RULE = -1;
    private static final long INTEREST = (1L << 30) | (1L << 33) | (1L << END) | (1L << RECOGNIZED) | (1L << REJECTED);
    private static final NativeLibrary OLE = system("ole32");
    private static final NativeLibrary SHELL = system("shlwapi");

    private Pointer recognizer, context;
    private long nextGrammar = 1;
    int language;

    record Result(long grammar, String text, String label, float confidence, float labelConfidence, boolean accepted) {
        static final Result NONE = new Result(0, "", "", 0, 0, false);
    }

    private static NativeLibrary system(String name) {
        return NativeLibrary.getInstance(Path.of(System.getenv("SystemRoot"), "System32", name + ".dll").toString());
    }

    static Memory guid(String id) {
        UUID uuid = UUID.fromString(id);
        long hi = uuid.getMostSignificantBits(), lo = uuid.getLeastSignificantBits();
        var bytes = new Memory(16);
        bytes.setInt(0, (int) (hi >>> 32));
        bytes.setShort(4, (short) (hi >>> 16));
        bytes.setShort(6, (short) hi);
        for (int i = 0; i < 8; i++) bytes.setByte(8+i, (byte) (lo >>> (56-8*i)));
        return bytes;
    }

    private static Function api(NativeLibrary lib, String name) {
        return lib.getFunction(name, Function.ALT_CONVENTION);
    }

    static int call(Pointer self, int slot, Object... args) {
        Object[] all = new Object[1 + args.length];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return Function.getFunction(self.getPointer(0).getPointer((long) slot * PTR),
                Function.ALT_CONVENTION).invokeInt(all);
    }

    static void ok(int result, String stage) {
        if (result < 0) throw new IllegalStateException(stage + " HRESULT 0x" + Integer.toHexString(result));
    }

    private static Pointer create(String cls, String iid) {
        var out = new PointerByReference();
        try (var c = guid(cls); var i = guid(iid)) {
            ok(api(OLE, "CoCreateInstance").invokeInt(new Object[]{c, null, 1, i, out}), "CoCreateInstance");
        }
        return out.getValue();
    }

    static void release(Pointer p) { if (p != null) call(p, 2); }
    static WString wide(String s) { return new WString(s); }

    Sapi() {
        if (PTR != 8) throw new IllegalStateException("64-bit Windows speech runtime required");
        ok(api(OLE, "CoInitializeEx").invokeInt(new Object[]{null, 0}), "CoInitializeEx");
        try {
            recognizer = create("41B89B6B-9399-11D2-9623-00C04F8EE628", "C2B5F241-DAA0-4507-9E16-5A1EAA2B7A5C");
            selectDesktopEnglish();
            call(recognizer, 3, wide("AdaptationOn"), 0);
            // Set memory input before creating a context or activating any grammar. No default audio device is opened.
            setInput(new byte[640]);
            ok(call(recognizer, 17, 0), "initial inactive state");
            var result = new PointerByReference();
            ok(call(recognizer, 12, result), "CreateRecoContext");
            context = result.getValue();
            ok(call(context, 7), "SetNotifyWin32Event");
            ok(call(context, 10, INTEREST, INTEREST), "SetInterest");
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    private static String ownedString(Pointer p) {
        if (p == null) return "";
        try { return p.getWideString(0); }
        finally { api(OLE, "CoTaskMemFree").invokeVoid(new Object[]{p}); }
    }

    private void selectDesktopEnglish() {
        Pointer category = create("A910187F-0C7A-45AC-92CC-59EDAFB77B53", "2D3D3845-39AF-4850-BBF9-40B49780011D");
        Pointer tokens = null;
        try {
            ok(call(category, 15, wide("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Recognizers"), 0), "recognizer category");
            var enumeration = new PointerByReference();
            ok(call(category, 18, null, null, enumeration), "installed desktop recognizers");
            tokens = enumeration.getValue();
            var count = new IntByReference();
            ok(call(tokens, 8, count), "token count");
            for (int i = 0; i < count.getValue(); i++) {
                var out = new PointerByReference();
                ok(call(tokens, 7, i, out), "token");
                Pointer token = out.getValue(), attrs = null;
                try {
                    ok(call(token, 16, out), "token id");
                    String id = ownedString(out.getValue());
                    if (!id.matches("(?i).*\\\\MS-\\d+-\\d+-DESK")) continue;
                    if (call(token, 9, wide("Attributes"), out) < 0) continue;
                    attrs = out.getValue();
                    if (call(attrs, 6, wide("Language"), out) < 0) continue;
                    for (String value : ownedString(out.getValue()).split(";")) {
                        if (!value.trim().matches("[0-9A-Fa-f]{1,4}")) continue;
                        int lang = Integer.parseUnsignedInt(value.trim(), 16);
                        if ((lang & 0x3ff) == 9) {
                            ok(call(recognizer, 7, token), "select English recognizer");
                            language = lang;
                            return;
                        }
                    }
                } finally { release(attrs); release(token); }
            }
            throw new IllegalStateException("English desktop recognition unavailable");
        } finally { release(tokens); release(category); }
    }

    private void setInput(byte[] pcm) {
        Pointer base = api(SHELL, "SHCreateMemStream").invokePointer(new Object[]{pcm, pcm.length});
        if (base == null) throw new IllegalStateException("Create memory audio");
        Pointer stream = null;
        try (var wave = guid("C31ADBAE-527F-4FF5-A230-F62BB61FF70C"); var fmt = new Memory(18)) {
            fmt.clear();
            fmt.setShort(0, (short) 1); fmt.setShort(2, (short) 1);
            fmt.setInt(4, 16000); fmt.setInt(8, 32000);
            fmt.setShort(12, (short) 2); fmt.setShort(14, (short) 16);
            stream = create("715D9C59-4442-11D2-9605-00C04F8EE628", "12E3CCA9-7518-44C5-A5E7-BA5A79CB929E");
            ok(call(stream, 15, base, wave, fmt), "format PCM stream");
            ok(call(recognizer, 9, stream, 0), "SetInput");
        } finally { release(stream); release(base); }
    }

    Grammar grammar() {
        var out = new PointerByReference();
        long id = nextGrammar++;
        ok(call(context, 14, id, out), "CreateGrammar");
        return new Grammar(id, out.getValue());
    }

    final class Grammar implements AutoCloseable {
        final long id;
        private final Pointer object;
        Grammar(long id, Pointer object) { this.id = id; this.object = object; }
        void reset() { ok(call(object, 3, (short) language), "ResetGrammar"); }
        Pointer rule(String name, int number, boolean top) {
            var out = new PointerByReference();
            ok(call(object, 4, wide(name), number, top ? 35 : 32, 1, out), "GetRule");
            return out.getValue();
        }
        Pointer state(Pointer rule) {
            var out = new PointerByReference();
            ok(call(object, 6, rule, out), "CreateNewState");
            return out.getValue();
        }
        void word(Pointer from, Pointer to, String words) {
            ok(call(object, 7, from, to, words == null ? null : wide(words), wide(" "), 1, 1f, null), "AddWordTransition");
        }
        void branch(Pointer from, Pointer to, Pointer target) {
            ok(call(object, 8, from, to, target, 1f, null), "AddRuleTransition");
        }
        void freeWords(Pointer from, Pointer to) { branch(from, to, Pointer.createConstant(-2L)); }
        void priority(String name,int value) {
            var out=new PointerByReference();
            try(var iid=guid("4B37BC9E-9ED6-44A3-93D3-18F022B79EC3")) {
                ok(call(object,0,iid,out),"extended grammar");
                try { ok(call(out.getValue(),6,wide(name),0,value),"command priority"); }
                finally { release(out.getValue()); }
            }
        }
        void commit() { ok(call(object, 10, 0), "Commit"); }
        void activate(String name) { ok(call(object, 18, wide(name), null, 1), "activate "+name); }
        void phonetics(java.util.Set<String> registered) throws java.io.IOException {
            var phones=java.util.Map.of(
                    "enderman",java.util.List.of("eh n d er m ae n","eh n d er m ax n"),
                    "lopunny",java.util.List.of("l aa p ax n iy","l aa p ah n iy","l ow p ah n iy","l aa p b ah n iy"),
                    "geodude",java.util.List.of("jh iy ow d uw d","jh iy ow d y uw d","jh iy ax d uw d"),
                    "hoglin",java.util.List.of("h aa g l ih n","h ao g l ih n"),
                    "zoglin",java.util.List.of("z aa g l ih n","z ao g l ih n"));
            var xml=new StringBuilder("""
                    <grammar xmlns="http://www.w3.org/2001/06/grammar" xmlns:sapi="http://schemas.microsoft.com/Speech/2002/06/SRGSExtensions"
                      version="1.0" xml:lang="en-US" root="Pronunciations" sapi:alphabet="x-microsoft-sapi">
                    <rule id="Pronunciations" scope="public"><one-of>
                    """);
            for(String ordinary:java.util.List.of("poponee","buneary","endermite","ender dragon","ender guardian",
                    "slender man","bunny","hold on","logged in","huddling","jogging","geode","good dude"))
                xml.append("<item>").append(ordinary).append("</item>");
            phones.forEach((label,forms)->{
                if(registered.contains(label)) for(String form:forms) for(String prefix:java.util.List.of("","hey ","call "))
                    xml.append("<item>").append(prefix).append("<token sapi:pron='").append(form).append("'>")
                            .append(label).append("</token></item>");
            });
            xml.append("</one-of></rule></grammar>");
            Path path=java.nio.file.Files.createTempFile("mobsear-pronunciation-",".grxml");
            try {
                java.nio.file.Files.writeString(path,xml);
                ok(call(object,13,wide(path.toString()),0),"load pronunciation grammar");
            } finally { java.nio.file.Files.deleteIfExists(path); }
        }
        void dictation() {
            ok(call(object, 20, null, 0), "LoadDictation");
            ok(call(object, 22, 1), "SetDictationState");
        }
        void enabled(boolean on) { ok(call(object, 26, on ? 1 : 0), "SetGrammarState"); }
        @Override public void close() { release(object); }
    }

    Result recognize(byte[] pcm) {
        ok(call(recognizer, 17, 3), "purge previous input");
        drain(false);
        setInput(pcm);
        ok(call(recognizer, 17, 2), "start PCM input");
        Result best = Result.NONE;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (System.nanoTime() < end) {
            int ready = call(context, 8, 200);
            ok(ready, "WaitForNotifyEvent");
            if (ready != 0) continue;
            var batch = drain(true);
            if (batch.best != Result.NONE && (!best.accepted() || batch.best.accepted())) best = batch.best;
            if (batch.end) return best;
        }
        throw new IllegalStateException("SAPI input timeout");
    }

    private record Batch(boolean end, Result best) {}
    private Batch drain(boolean read) {
        Result best = Result.NONE;
        boolean end = false;
        try (var event = new Memory(32)) {
            var count = new IntByReference();
            for (;;) {
                ok(call(context, 11, 1, event, count), "GetEvents");
                if (count.getValue() == 0) break;
                int type = event.getShort(0), param = event.getShort(2);
                Pointer data = event.getPointer(24);
                try {
                    if (type == END) end = true;
                    if (read && (type == RECOGNIZED || type == REJECTED) && data != null) {
                        var result = phrase(data, type == RECOGNIZED);
                        if (!best.accepted() || result.accepted()) best = result;
                    }
                } finally {
                    if (param == 1 || param == 2) release(data);
                    else if ((param == 3 || param == 4) && data != null)
                        api(OLE, "CoTaskMemFree").invokeVoid(new Object[]{data});
                }
            }
        }
        return new Batch(end, best);
    }

    private String text(Pointer result, int first, int length) {
        var text = new PointerByReference();
        ok(call(result, 5, first, length, 0, text, null), "GetText");
        return ownedString(text.getValue());
    }

    private Result phrase(Pointer result, boolean accepted) {
        var out = new PointerByReference();
        ok(call(result, 3, out), "GetPhrase");
        Pointer p = out.getValue();
        try {
            // 64-bit SPPHRASE_50.Rule follows three ULONG fields and alignment padding.
            Pointer rule = p.share(48), child = rule.getPointer(32);
            String all = text(result, END_RULE, END_RULE);
            String label = child == null ? all : text(result, child.getInt(12), child.getInt(16));
            float confidence = rule.getFloat(40);
            return new Result(p.getLong(8), all, label, confidence, child == null ? confidence : child.getFloat(40), accepted);
        } finally { api(OLE, "CoTaskMemFree").invokeVoid(new Object[]{p}); }
    }

    @Override public void close() {
        if (recognizer != null) call(recognizer, 17, 3);
        release(context); release(recognizer);
        context = recognizer = null;
        api(OLE, "CoUninitialize").invokeVoid(new Object[0]);
    }
}
