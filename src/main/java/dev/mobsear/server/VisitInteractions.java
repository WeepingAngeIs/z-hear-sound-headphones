package dev.mobsear.server;

import com.cobblemon.mod.common.api.item.PokemonSelectingItem;
import com.cobblemon.mod.common.api.tags.CobblemonItemTags;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.berry.HealingBerryItem;
import com.cobblemon.mod.common.item.berry.PortionHealingBerryItem;
import dev.mobsear.MobsEar;
import dev.mobsear.net.Packets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Interactions are authorized against the existing visit on the server, including on the pickup tick. */
final class VisitInteractions {
    private record Bite(ItemStack food, int start) {}
    // Vanilla replaces the surviving ItemStack when ground entities merge. Never claim a mixed pile.
    private record Offer(UUID caller, ItemStack tossedStack, Set<UUID> visitors) {}
    private static final double GROUND_REACH = 2.0;
    private static final Map<PokemonEntity,Bite> bites = new WeakHashMap<>();
    private static final Map<PokemonEntity,Integer> lastClick = new WeakHashMap<>();
    private static final Map<ItemEntity,Offer> offers = new WeakHashMap<>();

    private VisitInteractions() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (PlayerInteractEvent.EntityInteractSpecific e) -> {
            if (interact(e, e.getTarget())) { e.setCancellationResult(InteractionResult.SUCCESS); e.setCanceled(true); }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (PlayerInteractEvent.EntityInteract e) -> {
            if (interact(e, e.getTarget())) { e.setCancellationResult(InteractionResult.SUCCESS); e.setCanceled(true); }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, VisitInteractions::toss);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> bites.entrySet().removeIf(entry -> {
            var mob = entry.getKey();
            var bite = entry.getValue();
            int age = mob.tickCount - bite.start;
            if (!mob.isAlive() || mob.isRemoved() || mob.isBusy() || mob.isBattling() || age >= 30) return true;
            if (age > 0 && age % 6 == 0) chew(mob, bite.food);
            return false;
        }));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> { bites.clear(); offers.clear(); lastClick.clear(); });
    }

    static void status(PokemonEntity mob, ServerPlayer caller, boolean arrived) {
        if (caller.connection != null && caller.connection.hasChannel(Packets.Visit.TYPE))
            PacketDistributor.sendToPlayer(caller, new Packets.Visit(mob.getUUID(), arrived));
        if (!arrived) {
            lastClick.remove(mob);
            offers.values().removeIf(offer -> {
                offer.visitors.remove(mob.getUUID());
                return offer.visitors.isEmpty();
            });
        }
    }

    private static boolean healing(ItemStack stack) {
        return stack.getItem() instanceof HealingBerryItem || stack.getItem() instanceof PortionHealingBerryItem;
    }

    private static boolean interact(PlayerInteractEvent event, Entity target) {
        if (!(target instanceof PokemonEntity mob)) return false;
        var player = event.getEntity();
        var stack = event.getItemStack();
        if (stack.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND && healing(player.getOffhandItem()))
            stack = player.getOffhandItem();
        boolean pat = stack.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND;
        if (!pat && !healing(stack)) return false;
        if (player.level().isClientSide()) return MobsEar.localVisitor.test(mob.getUUID());
        if (!(player instanceof ServerPlayer caller) || !CobblemonCalls.visiting(mob, caller)) return false;
        int now = mob.tickCount;
        if (now - lastClick.getOrDefault(mob, -30) < 6) return true;
        lastClick.put(mob, now);
        if (pat) {
            mob.cry();
            caller.serverLevel().sendParticles(ParticleTypes.HEART, mob.getX(), mob.getEyeY()+0.22, mob.getZ(), 4, .20,.10,.20,.025);
        } else if (!feed(mob, caller, stack).isEmpty()) stack.shrink(1);
        return true;
    }

    private static void toss(ItemTossEvent event) {
        var drop = event.getEntity();
        if (!(event.getPlayer() instanceof ServerPlayer caller) || !healing(drop.getItem())) return;
        var visitors = new HashSet<UUID>();
        for (var mob : caller.serverLevel().getEntitiesOfClass(PokemonEntity.class, caller.getBoundingBox().inflate(5),
                mob -> CobblemonCalls.visiting(mob, caller))) visitors.add(mob.getUUID());
        if (!visitors.isEmpty()) offers.put(drop, new Offer(caller.getUUID(), drop.getItem(), visitors));
    }

    static void collect(PokemonEntity mob, ServerPlayer caller) {
        if (offers.isEmpty() || !CobblemonCalls.visiting(mob, caller)) return;
        for (var it = offers.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            var drop = entry.getKey();
            var offer = entry.getValue();
            if (!drop.isAlive() || !drop.isAddedToLevel() || drop.getItem() != offer.tossedStack) { it.remove(); continue; }
            if (!offer.caller.equals(caller.getUUID()) || !offer.visitors.contains(mob.getUUID())
                    || drop.level() != mob.level() || !withinReach(mob, drop) || !mob.hasLineOfSight(drop)) continue;
            var stack = drop.getItem();
            if (feed(mob, caller, stack).isEmpty()) continue;
            mob.getLookControl().setLookAt(drop);
            mob.take(drop, 1);
            var rest = stack.copyWithCount(stack.getCount()-1);
            if (rest.isEmpty()) drop.discard(); else drop.setItem(rest);
            it.remove();
            return;
        }
    }

    private static boolean withinReach(PokemonEntity mob, ItemEntity drop) {
        var body = mob.getBoundingBox();
        if (body.inflate(0.9).intersects(drop.getBoundingBox())) return true;
        return drop.onGround() && Math.abs(mob.getY() - drop.getY()) <= 0.75
                && body.distanceToSqr(drop.position()) <= GROUND_REACH * GROUND_REACH;
    }

    private static ItemStack feed(PokemonEntity mob, ServerPlayer caller, ItemStack stack) {
        if (stack.isEmpty() || !healing(stack)) return ItemStack.EMPTY;
        var pokemon = mob.getPokemon();
        var food = stack.copyWithCount(1);
        if (pokemon.isFullHealth()) {
            // Cobblemon's healing action rejects full HP. Use its ordinary feeding routine for a treat.
            if (food.is(CobblemonItemTags.POKE_FOOD) && pokemon.isFull()) return ItemStack.EMPTY;
            int fullness = food.getItem() instanceof PortionHealingBerryItem ? 5 : 1;
            pokemon.feedPokemon(fullness, true);
        } else {
            var berry = (PokemonSelectingItem) food.getItem();
            int before = pokemon.getCurrentHealth();
            // The native action checks eligibility and consumes its argument. Commit the real stack once.
            var result = berry.applyToPokemon(caller, food.copy(), pokemon);
            if (result == null || !result.getResult().consumesAction() || pokemon.getCurrentHealth() <= before) return ItemStack.EMPTY;
        }
        bites.put(mob, new Bite(food, mob.tickCount));
        chew(mob, food);
        mob.playSound(MobsEar.NOM.get(), .56F, 1F);
        return food;
    }

    private static void chew(PokemonEntity mob, ItemStack food) {
        var level = (ServerLevel)mob.level();
        var mouth = mob.getEyePosition().add(mob.getLookAngle().scale(.25));
        level.broadcastEntityEvent(mob, (byte)45);
        mob.playSound(mob.getEatingSound(food), .7F, 1F);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food), mouth.x, mouth.y, mouth.z, 4, .08,.06,.08,.015);
    }
}
