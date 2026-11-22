package dev.mobsear.packprobe;

import dev.mobsear.MobsEar;
import dev.mobsear.client.VoiceClient;
import dev.mobsear.net.Packets;
import net.minecraft.client.Minecraft;

/** The earlier JAR predates request IDs and server-confirmed hearing notices. */
final class CurrentNotices {
    static void install(PackProbe observer) {
        var link=Minecraft.getInstance().getConnection();
        if (!link.hasChannel(Packets.Heard.TYPE) || !link.hasChannel(Packets.CallRequest.TYPE))
            throw new IllegalStateException("Production request and reply channels missing");
        if (VoiceClient.options().recognitionDiagnostics()) throw new IllegalStateException("A new tester was already opted in");
        VoiceClient.options().setRecognitionDiagnostics(true);
        var original=MobsEar.callHeard;
        MobsEar.callHeard=reply -> {
            original.accept(reply);
            observer.heard(reply.requestId(),reply.id().toString(),reply.species());
        };
    }
}
