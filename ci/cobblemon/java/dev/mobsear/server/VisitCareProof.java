package dev.mobsear.server;

import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** The positive inputs are real client-to-server clicks and a vanilla drop. Exclusions use the same server hooks. */
final class VisitCareProof {
    final PokemonEntity mob;
    final ServerPlayer caller, viewer;
    final PokemonCase scenario;
    private final ItemStack originalHand;
    final int max;
    int step, arrived, initial, next, reserved=-1;
    boolean finished, duplicateChecked;
    final java.util.List<ItemEntity> duplicateDrops=new java.util.ArrayList<>();
    ItemEntity rejected;

    VisitCareProof(PokemonEntity mob, ServerPlayer caller, ServerPlayer viewer, PokemonCase scenario) {
        this.mob=mob; this.caller=caller; this.viewer=viewer; this.scenario=scenario;
        originalHand=caller.getMainHandItem().copy();
        max=mob.getPokemon().getMaxHealth();
        excluded(mob, caller, "before arrival");
    }

    static void excluded(PokemonEntity mob, ServerPlayer player, String why) {
        var original=player.getMainHandItem().copy();
        var stack=new ItemStack(CobblemonItems.ORAN_BERRY,3);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        int hp=mob.getPokemon().getCurrentHealth();
        var e=new PlayerInteractEvent.EntityInteractSpecific(player,InteractionHand.MAIN_HAND,mob,Vec3.ZERO);
        NeoForge.EVENT_BUS.post(e);
        if (e.isCanceled() || stack.getCount()!=3 || mob.getPokemon().getCurrentHealth()!=hp)
            throw PokemonProbe.fail("Ineligible visit fed a Pokémon: "+why);
        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        e=new PlayerInteractEvent.EntityInteractSpecific(player,InteractionHand.MAIN_HAND,mob,Vec3.ZERO);
        NeoForge.EVENT_BUS.post(e);
        if (e.isCanceled()) throw PokemonProbe.fail("Ineligible visit accepted a pat: "+why);
        player.setItemInHand(InteractionHand.MAIN_HAND,original);
        System.out.println("MOBSEAR_POKEMON_CARE_EXCLUDED "+why+" hp="+hp+" berries=3 pat=false");
    }

    private void clientAction(int stage, ItemStack stack) {
        caller.setItemInHand(InteractionHand.MAIN_HAND,stack);
        caller.inventoryMenu.broadcastChanges();
        PacketDistributor.sendToPlayer(caller,new PokemonProbe.Message(stage,scenario.ordinal(),mob.getId()));
    }

    static void measurePortion(PokemonEntity mob, ServerPlayer caller) {
        var pokemon=mob.getPokemon();
        int max=pokemon.getMaxHealth();
        int start=Math.max(1,max/3);
        pokemon.setCurrentHealth(start);
        var formula=com.cobblemon.mod.common.CobblemonMechanics.INSTANCE.getBerries().getSitrusHealAmount();
        int expected=com.cobblemon.mod.common.util.MoLangExtensionsKt.resolveInt(
                com.cobblemon.mod.common.util.MoLangExtensionsKt.getGenericRuntime(),formula,pokemon,java.util.Map.of());
        if (expected<=0 || start+expected>=max) throw PokemonProbe.fail("Expected a positive uncapped native Sitrus amount");
        var original=caller.getMainHandItem().copy();
        var food=new ItemStack(CobblemonItems.SITRUS_BERRY,2);
        caller.setItemInHand(InteractionHand.MAIN_HAND,food);
        var click=new PlayerInteractEvent.EntityInteractSpecific(caller,InteractionHand.MAIN_HAND,mob,Vec3.ZERO);
        NeoForge.EVENT_BUS.post(click);
        if (!click.isCanceled() || pokemon.getCurrentHealth()!=start+expected || food.getCount()!=1)
            throw PokemonProbe.fail("Native Sitrus expected="+expected+" hp="+start+"->"+pokemon.getCurrentHealth()+" food="+food.getCount());
        caller.setItemInHand(InteractionHand.MAIN_HAND,original);
        System.out.println("MOBSEAR_POKEMON_NATIVE_SITRUS hp="+start+"->"+pokemon.getCurrentHealth()+" max="+max+" configured="+expected+" consumed=1");
    }

