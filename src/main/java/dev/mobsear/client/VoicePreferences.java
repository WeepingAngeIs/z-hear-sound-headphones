package dev.mobsear.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** Local device and playback preferences. Loading settings never accesses audio devices. */
public final class VoicePreferences {
    public record Device(String id, String label) {}

    private final Path file;
    private final VoiceDevices sound;
    private final Properties values = new Properties();
    private long deviceRevision;

    public VoicePreferences(Path file) {
        this(file, new VoiceDevices());
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
        return normalizeTheme(values.getProperty("theme"));
    }

    public synchronized void setTheme(String theme) {
        values.setProperty("theme", normalizeTheme(theme));
    }

    private static String normalizeTheme(String value) {
        if (value == null) return "uwu";
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "emo" -> "emo";
            case "vanilla" -> "vanilla";
            case "amethyst" -> "amethyst";
            default -> "uwu";
        };
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
