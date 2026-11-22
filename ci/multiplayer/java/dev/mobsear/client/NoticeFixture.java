package dev.mobsear.client;

/** Read the same client notice that the game HUD displays. Only compiled in disposable worlds. */
public final class NoticeFixture {
    private NoticeFixture() {}

    public static String text() {
        var message = MobCallNotice.current();
        return message == null ? "" : message.getString();
    }

    public static void clear() { MobCallNotice.clear(); }

    public static String localMatch() {
        var message = MobCallNotice.localMatch();
        return message == null ? "" : message.getString();
    }
}
