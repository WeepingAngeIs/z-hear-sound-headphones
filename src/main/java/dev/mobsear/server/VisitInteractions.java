package dev.mobsear.server;

import com.cobblemon.mod.common.api.item.PokemonSelectingItem;
import com.cobblemon.mod.common.api.tags.CobblemonItemTags;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.berry.HealingBerryItem;
import com.cobblemon.mod.common.item.berry.PortionHealingBerryItem;
import dev.mobsear.MobsEar;
import dev.mobsear.net.Packets;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Interactions are authorized against the existing visit on the server, including on the pickup tick. */
final class VisitInteractions {
    private record Bite(ItemStack food, ServerPlayer caller, Display.ItemDisplay display, ServerLevel level, long start) {}
    private static final int CHEW_TICKS = 40;
    private static final String PROP_TAG = "mobsear_berry_chew_prop";
    // Vanilla replaces the surviving ItemStack when ground entities merge. Never claim a mixed pile.
    private record Offer(UUID caller, ItemStack tossedStack, Set<UUID> visitors) {}
    private static final double GROUND_REACH = 2.0;
    private static final Map<PokemonEntity,Bite> bites = new IdentityHashMap<>();
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
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (e.loadedFromDisk() && e.getEntity() instanceof Display.ItemDisplay && e.getEntity().getTags().contains(PROP_TAG))
                e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(VisitInteractions::tickBites);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            bites.forEach((mob, bite) -> { bite.display.discard(); refund(mob, bite); });
            bites.clear();
            offers.clear();
            lastClick.clear();
        });
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
        if (bites.containsKey(mob)) return true;
        if (pat) {
            mob.cry();
            caller.serverLevel().sendParticles(ParticleTypes.HEART, mob.getX(), mob.getEyeY()+0.22, mob.getZ(), 4, .20,.10,.20,.025);
        } else if (beginBite(mob, caller, stack)) stack.shrink(1);
        return true;
    }

    private static void toss(ItemTossEvent event) {
        var drop = event.getEntity();
        if (!(event.getPlayer() instanceof ServerPlayer caller) || !healing(drop.getItem())) return;
        var visitors = new HashSet<UUID>();
        for (var mob : caller.serverLevel().getEntitiesOfClass(PokemonEntity.class, caller.getBoundingBox().inflate(5),
                mob -> !bites.containsKey(mob) && CobblemonCalls.visiting(mob, caller))) visitors.add(mob.getUUID());
        if (!visitors.isEmpty()) offers.put(drop, new Offer(caller.getUUID(), drop.getItem(), visitors));
    }

    static void collect(PokemonEntity mob, ServerPlayer caller) {
        if (offers.isEmpty() || bites.containsKey(mob) || !CobblemonCalls.visiting(mob, caller)) return;
        for (var it = offers.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            var drop = entry.getKey();
            var offer = entry.getValue();
            if (!drop.isAlive() || !drop.isAddedToLevel() || drop.getItem() != offer.tossedStack) { it.remove(); continue; }
            if (!offer.caller.equals(caller.getUUID()) || !offer.visitors.contains(mob.getUUID())
                    || drop.level() != mob.level() || !withinReach(mob, drop) || !mob.hasLineOfSight(drop)) continue;
            var stack = drop.getItem();
            if (!beginBite(mob, caller, stack)) continue;
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

    private static boolean beginBite(PokemonEntity mob, ServerPlayer caller, ItemStack stack) {
        if (stack.isEmpty() || !healing(stack) || bites.containsKey(mob)) return false;
        var food = stack.copyWithCount(1);
        var pokemon = mob.getPokemon();
        if (pokemon.isFullHealth()) {
            if (food.is(CobblemonItemTags.POKE_FOOD) && pokemon.isFull()) return false;
        } else if (!((PokemonSelectingItem)food.getItem()).canUseOnPokemon(food.copy(), pokemon)) return false;

        var level = caller.serverLevel();
        var display = EntityType.ITEM_DISPLAY.create(level);
        if (display == null || !display.getSlot(0).set(food.copy())) return false;
        display.addTag(PROP_TAG);
        var mouth = mouth(mob);
        display.setPos(mouth.x, mouth.y, mouth.z);
        if (!level.addFreshEntity(display)) return false;

        bites.put(mob, new Bite(food, caller, display, level, level.getGameTime()));
        chew(mob, food);
        return true;
    }

    private static void tickBites(ServerTickEvent.Post event) {
        var it = bites.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var mob = entry.getKey();
            var bite = entry.getValue();
            if (!CobblemonCalls.available(mob)
                    || mob.level() != bite.level || bite.display.isRemoved()) {
                it.remove();
                bite.display.discard();
                refund(mob, bite);
                continue;
            }
            long age = bite.level.getGameTime() - bite.start;
            if (age >= CHEW_TICKS) {
                it.remove();
                bite.display.discard();
                if (finishFeed(mob, bite.caller, bite.food)) mob.cry();
                else refund(mob, bite);
                continue;
            }
            if (age <= 0) continue;
            var eyes = mob.getEyePosition();
            var target = bite.caller.level() == mob.level() ? bite.caller.getEyePosition() : eyes.add(mob.getLookAngle());
            var delta = target.subtract(eyes);
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
            mob.setXRot(net.minecraft.util.Mth.clamp(pitch + (float) Math.sin(age * Math.PI / 4) * 12F, -75F, 75F));
            var mouth = mouth(mob);
            bite.display.setPos(mouth.x, mouth.y, mouth.z);
            if (age % 8 == 0) chew(mob, bite.food);
        }
    }

    private static boolean finishFeed(PokemonEntity mob, ServerPlayer caller, ItemStack food) {
        var pokemon = mob.getPokemon();
        if (pokemon.isFullHealth()) {
            if (food.is(CobblemonItemTags.POKE_FOOD) && pokemon.isFull()) return false;
            pokemon.feedPokemon(food.getItem() instanceof PortionHealingBerryItem ? 5 : 1, true);
            return true;
        }
        var berry = (PokemonSelectingItem)food.getItem();
        int before = pokemon.getCurrentHealth();
        var result = berry.applyToPokemon(caller, food.copy(), pokemon);
        return result != null && result.getResult().consumesAction() && pokemon.getCurrentHealth() > before;
    }

    private static void refund(PokemonEntity mob, Bite bite) {
        var food = bite.food.copy();
        if (bite.caller.isAlive() && !bite.caller.isRemoved()) bite.caller.addItem(food);
        if (food.isEmpty()) return;
        var at = mob != null && mob.level() == bite.level ? mob.position() : bite.display.position();
        var drop = new ItemEntity(bite.level, at.x, at.y, at.z, food);
        drop.setDefaultPickUpDelay();
        bite.level.addFreshEntity(drop);
    }

    private static net.minecraft.world.phys.Vec3 mouth(PokemonEntity mob) {
        double forward = Math.max(.25, mob.getBbWidth() * .55);
        return mob.getEyePosition().add(mob.getLookAngle().scale(forward)).add(0, -.12, 0);
    }

    private static void chew(PokemonEntity mob, ItemStack food) {
        var level = (ServerLevel)mob.level();
        var mouth = mouth(mob);
        mob.playSound(SoundEvents.GENERIC_EAT, .7F, 1F);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food), mouth.x, mouth.y, mouth.z, 4, .08,.06,.08,.015);
    }
}
