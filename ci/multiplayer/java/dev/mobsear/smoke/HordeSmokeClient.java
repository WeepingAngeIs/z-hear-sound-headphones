package dev.mobsear.smoke;

import dev.mobsear.MobsEar;
import dev.mobsear.client.FixtureSound;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.MicState;
import dev.mobsear.net.Packets;
import dev.mobsear.net.VoiceSignal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

final class HordeSmokeClient {
    private static HordeSmokeClient instance;
    private final boolean participant;
    private final UUID peer;
    private int phase, loudFrames, replies, previousReplies;
    private boolean acknowledged;
    private long deadline;

    private HordeSmokeClient(boolean isParticipant) {
        participant = isParticipant;
        String other = participant ? SmokeControl.LISTENER : SmokeControl.SPEAKER;
        peer = UUID.nameUUIDFromBytes(("OfflinePlayer:" + other).getBytes(StandardCharsets.UTF_8));
        var link = Minecraft.getInstance().getConnection();
        if (link == null || !link.hasChannel(Packets.HordeOpt.TYPE) || !link.hasChannel(Packets.HordeState.TYPE)
                || !MobsEar.hordeAvailable() || VoiceClient.hordeEnabled()) {
            throw SmokeControl.failure("client missing synchronized server offer, consent defaults or negotiated channels");
        }
        var status = MobsEar.hordeStatus;
        MobsEar.hordeStatus = accepted -> {
            status.accept(accepted);
            if (VoiceClient.hordeEnabled() != accepted) throw SmokeControl.failure("client lost production horde reply");
            replies++;
        };
        var playback = MobsEar.playback;
        MobsEar.playback = frame -> {
            playback.accept(frame);
            if (frame.speaker().equals(peer) && VoiceSignal.rms(frame.pcm()) >= 0.03) loudFrames++;
        };
        var settings = VoiceClient.options();
        settings.setMuted(false);
        settings.setDeafened(false);
        settings.setGain(peer, 100);
        VoiceClient.setTestingMic(false);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    static void begin(int stage, boolean participant) {
        if (instance == null) instance = new HordeSmokeClient(participant);
        instance.phase = stage;
        instance.loudFrames = 0;
        instance.acknowledged = false;
        instance.previousReplies = instance.replies;
        instance.deadline = System.nanoTime() + 25_000_000_000L;
        boolean speak = stage == HordeSmoke.BYSTANDER && !participant || stage == HordeSmoke.PARTICIPANT && participant;
        FixtureSound.tone = speak;
        VoiceClient.setVoiceActivated(speak);
        if (participant) {
            if (stage == HordeSmoke.DENIED || stage == HordeSmoke.JOIN) VoiceClient.setHordeEnabled(true);
            if (stage == HordeSmoke.LEAVE) VoiceClient.setHordeEnabled(false);
        }
    }

    private void tick(ClientTickEvent.Post event) {
        if (acknowledged) return;
        if (System.nanoTime() > deadline) throw SmokeControl.failure("horde client timeout at " + phase
                + " participant=" + participant + " incoming=" + loudFrames + " replies=" + (replies - previousReplies));
        if (!participant && VoiceClient.hordeEnabled()) throw SmokeControl.failure("listener opted in automatically");
        boolean done = switch (phase) {
            case HordeSmoke.DENIED, HordeSmoke.LEAVE -> !participant || replies > previousReplies && !VoiceClient.hordeEnabled();
            case HordeSmoke.JOIN -> !participant || replies > previousReplies && VoiceClient.hordeEnabled();
            case HordeSmoke.BYSTANDER -> participant ? loudFrames >= 70 : VoiceClient.localState() == MicState.SPEAKING;
            case HordeSmoke.PARTICIPANT -> participant ? VoiceClient.localState() == MicState.SPEAKING : loudFrames >= 70;
            default -> false;
        };
        if (done) {
            acknowledged = true;
            PacketDistributor.sendToServer(new SmokeControl.Barrier(phase));
        }
    }
}
