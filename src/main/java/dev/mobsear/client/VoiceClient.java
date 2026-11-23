package dev.mobsear.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mobsear.MobsEar;
import dev.mobsear.net.Cue;
import dev.mobsear.net.MicPresence;
import dev.mobsear.net.MicState;
import dev.mobsear.net.Packets;
import dev.mobsear.net.VoiceSignal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value = "mobsear", dist = Dist.CLIENT)
public final class VoiceClient {
    private static VoiceClient instance;
    private record Seen(MicPresence.Nearby snapshot, long arrival) {}
    private final HashMap<UUID, Seen> nearby = new HashMap<>();
    private final Map<UUID, Long> visitors = new HashMap<>();
    private final KeyMapping talk = mapping("talk", GLFW.GLFW_KEY_V);
    private final KeyMapping toggle = mapping("recognition", GLFW.GLFW_KEY_N);
    private final KeyMapping settings = mapping("settings", GLFW.GLFW_KEY_G);
    private final KeyMapping mute = mapping("mute", GLFW.GLFW_KEY_M);
    private final KeyMapping deafen = mapping("deafen", GLFW.GLFW_KEY_UNKNOWN);
    private final VoicePreferences preferences = new VoicePreferences(FMLPaths.CONFIGDIR.get().resolve("mobsear-client.properties"));
    private final CallDebugLog debug = new CallDebugLog(preferences::callDebugLog);
    private ClientPacketListener connection;
    private Session session;
    private boolean testMic;
    private boolean horde;
    private MicPresence.Controls advertised;
    private int sinceAdvertised;
    private boolean waitForRelease;
    // Monotonic across device changes, dimensions, and reconnects in this client process.
    private long lastRequestId, pendingRequestId, pendingInputRevision;

