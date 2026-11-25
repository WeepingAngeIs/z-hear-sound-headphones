package dev.mobsear.client.speech;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;

/** Fixed local diagnostics. The worker never serializes an exception message, path, or hypothesis here. */
public record ModelStartup(Stage stage, Reason reason, int hresult) {
    public enum Stage {
        DATA("speech data"), RUNTIME("Windows ML"), ENCODER_FILE("encoder file"), ENCODER_LOAD("encoder load"),
        ENCODER_SESSION("encoder session"), DECODER_FILE("decoder file"), DECODER_LOAD("decoder load"),
        DECODER_SESSION("decoder session"), ENCODER_WARMUP("encoder warmup"), DECODER_WARMUP("decoder warmup");
        private final String label;
        Stage(String label) { this.label = label; }
    }

    public enum Reason { WINDOWS, ACCESS_DENIED, FILE_EXISTS, FILE_MISSING, FILE_IO, LINKAGE, INITIALIZATION }

    static final class Progress {
        Stage stage = Stage.DATA;

        ModelStartup failure(Throwable cause) {
            Reason reason = switch (cause) {
                case WindowsError error -> Reason.WINDOWS;
                case AccessDeniedException denied -> Reason.ACCESS_DENIED;
                case SecurityException denied -> Reason.ACCESS_DENIED;
                case FileAlreadyExistsException exists -> Reason.FILE_EXISTS;
                case NoSuchFileException missing -> Reason.FILE_MISSING;
                case IOException other -> Reason.FILE_IO;
                case LinkageError linkage -> Reason.LINKAGE;
                default -> Reason.INITIALIZATION;
            };
            return new ModelStartup(stage, reason, cause instanceof WindowsError error ? error.code : 0);
        }
    }

    static final class WindowsError extends IllegalStateException {
        final int code;
        WindowsError(String operation, int code) {
            super(operation + " HRESULT 0x" + Integer.toHexString(code));
            this.code = code;
        }
    }

    public String code() {
        return reason == Reason.WINDOWS ? String.format(Locale.ROOT, "0x%08X", hresult) : reason.name();
    }

    public String display() { return stage.label + " / " + code(); }

    void write(DataOutput out) throws IOException {
        out.writeByte(stage.ordinal());
        out.writeByte(reason.ordinal());
        out.writeInt(hresult);
    }

    public static ModelStartup read(DataInput in) throws IOException {
        int stage = in.readUnsignedByte(), reason = in.readUnsignedByte();
        if (stage >= Stage.values().length || reason >= Reason.values().length)
            throw new IOException("Unknown model startup diagnostic");
        return new ModelStartup(Stage.values()[stage], Reason.values()[reason], in.readInt());
    }
}
