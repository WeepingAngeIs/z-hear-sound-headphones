package dev.hearcheck;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The observer never imports Mic, registers a channel, or supplies a missing registry entry. */
final class JoinCase {
    static final BlockPos LEVER = new BlockPos(9,101,8);
    static final BlockPos MARKER = LEVER.east();
    static final List<CustomPacketPayload.Type<CustomPacketPayload>> CHANNELS =
            List.of("voice_up","voice_down","mic_controls","nearby_mic","mob_cue","call_request","heard_reply",
                    "server_rules","horde_opt","species_call").stream().map(id ->
                    new CustomPacketPayload.Type<CustomPacketPayload>(ResourceLocation.fromNamespaceAndPath("mobsear",id))).toList();
}
