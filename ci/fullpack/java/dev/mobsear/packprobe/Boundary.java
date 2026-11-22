package dev.mobsear.packprobe;

import dev.mobsear.client.VoiceClient;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import net.minecraft.network.chat.Component;

/** The only test input is a generated PCM stream delivered through the Java Sound line. */
final class Boundary {
    private static byte[] speech;
    private static int offset;
    static volatile int nonzeroFrames;
    static volatile long lastSignalAt;

    static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static Object get(Object receiver, String name) throws ReflectiveOperationException {
        return field(receiver.getClass(), name).get(receiver);
    }

    static Object client() throws ReflectiveOperationException {
        return field(VoiceClient.class, "instance").get(null);
    }

    static String readiness() throws ReflectiveOperationException {
        var worker = get(get(client(), "session"), "speech");
        var method = worker.getClass().getDeclaredMethod("status");
        method.setAccessible(true);
        return method.invoke(worker).toString();
    }

    record Decision(long number, boolean matched, boolean timely, long ageMillis) {}

    static Decision decision() throws ReflectiveOperationException {
        var speech = get(get(client(), "session"), "speech");
        java.lang.reflect.Method accessor;
        try {
            accessor = speech.getClass().getMethod("lastDecision");
        } catch (NoSuchMethodException previousVersion) {
            return null;
        }
        var result = accessor.invoke(speech);
        if (result == null) return null;
        var type = result.getClass();
        return new Decision((long) type.getMethod("number").invoke(result),
                (boolean) type.getMethod("matched").invoke(result), (boolean) type.getMethod("timely").invoke(result),
                (long) type.getMethod("ageMillis").invoke(result));
    }

    static Process desktopProcess() throws ReflectiveOperationException {
        var speech = get(get(client(), "session"), "speech");
        synchronized (speech) {
            var session = get(speech, "session");
            return session == null ? null : (Process) get(session, "helper");
        }
    }

    static int startupProgress() throws ReflectiveOperationException {
        var speech=get(get(client(),"session"),"speech");
        synchronized (speech) {
            var session=get(speech,"session");
            return session==null ? -1 : (int)get(session,"startupStep");
        }
    }

    private static Object wrappedSegmenter;

    static void observeGeneratedSegments(Consumer<byte[]> sink) throws ReflectiveOperationException {
        var speech=get(get(client(),"session"),"speech");
        synchronized (speech) {
            var session=get(speech,"session");
            if (session==null) return;
            var segmenter=get(session,"utterances");
            if (segmenter==wrappedSegmenter) return;
            var field=field(segmenter.getClass(),"output");
            @SuppressWarnings("unchecked") var original=(Consumer<byte[]>)field.get(segmenter);
            Consumer<byte[]> observed=pcm->{
                var generated=pcm.clone();
                original.accept(pcm);
                sink.accept(generated);
            };
            field.set(segmenter,observed);
            wrappedSegmenter=segmenter;
        }
    }

    // Re-create only public registry labels. Never read a player's custom aliases or audio.
    static byte[] publicVocabulary() throws Exception {
        var speech = get(get(client(), "session"), "speech");
        var registry = get(speech, "catalogs");
        var type = Class.forName("dev.mobsear.client.SpeechGrammar");
        var constructor = type.getDeclaredConstructor(Path.class, registry.getClass());
        constructor.setAccessible(true);
        var method = type.getDeclaredMethod("writeTo", DataOutputStream.class);
        method.setAccessible(true);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            method.invoke(constructor.newInstance(null, registry), output);
        }
        return bytes.toByteArray();
    }

    static Component noticeComponent(String accessor) throws ReflectiveOperationException {
        var method = Class.forName("dev.mobsear.client.MobCallNotice").getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (Component) method.invoke(null);
    }

    static String notice(String accessor) throws ReflectiveOperationException {
        var component = noticeComponent(accessor);
        return component == null ? "" : component.getString();
    }

    static String noticeKey(String accessor) throws ReflectiveOperationException {
        var component = noticeComponent(accessor);
        return component != null && component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                ? text.getKey() : "";
    }

    static Component prompt() throws ReflectiveOperationException {
        var method = VoiceClient.class.getDeclaredMethod("speechTestPrompt");
        method.setAccessible(true);
        return (Component) method.invoke(null);
    }

    static String promptKey() throws ReflectiveOperationException {
        var component = prompt();
        return component != null && component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                ? text.getKey() : "";
    }

    static void install() throws ReflectiveOperationException {
        var deviceType = Class.forName("dev.mobsear.client.VoiceDevices");
        var factoryType = Class.forName("dev.mobsear.client.VoiceDevices$DefaultLine");
        var factory = Proxy.newProxyInstance(factoryType.getClassLoader(), new Class<?>[]{factoryType},
                (proxy, method, args) -> line((DataLine.Info) args[0]));
        var constructor = deviceType.getDeclaredConstructor(Supplier.class, factoryType);
        constructor.setAccessible(true);
        var prefs = VoiceClient.options();
        field(prefs.getClass(), "sound").set(prefs, constructor.newInstance((Supplier<List<?>>) List::of, factory));
    }

    static synchronized void say(byte[] input) {
        if (speech != null) throw new IllegalStateException("Overlapping test phrases");
        speech = input;
        offset = 0;
        nonzeroFrames = 0;
        lastSignalAt = 0;
    }

    static synchronized boolean consumed() { return speech == null; }

    private static synchronized void fill(byte[] dest, int start, int count) {
        Arrays.fill(dest, start, start + count, (byte) 0);
        if (speech == null) return;
        int copy = Math.min(count, speech.length - offset);
        System.arraycopy(speech, offset, dest, start, copy);
        for (int i = start; i < start + copy; i++) {
            if (dest[i] != 0) {
                nonzeroFrames++;
                lastSignalAt=System.nanoTime();
                break;
            }
        }
        offset += copy;
        if (offset == speech.length) speech = null;
    }

    private static Line line(DataLine.Info info) {
        var type = info.getLineClass();
        return (Line) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new java.lang.reflect.InvocationHandler() {
            long next;
            boolean open;
            @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "available" -> System.nanoTime() >= next ? 640 : 0;
                    case "close" -> { open = false; yield null; }
                    case "open" -> { open = true; yield null; }
                    case "isOpen", "isRunning", "isActive" -> open;
                    case "getFormat" -> info.getFormats()[0];
                    case "getLineInfo" -> info;
                    case "write" -> args[2];
                    case "read" -> {
                        fill((byte[]) args[0], (int) args[1], (int) args[2]);
                        next = System.nanoTime() + 20_000_000L;
                        yield args[2];
                    }
                    case "toString" -> "Generated speech line";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
        });
    }
}
