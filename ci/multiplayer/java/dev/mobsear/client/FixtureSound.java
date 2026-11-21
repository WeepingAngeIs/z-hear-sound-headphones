package dev.mobsear.client;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;

/** Hosted fixture only. Satisfies the ordinary Java Sound interface without enumerating physical devices. */
public final class FixtureSound {
    public static volatile boolean tone;
    public static final AtomicLong played = new AtomicLong();

    private FixtureSound() {}

    public static void install() {
        try {
            var boundary = VoicePreferences.class.getDeclaredField("sound");
            boundary.setAccessible(true);
            boundary.set(VoiceClient.options(), new VoiceDevices(List::of, FixtureSound::line));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to install disposable sound provider", ex);
        }
    }

    private static Line line(DataLine.Info info) {
        var cls = info.getLineClass();
        return (Line) Proxy.newProxyInstance(cls.getClassLoader(), new Class<?>[]{cls}, new java.lang.reflect.InvocationHandler() {
            private long nextFrame;
            private boolean open;
            @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "available" -> System.nanoTime() >= nextFrame ? VoiceAudio.FRAME_BYTES : 0;
                    case "close" -> { open = false; yield null; }
                    case "open" -> { open = true; yield null; }
                    case "getFormat" -> info.getFormats()[0];
                    case "isOpen", "isRunning", "isActive" -> open;
                    case "getLineInfo" -> info;
                    case "write" -> { played.incrementAndGet(); yield (int) args[2]; }
                    case "read" -> {
                        byte[] bytes = (byte[]) args[0];
                        int start = (int) args[1], count = (int) args[2];
                        int amplitude = tone ? 9100 : 0;
                        for (int n = 0; n < count / 2; n++) {
                            int sample = (int) (amplitude * Math.sin(n * Math.PI / 20));
                            bytes[start + 2*n] = (byte) sample;
                            bytes[start + 2*n + 1] = (byte) (sample >> 8);
                        }
                        nextFrame = System.nanoTime() + 20_000_000L;
                        yield count;
                    }
                    case "toString" -> "Hosted synthetic sound line";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
        });
    }

    /** The same component that the real menu row renders and narrates. */
    public static String rowStatus() {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (!(screen instanceof VoiceSettingsScreen)) return "";
        for (var widget : screen.children()) {
            if (widget instanceof OnlineVoiceList list && list.children().size() == 1) {
                return list.children().getFirst().status().getString();
            }
        }
        return "";
    }

    public static int sceneFrame() {
        return ((VoiceSettingsScreen) net.minecraft.client.Minecraft.getInstance().screen).sceneFrame();
    }
}