    public VoiceClient(IEventBus bus, ModContainer container) {
        instance = this;
        preferences.initializeProfile();
        bus.addListener(MobCallNotice::register);
        bus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(talk);
            event.register(toggle);
            event.register(settings);
            event.register(mute);
            event.register(deafen);
        });
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> settingsScreen(parent));
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> disconnect());
        MobsEar.playback = this::receive;
        MobsEar.presence = packet -> nearby.put(packet.speaker(), new Seen(packet, System.nanoTime()));
        MobsEar.hordeStatus = enabled -> horde = enabled;
        MobsEar.callHeard = VoiceClient::heard;
        MobsEar.visitStatus = packet -> {
            var level = Minecraft.getInstance().level;
            if (level == null || !packet.arrived()) visitors.remove(packet.pokemonEntity());
            else visitors.put(packet.pokemonEntity(), level.getGameTime() + Packets.Visit.WINDOW_TICKS);
        };
        MobsEar.localVisitor = id -> {
            var level = Minecraft.getInstance().level;
            return level != null && visitors.getOrDefault(id, Long.MIN_VALUE) > level.getGameTime();
        };
        MobsEar.nearbySpecies = reply -> {
            var mc = Minecraft.getInstance();
            if (session == null || !session.recognition || session.suspended || mc.level != session.level
                    || !mc.level.dimension().location().equals(reply.dimension())) return;
            session.speech.setNearbySpecies(reply.species().stream().map(ResourceLocation::toString).toList());
            session.lastRosterTick = mc.level.getGameTime();
        };
        MobsEar.callDeclined = reply -> {
            debug.reply(reply.requestId(), CallDebugLog.Event.valueOf(reply.reason().name()));
            if (pendingRequestId == reply.requestId()) pendingRequestId = 0;
        };
        ClientMobPose.register();
    }

    public static boolean connected() { return instance != null && instance.session != null; }
    public static void heard(Packets.Heard ack) {
        var mc = Minecraft.getInstance();
        if (connected()) instance.debug.reply(ack.requestId(), CallDebugLog.Event.HEARD);
        if (!connected() || ack.requestId() != instance.pendingRequestId || instance.pendingRequestId == 0) return;
        instance.pendingRequestId = 0;
        if (!options().localMobCalls() || instance.pendingInputRevision != options().inputRevision()
                || options().muted() || options().deafened()
                || instance.connection != mc.getConnection() || mc.player == null || !mc.player.isAlive()
                || mc.player.isSpectator() || instance.session.level != mc.level) return;
        Component name;
        if (ack.species()) {
            if (!ModList.get().isLoaded("cobblemon")) return;
            name = CobblemonNames.display(ack.id());
        } else {
            name = EntityCallNames.display(ack.id());
        }
        if (name != null) MobCallNotice.confirm(Component.translatable("mobsear.speech.heard", name));
    }

    static void sendCall(ClientPacketListener link, Packets.MobCall call) {
        if (link.hasChannel(Packets.CallRequest.TYPE) && link.hasChannel(Packets.Heard.TYPE)) {
            long requestId = ++instance.lastRequestId;
            instance.pendingRequestId = requestId;
            instance.pendingInputRevision = options().inputRevision();
            instance.debug.sent(requestId, call);
            PacketDistributor.sendToServer(new Packets.CallRequest(requestId, call));
        } else {
            instance.pendingRequestId = 0;
            instance.debug.write(CallDebugLog.Event.SERVER_CHANNEL_UNAVAILABLE, CallDebugLog.target(call), 0);
            PacketDistributor.sendToServer(call);
        }
    }

    static void setRecognition(boolean enabled) {
        if (options().localMobCalls() != enabled) {
            options().setLocalMobCalls(enabled);
            saveInputChoice();
        }
        if (!enabled) {
            instance.pendingRequestId = 0;
            instance.debug.clearSession();
            MobCallNotice.clearMatch();
            if (instance.session != null && instance.session.recognition) instance.session.enableRecognition(false);
        }
    }

    public static VoicePreferences options() { return instance.preferences; }
    static Component speechTestPrompt() {
        if (!connected() || !options().recognitionDiagnostics() || !options().localMobCalls()) return null;
        var session = instance.session;
        if (session.suspended || options().muted() || options().deafened() || session.speech.status() != SpeechCues.Status.READY)
            return null;
        return session.held ? Component.translatable("mobsear.speech.test_wait")
                : Component.translatable("mobsear.speech.test_ptt", instance.talk.getTranslatedKeyMessage());
    }
    public static boolean voiceActivated() { return instance != null && options().voiceActivated(); }
    public static void setVoiceActivated(boolean enabled) {
        if (instance == null || options().voiceActivated() == enabled) return;
        options().setVoiceActivated(enabled);
        saveInputChoice();
    }
    private static void saveInputChoice() {
        if (!options().save()) MobCallNotice.show(Component.translatable("mobsear.settings.save_error"));
    }
    public static boolean testingMic() { return connected() && instance.testMic; }
    public static void setTestingMic(boolean enabled) {
        if (connected()) instance.testMic = enabled && Minecraft.getInstance().screen instanceof VoiceSettingsScreen;
    }
    public static boolean hordeEnabled() { return connected() && instance.horde; }
    public static void setHordeEnabled(boolean enable) {
        var link = Minecraft.getInstance().getConnection();
        if (connected() && link != null && link.hasChannel(Packets.HordeOpt.TYPE) && (!enable || MobsEar.hordeAvailable())) {
            PacketDistributor.sendToServer(new Packets.HordeOpt(enable));
        }
    }
    public static String localStyle() { return options().resolvedMicStyle(); }
    public static double inputLevel() {
        return connected() && !options().muted() && !options().deafened() ? instance.session.meterLevel() : 0;
    }
    public static MicState localState() {
        if (!connected()) return MicState.IDLE;
        if (options().deafened()) return MicState.DEAFENED;
        if (options().muted()) return MicState.MUTED;
        return instance.session.speaking() ? MicState.SPEAKING : MicState.IDLE;
    }
    public static MicState remoteState(UUID player) {
        var status = remote(player);
        return status == null ? null : status.state();
    }
    public static double remoteLevel(UUID player) {
        var status = remote(player);
        return status == null ? 0 : status.level() / 255.0;
    }
    public static String remoteStyle(UUID player) {
        var status = remote(player);
        return status == null ? "uwu" : MicPresence.styleName(status.style());
    }
    private static MicPresence.Nearby remote(UUID id) {
        if (!connected()) return null;
        var seen = instance.nearby.get(id);
        var mc = Minecraft.getInstance();
        if (seen == null || mc.level == null || mc.player == null || mc.getPlayerSocialManager().shouldHideMessageFrom(id)) return null;
        var pkt = seen.snapshot();
        if (!pkt.dimension().equals(mc.level.dimension().location()) || System.nanoTime() - seen.arrival() > 1_000_000_000L) return null;
        var other = mc.level.getPlayerByUUID(id);
        if (other == null || other == mc.player || !other.isAlive() || other.isSpectator() || !MicPresence.nameVisible(other, mc.player)
                || other.distanceToSqr(mc.player) >= (double) pkt.radius() * pkt.radius()) return null;
        return pkt;
    }

    private static KeyMapping mapping(String action, int key) {
        return new KeyMapping("key.mobsear." + action, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                key, "key.categories.mobsear");
    }

    private Screen settingsScreen(Screen parent) {
        var mc = Minecraft.getInstance();
        preferences.bindPitchAccount(mc.getUser().getProfileId());
        return new VoiceSettingsScreen(parent, preferences, preferences::localMobCalls, VoiceClient::setRecognition,
                uuid -> session != null && session.activity.speaking(uuid, System.nanoTime()));
    }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        debug.tick();
        preferences.bindPitchAccount(mc.getUser().getProfileId());
        var current = mc.getConnection();
        if (connection != current) {
            disconnect();
            connection = current;
            waitForRelease = talk.isDown();
        }
        while (settings.consumeClick()) {
            if (mc.screen == null && mc.player != null) mc.setScreen(settingsScreen(null));
        }
        boolean focused = mc.screen == null && mc.isWindowActive() && !mc.isPaused();
        boolean usable = current != null && mc.level != null && mc.player != null && mc.player.isAlive()
                && !mc.player.isSpectator() && current.hasChannel(Packets.Up.TYPE);
        while (toggle.consumeClick()) {
            if (usable && focused && current.hasChannel(Packets.Call.TYPE)) setRecognition(!preferences.localMobCalls());
        }
        while (mute.consumeClick()) if (usable && focused) {
            preferences.setMuted(!preferences.muted());
            preferences.save();
        }
        while (deafen.consumeClick()) if (usable && focused) {
            preferences.setDeafened(!preferences.deafened());
            preferences.save();
        }
        if (session != null && (!usable || session.level != mc.level
                || session.deviceRevision != preferences.deviceRevision()
                || session.deafened != preferences.deafened())) closeSession();
        if (!usable) return;
        if (session == null) session = new Session(mc.level, preferences);
        session.speech.setTesting(preferences.recognitionDiagnostics());
        publishControls(current);
        if (!(mc.screen instanceof VoiceSettingsScreen) || !mc.isWindowActive()) testMic = false;
        boolean inputBlocked = preferences.muted() || preferences.deafened();
        if (inputBlocked) {
            pendingRequestId = 0;
            MobCallNotice.clearMatch();
        }
        if (inputBlocked) testMic = false;
        if (!talk.isDown()) waitForRelease = false;
        boolean activation = preferences.voiceActivated();
        boolean recognition = preferences.localMobCalls() && current.hasChannel(Packets.Call.TYPE);
        boolean held = focused && !inputBlocked && (activation || (talk.isDown() && !waitForRelease));
        if (!focused || inputBlocked) {
            if (talk.isDown()) waitForRelease = true;
            session.suspendInput();
            session.monitor(testMic);
        }
        if (recognition && focused && !inputBlocked) session.refreshCatalog(current);
        if (session.recognition != recognition) session.enableRecognition(recognition);
        if (focused && !inputBlocked) {
            session.setHeld(held, activation);
            session.send(current);
        }
        session.reportStatus(mc);
    }

    private void publishControls(ClientPacketListener link) {
        var controls = new MicPresence.Controls(MicPresence.styleId(preferences.resolvedMicStyle()),
                preferences.muted(), preferences.deafened());
        if (++sinceAdvertised < 16 && controls.equals(advertised)) return;
        sinceAdvertised = 0;
        advertised = controls;
        if (link.hasChannel(MicPresence.Controls.TYPE)) PacketDistributor.sendToServer(controls);
        long now = System.nanoTime();
        nearby.values().removeIf(seen -> now - seen.arrival() > 1_000_000_000L);
    }

    private void receive(Packets.Down frame) {
        var mc = Minecraft.getInstance();
        if (session == null || preferences.deafened() || mc.level != session.level || mc.player == null || !mc.player.isAlive()
                || mc.player.isSpectator() || !frame.dimension().equals(mc.level.dimension().location())
                || mc.getPlayerSocialManager().shouldHideMessageFrom(frame.speaker())) return;
        double gain = frame.gain() * (preferences.volume() / 100.0) * (preferences.gain(frame.speaker()) / 100.0);
        session.activity.heard(frame.speaker(), frame.pcm(), gain, System.nanoTime());
        session.audio.play(frame.speaker(), frame.pcm(), gain);
    }

    private void closeSession() {
        pendingRequestId = 0;
        debug.clearSession();
        MobCallNotice.clear();
        if (session != null) {
            session.close();
            session = null;
            waitForRelease = talk.isDown();
        }
        nearby.clear();
        visitors.clear();
        advertised = null;
    }

    private void disconnect() {
        closeSession();
        connection = null;
        testMic = false;
        horde = false;
    }

    private static final class Session {
        final ClientLevel level;
        final VoicePreferences prefs;
        final boolean deafened;
        final long deviceRevision;
        final SpeakingActivity activity = new SpeakingActivity();
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(12);
        final CaptureGate gate = new CaptureGate();
        final OutgoingVoice effects = new OutgoingVoice();
        final AtomicReference<Packets.MobCall> cue = new AtomicReference<>();
        final AtomicReference<VoiceAudio.Problem> problem = new AtomicReference<>();
        final SpeechCues speech = new SpeechCues(mob -> cue.set(new Packets.Call(mob)), id -> {
            ResourceLocation species = ResourceLocation.tryParse(id);
            if (species != null) cue.set(new Packets.SpeciesCall(species));
        }, id -> {
            ResourceLocation type = ResourceLocation.tryParse(id);
            if (type != null) cue.set(new Packets.EntityCall(type));
        }, FMLPaths.CONFIGDIR.get().resolve("mobsear-aliases.properties"), Map.of(), Map.of(), WindowsSpeechWorker::configure);
        CobblemonNames names;
        final EntityCallNames mobNames = new EntityCallNames();
        Map<String, List<String>> knownSpecies = Map.of(), knownEntities = Map.of();
        private long nextHints;
        private long lastRosterTick = Long.MIN_VALUE;
        final VoiceAudio audio;
        volatile boolean held;
        boolean automatic;
        boolean monitoring;
        long lastSpeaking;
        long lastMeter;
        long displayedAttempt;
        long loggedDecision;
        boolean recognition;
        boolean suspended;
        SpeechCues.Status previous = SpeechCues.Status.OFF;

        Session(ClientLevel level, VoicePreferences preferences) {
            this.level = level;
            prefs = preferences;
            deafened = preferences.deafened();
            deviceRevision = preferences.deviceRevision();
            audio = new VoiceAudio(this::capture, problem::set, preferences::microphone, preferences::speaker);
        }

        // Keep the local word continuous while requiring some voiced audio to enter proximity chat.
        private synchronized void capture(byte[] frame) {
            if ((!held && !monitoring) || prefs.muted() || prefs.deafened()) return;
            lastMeter = System.nanoTime();
            gate.capture(frame, automatic && !monitoring, prefs.sensitivity(), prefs.noiseSuppression(), pcm -> {
                if (monitoring || !held) return false;
                boolean enabled = prefs.pitchEnabled();
                int cents = enabled ? prefs.pitchCents() : 0;
                int reverb = enabled ? prefs.reverbAmount() : 0;
                boolean queued = effects.route(pcm, cents, reverb, frames::offer);
                if (queued && VoiceSignal.meter(VoiceSignal.rms(pcm)) > 0) lastSpeaking = lastMeter;
                return queued;
            }, recognition && !monitoring ? speech::acceptFrame : null, effects::reset);
        }

        synchronized boolean speaking() {
            return held && lastSpeaking != 0 && System.nanoTime() - lastSpeaking < 360_000_000L;
        }

        synchronized double meterLevel() {
            return (held || monitoring) && System.nanoTime() - lastMeter < 220_000_000L ? gate.level() : 0;
        }

        synchronized void monitor(boolean enable) {
            if (monitoring == enable) return;
            monitoring = enable;
            gate.reset();
            effects.reset();
            audio.setTransmitting(enable);
        }

        synchronized void setHeld(boolean value, boolean voiceActivation) {
            if (suspended) {
                monitor(false);
                suspended = false;
                speech.setInputPaused(false);
                if (recognition) speech.setEnabled(true);
            }
            if (held == value && automatic == voiceActivation) return;
            automatic = voiceActivation;
            held = value;
            gate.reset();
            effects.reset();
            lastSpeaking = 0;
            audio.setTransmitting(value);
            if (!value) speech.endUtterance();
        }

        synchronized void suspendInput() {
            if (suspended) return;
            held = false;
            suspended = true;
            gate.reset();
            effects.reset();
            lastSpeaking = 0;
            audio.setTransmitting(false);
            speech.setInputPaused(true);
            speech.setNearbySpecies(List.of());
            MobCallNotice.clearDiagnostic();
            cue.set(null);
            frames.clear();
        }

        void refreshCatalog(ClientPacketListener link) {
            var mc = Minecraft.getInstance();
            long now = System.nanoTime();
            if (now >= nextHints && mc.player != null && mc.level != null) {
                nextHints = now + 500_000_000L;
                long tick = mc.level.getGameTime();
                if (lastRosterTick == Long.MIN_VALUE || tick - lastRosterTick > 30)
                    speech.setNearbySpecies(List.of());
                if (link.hasChannel(Packets.NearbySpeciesRequest.TYPE))
                    PacketDistributor.sendToServer(new Packets.NearbySpeciesRequest());
                var labels = new java.util.LinkedHashSet<String>();
                var mobs = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                        mc.player.getBoundingBox().inflate(64), mob -> mob.distanceToSqr(mc.player) <= 64 * 64);
                mobs.sort(java.util.Comparator.comparingDouble(mob -> mob.distanceToSqr(mc.player)));
                for (var mob : mobs) {
                    if (names != null && CobblemonNames.nearbyName(mob) != null) continue;
                    String name = SpeechGrammar.spokenName(mob.getType().getDescription().getString(), true);
                    if (!name.isEmpty()) labels.add(name);
                    if (labels.size() >= 20) break;
                }
                speech.setNearbyNames(List.copyOf(labels));
            }
            Map<String, List<String>> species = Map.of();
            if (link.hasChannel(Packets.SpeciesCall.TYPE) && ModList.get().isLoaded("cobblemon")) {
                if (names == null) names = new CobblemonNames();
                species = names.snapshot();
            }
            Map<String, List<String>> entities = link.hasChannel(Packets.EntityCall.TYPE) ? mobNames.snapshot() : Map.of();
            if (species != knownSpecies || entities != knownEntities) {
                speech.refreshCatalog(species, entities);
                knownSpecies = species;
                knownEntities = entities;
            }
        }

        synchronized void enableRecognition(boolean enable) {
            recognition = enable;
            gate.reset();
            speech.setEnabled(enable && !suspended);
            if (!enable) {
                speech.setNearbySpecies(List.of());
                cue.set(null);
                MobCallNotice.clearMatch();
            }
        }

        void send(ClientPacketListener link) {
            byte[] frame;
            for (int i = 0; i < 12 && (frame = frames.poll()) != null; i++) {
                PacketDistributor.sendToServer(new Packets.Up(frame));
            }
            var test = speech.takeTestFeedback();
            if (test != null && prefs.recognitionDiagnostics() && recognition) {
                if (displayedAttempt != test.attempt()) {
                    displayedAttempt = test.attempt();
                    // An acknowledgement for an earlier utterance must not label this test attempt.
                    instance.pendingRequestId = 0;
                }
                Component detail = switch (test.stage()) {
                    case LISTENING -> Component.translatable("mobsear.speech.test_listening");
                    case CHECKING -> Component.translatable("mobsear.speech.test_checking");
                    case NO_MATCH -> test.guess().isEmpty() ? Component.translatable("mobsear.speech.no_match")
                            : Component.translatable("mobsear.speech.test_guess", Component.literal(test.guess()));
                    case LATE -> Component.translatable("mobsear.speech.late_match");
                    case DECODER_ERROR -> Component.translatable("mobsear.speech.word_decoder_error");
                    case TOO_LONG -> Component.translatable("mobsear.speech.test_long");
                    case NO_VOICE -> Component.translatable("mobsear.speech.test_unsent");
                };
                MobCallNotice.diagnose(detail, test.stage() == SpeechCues.TestStage.LISTENING
                        || test.stage() == SpeechCues.TestStage.CHECKING);
            }
            Packets.MobCall ready = cue.getAndSet(null);
            if (ready == null || !recognition) return;
            instance.pendingRequestId = 0;
            MobCallNotice.clearMatch();
            var mc = Minecraft.getInstance();
            Component name;
            var target = CallDebugLog.target(ready);
            instance.debug.write(CallDebugLog.Event.DETECTED, target, 0);
            if (ready instanceof Packets.SpeciesCall call) {
                if (names == null || !link.hasChannel(Packets.SpeciesCall.TYPE)) return;
                name = CobblemonNames.display(call.species());
                if (name == null) return;
            } else if (ready instanceof Packets.EntityCall call) {
                if (!link.hasChannel(Packets.EntityCall.TYPE)) return;
                name = EntityCallNames.display(call.entityType());
                if (name == null) return;
            } else {
                Cue mob = ((Packets.Call)ready).cue();
                name = mob == Cue.MOBS ? Component.translatable("mobsear.speech.hostiles")
                        : Component.translatable("entity.minecraft." + mob.entityPath);
                if (!link.hasChannel(Packets.Call.TYPE)) return;
                if (mob != Cue.ZOMBIE && mob != Cue.MOBS && mob != Cue.ENDERMAN && !link.hasChannel(MicPresence.Controls.TYPE)) return;
            }
            // A successful local decision displays its canonical name even when no mob answers.
            if (mc.player != null && mc.player.isSpectator()) {
                instance.debug.write(CallDebugLog.Event.GAME_MODE, target, 0);
                if (prefs.recognitionDiagnostics())
                    MobCallNotice.diagnose(Component.translatable("mobsear.speech.recognized_mode", name));
                return;
            }
            if (prefs.recognitionDiagnostics())
                MobCallNotice.diagnose(Component.translatable("mobsear.speech.recognized", name));
            sendCall(link, ready);
        }

        void reportStatus(Minecraft mc) {
            var failure = problem.getAndSet(null);
            var state = speech.status();
            var debug = instance.debug;
            if (!suspended) {
                var status = speech.startupTimedOut() ? CallDebugLog.Event.STARTUP_TIMEOUT
                        : speech.decoderTimedOut() ? CallDebugLog.Event.DECODE_TIMEOUT : CallDebugLog.Event.valueOf(state.name());
                debug.state(status);
            }
            var decision = speech.lastDecision();
            if (decision != null && decision.number() != loggedDecision) {
                loggedDecision = decision.number();
                if (decision.issue() == SpeechCues.DecoderIssue.ERROR) debug.write(CallDebugLog.Event.WORD_DECODER_ERROR, null, 0);
                else if (decision.issue() == SpeechCues.DecoderIssue.TIMEOUT) debug.write(CallDebugLog.Event.DECODE_TIMEOUT, null, 0);
                else if (!decision.timely()) debug.write(CallDebugLog.Event.LATE, null, 0);
                else if (!decision.matched()) debug.write(CallDebugLog.Event.NO_MATCH, null, 0);
            }
            if (failure != null) {
                debug.write(CallDebugLog.Event.valueOf(failure.name()), null, 0);
                String key = failure == VoiceAudio.Problem.MICROPHONE_UNAVAILABLE ? "mic" : "output";
                MobCallNotice.show(Component.translatable("mobsear.problem." + key));
            } else if (state != previous && !suspended) {
                previous = state;
                String key = state.name().toLowerCase(java.util.Locale.ROOT);
                if (speech.startupTimedOut()) key = "startup_timeout";
                if (speech.decoderTimedOut()) key = "decode_timeout";
                Component status = Component.translatable("mobsear.speech." + key);
                if (state == SpeechCues.Status.READY) MobCallNotice.ready(status);
                else MobCallNotice.show(status);
            }
        }

        void close() {
            suspendInput();
            audio.close();
            speech.close();
        }
    }
}
