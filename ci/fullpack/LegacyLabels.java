import java.nio.file.Path;

/** Ask the older packaged implementation for its public defaults on the runner. */
class LegacyLabels {
    public static void main(String[] args) throws Exception {
        var method=Class.forName("dev.mobsear.client.MobLexicon").getDeclaredMethod("grammar",Path.class);
        method.setAccessible(true);
        System.out.print(method.invoke(null,(Object)null));
    }
}
