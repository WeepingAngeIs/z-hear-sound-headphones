package dev.mobsear.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** Local device and playback preferences. Loading settings never accesses audio devices. */
public final class VoicePreferences {
    public static final int PITCH_RANGE_CENTS = 1200;
    public static final int SQUEAKY_CENTS = 700;
    public static final int DEEP_CENTS = -500;
    public record Device(String id, String label) {}

    private final Path file;
    private final VoiceDevices sound;
    private final Properties values = new Properties();
    private static final List<String> PITCH_KEYS = List.of("pitchEnabled", "pitchCents", "pitchPreset");
    private UUID pitchAccount;
    private String pitchPrefix = "";
    private long deviceRevision;
    private long inputRevision;

    public VoicePreferences(Path file) {
        this(file, new VoiceDevices());
    }

    public VoicePreferences(Path file, UUID localUser) {
        this(file);
        bindPitchAccount(localUser);
    }

    public synchronized void bindPitchAccount(UUID localUser) {
        Objects.requireNonNull(localUser);
        if (localUser.equals(pitchAccount)) return;
        pitchAccount = localUser;
        pitchPrefix = "account." + localUser + ".";
        // The first local account to open an older profile retains the former unscoped settings.
        boolean migrated = false;
        for (String key : PITCH_KEYS) {
            Object legacy = values.remove(key);
            if (legacy == null) continue;
            values.putIfAbsent(pitchPrefix + key, legacy);
            migrated = true;
        }
        if (migrated) save();
    }

    VoicePreferences(Path file, VoiceDevices sound) {
        this.file = file.toAbsolutePath().normalize();
        this.sound = sound;
        try (var reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            values.clear();
        }
    }

    public synchronized String theme() {
        return MenuTheme.fromId(values.getProperty("theme")).id();
    }

    public synchronized void setTheme(String theme) {
        var choice = MenuTheme.fromId(theme);
        values.setProperty("theme", choice.id());
        values.setProperty("backdrop." + choice.family(), choice.id());
    }

    /** First visits show an animated scene. Returning to a family restores its explicit backdrop, including Original. */
    public synchronized void selectFamily(String family) {
        var current = MenuTheme.fromId(theme());
        if (current.family().equals(family)) return;
        values.setProperty("backdrop." + current.family(), current.id());
        var firstScene = MenuTheme.fromId(family).next();
        String saved = values.getProperty("backdrop." + family);
        var remembered = MenuTheme.fromId(saved);
        setTheme(saved != null && remembered.family().equals(family) ? remembered.id() : firstScene.id());
    }

    public synchronized boolean muted() { return flag("micMuted", false); }
    public synchronized void setMuted(boolean value) {
        if (value != muted()) inputRevision++;
        setFlag("micMuted", value);
    }
    public synchronized boolean deafened() { return flag("deafened", false); }
    public synchronized void setDeafened(boolean value) {
        if (value != deafened()) inputRevision++;
        setFlag("deafened", value);
    }
    synchronized long inputRevision() { return inputRevision; }
    public synchronized boolean noiseSuppression() { return flag("noiseSuppression", false); }
    public synchronized void setNoiseSuppression(boolean value) { setFlag("noiseSuppression", value); }
    public synchronized boolean recognitionDiagnostics() { return flag("recognitionDiagnostics", false); }
    public synchronized void setRecognitionDiagnostics(boolean value) { setFlag("recognitionDiagnostics", value); }
    public synchronized boolean pitchEnabled() { return flag(pitchPrefix + "pitchEnabled", false); }
    public synchronized void setPitchEnabled(boolean value) { setFlag(pitchPrefix + "pitchEnabled", value); }
    public synchronized int customPitchCents() {
        try { return Math.clamp(Integer.parseInt(values.getProperty(pitchPrefix + "pitchCents", "0")), -PITCH_RANGE_CENTS, PITCH_RANGE_CENTS); }
        catch (NumberFormatException invalid) { return 0; }
    }
    public synchronized String pitchPreset() {
        String preset = values.getProperty(pitchPrefix + "pitchPreset");
        if (preset == null) return customPitchCents() == 0 ? "natural" : "custom";
        return switch (preset) {
            case "custom", "deep", "squeaky" -> preset;
            default -> "natural";
        };
    }
    public synchronized void setPitchPreset(String preset) {
        String selected = switch (Objects.toString(preset, "")) {
            case "custom", "deep", "squeaky" -> preset;
            default -> "natural";
        };
        values.setProperty(pitchPrefix + "pitchPreset", selected);
    }
    public synchronized int pitchCents() {
        return switch (pitchPreset()) {
            case "custom" -> customPitchCents();
            case "deep" -> DEEP_CENTS;
            case "squeaky" -> SQUEAKY_CENTS;
            default -> 0;
        };
    }
    public synchronized void setPitchCents(int value) {
        values.setProperty(pitchPrefix + "pitchCents", Integer.toString(Math.clamp(value, -PITCH_RANGE_CENTS, PITCH_RANGE_CENTS)));
        setPitchPreset("custom");
    }
    public synchronized void resetPitch() {
        for (String key : PITCH_KEYS) values.remove(pitchPrefix + key);
    }
    public synchronized boolean hudVisible() { return flag("hudVisible", true); }
    public synchronized void setHudVisible(boolean value) { setFlag("hudVisible", value); }
    public synchronized boolean nameplates() { return flag("nameplates", true); }
    public synchronized void setNameplates(boolean value) { setFlag("nameplates", value); }

