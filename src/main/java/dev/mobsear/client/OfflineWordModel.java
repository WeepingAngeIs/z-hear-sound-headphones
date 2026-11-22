package dev.mobsear.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Unpack only the trusted, bundled model and executable. Player audio never uses these files. */
final class OfflineWordModel {
    private static final Set<String> FILES = Set.of("whisper-cli.exe", "whisper.dll", "ggml.dll",
            "ggml-base.dll", "ggml-cpu.dll", "tiny.en.bin", "LICENSE-Whisper.txt", "LICENSE-whisper.cpp.txt");
    private static Path directory;

    static synchronized Path prepare() throws IOException {
        if (directory != null) return directory;
        String arch = System.getProperty("os.arch", "");
        if (!arch.equalsIgnoreCase("amd64") && !arch.equalsIgnoreCase("x86_64")) return null;
        try (var resource = OfflineWordModel.class.getResourceAsStream("words-win-x64.zip")) {
            if (resource == null) return null;
            Path root = Files.createTempDirectory("mobsear-words-");
            root.toFile().deleteOnExit();
            var remaining = new HashSet<>(FILES);
            try (var zip = new ZipInputStream(resource)) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (!remaining.remove(entry.getName())) throw new IOException("Invalid offline word asset");
                    Path file = root.resolve(entry.getName());
                    Files.copy(zip, file);
                    file.toFile().deleteOnExit();
                }
                if (!remaining.isEmpty()) throw new IOException("Incomplete offline word bundle");
                directory = root;
                return directory;
            }
        }
    }

    private OfflineWordModel() {}
}
