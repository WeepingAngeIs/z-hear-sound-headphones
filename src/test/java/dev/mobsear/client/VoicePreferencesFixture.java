package dev.mobsear.client;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** Disposable properties file and fake mixers only. No host mixer, microphone or speaker is used. */
public final class VoicePreferencesFixture {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mobsear-preferences-");
        Path file = dir.resolve("voice.properties");
        try {
            check(file);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
        System.out.println("Voice preferences and device selection fixture passed.");
    }

    private static void check(Path file) throws Exception {
        var devices = new ArrayList<Mixer>();
        var defaults = new AtomicInteger();
        var enumerations = new AtomicInteger();
        var requests = new AtomicInteger();
        var sound = new VoiceDevices(() -> {
            enumerations.incrementAndGet();
            return List.copyOf(devices);
        }, info -> {
            defaults.incrementAndGet();
            return fakeLine(info.getLineClass());
        });
        var prefs = new VoicePreferences(file, sound);
        require(prefs.theme().equals("uwu") && prefs.volume() == 100 && prefs.gain(PLAYER) == 100, "Defaults");
        require(!prefs.muted() && !prefs.deafened() && !prefs.noiseSuppression() && prefs.sensitivity() == 50,
                "Default audio controls");
        require(prefs.hudVisible() && prefs.nameplates() && prefs.effects().equals("full")
                && prefs.accent().equals("auto") && prefs.micStyle().equals("auto"), "Default appearance controls");
        require(enumerations.get() == 0 && !Files.exists(file), "Construction had side effects");
        for (var theme : MenuTheme.values()) {
            prefs.setTheme(theme.id());
            require(prefs.save(), "Save " + theme.id());
            var reloaded = new VoicePreferences(file, sound);
            require(reloaded.theme().equals(theme.id()) && reloaded.resolvedMicStyle().equals(theme.family()),
                    "Persist theme and resolve automatic style " + theme.id());
        }
        prefs.setMuted(true);
        prefs.setDeafened(true);
        prefs.setNoiseSuppression(true);
        prefs.setSensitivity(140);
        prefs.setHudVisible(false);
        prefs.setNameplates(false);
        prefs.setEffects("reduced");
        prefs.setAccent("mint");
        prefs.setMicStyle("emo");
        prefs.setTheme("AMETHYST");
        prefs.setVolume(133);
        prefs.setGain(PLAYER, 999);
        require(prefs.gain(PLAYER) == 200 && prefs.gain(new UUID(0, 1)) == 100, "Gain clamp and isolation");
        prefs.setVolume(-10);
        require(prefs.volume() == 0, "Master volume lower bound");
        prefs.setVolume(133);

        Mixer mic = mixer("Fixture USB microphone", true, 16000, requests);
        Mixer speaker = mixer("Fixture headset", false, 16000, requests);
        devices.add(mic);
        devices.add(speaker);
        devices.add(mixer("Unsupported audio format", true, 48000, requests));
        var microphones = prefs.devices(true);
        var speakers = prefs.devices(false);
        require(microphones.size() == 2 && speakers.size() == 2, "Direction and format filtering");
        require(microphones.getFirst().id().isEmpty() && speakers.getFirst().id().isEmpty(), "Default first");
        require(requests.get() == 0 && defaults.get() == 0, "Enumeration requested a line");
        String micId = microphones.get(1).id();
        String speakerId = speakers.get(1).id();
        prefs.setDevice(true, micId);
        prefs.setDevice(false, speakerId);
        prefs.setDevice(true, micId);
        require(prefs.deviceRevision() == 2, "Only device changes should advance revision");
        require(prefs.save(), "Settings save");
        prefs = new VoicePreferences(file, sound);
        require(prefs.theme().equals("amethyst") && prefs.volume() == 133 && prefs.gain(PLAYER) == 200,
                "Settings round trip");
        require(prefs.muted() && prefs.deafened() && prefs.noiseSuppression() && prefs.sensitivity() == 100,
                "Persist audio and clamp sensitivity");
        require(!prefs.hudVisible() && !prefs.nameplates() && prefs.effects().equals("reduced")
                && prefs.accent().equals("mint") && prefs.resolvedMicStyle().equals("emo"), "Independent style and local appearance");
        require(prefs.device(true).equals(micId) && prefs.device(false).equals(speakerId), "Device ID round trip");

        var decoyRequests = new AtomicInteger();
        devices.addFirst(mixer("Earlier enumerated mic", true, 16000, decoyRequests));
        require(prefs.microphone(FORMAT) != null && prefs.speaker(FORMAT) != null, "Selected line lookup");
        require(requests.get() == 2 && decoyRequests.get() == 0 && defaults.get() == 0,
                "Selection used wrong provider");
        devices.remove(mic);
        requireUnavailable(prefs);
        var missing = prefs.devices(true).getLast();
        require(missing.id().equals(micId) && missing.label().equals("Fixture USB microphone (unavailable)"),
                "Missing device should retain its label");
        devices.add(mic);
        devices.add(mixer("Fixture USB microphone", true, 16000, requests));
        requireUnavailable(prefs);
        require(defaults.get() == 0, "Missing or ambiguous device silently used default");
        prefs.setDevice(true, "");
        require(prefs.microphone(FORMAT) != null && defaults.get() == 1, "Explicit system default");
        prefs.setGain(PLAYER, 100);
        require(prefs.save() && new VoicePreferences(file, sound).gain(PLAYER) == 100, "Reset player gain");

        String previous = Files.readString(file);
        require(!new VoicePreferences(file.resolve("nested"), sound).save(), "Report failed save");
        require(Files.readString(file).equals(previous), "Failed save damaged previous settings");
        Files.writeString(file, "theme=wrong\nvolume=oops\ngain." + PLAYER + "=400\nfuture=value\n"
                + "micStyle=oops\neffects=oops\naccent=oops\nsensitivity=oops\nhudVisible=oops\n");
        prefs = new VoicePreferences(file, sound);
        require(prefs.theme().equals("uwu") && prefs.volume() == 100 && prefs.gain(PLAYER) == 200,
                "Invalid individual preferences");
        require(prefs.micStyle().equals("auto") && prefs.resolvedMicStyle().equals("uwu")
                && prefs.effects().equals("full") && prefs.accent().equals("auto") && prefs.sensitivity() == 50
                && prefs.hudVisible(), "Invalid options recover independently");
        require(prefs.save() && Files.readString(file).contains("future=value"), "Preserve unknown keys");
    }