    void tick() {
        if (finished) return;
        var pokemon=mob.getPokemon();
        int now=mob.tickCount;
        for (var drop:duplicateDrops)
            if (!drop.isAlive() || drop.getItem().getCount()!=2)
                throw PokemonProbe.fail("A toss offered during chewing was consumed later");
        boolean handReserved=step==1 && caller.getMainHandItem().getCount()==2;
        boolean floorReserved=step==4 && !caller.serverLevel().getEntitiesOfClass(ItemEntity.class,mob.getBoundingBox().inflate(5),
                item->item.getItem().is(CobblemonItems.SITRUS_BERRY) && item.getItem().getCount()==2).isEmpty();
        if ((handReserved || floorReserved) && reserved<0) {
            if (pokemon.getCurrentHealth()!=initial) throw PokemonProbe.fail("Berry healed before chewing");
            reserved=now;
        }
        if (reserved>=0 && !duplicateChecked && now-reserved>=10) {
            if (pokemon.getCurrentHealth()!=initial) throw PokemonProbe.fail("Berry healed before the duplicate control");
            var original=caller.getMainHandItem().copy();
            var extra=new ItemStack(CobblemonItems.ORAN_BERRY,2);
            caller.setItemInHand(InteractionHand.MAIN_HAND,extra);
            var e=new PlayerInteractEvent.EntityInteractSpecific(caller,InteractionHand.MAIN_HAND,mob,Vec3.ZERO);
            NeoForge.EVENT_BUS.post(e);
            if (!e.isCanceled() || extra.getCount()!=2 || pokemon.getCurrentHealth()!=initial)
                throw PokemonProbe.fail("An active chew accepted a duplicate offer after the click debounce");
            caller.setItemInHand(InteractionHand.MAIN_HAND,original);
            var towardCaller=caller.position().subtract(mob.position()).multiply(1,0,1).normalize();
            var side=new Vec3(-towardCaller.z,0,towardCaller.x).scale(step==1?-1.1:1.1);
            var drop=new ItemEntity(caller.serverLevel(),mob.getX()+side.x,mob.getY()+.1,mob.getZ()+side.z,
                    new ItemStack(CobblemonItems.ORAN_BERRY,2));
            drop.setPickUpDelay(240);
            drop.setDeltaMovement(Vec3.ZERO);
            NeoForge.EVENT_BUS.post(new ItemTossEvent(drop,caller));
            caller.serverLevel().addFreshEntity(drop);
            duplicateDrops.add(drop);
            duplicateChecked=true;
            System.out.println("MOBSEAR_POKEMON_DUPLICATE_REJECTED chew="+(step==1?"right-click":"toss")+
                    " afterTicks="+(now-reserved)+" clickUnspent=true tossObserved=true");
        }
        if (step==0) {
            if (!CobblemonCalls.visiting(mob,caller)) return;
            arrived=now;
            if (caller!=viewer) {
                viewer.setGameMode(GameType.ADVENTURE);
                viewer.teleportTo(caller.getX(),caller.getY(),caller.getZ()+.8);
                excluded(mob,viewer,"second player beside visitor");
                rejected=rejectedToss(viewer);
                viewer.setGameMode(GameType.SPECTATOR);
            }
            initial=max-12;
            pokemon.setCurrentHealth(initial);
            clientAction(PokemonProbe.CARE_CLICK,new ItemStack(CobblemonItems.ORAN_BERRY,3));
            step=1;
        } else if (step==1 && caller.getMainHandItem().getCount()==2 && pokemon.getCurrentHealth()!=initial) {
            verifyChew(now);
            if (pokemon.getCurrentHealth()!=initial+10) throw PokemonProbe.fail("Oran HP delta "+(pokemon.getCurrentHealth()-initial));
            System.out.println("MOBSEAR_POKEMON_FEED_HAND hp="+initial+"->"+pokemon.getCurrentHealth()+" berries=3->2 chewTicks="+(now-reserved));
            reserved=-1;
            next=now+7; step=2;
        } else if (step==2 && now>=next) {
            clientAction(PokemonProbe.CARE_CLICK,ItemStack.EMPTY);
            next=now+7; step=3;
        } else if (step==3 && now>=next) {
            initial=max-2;
            pokemon.setCurrentHealth(initial);
            duplicateChecked=false;
            clientAction(PokemonProbe.CARE_DROP,new ItemStack(CobblemonItems.SITRUS_BERRY,3));
            step=4;
        } else if (step==4 && pokemon.getCurrentHealth()==max) {
            verifyChew(now);
            var drops=caller.serverLevel().getEntitiesOfClass(ItemEntity.class,mob.getBoundingBox().inflate(5),
                    item->item.getItem().is(CobblemonItems.SITRUS_BERRY) && item.getItem().getCount()==2);
            if (drops.size()!=1) throw PokemonProbe.fail("Toss did not leave precisely two original berries");
            if (rejected!=null && (!rejected.isAlive() || rejected.getItem().getCount()!=2)) throw PokemonProbe.fail("A stranger's toss was consumed");
            System.out.println("MOBSEAR_POKEMON_FEED_TOSS hp="+initial+"->"+max+" capped=true berries=3->2 chewTicks="+(now-reserved));
            reserved=-1;
            drops.getFirst().discard();
            if (rejected!=null) rejected.discard();
            step=6;
        } else if (step==6 && !PokemonReactions.isReacting(mob)) {
            pokemon.setCurrentHealth(max-12);
            excluded(mob,caller,"after visit");
            rejected=rejectedToss(caller);
            next=now+7; step=7;
        } else if (step==7 && now>=next) {
            if (!rejected.isAlive() || rejected.getItem().getCount()!=2 || pokemon.getCurrentHealth()!=max-12)
                throw PokemonProbe.fail("Unclaimed toss changed HP or count");
            rejected.discard(); finished=true;
            if (duplicateDrops.size()!=2) throw PokemonProbe.fail("Missing duplicate-toss controls");
            duplicateDrops.forEach(ItemEntity::discard);
            caller.setItemInHand(InteractionHand.MAIN_HAND,originalHand);
            caller.inventoryMenu.broadcastChanges();
            System.out.println("MOBSEAR_POKEMON_VISIT_CARE_PASS duplicateClicks=2 duplicateTossesUnspentThroughExpiry=2 afterVisitDropUnspent=true bystander="+(caller!=viewer));
        }
        if (step>0 && step<6 && now-arrived>115)
            throw PokemonProbe.fail("Missed visit input stage="+step+" hp="+pokemon.getCurrentHealth()+" hand="+caller.getMainHandItem());
    }

    private void verifyChew(int now) {
        if (!duplicateChecked || reserved<0 || now-reserved<39 || now-reserved>41)
            throw PokemonProbe.fail("Missing timed chew and duplicate control: ticks="+(now-reserved));
    }

    private ItemEntity rejectedToss(ServerPlayer player) {
        var drop=new ItemEntity(player.serverLevel(),mob.getX(),mob.getY()+.1,mob.getZ(),new ItemStack(CobblemonItems.ORAN_BERRY,2));
        drop.setPickUpDelay(120);
        drop.setDeltaMovement(Vec3.ZERO);
        NeoForge.EVENT_BUS.post(new ItemTossEvent(drop,player));
        player.serverLevel().addFreshEntity(drop);
        return drop;
    }
}
