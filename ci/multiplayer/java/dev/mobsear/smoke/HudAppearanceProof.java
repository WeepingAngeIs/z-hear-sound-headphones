package dev.mobsear.smoke;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.MenuTheme;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.MicState;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

final class HudAppearanceProof {
    private final Minecraft mc = Minecraft.getInstance();
    private final Map<String,BufferedImage> framed = new HashMap<>(), bare = new HashMap<>();
    private final Path output = mc.gameDirectory.toPath().resolve("screenshots");
    private final boolean restart = "1".equals(System.getenv("MOBSEAR_HUD_RELOAD"));
    private final int[] background = new int[3];
    private int[] donePixels;
    private int stage, age, index, firstFrame = -1;
    private boolean installed, configured;
    private static final MenuTheme[] THEMES = MenuTheme.values();
    private static final MicState[] STATES = {MicState.IDLE,MicState.MUTED,MicState.DEAFENED,MicState.SPEAKING};

    HudAppearanceProof() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
    }

    private void advance() { stage++; age=0; }

    private void tick(ClientTickEvent.Post ignored) {
        try {
            if (!installed) { FixtureSound.install(); installed=true; }
            if (mc.player==null || !VoiceClient.connected() || stage==99) return;
            if (++age>400) throw new IllegalStateException("timeout stage="+stage+" index="+index+
                    " state="+VoiceClient.localState()+" GUI="+mc.screen+" scale="+mc.getWindow().getGuiScale());
            var prefs=VoiceClient.options();
            if (stage==0) {
                if (restart) {
                    check(!prefs.micFrame() && prefs.menuTransparency()==37 && prefs.hudVisible(),"restart values");
                    System.out.println("MOBSEAR_HUD_RESTART_PASS frame=false transparency=37 hudVisible=true");
                    stage=99; return;
                }
                check(prefs.micFrame() && prefs.menuTransparency()==0,"original defaults");
                Files.createDirectories(output);
                for (String family : new String[]{"uwu","emo","vanilla","amethyst"}) {
                    framed.put(family,atlas("font",family)); bare.put(family,atlas("gui/mics",family));
                }
                GLFW.glfwFocusWindow(mc.getWindow().getWindow());
                mc.player.setXRot(-80);
                mc.setScreen(null); MenuSmoke.pressG(); advance(); return;
            }
            if (stage==1) {
                if (mc.screen==null || !mc.screen.getTitle().getString().equals("Proximity Voice")) return;
                var list=(AbstractSelectionList<?>)mc.screen.children().stream()
                        .filter(e->e.getClass().getSimpleName().equals("VoiceControlList")).findFirst().orElseThrow();
                var slider=find("Menu Background",true);
                var toggle=find("Mic Frame",true);
                if (slider.getY()<list.getY() || slider.getBottom()>list.getBottom() ||
                        toggle.getY()<list.getY() || toggle.getBottom()>list.getBottom() || list.getScrollAmount()==0) {
                    mc.screen.mouseScrolled(list.getX()+15,list.getY()+12,0,-1); return;
                }
                click(toggle,.5); check(!prefs.micFrame() && prefs.menuTransparency()==0,"frame affected backdrop");
                advance(); return;
            }
            if (stage>=2 && stage<=5 && age>=5) {
                int i=stage-2;
                if (i<3) {
                    check(prefs.menuTransparency()==i*50 && !prefs.micFrame(),"live slider state "+i);
                    captureMenu(i);
                }
                if (i==2) {
                    check(background[0]!=background[1] && background[1]!=background[2] && background[0]!=background[2],
                            "backdrop did not change at midpoint and endpoints");
                    System.out.println("MOBSEAR_HUD_BACKDROP_PASS live=0,50,100 unchangedDoneButtonAndLabel=true");
                }
                var slider=find("Menu Background",true);
                if (i<3) click(slider,i==0?.5:i==1?1:.37);
                else {
                    check(prefs.menuTransparency()==37 && !prefs.micFrame(),"independent settings");
                    click(find("Done",false),.5);
                }
                advance(); return;
            }
            if (stage!=6) return;
            if (index==THEMES.length*STATES.length*4) {
                prefs.setMicFrame(false);
                check(prefs.save(),"save failed");
                System.out.println("MOBSEAR_HUD_MATRIX_PASS cases="+index+" themes="+THEMES.length+
                        " scales=1,2 frames=on,off states=idle,speaking,muted,deafened speakingAnimation=true");
                stage=99; return;
            }
            if (!configured) {
                mc.options.guiScale().set(scale());
                prefs.setTheme(theme().id());
                prefs.setMicStyle("auto"); prefs.setEffects("full");
                prefs.setMuted(state()==MicState.MUTED); prefs.setDeafened(state()==MicState.DEAFENED);
                FixtureSound.tone=state()==MicState.SPEAKING;
                prefs.setMicFrame(hasFrame());
                configured=true; firstFrame=-1; age=0;
            }
        } catch (Exception | AssertionError e) { throw SmokeControl.failure("HUD stage="+stage+" "+e); }
    }

    private AbstractWidget find(String label, boolean prefix) {
        var item=MenuSmoke.find(mc.screen,label,prefix);
        if(item==null) throw new IllegalStateException("missing "+label);
        return item;
    }

    private void click(AbstractWidget w,double fraction) {
        double x=w.getX()+4+(w.getWidth()-8)*fraction,y=w.getY()+w.getHeight()/2.0;
        var screen=mc.screen;
        check(screen.mouseClicked(x,y,0),"click ignored "+w.getMessage());
        screen.mouseReleased(x,y,0);
    }

    private void captureMenu(int i) throws Exception {
        try(var img=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int s=(int)mc.getWindow().getGuiScale();
            background[i]=img.getPixelRGBA(3,img.getHeight()/2);
            var done=find("Done",false);
            int[] samples=new int[(done.getWidth()-4)*(done.getHeight()-4)];
            int at=0;
            for(int y=2;y<done.getHeight()-2;y++) for(int x=2;x<done.getWidth()-2;x++)
                samples[at++]=img.getPixelRGBA((done.getX()+x)*s,(done.getY()+y)*s);
            if(i==0) donePixels=samples;
            else check(Arrays.equals(donePixels,samples),"button or text opacity changed");
            img.writeToFile(output.resolve("menu-transparency-"+(i*50)+".png"));
        }
    }

    private BufferedImage atlas(String dir,String family) throws Exception {
        var id=ResourceLocation.fromNamespaceAndPath("mobsear","textures/"+dir+"/mic_"+family+".png");
        try(var in=mc.getResourceManager().open(id)) { return ImageIO.read(in); }
    }
    private boolean hasFrame() { return index%2==0; }
    private MenuTheme theme() { return THEMES[(index/2)%THEMES.length]; }
    private MicState state() { return STATES[(index/(THEMES.length*2))%STATES.length]; }
    private int scale() { return 1+index/(THEMES.length*2*STATES.length); }

    private void render(RenderFrameEvent.Post event) {
        if(stage!=6 || !configured || age<2 || mc.screen!=null || mc.getWindow().getGuiScale()!=scale()
                || VoiceClient.localState()!=state()) return;
        try(var screen=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var family=theme().family();
            var fg=bare.get(family); var original=framed.get(family);
            int s=scale(),top=(mc.getWindow().getGuiScaledHeight()-29)*s;
            int border=0;
            for(int x=2;x<18;x++) if(rgb(screen,(7+x)*s+s/2,top+2*s+s/2)==(original.getRGB(x,2)&0xffffff)) border++;
            if(hasFrame()?border!=16:border!=0) return;
            int from=switch(state()){case IDLE->0;case MUTED->1;case DEAFENED->2;case SPEAKING->5;};
            int end=state()==MicState.SPEAKING?13:from+1;
            for(int frame=from;frame<end;frame++) {
                boolean match=true;
                for(int y=0;y<22 && match;y++) for(int x=0;x<22;x++) {
                    int pixel=fg.getRGB(frame*22+x,y);
                    if((pixel>>>24)==255 && (pixel&0xffffff)!=rgb(screen,(7+x)*s+s/2,top+y*s+s/2)) { match=false;break; }
                }
                if(!match) continue;
                if(state()==MicState.SPEAKING && firstFrame==frame) return;
                if(s==2 && theme().id().equals(family) && (firstFrame==-1 || state()==MicState.SPEAKING)) {
                    var crop=new BufferedImage(22*s,22*s,BufferedImage.TYPE_INT_RGB);
                    for(int y=0;y<crop.getHeight();y++) for(int x=0;x<crop.getWidth();x++)
                        crop.setRGB(x,y,rgb(screen,7*s+x,top+y));
                    ImageIO.write(crop,"png",output.resolve("menu-mic-"+family+"-"+state()+"-"+hasFrame()+
                            (firstFrame==-1?"":"-next")+".png").toFile());
                }
                if(state()==MicState.SPEAKING && firstFrame==-1) { firstFrame=frame;return; }
                index++; configured=false; return;
            }
        } catch(Exception e) { throw SmokeControl.failure("HUD pixels "+e); }
    }

    private static int rgb(NativeImage img,int x,int y) {
        int c=img.getPixelRGBA(x,y);
        return (c&255)<<16 | c&0xff00 | c>>>16&255;
    }
    private static void check(boolean ok,String message) { if(!ok) throw new IllegalStateException(message); }
}
