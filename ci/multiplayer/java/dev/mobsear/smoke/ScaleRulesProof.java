package dev.mobsear.smoke;

import dev.mobsear.MobsEar;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Packets;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Exercise the production screens, physical mouse mapping and permission checks on a disposable server. */
final class ScaleRulesProof {
    private final Minecraft mc = Minecraft.getInstance();
    private static final int[][] SIZES = {{854,480,1},{640,480,2},{1280,720,3},{1280,720,0}};
    private Packets.ServerRules reply;
    private int stage, ticks, size, scrollTries, verified = -1, lastStage = -1, sameStage;
    private boolean admin, initialized;
    ScaleRulesProof() {
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        try {
            if (!initialized) {
                FixtureSound.install();
                var receiver = MobsEar.serverRules;
                MobsEar.serverRules = packet -> { receiver.accept(packet); reply = packet; };
                SmokeControl.client = value -> verified = value;
                initialized = true;
            }
            if (mc.player == null || mc.getConnection() == null || stage == 99 || ++ticks % 5 != 0) return;
            if (lastStage != stage) {
                lastStage = stage;
                sameStage = 0;
                System.out.println("MOBSEAR_GUI_STAGE "+stage+" index="+size);
            } else if (++sameStage > 100) {
                var w=mc.getWindow();
                throw new AssertionError("Timed out at stage "+stage+" index="+size+" window="+w.getWidth()+"x"+w.getHeight()+
                        " selected="+mc.options.guiScale().get()+" effective="+w.getGuiScale()+" screen="+mc.screen+" rules="+reply+" proof="+verified);
            }
            if (stage == 0) {
                admin = mc.getUser().getName().equals(SmokeControl.LISTENER);
                GLFW.glfwFocusWindow(mc.getWindow().getWindow());
                mc.setScreen(null);
                request(Packets.RuleAction.QUERY); stage++; return;
            }
            if (stage == 1) {
                if (reply == null) return;
                check(reply.editable() == admin && reply.calls() && !reply.horror(), "default and permission");
                if (!admin) request(Packets.RuleAction.CALLS_OFF);
                else MenuSmoke.pressG();
                stage++; return;
            }
            if (!admin) { guest(); return; }
            if (stage < 10) { layout(); return; }
            switch (stage) {
                case 10 -> {
                    if (!serverReady()) return;
                    check(reply.calls() && !reply.horror(), "initial admin settings");
                    proof(); stage++;
                }
                case 11, 12, 13, 14 -> {
                    if (!serverReady() || verified != mask()) return;
                    if (stage == 11) click("Mob Calls",true);
                    if (stage == 12) click("Horror",true);
                    if (stage == 13) click("Mob Calls",true);
                    if (stage == 14) click("Mob Calls",true);
                    stage += 10;
                }
                case 21, 22, 23, 24 -> {
                    if (!serverReady()) return;
                    int expected = switch(stage) { case 21 -> 1000; case 22,24 -> 1002; default -> 1003; };
                    if (mask() != expected) return;
                    proof();
                    stage = stage == 24 ? 25 : stage - 9;
                }
                case 25 -> {
                    if (verified != mask()) return;
                    save("menu-server-settings");
                    click("Back",false);
                    System.out.println("MOBSEAR_ADMIN_GUI_PASS");
                    stage = 99;
                }
                default -> throw new AssertionError("stage " + stage);
            }
        } catch (Exception | AssertionError ex) { throw SmokeControl.failure("GUI stage="+stage+" "+ex); }
    }

    private void guest() throws Exception {
        if (reply == null) return;
        check(!reply.editable() && reply.calls() && !reply.horror(), "guest changed a rule");
        if (stage == 2) { request(Packets.RuleAction.HORROR_ON); stage++; return; }
        if (stage == 3) { MenuSmoke.pressG(); stage++; return; }
        if (!main()) return;
        check(find("Server Settings",false)==null, "guest saw server settings");
        noLocalSwitches();
        save("menu-guest");
        System.out.println("MOBSEAR_GUI_GUEST neither setting exposed; both unauthorized writes refused");
        System.out.println("MOBSEAR_CLIENT_READY");
        stage = 99;
    }

