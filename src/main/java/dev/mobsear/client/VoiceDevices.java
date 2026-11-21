package dev.mobsear.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

final class VoiceDevices {
    private static final AudioFormat FORMAT = new AudioFormat(VoiceAudio.SAMPLE_RATE, 16, 1, true, false);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private final Supplier<List<Mixer>> mixers;
    private final DefaultLine defaultLine;

    @FunctionalInterface
    interface DefaultLine {
        Line get(DataLine.Info info) throws LineUnavailableException;
    }

    VoiceDevices() {
        this(VoiceDevices::systemMixers, AudioSystem::getLine);
    }

    VoiceDevices(Supplier<List<Mixer>> mixers, DefaultLine defaultLine) {
        this.mixers = mixers;
        this.defaultLine = defaultLine;
    }

    private static List<Mixer> systemMixers() {
        var found = new ArrayList<Mixer>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                found.add(AudioSystem.getMixer(info));
            } catch (IllegalArgumentException | SecurityException unavailable) {
                // A provider may lose a hot-plugged device during enumeration.
            }
        }
        return found;
    }

    List<VoicePreferences.Device> choices(boolean microphone, String savedId) {
        Map<String, Mixer> available = available(request(microphone, FORMAT));
        var choices = new ArrayList<VoicePreferences.Device>();
        var names = new HashSet<String>();
        var duplicates = new HashSet<String>();
        for (Mixer mixer : available.values()) {
            String name = mixer.getMixerInfo().getName();
            if (!names.add(name)) duplicates.add(name);
        }
        available.forEach((id, mixer) -> {
            Mixer.Info info = mixer.getMixerInfo();
            String label = info.getName();
            if (duplicates.contains(label)) label += " (" + info.getVendor() + ", " + info.getDescription() + ")";
            choices.add(new VoicePreferences.Device(id, label));
        });
        choices.sort(Comparator.comparing(VoicePreferences.Device::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(VoicePreferences.Device::id));
        choices.addFirst(new VoicePreferences.Device("", "System default"));
        if (!savedId.isEmpty() && !available.containsKey(savedId)) {
            choices.add(new VoicePreferences.Device(savedId, savedName(savedId) + " (unavailable)"));
        }
        return List.copyOf(choices);
    }

    Line line(boolean microphone, AudioFormat format, String selected) throws LineUnavailableException {
        DataLine.Info request = request(microphone, format);
        if (selected.isEmpty()) return defaultLine.get(request);
        Mixer mixer = available(request).get(selected);
        if (mixer == null) throw new LineUnavailableException("Selected voice device is unavailable");
        return mixer.getLine(request);
    }

    private static DataLine.Info request(boolean microphone, AudioFormat format) {
        return new DataLine.Info(microphone ? TargetDataLine.class : SourceDataLine.class, format);
    }

    private Map<String, Mixer> available(DataLine.Info request) {
        var unique = new LinkedHashMap<String, Mixer>();
        var ambiguous = new HashSet<String>();
        try {
            for (Mixer mixer : mixers.get()) {
                try {
                    if (mixer.isLineSupported(request)) {
                        String id = id(mixer.getMixerInfo());
                        if (unique.putIfAbsent(id, mixer) != null) ambiguous.add(id);
                    }
                } catch (IllegalArgumentException | SecurityException unavailable) {
                    // The menu can still offer the other devices.
                }
            }
        } catch (SecurityException denied) {
            return Map.of();
        }
        // Java Sound exposes descriptions, not hardware GUIDs. Never resolve a choice by list order.
        ambiguous.forEach(unique::remove);
        return unique;
    }

    private static String id(Mixer.Info info) {
        return "v1." + encode(info.getName()) + "." + encode(info.getVendor()) + "."
                + encode(info.getDescription()) + "." + encode(info.getClass().getName());
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String savedName(String id) {
        String[] fields = id.split("\\.", -1);
        if (fields.length == 5 && fields[0].equals("v1")) {
            try {
                String name = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
                if (!name.isBlank()) return name;
            } catch (IllegalArgumentException invalid) {
                // An ID from a different version can still be replaced through the menu.
            }
        }
        return "Saved device";
    }
}
