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
                    "server_rules","server_rule_request","horde_opt","horde_state","mob_pose","call_declined","entity_call",
                    "species_call","nearby_species","nearby_species_request","pokemon_visit").stream().map(id ->
                    new CustomPacketPayload.Type<CustomPacketPayload>(ResourceLocation.fromNamespaceAndPath("mobsear",id))).toList();
}
