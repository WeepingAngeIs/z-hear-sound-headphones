package dev.mobsear.packprobe;

import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.mobsear.MobsEar;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/** Feed the actual walker using the normal client click while all pack interaction handlers are installed. */
final class PackVisitFeeding {
    private int start;
    private ItemStack previouslyHeld;
    private volatile boolean ready, clicked, passed;

    void server(PokemonEntity mob,ServerPlayer caller,boolean reacting) {
        if (passed) return;
        var pokemon=mob.getPokemon();
        if (!ready && reacting && mob.distanceTo(caller)<=2.5 && mob.getNavigation().isDone()) {
            previouslyHeld=caller.getMainHandItem().copy();
            start=pokemon.getMaxHealth()-11;
            pokemon.setCurrentHealth(start);
            caller.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CobblemonItems.ORAN_BERRY,2));
            caller.inventoryMenu.broadcastChanges();
            ready=true;
        } else if (clicked && caller.getMainHandItem().getCount()==1 && pokemon.getCurrentHealth()==start+10) {
            caller.setItemInHand(InteractionHand.MAIN_HAND,previouslyHeld);
            caller.inventoryMenu.broadcastChanges();
            passed=true;
            System.out.println("ACOUSTIC_PACK_BERRY_PASS species=Lopunny hp="+start+"->"+pokemon.getCurrentHealth()+" stack=2->1");
        }
    }

    void client(Minecraft mc,PokemonEntity mob) {
        if (!ready || clicked || !mc.player.getMainHandItem().is(CobblemonItems.ORAN_BERRY) ||
                !MobsEar.localVisitor.test(mob.getUUID())) return;
        var hit=new EntityHitResult(mob,mob.getEyePosition());
        if (!mc.gameMode.interactAt(mc.player,mob,hit,InteractionHand.MAIN_HAND).consumesAction())
            throw new IllegalStateException("Pack did not handle the visitor's berry click");
        clicked=true;
    }

    boolean passed() { return passed; }
}
