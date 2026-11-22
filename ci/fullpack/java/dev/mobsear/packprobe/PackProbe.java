package dev.mobsear.packprobe;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.mobsear.MobsEar;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Packets;
import dev.mobsear.server.NearbyMics;
import dev.mobsear.server.ServerVoice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Observes an unmodified production JAR in one genuine, disposable integrated server. */
@Mod(value="acoustic_pack_probe", dist=Dist.CLIENT)
public final class PackProbe {
    record Trial(String name, int range, boolean creative, boolean hears, boolean openMic) {}
    static final Trial[] TESTS = {
        new Trial("Enderman", 24, false, true, true),
        new Trial("Pikachu", 12, false, true, true),
        new Trial("Lopunny", 16, false, true, true),
        new Trial("Pikachu", 40, false, false, true),
        new Trial("Enderman", 24, true, false, true),
        new Trial("Enderman", 24, false, true, false)
    };
    static final class Observation {
        final Trial test;
        final int number, entity;
        final Mob subject; // Accessed only on the server thread.
        final long frameBefore, cueBefore;
        volatile boolean wireAudio, acceptedCue, reaction, targeted;
        volatile double callDistance = -1;
        volatile boolean started;
        boolean recognized, dispatched, confirmed, notice, facedAway, screenshot;
        int aligned, postAckTicks;
        double initialError = -1, closestError = 180;
        long initialRequest, startedAt, seenAt;
        Observation(Trial test, int number, Mob mob, long frame, long cue) {
            this.test = test; this.number = number; this.subject = mob; this.entity = mob.getId();
            this.frameBefore = frame; this.cueBefore = cue;
        }
    }
    private volatile Observation current;
    private volatile boolean ready, next, fatal, complete;
    private long nextSetup;
    private int index, failures, clientTicks;
    private boolean installed, hooked, armed;
    private String lastScreen = "";
    private final PackOnboarding onboarding=new PackOnboarding();
    private final Path home = Path.of(System.getenv("MOBSEAR_SMOKE_DIR"));

