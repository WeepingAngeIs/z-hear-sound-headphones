package dev.mobsear.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

public final class MobCallTypes {
    public static final ResourceLocation OWL_GUARDIAN = ResourceLocation.fromNamespaceAndPath("uwudimension", "blossom_warden");
    public static final ResourceLocation VELVET_VESPER = ResourceLocation.fromNamespaceAndPath("uwudimension", "velvet_vesper");
    private static final ResourceLocation POKEMON = ResourceLocation.fromNamespaceAndPath("cobblemon", "pokemon");

    private MobCallTypes() {}

    public static boolean eligibleType(ResourceLocation id, EntityType<?> type) {
        return id != null && id.toString().length() <= Packets.MAX_CALL_ID_LENGTH && !POKEMON.equals(id)
                && type != EntityType.PLAYER && type != EntityType.ARMOR_STAND && DefaultAttributes.hasSupplier(type);
    }
}