    private boolean flag(String key, boolean fallback) {
        String raw = values.getProperty(key);
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        return fallback;
    }

    private void setFlag(String key, boolean value) { values.setProperty(key, Boolean.toString(value)); }

    public synchronized int sensitivity() {
        try { return Math.clamp(Integer.parseInt(values.getProperty("sensitivity", "50")), 0, 100); }
        catch (NumberFormatException e) { return 50; }
    }

    public synchronized void setSensitivity(int value) {
        values.setProperty("sensitivity", Integer.toString(Math.clamp(value, 0, 100)));
    }

    public synchronized String micStyle() { return styleValue(values.getProperty("micStyle")); }
    public synchronized void setMicStyle(String id) { values.setProperty("micStyle", styleValue(id)); }
    public synchronized String resolvedMicStyle() {
        String id = micStyle();
        return id.equals("auto") ? MenuTheme.fromId(theme()).family() : id;
    }

    private static String styleValue(String id) {
        return List.of("uwu", "emo", "vanilla", "amethyst").contains(Objects.toString(id, "")) ? id : "auto";
    }
    public synchronized String effects() { return effectValue(values.getProperty("effects", "full")); }
    public synchronized void setEffects(String id) { values.setProperty("effects", effectValue(id)); }
    public synchronized String accent() { return accentValue(values.getProperty("accent", "auto")); }
    public synchronized void setAccent(String id) { values.setProperty("accent", accentValue(id)); }

    private static String effectValue(String id) {
        return "off".equals(id) || "reduced".equals(id) ? id : "full";
    }

    private static String accentValue(String id) {
        return List.of("rose", "gold", "mint", "aqua", "violet").contains(Objects.toString(id, "")) ? id : "auto";
    }

    public synchronized int volume() {
        return percent("volume");
    }

    public synchronized void setVolume(int volume) {
        values.setProperty("volume", Integer.toString(Math.clamp(volume, 0, 200)));
    }

    public synchronized int gain(UUID player) {
        return percent("gain." + Objects.requireNonNull(player));
    }

    public synchronized void setGain(UUID player, int value) {
        String key = "gain." + Objects.requireNonNull(player);
        int percent = Math.clamp(value, 0, 200);
        if (percent == 100) values.remove(key);
        else values.setProperty(key, Integer.toString(percent));
    }

    private int percent(String key) {
        String value = values.getProperty(key);
        if (value == null) return 100;
        try {
            return Math.clamp(Integer.parseInt(value.trim()), 0, 200);
        } catch (NumberFormatException invalid) {
            return 100;
        }
    }

    public synchronized String device(boolean microphone) {
        return values.getProperty(microphone ? "microphone" : "speaker", "");
    }

    public synchronized void setDevice(boolean microphone, String id) {
        Objects.requireNonNull(id);
        if (id.equals(device(microphone))) return;
        values.setProperty(microphone ? "microphone" : "speaker", id);
        deviceRevision++;
    }

    public synchronized long deviceRevision() {
        return deviceRevision;
    }

    public List<Device> devices(boolean microphone) {
        return sound.choices(microphone, device(microphone));
    }

    TargetDataLine microphone(AudioFormat format) throws LineUnavailableException {
        return (TargetDataLine) sound.line(true, format, device(true));
    }

    SourceDataLine speaker(AudioFormat format) throws LineUnavailableException {
        return (SourceDataLine) sound.line(false, format, device(false));
    }

    /** Replace the previous complete file only after the new one is closed successfully. */
    public synchronized boolean save() {
        Path temp = null;
        try {
            Path parent = file.getParent();
            if (parent == null) return false;
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".mobsear-", ".tmp");
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                values.store(writer, "Mobs Ear client preferences");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException | SecurityException ignored) {
                    // Cleanup is best effort after an unsuccessful replacement.
                }
            }
        }
    }
}