    public PackProbe() {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))) throw new IllegalStateException("Hosted fixture only");
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
    }

    private void fail(Exception e) {
        if (fatal) return;
        fatal = true;
        e.printStackTrace();
        System.out.println("ACOUSTIC_FATAL " + e.getClass().getSimpleName() + " " + e.getMessage());
    }

    private static long timestamp(ServerPlayer p, String name) throws ReflectiveOperationException {
        Object window = ((Map<?,?>) Boundary.field(ServerVoice.class, "sessions").get(null)).get(p);
        return window == null ? 0 : (long) Boundary.get(window, name);
    }

    private static boolean responding(Mob mob) throws ReflectiveOperationException {
        if (!(mob instanceof PokemonEntity))
            return mob.goalSelector.getAvailableGoals().stream().anyMatch(goal -> goal.isRunning()
                    && goal.getGoal().getClass().getName().equals("dev.mobsear.server.MobReactions$ListenGoal"));
        var m = Class.forName("dev.mobsear.server.PokemonReactions").getDeclaredMethod("isReacting", Mob.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, mob);
    }

    private void serverTick(ServerTickEvent.Post e) {
        if (!ready || fatal || complete) return;
        try {
            var players = e.getServer().getPlayerList().getPlayers();
            if (players.size() != 1 || e.getServer().isDedicatedServer()) throw new IllegalStateException("Expected one integrated player");
            var p = players.getFirst();
            var level = p.serverLevel();
            if (next) {
                current.subject.discard();
                current = null;
                index++;
                nextSetup = System.nanoTime() + 2_000_000_000L;
                next = false;
            }
            if (index == TESTS.length) { complete = true; return; }
            if (current == null) {
                if (System.nanoTime() < nextSetup) return;
                if (index == 0) {
                    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, e.getServer());
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, e.getServer());
                    level.setDayTime(18000);
                    level.setWeatherParameters(60000, 0, false, false);
                    for (int x=-6; x<=46; x++) for (int z=-5; z<=5; z++)
                        level.setBlockAndUpdate(new BlockPos(x,159,z), Blocks.SEA_LANTERN.defaultBlockState());
                }
                var t = TESTS[index];
                p.setGameMode(t.creative ? GameType.CREATIVE : GameType.SURVIVAL);
                p.teleportTo(level, t.range+.5, 160, .5, 90, 5);
                p.setDeltaMovement(0,0,0);
                p.hurtMarked = true;
                Mob mob = t.name.equals("Enderman") ? EntityType.ENDERMAN.create(level)
                        : PokemonProperties.Companion.parse(t.name.toLowerCase(Locale.ROOT) + " level=15").createEntity(level);
                if (mob == null) throw new IllegalStateException("Missing trial entity");
                if (mob instanceof PokemonEntity poke && (!poke.getPokemon().isWild() || poke.getOwnerUUID()!=null
                        || !poke.getPokemon().getSpecies().getResourceIdentifier().getPath().equals(t.name.toLowerCase(Locale.ROOT))))
                    throw new IllegalStateException("Invalid wild species");
                mob.moveTo(.5,160,.5,90,0);
                mob.setYHeadRot(90);
                mob.setYBodyRot(90);
                mob.setPersistenceRequired();
                if (!level.addFreshEntity(mob)) throw new IllegalStateException("Spawn failed");
                System.out.printf(Locale.ROOT,"ACOUSTIC_ARENA %s mode=%s actualDistance=%.1f wildPokemon=%s%n",
                        t.name,p.gameMode.getGameModeForPlayer(),mob.distanceTo(p),mob instanceof PokemonEntity);
                current = new Observation(t,index,mob,timestamp(p,"lastFrame"),timestamp(p,"lastCue"));
            }
            var o = current;
            if (o.started) {
                Object status=((Map<?,?>)Boundary.field(NearbyMics.class,"ACTIVE").get(null)).get(p);
                if (timestamp(p,"lastFrame") > o.frameBefore && status!=null && (int)Boundary.get(status,"level")>0
                        && (int)Boundary.get(status,"heard")>=e.getServer().getTickCount()-2) o.wireAudio=true;
                if (!o.acceptedCue && timestamp(p,"lastCue") > o.cueBefore) {
                    o.callDistance=o.subject.distanceTo(p);
                    o.acceptedCue=true;
                }
                o.reaction |= responding(o.subject);
                o.targeted |= o.subject.getTarget()!=null;
            }
        } catch (Exception ex) { fail(ex); }
    }

    private void clientTick(ClientTickEvent.Post e) {
        if (fatal || complete) return;
        try {
            var mc = Minecraft.getInstance();
            if (!installed) {
                Boundary.install();
                installed=true;
                System.out.println("ACOUSTIC_DEVICE synthetic Java Sound input, zero physical mixers");
            }
            clientTicks++;
            String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
            if (!screen.equals(lastScreen)) {
                String key=mc.screen!=null && mc.screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        ? t.getKey() : "";
                System.out.println("ACOUSTIC_SCREEN " + screen + " " + key);
                lastScreen=screen;
            }
            onboarding.tick(mc);
            if (mc.player==null) { continueWorld(mc); return; }
            if (mc.screen instanceof PauseScreen pause) pause.onClose();
            if (!VoiceClient.connected()) return;
            if (!hooked) {
                if (mc.getSingleplayerServer()==null || !mc.getConnection().getConnection().isMemoryConnection()
                        || !mc.getConnection().hasChannel(Packets.Heard.TYPE) || !mc.getConnection().hasChannel(Packets.CallRequest.TYPE))
                    throw new IllegalStateException("Integrated connection or production channels missing");
                VoiceClient.options().setRecognitionDiagnostics(true);
                var original=MobsEar.callHeard;
                MobsEar.callHeard=reply -> {
                    original.accept(reply);
                    var o=current;
                    if (o==null || !o.started || reply.requestId()<=o.initialRequest) return;
                    String id=(o.test.name.equals("Enderman") ? "minecraft:" : "cobblemon:")+o.test.name.toLowerCase(Locale.ROOT);
                    if (reply.id().toString().equals(id) && reply.species()==!o.test.name.equals("Enderman"))
                        o.confirmed=true;
                };
                hooked=true;
            }
            if (!mc.isWindowActive() || mc.isPaused() || mc.screen!=null) {
                if (clientTicks%200==0) System.out.println("ACOUSTIC_FOCUS active="+mc.isWindowActive()+" paused="+mc.isPaused()+" screen="+screen);
                return;
            }
            if (!armed) {
                // Use the mod's actual configured binding, including the pack's default key overrides.
                var toggle=(KeyMapping)Boundary.get(Boundary.client(),"toggle");
                KeyMapping.click(toggle.getKey());
                armed=true;
                return;
            }
            String state=Boundary.readiness();
            if (!state.equals("READY")) {
                if (clientTicks%100==0) System.out.println("ACOUSTIC_RECOGNIZER "+state+" optIn="+Boundary.get(Boundary.client(),"recognition"));
                return;
            }
            if (!ready) {
                System.out.println("ACOUSTIC_READY integrated=true mode="+mc.gameMode.getPlayerMode()+" loader="+
                        ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion()+
                        " mod="+ModList.get().getModContainerById("mobsear").orElseThrow().getModInfo().getVersion());
                ready=true;
            }
            var o=current;
            if (o==null || next) return;
            if (mc.level.getEntity(o.entity) instanceof Mob mob) {
                double yaw=Math.toDegrees(Math.atan2(mc.player.getZ()-mob.getZ(),mc.player.getX()-mob.getX()))-90;
                double err=Math.abs(Mth.wrapDegrees(mob.getYHeadRot()-yaw));
                if (!o.started) {
                    if (o.seenAt==0) o.seenAt=System.nanoTime();
                    if (err>60) o.facedAway=true;
                    VoiceClient.setVoiceActivated(o.test.openMic);
                    if (!o.test.openMic) ((KeyMapping)Boundary.get(Boundary.client(),"talk")).setDown(true);
                    if (mc.player.isCreative()!=o.test.creative || System.nanoTime()-o.seenAt<350_000_000L) return;
                    o.initialError=err;
                    o.initialRequest=(long)Boundary.get(Boundary.client(),"lastRequestId");
                    o.startedAt=System.nanoTime();
                    o.started=true;
                    Boundary.say(Files.readAllBytes(home.resolve("synth").resolve(o.test.name.toLowerCase(Locale.ROOT)+".pcm")));
                    return;
                }
                if (o.confirmed) {
                    o.postAckTicks++;
                    o.closestError=Math.min(o.closestError,err);
                    if (err<=30) o.aligned++;
                }
            }
            if (!o.started) return;
            o.recognized |= Boundary.notice("localMatch").contains(o.test.name);
            o.notice |= Boundary.notice("current").equals(o.test.name + " heard you");
            o.dispatched |= (long)Boundary.get(Boundary.client(),"lastRequestId") > o.initialRequest;
            if (o.notice && o.aligned>=2 && !o.screenshot) {
                try (var pic=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    var evidence=home.resolve("evidence");
                    Files.createDirectories(evidence);
                    pic.writeToFile(evidence.resolve(o.number+"-"+o.test.name+".png"));
                }
                o.screenshot=true;
            }
            long elapsed=System.nanoTime()-o.startedAt;
            if (!Boundary.consumed() && elapsed<12_000_000_000L) return;
            if (elapsed<6_000_000_000L && !(o.test.hears && o.notice && o.aligned>=2 && o.reaction)) return;
            boolean capture=Boundary.consumed() && Boundary.nonzeroFrames>=2;
            boolean expectWire=!o.test.creative;
            boolean success=capture && o.wireAudio && o.recognized && o.dispatched==expectWire && o.acceptedCue==expectWire
                    && o.confirmed==o.test.hears && o.notice==o.test.hears && o.reaction==o.test.hears && !o.targeted
                    && (!o.test.hears || (o.facedAway && o.aligned>=2));
            System.out.printf(Locale.ROOT,
                    "ACOUSTIC_RESULT %s %s mode=%s input=%s arranged=%d callDistance=%.1f captureFrames=%d serverAudio=%s localMatch=%s dispatch=%s serverAccepted=%s response=%s reply=%s notice=%s away=%s startAngle=%.1f bestAfter=%.1f facingTicks=%d attackTarget=%s%n",
                    success?"PASS":"FAIL",o.test.name,o.test.creative?"Creative":"Survival",o.test.openMic?"activation":"PTT",
                    o.test.range,o.callDistance,Boundary.nonzeroFrames,o.wireAudio,o.recognized,o.dispatched,o.acceptedCue,o.reaction,
                    o.confirmed,o.notice,o.facedAway,o.initialError,o.closestError,o.aligned,o.targeted);
            if (!success) failures++;
            if (!o.test.openMic) ((KeyMapping)Boundary.get(Boundary.client(),"talk")).setDown(false);
            if (o.number+1==TESTS.length) {
                System.out.println("ACOUSTIC_COMPLETE trials="+TESTS.length+" failed="+failures);
                complete=true;
            } else next=true;
        } catch (Exception ex) { fail(ex); }
    }

    private void continueWorld(Minecraft mc) {
        if (mc.screen==null || !mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(home.resolve("game").toAbsolutePath().normalize())) return;
        if (!Set.of("selectWorld.backupQuestion.experimental","selectWorld.experimental.title","selectWorld.warning.experimental.title")
                .stream().map(k->Component.translatable(k).getString()).anyMatch(mc.screen.getTitle().getString()::equals)) return;
        var allowed=Set.of(Component.translatable("selectWorld.backupJoinSkipButton").getString(),
                Component.translatable("gui.proceed").getString(),Component.translatable("gui.yes").getString());
        for (var widget:mc.screen.children()) if (widget instanceof Button b && b.active && allowed.contains(b.getMessage().getString())) {
            b.onPress(); return;
        }
    }
}
