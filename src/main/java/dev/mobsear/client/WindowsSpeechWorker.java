package dev.mobsear.client;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

/** Give the opted-in recognizer time to finish short calls while Minecraft is busy. */
final class WindowsSpeechWorker {
    private WindowsSpeechWorker() {}

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