    private static void requireUnavailable(VoicePreferences prefs) throws Exception {
        try {
            prefs.microphone(FORMAT);
            throw new AssertionError("Missing or ambiguous selected mic was accepted");
        } catch (LineUnavailableException expected) {
            // An explicit selection must never resolve to the system default.
        }
    }

    private static Mixer mixer(String name, boolean capture, int rate, AtomicInteger requests) {
        Mixer.Info metadata = new Mixer.Info(name, "Fixture", "Synthetic device", "1.0") {};
        Class<? extends DataLine> direction = capture ? TargetDataLine.class : SourceDataLine.class;
        return (Mixer) Proxy.newProxyInstance(VoicePreferencesFixture.class.getClassLoader(),
                new Class<?>[]{Mixer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMixerInfo" -> metadata;
                    case "isLineSupported" -> {
                        DataLine.Info request = (DataLine.Info) args[0];
                        yield request.getLineClass().equals(direction) && request.getFormats()[0].getSampleRate() == rate;
                    }
                    case "getLine" -> {
                        requests.incrementAndGet();
                        yield fakeLine(direction);
                    }
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected mixer operation " + method.getName());
                });
    }

    private static Line fakeLine(Class<?> lineType) {
        return (Line) Proxy.newProxyInstance(VoicePreferencesFixture.class.getClassLoader(),
                new Class<?>[]{lineType}, (proxy, method, args) -> {
                    throw new AssertionError("Device selection opened or accessed a line");
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
