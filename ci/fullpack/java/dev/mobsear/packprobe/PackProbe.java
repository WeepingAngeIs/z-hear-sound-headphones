package dev.mobsear.packprobe;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.fixture.CalledPokemonVisit;
import dev.mobsear.net.Packets;
import dev.mobsear.server.NearbyMics;
import dev.mobsear.server.ServerVoice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Observes an unmodified production JAR in one genuine, disposable integrated server. */
@Mod(value="acoustic_pack_probe", dist=Dist.CLIENT)
public final class PackProbe {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PackProbe.class);
    record Trial(String name, int range, boolean creative, boolean hears, boolean openMic, String recording, boolean detail) {
        Trial(String name, int range, boolean creative, boolean hears, boolean openMic, String recording) {
            this(name,range,creative,hears,openMic,recording,true);
        }
        Trial(String name, int range, boolean creative, boolean hears, boolean openMic) {
            this(name,range,creative,hears,openMic,name.toLowerCase(Locale.ROOT));
        }
        boolean spokenName() { return !Set.of("hold-on", "fan", "silence", "sentence-partial-mob", "sentence-ordinary").contains(recording); }
        boolean distantImperfect() { return recording.equals("nearby-bulbazore") && range>32; }
        String expectedName() { return recording.equals("pikachu") ? "Pikachu" : name; }
    }
    private final boolean earlier = "1".equals(System.getenv("MOBSEAR_EARLIER"));
    private final boolean measuredDecisions = "1".equals(System.getenv("MOBSEAR_EXTRA_NAMES"));
    private final boolean testFeedback = "1".equals(System.getenv("MOBSEAR_TEST_FEEDBACK"));
    private final boolean testVisit = "1".equals(System.getenv("MOBSEAR_TEST_VISIT"));
    private final boolean testSentences = "1".equals(System.getenv("MOBSEAR_TEST_SENTENCES"));
    private final LiveNearbyProof liveNearby = "1".equals(System.getenv("MOBSEAR_TEST_NEARBY")) ? new LiveNearbyProof() : null;
    private final Trial[] tests = trials();

    private Trial[] trials() {
        if (earlier) return new Trial[]{new Trial("Enderman",24,false,true,true), new Trial("Enderman",24,false,true,false)};
        var cases=new ArrayList<>(List.of(
        new Trial("Enderman", 24, false, true, true),
        new Trial("Pikachu", 12, false, true, true),
        new Trial("Lopunny", 16, false, true, true),
        new Trial("Pikachu", 40, false, false, true),
        new Trial("Enderman", 24, true, false, true),
        new Trial("Enderman", 24, false, true, false)));
        if ("1".equals(System.getenv("MOBSEAR_EXTRA_NAMES"))) {
            cases.add(new Trial("Enderman",24,false,true,true,"enderman-alternate"));
            cases.add(new Trial("Lopunny",16,false,true,false,"lopunny-alternate"));
            for (String negative : List.of("hold-on","fan","silence"))
                cases.add(new Trial("Pikachu",12,false,false,false,negative));
            if (testFeedback) {
                cases.add(new Trial("Lopunny",16,false,false,false,"pikachu"));
                cases.add(new Trial("Lopunny",16,false,false,false,"hold-on",false));
            }
        }
        if (testSentences) {
            cases.add(new Trial("Lopunny",16,false,true,true,"sentence-lopunny-end"));
            cases.add(new Trial("Pikachu",12,false,true,false,"sentence-pikachu-middle"));
            cases.add(new Trial("Enderman",24,false,true,true,"sentence-enderman-end"));
            cases.add(new Trial("Lopunny",40,false,false,true,"sentence-lopunny-end"));
            cases.add(new Trial("Pikachu",12,false,false,false,"sentence-partial-mob"));
            cases.add(new Trial("Pikachu",12,false,false,true,"sentence-ordinary"));
            cases.add(new Trial("Lopunny",16,false,true,true,"sentence-lopunny-middle"));
        }
        if (testVisit) {
            cases.add(new Trial("Pikachu",12,false,true,true));
            cases.add(new Trial("Pikachu",12,false,true,true));
        }
        if (liveNearby!=null) {
            cases.add(new Trial("Bulbasaur",16,false,true,true,"nearby-bulbazore"));
            cases.add(new Trial("Bulbasaur",40,false,false,true,"nearby-bulbazore"));
            cases.add(new Trial("Nidorino",16,false,true,true,"nearby-nidorine"));
            cases.add(new Trial("Pikachu",12,false,true,false,"sentence-pikachu-middle"));
        }
        if (testVisit) cases.add(new Trial("Lopunny",16,false,true,true));
        return cases.toArray(Trial[]::new);
    }
    static final class Observation {
        final Trial test;
        final int number, entity;
        final Mob subject; // Accessed only on the server thread.
        PokemonEntity relative;
        PackVisitFeeding feeding;
        final long frameBefore, cueBefore;
        final boolean visitTrial;
        CalledPokemonVisit visit; // Server-thread observer, after native navigation becomes available.
        int arrangeTicks, lastWanderTick, activeSamples;
        boolean wasActive;
        volatile double serverBestAngle=180;
        volatile String lastNativeState="";
        volatile boolean primed, beside, finished;
        boolean besideScreenshot;
        volatile boolean wireAudio, acceptedCue, reaction, targeted;
        volatile double callDistance = -1;
        volatile boolean started;
        boolean recognized, dispatched, confirmed, notice, facedAway, screenshot;
        boolean anyReply, listening, checking, guess, noMatch, privateText, waiting;
        int aligned, postAckTicks;
        double initialError = -1, closestError = 180;
        long initialRequest, startedAt, seenAt, initialDecision;
        Boundary.Decision decision;
        long matchedAt, dispatchedAt, turnedAt;
        volatile long acceptedAt;
        final ConcurrentLinkedQueue<byte[]> segments = new ConcurrentLinkedQueue<>();
        Observation(Trial test, int number, Mob mob, long frame, long cue, boolean visitTrial) {
            this.test = test; this.number = number; this.subject = mob; this.entity = mob.getId();
            this.frameBefore = frame; this.cueBefore = cue;
            this.visitTrial = visitTrial;
        }
    }
    private volatile Observation current;
    private volatile boolean ready, next, fatal, complete;
    private long nextSetup;
    private int index, failures, clientTicks, startupStep = -1;
    private boolean installed, hooked, armed, freshDefaults;
    private String lastScreen = "";
    private String lastDecoderState = "";
    private long armedAt, processAt;
    private Process desktop;
    private final PackOnboarding onboarding=new PackOnboarding();
    private final Path home = Path.of(System.getenv("MOBSEAR_SMOKE_DIR"));
    private final Set<String> testPreviews = new HashSet<>();
    private static final ResourceLocation CALL_NOTICE = ResourceLocation.fromNamespaceAndPath("mobsear","mob_call_notice");
    private record DrawnDiagnostic(String label, Component text) {}
    private DrawnDiagnostic drawnDiagnostic;
    private boolean observedListening, observedChecking;

    public PackProbe() {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))) throw new IllegalStateException("Hosted fixture only");
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::noticeDrawn);
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
            if (liveNearby!=null && !liveNearby.server(p)) return;
            if (next) {
                current.subject.discard();
                if (current.relative!=null) current.relative.discard();
                current = null;
                index++;
                nextSetup = System.nanoTime() + 2_000_000_000L;
                next = false;
            }
            if (index == tests.length) { complete = true; return; }
            if (current == null) {
                if (System.nanoTime() < nextSetup) return;
                if (index == 0) {
                    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, e.getServer());
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, e.getServer());
                    level.setDayTime(18000);
                    level.setWeatherParameters(60000, 0, false, false);
                    for (int x=-6; x<=46; x++) for (int z=-5; z<=5; z++)
                        level.setBlockAndUpdate(new BlockPos(x,159,z), Blocks.SEA_LANTERN.defaultBlockState());
                    // The lit wall behind the subject makes a distant Enderman visible against the night sky.
                    for (int y=160; y<=166; y++) for (int z=-5; z<=5; z++)
                        level.setBlockAndUpdate(new BlockPos(-5,y,z), Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
                }
                var t = tests[index];
                // Exercise Pikachu repeatedly at night as well as during daytime.
                boolean nightPikachu=testVisit && index>1 && t.hears && t.name.equals("Pikachu") && t.recording.equals("pikachu");
                level.setDayTime(t.name.equals("Enderman") || nightPikachu ? 18000 : 1000);
                boolean visitTrial=testVisit && index==tests.length-1;
                if (visitTrial) {
                    for (int x=-3; x<=19; x++) for (int z=-2; z<=25; z++) {
                        level.setBlockAndUpdate(new BlockPos(x,159,z),Blocks.SEA_LANTERN.defaultBlockState());
                        for (int y=160; y<=164; y++) {
                            var block=new BlockPos(x,y,z);
                            if (!level.isEmptyBlock(block)) level.setBlockAndUpdate(block,Blocks.AIR.defaultBlockState());
                        }
                    }
                }
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
                var observation = new Observation(t,index,mob,timestamp(p,"lastFrame"),timestamp(p,"lastCue"),visitTrial);
                if (visitTrial && liveNearby!=null) observation.feeding=new PackVisitFeeding();
                if (t.recording.equals("nearby-nidorine")) {
                    var relative=PokemonProperties.Companion.parse("nidorina level=15").createEntity(level);
                    relative.moveTo(p.getX()-7,160,p.getZ()+2,90,0);
                    relative.setPersistenceRequired();
                    if (!level.addFreshEntity(relative) || !relative.getPokemon().isWild()) throw new IllegalStateException("Missing nearer Nidorina");
                    observation.relative=relative;
                }
                current=observation;
            }
            var o = current;
            int tick = e.getServer().getTickCount();
            boolean active = responding(o.subject);
            if (o.feeding!=null && o.started) o.feeding.server((PokemonEntity)o.subject,p,active);
            if (o.relative!=null && responding(o.relative)) throw new IllegalStateException("Imperfect name called the wrong relative");
            if (o.relative!=null && !active && !o.acceptedCue && timestamp(p,"lastCue")==o.cueBefore) {
                // The input comparison requires two eligible, idle relatives. Release both
                // untouched as soon as the real production packet reaches the server.
                LiveNearbyProof.prepareIdle((PokemonEntity)o.subject,.5,.5);
                LiveNearbyProof.prepareIdle(o.relative,o.test.range-6.5,2.5);
            }
            if (o.visitTrial && !o.finished) {
                var pokemon=(PokemonEntity)o.subject;
                if (!o.started && !o.primed) {
                    o.arrangeTicks++;
                    if (o.arrangeTicks%40==0) {
                        // A wild Pokémon may replace the initially arranged idle route before moving.
                        // Arrange a new walk only before sending any audio. Leave all post-call AI alone.
                        System.out.println("ACOUSTIC_PRE_CALL_WALK tick="+o.arrangeTicks+" "+nativeState(pokemon));
                        o.visit=null;
                    }
                    if (o.arrangeTicks>360) throw new IllegalStateException("No physical pre-call wander: "+nativeState(pokemon));
                }
                // A freshly spawned entity has not taken its first ground/navigation tick.
                if (o.visit==null) {
                    if (o.arrangeTicks>=12 && o.arrangeTicks%4==0 && pokemon.onGround() && !pokemon.isSleeping() && !pokemon.isBusy()) {
                        for (int length : new int[]{16,12,8}) {
                            int direction=pokemon.getZ()>8 ? -length : length;
                            var path=pokemon.getNavigation().createPath(pokemon.blockPosition().offset(0,0,direction),0);
                            if (path!=null && path.canReach()) {
                                o.visit=new CalledPokemonVisit(pokemon,p,direction);
                                System.out.println("ACOUSTIC_WANDER_START route="+direction+" grounded=true");
                                break;
                            }
                        }
                    }
                    if (o.visit==null) return;
                }
                if (!o.reaction && !active) {
                    if (o.visit.isWandering()) {
                        o.lastWanderTick=tick;
                        o.primed=true;
                    }
                } else {
                    if (!o.reaction && tick-o.lastWanderTick>2)
                        throw new IllegalStateException("Lopunny was not still wandering when it heard its name");
                    o.finished=o.visit.observe(active,false,tick);
                    o.beside=o.visit.stayedTwoSeconds();
                }
            }
            if (o.started) {
                Object status=((Map<?,?>)Boundary.field(NearbyMics.class,"ACTIVE").get(null)).get(p);
                if (timestamp(p,"lastFrame") > o.frameBefore && status!=null && (int)Boundary.get(status,"level")>0
                        && (int)Boundary.get(status,"heard")>=e.getServer().getTickCount()-2) o.wireAudio=true;
                if (!o.acceptedCue && timestamp(p,"lastCue") > o.cueBefore) {
                    o.callDistance=o.subject.distanceTo(p);
                    o.acceptedAt=System.nanoTime();
                    o.acceptedCue=true;
                }
                o.reaction |= active;
                o.targeted |= o.subject.getTarget()!=null;
                if (active) {
                    o.activeSamples++;
                    double yaw=Math.toDegrees(Math.atan2(p.getZ()-o.subject.getZ(),p.getX()-o.subject.getX()))-90;
                    o.serverBestAngle=Math.min(o.serverBestAngle,Math.abs(Mth.wrapDegrees(o.subject.getYHeadRot()-yaw)));
                }
                if (o.subject instanceof PokemonEntity pokemon) {
                    if (active!=o.wasActive) {
                        o.lastNativeState=nativeState(pokemon);
                        System.out.println("ACOUSTIC_POKEMON_STATE trial="+o.number+" active="+active+" samples="+o.activeSamples+
                                " bestServerAngle="+Math.round(o.serverBestAngle)+" dayTime="+p.serverLevel().getDayTime()+" "+o.lastNativeState);
                    } else if (tick%10==0) o.lastNativeState=nativeState(pokemon);
                    o.wasActive=active;
                }
            }
        } catch (Exception ex) { fail(ex); }
    }

    private static String nativeState(PokemonEntity pokemon) {
        var brain=pokemon.getBrain();
        var nav=pokemon.getNavigation();
        return "position="+pokemon.blockPosition()+" tick="+pokemon.tickCount+" onGround="+pokemon.onGround()+
                " sleeping="+pokemon.isSleeping()+" busy="+pokemon.isBusy()+" noAI="+pokemon.isNoAi()+
                " activity="+brain.getActiveNonCoreActivity().map(Object::toString).orElse("none")+
                " hurt="+(pokemon.getLastHurtByMob()!=null)+" aggressive="+pokemon.isAggressive()+
                " navDone="+nav.isDone()+" route="+(nav.getPath()==null ? "none" : nav.getPath().getTarget())+
                " walkMemory="+brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
    }

    private void clientTick(ClientTickEvent.Post e) {
        if (fatal || complete) return;
        try {
            var mc = Minecraft.getInstance();
            if (ready && liveNearby!=null) liveNearby.client();
            if (!installed) {
                Boundary.install();
                installed=true;
                System.out.println("ACOUSTIC_DEVICE synthetic Java Sound input, zero physical mixers");
            }
            clientTicks++;
            if (!mc.isWindowActive() && clientTicks%40==0 && mc.getWindow()!=null)
                org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
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
                mc.options.chatVisibility().set(ChatVisiblity.HIDDEN);
                if (mc.getSingleplayerServer()==null || !mc.getConnection().getConnection().isMemoryConnection())
                    throw new IllegalStateException("Expected integrated connection");
                if (earlier) {
                    if (!mc.getConnection().hasChannel(Packets.Call.TYPE)) throw new IllegalStateException("Missing original call channel");
                } else CurrentNotices.install(this);
                if (testSentences) {
                    if (VoiceClient.options().callDebugLog()) throw new IllegalStateException("Debug logging defaulted on");
                    LOG.info("ACOUSTIC_DEBUG_WINDOW enabled=true freshDefaultOff=true");
                    VoiceClient.options().setCallDebugLog(true);
                }
                hooked=true;
            }
            if (!mc.isWindowActive() || mc.isPaused() || mc.screen!=null) {
                if (clientTicks%200==0) System.out.println("ACOUSTIC_FOCUS active="+mc.isWindowActive()+" paused="+mc.isPaused()+" screen="+screen);
                return;
            }
            if (!armed) {
                Boolean saved = Boundary.savedCalls();
                freshDefaults = saved != null;
                if (freshDefaults) {
                    if (!saved || !VoiceClient.voiceActivated() || VoiceClient.options().muted() || VoiceClient.options().deafened()
                            || ((KeyMapping)Boundary.get(Boundary.client(),"talk")).isDown())
                        throw new IllegalStateException("Unexpected fresh voice or call defaults in the disposable pack");
                    System.out.println("ACOUSTIC_FRESH_DEFAULTS voiceActivation=true localMobCalls=true muted=false talkKey=false");
                } else if (!Boundary.callsEnabled()) {
                    var toggle=(KeyMapping)Boundary.get(Boundary.client(),"toggle");
                    KeyMapping.click(toggle.getKey());
                }
                armed=true;
                armedAt=System.nanoTime();
                return;
            }
            String state=Boundary.readiness();
            Boundary.observeGeneratedSegments(pcm->{
                var trial=current;
                if (trial!=null && trial.started) trial.segments.add(pcm);
            });
            if (!earlier && "1".equals(System.getenv("MOBSEAR_TRACK_STARTUP"))) {
                int step=Boundary.startupProgress();
                if (step!=startupStep) {
                    String phase=switch(step) {
                        case -1 -> "inactive";
                        case 0 -> "launching";
                        case 81 -> "reading-catalog";
                        case 71 -> "starting-engine";
                        case 69 -> "loading-grammar";
                        case 76 -> "warming-up";
                        case 82 -> "ready";
                        default -> throw new IllegalStateException("Unexpected startup step");
                    };
                    System.out.println("ACOUSTIC_STARTUP_STEP "+phase+" elapsedMs="+(System.nanoTime()-armedAt)/1_000_000L);
                    startupStep=step;
                }
            }
            if (desktop != null && !desktop.isAlive()) {
                System.out.println("ACOUSTIC_DESKTOP_EXIT code="+desktop.exitValue()+" elapsedMs="+(System.nanoTime()-processAt)/1_000_000L);
                desktop=null;
            }
            var active=Boundary.desktopProcess();
            if (active!=null && active!=desktop && active.isAlive()) {
                desktop=active;
                processAt=System.nanoTime();
                System.out.println("ACOUSTIC_CHILD "+desktop.pid());
            }
            if (!state.equals(lastDecoderState)) {
                System.out.println("ACOUSTIC_RECOGNIZER "+state+" elapsedMs="+(System.nanoTime()-armedAt)/1_000_000L+" callsEnabled="+Boundary.callsEnabled());
                lastDecoderState=state;
            }
            if (!state.equals("READY") && !state.equals("UNAVAILABLE")) return;
            if (!ready) {
                // Voice stays opted in through onboarding. A first-join screen intentionally
                // suspends capture, so play comparison words only after the tutorial's Later action.
                if (!onboarding.readyForSpeech()) return;
                if (!earlier) Files.write(home.resolve("public-registry.bin"),Boundary.publicVocabulary());
                System.out.println("ACOUSTIC_TRIALS decoder="+state+" integrated=true mode="+mc.gameMode.getPlayerMode()+" loader="+
                        ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion()+
                        " mod="+ModList.get().getModContainerById("mobsear").orElseThrow().getModInfo().getVersion());
                ready=true;
            }
            var o=current;
            if (o==null || next) return;
            if (mc.level.getEntity(o.entity) instanceof Mob mob) {
                if (o.feeding!=null && o.started) o.feeding.client(mc,(PokemonEntity)mob);
                if (o.visitTrial) mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,mob.getEyePosition());
                double yaw=Math.toDegrees(Math.atan2(mc.player.getZ()-mob.getZ(),mc.player.getX()-mob.getX()))-90;
                double err=Math.abs(Mth.wrapDegrees(mob.getYHeadRot()-yaw));
                if (!o.started) {
                    if (o.seenAt==0) {
                        o.seenAt=System.nanoTime();
                        if (testSentences) {
                            boolean log = !o.test.recording.equals("lopunny-alternate");
                            if (log != VoiceClient.options().callDebugLog()) {
                                LOG.info("ACOUSTIC_DEBUG_WINDOW enabled={} trial={}",log,o.number);
                                VoiceClient.options().setCallDebugLog(log);
                            }
                        }
                    }
                    if (err>60) o.facedAway=true;
                    if (testFeedback) VoiceClient.options().setRecognitionDiagnostics(o.test.detail);
                    if (!freshDefaults || o.number!=0) VoiceClient.setVoiceActivated(o.test.openMic);
                    if (!o.test.openMic) ((KeyMapping)Boundary.get(Boundary.client(),"talk")).setDown(true);
                    if (mc.player.isCreative()!=o.test.creative || System.nanoTime()-o.seenAt<350_000_000L) return;
                    if (o.test.recording.equals("nearby-bulbazore") && (System.nanoTime()-o.seenAt<1_500_000_000L ||
                            LiveNearbyProof.has("cobblemon:bulbasaur")!=o.test.hears)) {
                        if (System.nanoTime()-o.seenAt>15_000_000_000L) throw new IllegalStateException("Incorrect live Bulbasaur pool before speech");
                        return;
                    }
                    if (o.test.recording.equals("nearby-nidorine") && (System.nanoTime()-o.seenAt<1_500_000_000L ||
                            !LiveNearbyProof.has("cobblemon:nidorina") || !LiveNearbyProof.has("cobblemon:nidorino"))) {
                        if (System.nanoTime()-o.seenAt>15_000_000_000L) {
                            System.out.println("ACOUSTIC_RELATIVES_TIMEOUT nidorina="+LiveNearbyProof.has("cobblemon:nidorina")+
                                    " nidorino="+LiveNearbyProof.has("cobblemon:nidorino"));
                            throw new IllegalStateException("Missing live pair before generated name");
                        }
                        return;
                    }
                    if (o.visitTrial && !o.primed) return;
                    o.initialError=err;
                    if (earlier) mc.gui.setOverlayMessage(Component.empty(),false);
                    else o.initialRequest=(long)Boundary.get(Boundary.client(),"lastRequestId");
                    var prior=Boundary.decision();
                    o.initialDecision=prior==null ? 0 : prior.number();
                    o.startedAt=System.nanoTime();
                    o.started=true;
                    System.out.println("ACOUSTIC_PLAY sample="+o.test.recording);
                    Boundary.say(Files.readAllBytes(home.resolve("synth").resolve(o.test.recording+".pcm")));
                    return;
                }
                if (o.acceptedCue || o.confirmed) {
                    o.postAckTicks++;
                    o.closestError=Math.min(o.closestError,err);
                    if (err<=30) {
                        o.aligned++;
                        if (o.turnedAt==0) o.turnedAt=System.nanoTime();
                    }
                }
            }
            if (!o.started) return;
            var observed=Boundary.decision();
            if (observed!=null && observed.number()>o.initialDecision) o.decision=observed;
            if (earlier) {
                var text=(Component)Boundary.field(net.minecraft.client.gui.Gui.class,"overlayMessageString").get(mc.gui);
                var originalNotice=Component.translatable("mobsear.speech.recognized",Component.translatable("entity.minecraft.enderman"));
                o.recognized |= text!=null && text.getString().equals(originalNotice.getString());
                o.dispatched |= o.acceptedCue;
            } else {
                boolean newMatch=!measuredDecisions || o.decision!=null && o.decision.matched() && o.decision.timely();
                o.recognized |= newMatch && Boundary.notice("localMatch").contains(o.test.expectedName());
                o.notice |= o.confirmed && Boundary.notice("current").equals(o.test.name + " heard you");
                o.dispatched |= (long)Boundary.get(Boundary.client(),"lastRequestId") > o.initialRequest;
                if (testFeedback) {
                    String key=Boundary.noticeKey("localMatch");
                    o.listening |= key.equals("mobsear.speech.test_listening");
                    o.checking |= key.equals("mobsear.speech.test_checking");
                    String prompt=Boundary.promptKey();
                    o.waiting |= prompt.equals("mobsear.speech.test_wait") && Boundary.notice("current").isEmpty();
                    o.privateText |= !key.isEmpty() || !prompt.isEmpty();
                    observedListening |= o.listening;
                    observedChecking |= o.checking;
                    if (o.decision!=null) {
                        o.guess |= key.equals("mobsear.speech.test_guess");
                        o.noMatch |= key.equals("mobsear.speech.no_match");
                    }
                }
            }
            if (o.recognized && o.matchedAt==0) o.matchedAt=System.nanoTime();
            if (o.dispatched && o.dispatchedAt==0) o.dispatchedAt=System.nanoTime();
            long elapsed=System.nanoTime()-o.startedAt;
            if (!Boundary.consumed() && elapsed<12_000_000_000L) return;
            long observationLimit=o.visitTrial ? 90_000_000_000L : o.test.hears ? 12_000_000_000L : 6_000_000_000L;
            // The first negative checks must also photograph the fully drawn diagnostic.
            // Give the renderer time after a slow frame, without extending decision freshness.
            if (testFeedback && o.test.detail && ((o.test.recording.equals("fan") && !testPreviews.contains("no-match"))
                    || (o.test.recording.equals("hold-on") && !testPreviews.contains("rejected-guess")))) {
                observationLimit=14_000_000_000L;
            }
            // Capture/render time varies with pack load. Allow real frames for the notice after
            // the generated voice finishes; the production recognizer's own freshness test still decides.
            if (!o.visitTrial && o.test.hears && Boundary.lastSignalAt>o.startedAt)
                observationLimit=Math.max(observationLimit,Math.min(18_000_000_000L,Boundary.lastSignalAt-o.startedAt+7_000_000_000L));
            boolean completedVisit=!o.visitTrial || o.finished && o.besideScreenshot && (o.feeding==null || o.feeding.passed());
            if (elapsed<observationLimit && !(o.test.hears && (earlier ? o.recognized : o.screenshot) && o.aligned>=2 && o.reaction && completedVisit)) return;
            boolean silent=o.test.recording.equals("silence");
            boolean capture=Boundary.consumed() && (silent ? Boundary.nonzeroFrames==0 : Boundary.nonzeroFrames>=2);
            int segments=0;
            byte[] generated;
            while ((generated=o.segments.poll())!=null) {
                var folder=home.resolve("segments");
                Files.createDirectories(folder);
                Files.write(folder.resolve(o.number+"_"+o.test.recording+"_"+segments++ +".pcm"),generated);
            }
            System.out.println("ACOUSTIC_SEGMENTS sample="+o.test.recording+" submitted="+segments);
            if (o.test.recording.equals("nearby-nidorine") && segments>0) {
                var rows=Boundary.generatedHints();
                Files.write(home.resolve("segments").resolve("nidorine-public-hints.txt"),rows);
                System.out.println("ACOUSTIC_GENERATED_HINTS "+rows);
            }
            if (o.decision!=null) System.out.println("ACOUSTIC_LIVE_DECISION sample="+o.test.recording
                    +" matched="+o.decision.matched()+" timely="+o.decision.timely()+" decoderAgeMs="+o.decision.ageMillis()
                    +" issue="+o.decision.issue());
            else System.out.println("ACOUSTIC_LIVE_DECISION sample="+o.test.recording+" returned=false");
            // Open dictation may spell the remote species exactly. A near-only guess must never redirect it.
            boolean expectedName=o.test.distantImperfect() ? o.decision!=null && o.decision.matched() : o.test.spokenName();
            boolean expectWire=expectedName && !o.test.creative;
            boolean correctDecision=expectedName || (silent ? o.decision==null : o.decision!=null && !o.decision.matched()
                    && o.decision.timely() && o.decision.issue().equals("NONE"));
            boolean success=capture && o.wireAudio!=silent && o.recognized==expectedName && correctDecision
                    && o.dispatched==expectWire && o.acceptedCue==expectWire
                    && (earlier || o.confirmed==o.test.hears && o.notice==o.test.hears) && o.reaction==o.test.hears && !o.targeted
                    && (!o.test.hears || (o.facedAway && o.aligned>=2 && (earlier || o.screenshot))) && completedVisit;
            if (o.visitTrial) System.out.println("ACOUSTIC_POKEMON_VISIT audio="+o.wireAudio+" recognized="+o.recognized+
                    " dispatched="+o.dispatched+" wandering="+o.primed+" fullVisit="+o.finished+" besideFrame="+o.besideScreenshot);
            if (!success && !o.lastNativeState.isEmpty()) System.out.println("ACOUSTIC_NATIVE_OBSERVATION trial="+o.number+
                    " image="+o.screenshot+" activeSamples="+o.activeSamples+" bestServerAngle="+Math.round(o.serverBestAngle)+" "+o.lastNativeState);
            if (testFeedback) {
                boolean detailOk=!o.test.detail ? !o.privateText : switch(o.test.recording) {
                    case "hold-on" -> o.guess;
                    case "fan" -> o.noMatch;
                    case "sentence-partial-mob", "sentence-ordinary" -> o.noMatch || o.guess;
                    case "nearby-bulbazore" -> o.recognized || o.test.distantImperfect() && (o.noMatch || o.guess);
                    case "silence" -> o.waiting && !o.listening && !o.checking;
                    default -> o.recognized;
                };
                success &= detailOk && o.anyReply==o.test.hears;
                System.out.printf("ACOUSTIC_TEST_FEEDBACK %d enabled=%s sample=%s nearby=%s listening=%s checking=%s localName=%s rejectedGuess=%s noMatch=%s waiting=%s serverReply=%s passed=%s%n",
                        o.number,o.test.detail,o.test.recording,o.test.name,o.listening,o.checking,o.recognized,o.guess,o.noMatch,o.waiting,o.anyReply,detailOk);
            }
            System.out.println("ACOUSTIC_LATENCY "+o.test.recording+" lastVoicedToMatchMs="+milliseconds(Boundary.lastSignalAt,o.matchedAt)
                    +" inputToDispatchMs="+milliseconds(o.startedAt,o.dispatchedAt)+" inputToServerMs="+milliseconds(o.startedAt,o.acceptedAt)
                    +" inputToTurnMs="+milliseconds(o.startedAt,o.turnedAt));
            System.out.printf(Locale.ROOT,
                    "ACOUSTIC_RESULT %s %s mode=%s input=%s arranged=%d callDistance=%.1f captureFrames=%d serverAudio=%s localMatch=%s dispatch=%s serverAccepted=%s response=%s reply=%s notice=%s away=%s startAngle=%.1f bestAfter=%.1f facingTicks=%d attackTarget=%s%n",
                    success?"PASS":"FAIL",o.test.name,o.test.creative?"Creative":"Survival",o.test.openMic?"activation":"PTT",
                    o.test.range,o.callDistance,Boundary.nonzeroFrames,o.wireAudio,o.recognized,o.dispatched,o.acceptedCue,o.reaction,
                    o.confirmed,o.notice,o.facedAway,o.initialError,o.closestError,o.aligned,o.targeted);
            if (testSentences && success && o.confirmed && !VoiceClient.options().callDebugLog())
                LOG.info("ACOUSTIC_DEBUG_CONFIRM_WHILE_DISABLED trial={}",o.number);
            if (!success) failures++;
            if (!o.test.openMic) ((KeyMapping)Boundary.get(Boundary.client(),"talk")).setDown(false);
            if (o.number+1==tests.length) {
                if (liveNearby!=null && !liveNearby.passed()) failures++;
                if (testSentences) verifyDecoderDirectory();
                if (testFeedback) {
                    var missing = new java.util.TreeSet<>(Set.of("listening","rejected-guess","no-match","waiting","other-name"));
                    missing.removeAll(testPreviews);
                    System.out.println("ACOUSTIC_DIAGNOSTIC_FRAMES missing="+missing+" listening="+observedListening+" checking="+observedChecking);
                    if (!observedListening || !observedChecking || !missing.isEmpty()) failures++;
                }
                System.out.println("ACOUSTIC_COMPLETE trials="+tests.length+" failed="+failures);
                complete=true;
            } else next=true;
        } catch (Exception ex) { fail(ex); }
    }

    private static void verifyDecoderDirectory() throws Exception {
        var field=Class.forName("dev.mobsear.client.OfflineWordModel").getDeclaredField("directory");
        field.setAccessible(true);
        var directory=(java.nio.file.Path)field.get(null);
        if (directory==null) throw new IllegalStateException("Bundled sentence decoder was unavailable");
        var expected=Set.of("whisper-cli.exe","whisper.dll","ggml.dll","ggml-base.dll","ggml-cpu.dll",
                "tiny.en.bin","LICENSE-Whisper.txt","LICENSE-whisper.cpp.txt");
        try (var files=java.nio.file.Files.list(directory)) {
            var actual=files.map(path->path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected)) throw new IllegalStateException("The decoder wrote unexpected files");
        }
        System.out.println("ACOUSTIC_MODEL_FILES_PASS only bundled model, executable, libraries and licenses");
    }

    private void render(RenderFrameEvent.Post event) {
        var o=current;
        var mc=Minecraft.getInstance();
        if (o!=null && o.visitTrial && o.beside && !o.besideScreenshot && !next && !fatal
                && mc.player!=null && mc.level!=null && mc.screen==null && mc.getOverlay()==null
                && mc.level.getEntity(o.entity) instanceof PokemonEntity pokemon && mc.player.distanceTo(pokemon)<=2.8) {
            try (var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(Files.createDirectories(home.resolve("evidence")).resolve("visit-beside-Lopunny.png"));
                o.besideScreenshot=true;
            } catch (Exception ex) { fail(ex); }
        }
        var drawn=drawnDiagnostic;
        drawnDiagnostic=null;
        if (drawn!=null && !fatal && !complete && mc.screen==null && mc.getOverlay()==null) {
            try { captureTestPreview(mc,drawn); } catch (Exception ex) { fail(ex); }
        }
        if (fatal || complete || next || o==null || o.screenshot || !o.confirmed || !o.notice
                || mc.screen!=null || mc.getOverlay()!=null) return;
        try {
            if (!Boundary.notice("current").equals(o.test.name+" heard you")) return;
            try (var pic=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int scale=(int)mc.getWindow().getGuiScale();
                int left=(mc.getWindow().getGuiScaledWidth()/2-91)*scale;
                int top=pic.getHeight(), bottom=0;
                for (int y=pic.getHeight()/2; y<pic.getHeight()-2*scale; y++) {
                    int row=0;
                    for (int x=Math.max(0,left); x<Math.min(pic.getWidth(),left+182*scale); x+=scale)
                        if ((pic.getPixelRGBA(x,y)&0xffffff)==0xa177d7) row++;
                    if (row>=8) { top=Math.min(top,y); bottom=y; }
                }
                // Earlier gates require a real server confirmation and that exact named message
                // still being displayed. A separately visible diagnostic adds a second line.
                String diagnostic = Boundary.notice("localMatch");
                int minHeight = diagnostic.isEmpty() || diagnostic.equals(o.test.name+" heard you") ? 13 : 21;
                if (bottom-top+1<minHeight*scale) return;
                var folder=Files.createDirectories(home.resolve("evidence"));
                pic.writeToFile(folder.resolve(o.number+"-"+o.test.name+".png"));
                o.screenshot=true;
                System.out.println("ACOUSTIC_RENDERED_NOTICE sample="+o.test.recording+" noticeHeight="+(bottom-top+1)+" guiScale="+scale);
            }
        } catch (Exception ex) { fail(ex); }
    }

    private void noticeDrawn(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Post event) {
        if (!testFeedback || !CALL_NOTICE.equals(event.getName()) || testPreviews.size()>=6) return;
        drawnDiagnostic=null;
        var o=current;
        if (o==null || !o.started || !o.test.detail || next || fatal || complete) return;
        try { drawnDiagnostic=readDrawnDiagnostic(o); } catch (Exception ex) { fail(ex); }
    }

    private DrawnDiagnostic readDrawnDiagnostic(Observation o) throws Exception {
        String key=Boundary.noticeKey("current");
        String label=switch (key) {
            case "mobsear.speech.test_listening" -> "listening";
            case "mobsear.speech.test_checking" -> "checking";
            case "mobsear.speech.test_guess" -> "rejected-guess";
            case "mobsear.speech.no_match" -> "no-match";
            case "mobsear.speech.recognized" -> !o.test.name.equals(o.test.expectedName())
                    && Boundary.notice("current").contains(o.test.expectedName()) ? "other-name" : "";
            default -> o.test.recording.equals("silence") && key.isEmpty()
                    && Boundary.promptKey().equals("mobsear.speech.test_wait") ? "waiting" : "";
        };
        if (label.isEmpty() || testPreviews.contains(label)) return null;
        var text=key.isEmpty() ? Boundary.prompt() : Boundary.noticeComponent("current");
        return text==null ? null : new DrawnDiagnostic(label,text);
    }

    private void captureTestPreview(Minecraft mc, DrawnDiagnostic drawn) throws Exception {
        var label=drawn.label();
        try (var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            var folder=Files.createDirectories(home.resolve("evidence"));
            if (!showsNotice(mc,image,drawn.text())) {
                var raw=folder.resolve("inspect-no-match.png");
                if (label.equals("no-match") && !Files.exists(raw)) image.writeToFile(raw);
                return;
            }
            image.writeToFile(folder.resolve("test-"+label+".png"));
        }
        testPreviews.add(label);
        System.out.println("ACOUSTIC_TEST_PREVIEW "+label);
    }

    // A render callback can see the next client tick's notice while the captured pixels still
    // show the previous one. Check the actual frame's rose border against the native font layout.
    private static boolean showsNotice(Minecraft mc, com.mojang.blaze3d.platform.NativeImage image, Component text) {
        int scale=(int)mc.getWindow().getGuiScale();
        int width=mc.getWindow().getGuiScaledWidth();
        int x=Math.max(2,width/2-91);
        var face=net.minecraft.network.chat.Style.EMPTY.withFont(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mobsear","notice"));
        var lines=mc.font.split(Component.empty().setStyle(face).append(text),Math.min(182,width-x-2)-27);
        int boxWidth=27+lines.stream().mapToInt(mc.font::width).max().orElse(0);
        int boxHeight=Math.max(17,lines.size()*mc.font.lineHeight+7);
        int borderLeft=x*scale;
        int borderRight=(x+boxWidth)*scale-1;
        if (borderRight+1>=image.getWidth()) return false;
        int rose=0xa177d7; // NativeImage holds ABGR.
        for (int y=image.getHeight()/2; y<image.getHeight()*9/10; y++) {
            int center=borderLeft+boxWidth*scale/2;
            if ((image.getPixelRGBA(center,y)&0xffffff)!=rose) continue;
            int bottom=y+boxHeight*scale-1, middle=(y+bottom)/2;
            if (bottom>=image.getHeight()) break;
            if ((image.getPixelRGBA(center,bottom)&0xffffff)==rose &&
                    (image.getPixelRGBA(borderLeft,middle)&0xffffff)==rose &&
                    (image.getPixelRGBA(borderRight,middle)&0xffffff)==rose &&
                    (image.getPixelRGBA(borderRight+1,middle)&0xffffff)!=rose) return true;
        }
        return false;
    }

    private static long milliseconds(long start,long end) {
        return start==0 || end==0 ? -1 : (end-start)/1_000_000L;
    }

    void heard(long request, String id, boolean species) {
        var o=current;
        if (o==null || !o.started || request<=o.initialRequest) return;
        o.anyReply=true;
        String expected=(o.test.name.equals("Enderman") ? "minecraft:" : "cobblemon:")+o.test.name.toLowerCase(Locale.ROOT);
        if (id.equals(expected) && species==!o.test.name.equals("Enderman")) o.confirmed=true;
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