    private void layout() throws Exception {
        int[] target = SIZES[size];
        if (!main() && stage != 7 && stage != 8) return;
        switch (stage) {
            case 2 -> {
                mc.getWindow().setWindowed(target[0],target[1]);
                stage++;
            }
            case 3 -> {
                var window = mc.getWindow();
                int expectedScale = target[2] == 0 ? 3 : target[2];
                if (window.getWidth()!=target[0] || window.getHeight()!=target[1]) return;
                // Minecraft validates the available scale choices against the current framebuffer.
                if (mc.options.guiScale().get()!=target[2]) {
                    mc.options.guiScale().set(target[2]);
                    return;
                }
                if (window.getGuiScale()!=expectedScale) return;
                check(mc.screen.width==window.getGuiScaledWidth() && mc.screen.height==window.getGuiScaledHeight(),"native coordinates");
                noLocalSwitches();
                var list = audio();
                scrollTries = 0;
                stage++;
            }
            case 4 -> {
                var last = find("Motion",true);
                var list = audio();
                check(last != null, "missing Motion row");
                if (scrollTries == 0 || last.getY()<list.getY() || last.getBottom()>list.getBottom() || list.getScrollAmount()==0) {
                    boolean handled = mc.screen.mouseScrolled(list.getX()+list.getWidth()/2.0,list.getY()+list.getHeight()/2.0,0,-2);
                    if (++scrollTries > 48) throw new AssertionError("Last row unreachable: wheel="+handled+
                            " offset="+list.getScrollAmount()+"/"+list.getMaxScroll()+" list="+list.getY()+".."+list.getBottom()+
                            " row="+last.getY()+".."+last.getBottom()+" scale="+mc.getWindow().getGuiScale());
                    return;
                }
                String before = VoiceClient.options().effects();
                click("Motion",true);
                check(!before.equals(VoiceClient.options().effects()),"scrolled click missed");
                stage++;
            }
            case 5 -> {
                save("menu-scale-"+target[2]);
                click("Voice Changer",false);
                stage=7;
            }
            case 7 -> {
                var prefs = VoiceClient.options();
                click("Squeaky",false);
                check(prefs.pitchCents()>0,"preset click missed");
                click("Reset to Defaults",false);
                check(!prefs.pitchEnabled() && prefs.pitchCents()==0,"reset missed");
                save("menu-pitch-scale-"+target[2]);
                click("Back",false);
                stage=8;
            }
            case 8 -> {
                if (!main()) return;
                System.out.println("MOBSEAR_GUI_SCALE window="+target[0]+"x"+target[1]+" setting="+target[2]+" actual="+mc.getWindow().getGuiScale()+" scrolledLastRow=true pitchClicks=true");
                if (++size < SIZES.length) stage=2;
                else { click("Server Settings",false); stage=10; }
            }
        }
    }

    private void request(Packets.RuleAction action) {
        reply=null;
        PacketDistributor.sendToServer(new Packets.RuleRequest(action));
    }
    private boolean main() { return mc.screen != null && mc.screen.getTitle().getString().equals("Proximity Voice"); }
    private void noLocalSwitches() {
        check(find("Local mob calls",true)==null && find("Quiet horror",true)==null &&
                find("Mob Calls",true)==null && find("Horror",true)==null,"server switches leaked into personal settings");
    }
    private boolean serverReady() { var b=find("Mob Calls",true); return reply!=null && b!=null && b.active; }
    private int mask() { return 1000 + (reply.calls()?1:0) + (reply.horror()?2:0); }
    private void proof() { verified=-1; PacketDistributor.sendToServer(new SmokeControl.Barrier(mask())); }
    private AbstractSelectionList<?> audio() {
        return (AbstractSelectionList<?>)mc.screen.children().stream().filter(c -> c.getClass().getSimpleName().equals("VoiceControlList")).findFirst().orElseThrow();
    }
    private AbstractWidget find(String name, boolean prefix) { return mc.screen==null?null:MenuSmoke.find(mc.screen,name,prefix); }

    private void click(String name,boolean prefix) throws Exception {
        var w=find(name,prefix);
        check(w!=null && w.active,name+" unavailable");
        var win=mc.getWindow();
        double px=(w.getX()+w.getWidth()*.5)*win.getScreenWidth()/mc.screen.width;
        double py=(w.getY()+w.getHeight()*.5)*win.getScreenHeight()/mc.screen.height;
        var move=MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);
        var press=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);
        move.setAccessible(true); press.setAccessible(true);
        move.invoke(mc.mouseHandler,win.getWindow(),px,py);
        press.invoke(mc.mouseHandler,win.getWindow(),0,GLFW.GLFW_PRESS,0);
        press.invoke(mc.mouseHandler,win.getWindow(),0,GLFW.GLFW_RELEASE,0);
    }
    private void save(String name) throws Exception {
        var dir=mc.gameDirectory.toPath().resolve("screenshots");
        Files.createDirectories(dir);
        try(var img=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { img.writeToFile(dir.resolve(name+".png")); }
    }
    private static void check(boolean ok,String why) { if (!ok) throw new AssertionError(why); }
}
