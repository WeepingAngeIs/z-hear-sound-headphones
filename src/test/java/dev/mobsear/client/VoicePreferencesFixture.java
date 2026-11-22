package dev.mobsear.client;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
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
            checkLaunchModes(dir);
            check(file);
            checkBackdropStrips();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
        System.out.println("Voice preferences and device selection fixture passed.");
    }

    private static void checkLaunchModes(Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        Path saved = config.resolve("mobsear-client.properties");
        Path options = dir.resolve("options.txt");
        Path aliases = config.resolve("mobsear-aliases.properties");
        try {
            Files.writeString(options, "guiScale:2\nkey_key.forward:key.keyboard.w\n");
            var fresh = new VoicePreferences(saved);
            require(!fresh.callDebugLog(), "Private debug logging starts off");
            require(fresh.voiceActivated() && fresh.localMobCalls() && !fresh.muted() && !fresh.deafened(),
                    "Fresh install uses hands-free voice and local calls without opening a device");
            fresh.initializeProfile();
            Files.writeString(options, "key_key.mobsear.talk:key.keyboard.v\n");
            var restart = new VoicePreferences(saved);
            require(restart.voiceActivated() && restart.localMobCalls(), "Fresh defaults survive the first restart");
            for (boolean activation : List.of(true, false)) for (boolean calls : List.of(true, false)) {
                restart.setVoiceActivated(activation);
                restart.setLocalMobCalls(calls);
                restart.setMuted(true);
                restart.setCallDebugLog(calls);
                require(restart.save(), "Save explicit modes");
                restart = new VoicePreferences(saved);
                require(restart.callDebugLog() == calls && !restart.recognitionDiagnostics(), "Log opt-in is saved separately from HUD feedback");
                require(restart.voiceActivated() == activation && restart.localMobCalls() == calls && restart.muted(),
                        "Independent input, calls and mute choices survive restart");
            }
            String previous = "micMuted=true\ndeafened=true\nsensitivity=77\n";
            Files.writeString(saved, previous);
            var upgrade = new VoicePreferences(saved);
            upgrade.initializeProfile();
            require(!upgrade.voiceActivated() && !upgrade.localMobCalls() && upgrade.muted() && upgrade.deafened()
                    && upgrade.sensitivity() == 77 && Files.readString(saved).equals(previous), "0.3.4 settings preserved");
            Files.delete(saved);
            require(!new VoicePreferences(saved).voiceActivated() && !new VoicePreferences(saved).localMobCalls(),
                    "0.3.4 key bindings identify an upgrade without a preferences file");
            Files.delete(options);
            Files.writeString(aliases, "# saved mod aliases\n");
            require(!new VoicePreferences(saved).voiceActivated() && !new VoicePreferences(saved).localMobCalls(),
                    "0.3.4 alias file identifies an upgrade");
            Files.delete(aliases);
            var first = new VoicePreferences(saved);
            Files.writeString(saved, previous);
            first.initializeProfile();
            require(!first.voiceActivated() && !first.localMobCalls() && first.muted()
                    && Files.readString(saved).equals(previous), "Concurrent first launch honors the existing file");
            System.out.println("MOBSEAR_PREFS_PASS fresh restart 0.3.4-upgrade independent-opt-outs muted deafened");
        } finally {
            Files.deleteIfExists(saved);
            Files.deleteIfExists(aliases);
            Files.deleteIfExists(options);
            Files.deleteIfExists(config);
        }
    }

    private static void checkBackdropStrips() throws Exception {
        int sceneCount = 0;
        for (MenuTheme theme : MenuTheme.values()) {
            if (!theme.hasScene()) continue;
            sceneCount++;
            String path = "/assets/mobsear/textures/gui/themes/" + theme.id() + ".png";
            try (var stream = VoicePreferencesFixture.class.getResourceAsStream(path)) {
                require(stream != null, "Missing backdrop " + path);
                var strip = ImageIO.read(stream);
                require(strip != null && strip.getWidth() == 192 && strip.getHeight() == 768, "Eight-frame strip " + path);
                var frames = new ArrayList<int[]>();
                var stamps = new ArrayList<int[]>();
                for (int frame = 0; frame < 8; frame++) {
                    int[] pixels = strip.getRGB(0, frame * 96, 192, 96, null, 0, 192);
                    for (int pixel : pixels) require(pixel >>> 24 == 208, "Backdrop alpha in " + path);
                    int[] stamp = new int[48 * 24];
                    for (int y = 0; y < 24; y++) {
                        for (int x = 0; x < 48; x++) stamp[y * 48 + x] = pixels[(y * 4 + 2) * 192 + x * 4 + 2];
                    }
                    for (int[] old : stamps) require(!Arrays.equals(stamp, old), "Repeated GUI-scale phase in " + path);
                    frames.add(pixels);
                    stamps.add(stamp);
                }
                for (int i = 0; i < frames.size(); i++) {
                    int changed = 0;
                    int[] current = frames.get(i), next = frames.get((i + 1) % frames.size());
                    for (int j = 0; j < current.length; j++) if (current[j] != next[j]) changed++;
                    require(changed > 0 && changed < current.length * 0.15, "Abrupt backdrop transition in " + path);
                }
            }
        }
        require(sceneCount == 16, "Expected four animated options in each of four theme families");
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
        require(prefs.theme().equals("uwu_lotus") && prefs.volume() == 100 && prefs.gain(PLAYER) == 100, "Defaults");
        prefs.selectFamily("emo");
        require(prefs.theme().equals("emo_skulls"), "First family selection shows a scene");
        prefs.setTheme("emo");
        prefs.selectFamily("uwu");
        prefs.setTheme("uwu_blossom");
        prefs.selectFamily("uwu");
        require(prefs.theme().equals("uwu_blossom"), "Clicking current family preserves selected scene");
        prefs.selectFamily("emo");
        require(prefs.theme().equals("emo"), "Explicit Original remains selectable and is remembered");
        prefs.selectFamily("uwu");
        require(prefs.theme().equals("uwu_blossom"), "Returning to family restores scene");
        require(!prefs.muted() && !prefs.deafened() && !prefs.noiseSuppression() && prefs.sensitivity() == 50,
                "Default audio controls");
        require(!prefs.pitchEnabled() && prefs.pitchCents() == 0 && prefs.pitchPreset().equals("natural")
                && prefs.customPitchCents() == 0, "Default is natural/bypass");
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
        prefs.setPitchEnabled(true);
        prefs.setPitchCents(-32_000);
        require(prefs.pitchCents() == -VoicePreferences.PITCH_RANGE_CENTS, "Pitch lower bound");
        prefs.setPitchCents(32_000);
        require(prefs.pitchCents() == VoicePreferences.PITCH_RANGE_CENTS, "Pitch upper bound");
        prefs.setPitchCents(367);
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
        require(prefs.pitchEnabled() && prefs.pitchCents() == 367, "Persist custom pitch independent of presets");
        prefs.setPitchPreset("deep");
        prefs.setPitchEnabled(false);
        require(prefs.save(), "Save bypass and selected quick preset");
        prefs = new VoicePreferences(file, sound);
        require(!prefs.pitchEnabled() && prefs.pitchCents() == VoicePreferences.DEEP_CENTS
                && prefs.pitchPreset().equals("deep") && prefs.customPitchCents() == 367, "Persist separate preset and custom position");
        prefs.setPitchPreset("squeaky");
        require(prefs.pitchCents() == VoicePreferences.SQUEAKY_CENTS && prefs.customPitchCents() == 367,
                "A quick preset should retain the manual position");
        prefs.setPitchPreset("custom");
        require(prefs.pitchCents() == 367 && !prefs.pitchEnabled(), "Restoring custom must leave bypass under user control");
        prefs.setPitchPreset("natural");
        require(prefs.pitchCents() == 0 && prefs.customPitchCents() == 367, "Natural preset discarded custom position");
        prefs.setPitchEnabled(true);
        prefs.resetPitch();
        require(prefs.save(), "Save pitch-only reset");
        prefs = new VoicePreferences(file, sound);
        require(!prefs.pitchEnabled() && prefs.pitchCents() == 0 && prefs.customPitchCents() == 0
                && prefs.pitchPreset().equals("natural"), "Pitch-only reset defaults");
        require(prefs.theme().equals("amethyst") && prefs.muted() && prefs.deafened() && prefs.noiseSuppression()
                && prefs.volume() == 133 && prefs.gain(PLAYER) == 200, "Pitch reset changed unrelated settings");
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
        require(prefs.theme().equals("uwu_lotus") && prefs.volume() == 100 && prefs.gain(PLAYER) == 200,
                "Invalid individual preferences");
        require(prefs.micStyle().equals("auto") && prefs.resolvedMicStyle().equals("uwu")
                && prefs.effects().equals("full") && prefs.accent().equals("auto") && prefs.sensitivity() == 50
                && prefs.hudVisible(), "Invalid options recover independently");
        require(prefs.save() && Files.readString(file).contains("future=value"), "Preserve unknown keys");
        checkPitchAccounts(file, sound);
    }

    private static void checkPitchAccounts(Path file, VoiceDevices sound) throws Exception {
        var first = new UUID(0, 101);
        var second = new UUID(0, 102);
        Files.writeString(file, "pitchEnabled=true\npitchCents=367\npitchPreset=squeaky\nvolume=135\n");
        var prefs = new VoicePreferences(file, sound);
        prefs.bindPitchAccount(first);
        require(prefs.pitchEnabled() && prefs.pitchPreset().equals("squeaky") && prefs.customPitchCents() == 367,
                "Existing local settings did not migrate to their first account");
        var other = new VoicePreferences(file, second);
        require(!other.pitchEnabled() && other.pitchPreset().equals("natural") && other.customPitchCents() == 0
                && other.volume() == 135, "The next local account inherited someone else's voice effect");
        other.setPitchCents(-281);
        other.setPitchPreset("deep");
        other.setPitchEnabled(true);
        require(other.save(), "Save second account selections");
        prefs = new VoicePreferences(file, first);
        require(prefs.pitchPreset().equals("squeaky") && prefs.customPitchCents() == 367, "First account lost its preset");
        prefs.bindPitchAccount(second);
        require(prefs.pitchPreset().equals("deep") && prefs.customPitchCents() == -281, "Account switch did not restore pitch");
        prefs.resetPitch();
        require(prefs.save(), "Reset second account");
        prefs = new VoicePreferences(file, first);
        require(prefs.pitchEnabled() && prefs.pitchPreset().equals("squeaky") && prefs.customPitchCents() == 367
                && prefs.volume() == 135, "Reset changed another local account");
        prefs.bindPitchAccount(second);
        require(!prefs.pitchEnabled() && prefs.pitchCents() == 0 && prefs.customPitchCents() == 0, "Reset scoped account");
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
