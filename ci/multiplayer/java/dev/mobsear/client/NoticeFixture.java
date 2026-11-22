package dev.mobsear.client;

import dev.mobsear.net.Packets;
import net.minecraft.client.Minecraft;

/** Read the same client notice that the game HUD displays. Only compiled in disposable worlds. */
public final class NoticeFixture {
    private NoticeFixture() {}

    public static String text() {
        var message = MobCallNotice.current();
        return message == null ? "" : message.getString();
    }

    public static void clear() { MobCallNotice.clear(); }

    /** Read the live engine state independently of any temporary on-screen notice. */
    public static boolean recognizerReady() {
        try {
            var client=VoiceClient.class.getDeclaredField("instance"); client.setAccessible(true);
            var session=VoiceClient.class.getDeclaredField("session"); session.setAccessible(true);
            Object connection=session.get(client.get(null));
            if (connection==null) return false;
            var field=connection.getClass().getDeclaredField("speech"); field.setAccessible(true);
            return ((SpeechCues)field.get(connection)).status()==SpeechCues.Status.READY;
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Could not read recognizer readiness",ex); }
    }

    /** Replay only the recognizer decision, using the actual client's negotiated outgoing packet. */
    public static void send(Packets.MobCall call) {
        MobCallNotice.clearMatch();
        VoiceClient.sendCall(Minecraft.getInstance().getConnection(), call);
    }

    public static void cycleRecognition() {
        VoiceClient.setRecognition(false);
        VoiceClient.setRecognition(true);
    }

    public static String localMatch() {
        var message = MobCallNotice.localMatch();
        return message == null ? "" : message.getString();
    }
}
