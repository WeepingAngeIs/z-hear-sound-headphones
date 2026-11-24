package dev.mobsear.client;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.jna.platform.win32.WinNT;

/** Give the opted-in recognizer time to finish short calls while Minecraft is busy. */
final class WindowsSpeechWorker {
    private WindowsSpeechWorker() {}

    // Both paths come from the running launcher. The speech JVM needs just the installed
    // mod and Mojang's JNA library. It has no updater, script interpreter, or native bundle.
    static Path installedMod;

    static Process launch(boolean tracked) throws IOException {
        Path mod = installedMod;
        if (mod == null || !Files.isRegularFile(mod)) mod = location(WindowsSpeechWorker.class);
        Path jna = location(Native.class);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xmx96m", "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC", "-cp",
                mod + File.pathSeparator + jna, "dev.mobsear.client.speech.SpeechWorker",
                tracked ? "stages" : "quiet");
        // The normal Java launcher's environment may include full-game agents/VM options.
        for (String opt : java.util.List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
            builder.environment().remove(opt);
        builder.redirectError(tracked ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.DISCARD);
        if (!tracked && "github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")) &&
                "1".equals(System.getenv("MOBSEAR_SYNTHETIC_WORKER_FAULTS")))
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return builder.start();
    }

    private static Path location(Class<?> type) throws IOException {
        try {
            var uri = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            var path = Path.of(uri);
            if ("union".equalsIgnoreCase(uri.getScheme())) {
                // NeoForge wraps library roots in SecureJarHandler's public UnionFileSystem.
                path = (Path) path.getFileSystem().getClass().getMethod("getPrimaryPath").invoke(path.getFileSystem());
            }
            if (!path.getFileSystem().provider().getScheme().equals("file") || !Files.exists(path))
                throw new IOException("Missing installed speech class path");
            return path.toAbsolutePath();
        } catch (ReflectiveOperationException | java.net.URISyntaxException | RuntimeException e) {
            throw new IOException("Cannot locate installed Java speech classes", e);
        }
    }

    static void configure(Process child) {
        try {
            var kernel = Kernel32.INSTANCE;
            var handle = kernel.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION | WinNT.PROCESS_SET_INFORMATION,
                    false, Math.toIntExact(child.pid()));
            if (handle == null) return;
            try {
                if (!child.isAlive()) return;
                var priority = kernel.GetPriorityClass(handle);
                if (Kernel32.IDLE_PRIORITY_CLASS.equals(priority) || Kernel32.BELOW_NORMAL_PRIORITY_CLASS.equals(priority)
                        || Kernel32.NORMAL_PRIORITY_CLASS.equals(priority)) {
                    kernel.SetPriorityClass(handle, Kernel32.ABOVE_NORMAL_PRIORITY_CLASS);
                }
            } finally {
                kernel.CloseHandle(handle);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The local decoder also works when process scheduling is unavailable.
        }
    }
}
