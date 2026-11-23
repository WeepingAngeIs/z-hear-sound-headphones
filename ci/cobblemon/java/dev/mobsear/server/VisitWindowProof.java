package dev.mobsear.server;

import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Observe two separate late visits through real game inputs and the original dropped entity. */
final class VisitWindowProof {
    private final PokemonEntity mob;
    private final ServerPlayer caller, other;
    private final PokemonCase scenario;
    private final boolean ground;
    private final ItemStack originalHand, otherHand;
    private long arrival=-1, callerDropAge=-1, ateAt=-1;
    private int initialHp, otherRest, expiredRest;
    private boolean checkedOther, checkedCutoff, lateSent, expiredSent, expiredBeside, finished;
    private ItemEntity otherDrop, offered, expiredDrop;

    VisitWindowProof(PokemonEntity mob, ServerPlayer caller, ServerPlayer other, PokemonCase scenario) {
        this.mob=mob; this.caller=caller; this.other=other; this.scenario=scenario;
        ground=scenario==PokemonCase.WALKING_LOPUNNY;
        originalHand=caller.getMainHandItem().copy();
        otherHand=other.getMainHandItem().copy();
    }

    boolean finished() { return finished; }

    void tossed(ItemTossEvent event) {
        if (arrival<0 || !event.getEntity().getItem().is(CobblemonItems.ORAN_BERRY)) return;
        var drop=event.getEntity();
        var player=event.getPlayer();
        require(drop.getItem().getCount()==1 && drop.getOwner()==player,"Single Q must create one player-owned item");
        if (player==caller) {
            if (expiredSent) expiredDrop=drop;
            else if (lateSent && ground) { offered=drop; callerDropAge=age(); }
        } else if (player==other && !checkedOther) otherDrop=drop;
    }

    void tick() {
        if (finished) return;
        boolean visiting=CobblemonCalls.visiting(mob,caller);
        if (arrival<0) {
            if (!visiting) return;
            arrival=serverArrival();
            initialHp=mob.getPokemon().getMaxHealth()-14;
            mob.getPokemon().setCurrentHealth(initialHp);
        }
        long t=age();
        int hp=mob.getPokemon().getCurrentHealth();
        if (t<120) require(visiting,"Visit closed early at "+t);
        if (t==65 && other!=caller && ground) {
            other.setGameMode(GameType.ADVENTURE);
            var perpendicular=caller.position().subtract(mob.position()).multiply(1,0,1).normalize();
            other.teleportTo(mob.getX()-2.1*perpendicular.z,mob.getY(),mob.getZ()+2.1*perpendicular.x);
            action(other,PokemonProbe.DROP_ONE);
        }
        if (otherDrop!=null && !checkedOther && otherDrop.onGround()) {
            require(otherDrop.isAlive() && otherDrop.getItem().getCount()==1 && hp==initialHp && reachable(otherDrop),
                    "Bystander's normal drop changed or missed the visitor at "+t);
            if (++otherRest==5) {
                checkedOther=true;
                System.out.println("MOBSEAR_POKEMON_VISIT_STRANGER_Q grounded=true hp="+hp+" hand="+other.getMainHandItem().getCount()+" ground=1");
                other.setGameMode(GameType.SPECTATOR);
                other.setItemInHand(InteractionHand.MAIN_HAND,otherHand);
                other.inventoryMenu.broadcastChanges();
                otherDrop.discard();
            }
        }
        if (!lateSent && t>=(ground?101:111)) {
            require(!ground || other==caller || checkedOther,"Missing bystander ground control");
            lateSent=true;
            action(caller,ground?PokemonProbe.DROP_ONE:PokemonProbe.CARE_CLICK);
        }
        if (lateSent && ateAt<0 && hp!=initialHp) {
            require(t>=100 && t<120 && hp==initialHp+10 && caller.getMainHandItem().getCount()==2,
                    "Late feeding HP, inventory or timestamp: tick="+t+" hp="+hp);
            if (ground) {
                require(offered!=null && callerDropAge>=100 && offered.onGround() && offered.isRemoved() && reachable(offered),
                        "Single Q was not eaten after the original item landed at the visitor's feet");
                require(!mob.getBoundingBox().inflate(.9).intersects(offered.getBoundingBox()),
                        "Ground control fell inside the old catch box");
            }
            ateAt=t;
            System.out.println("MOBSEAR_POKEMON_VISIT_LATE_FEED input="+(ground?"single-Q-on-floor":"right-click")+" tick="+t+
                    " droppedAt="+callerDropAge+" hp="+initialHp+"->"+hp+" totalBerries=3->2"+
                    (ground?" grounded=true clearance="+Math.sqrt(mob.getBoundingBox().distanceToSqr(offered.position())):""));
        }
        if (t==119) require(ateAt>=0 && visiting,"Final eligible tick missing");
        if (t==120) {
            require(!visiting && mob.distanceTo(caller)<=2.55,"Cutoff was not observed beside the caller");
            VisitCareProof.excluded(mob,caller,"on tick 120 after arrival");
            checkedCutoff=true;
        }
        if (t>=120) require(!visiting,"Expired visit reopened");
        if (checkedCutoff && !expiredSent) {
            expiredSent=true;
            initialHp=mob.getPokemon().getMaxHealth()-14;
            mob.getPokemon().setCurrentHealth(initialHp);
            action(caller,PokemonProbe.DROP_ONE);
        }
        if (expiredDrop!=null && expiredDrop.onGround() && !finished) {
            expiredBeside |= reachable(expiredDrop);
            require(expiredDrop.isAlive() && expiredDrop.getItem().getCount()==1 && mob.getPokemon().getCurrentHealth()==initialHp
                    && caller.getMainHandItem().getCount()==2,"Expired single Q spent a berry or healed");
            if (++expiredRest==5) {
                require(expiredBeside,"Expired berry never landed within reach of the former visitor");
                System.out.println("MOBSEAR_POKEMON_VISIT_EXPIRY tick=120 dropGrounded=true withinReach=true hp="+initialHp+" hand=2 ground=1 input="+scenario);
                expiredDrop.discard();
                caller.setItemInHand(InteractionHand.MAIN_HAND,originalHand);
                caller.inventoryMenu.broadcastChanges();
                finished=true;
            }
        }
        if (t>152 && !finished) throw PokemonProbe.fail("Missing late visit evidence for "+scenario+" tick="+t);
    }

    private boolean reachable(ItemEntity drop) {
        return mob.getBoundingBox().distanceToSqr(drop.position())<=4.0 && Math.abs(mob.getY()-drop.getY())<=.75 && mob.hasLineOfSight(drop);
    }

    private void action(ServerPlayer player,int input) {
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CobblemonItems.ORAN_BERRY,3));
        player.inventoryMenu.broadcastChanges();
        PacketDistributor.sendToPlayer(player,new PokemonProbe.Message(input,scenario.ordinal(),mob.getId()));
    }

    private long age() { return caller.serverLevel().getServer().overworld().getGameTime()-arrival; }

    private long serverArrival() {
        try {
            var responses=CobblemonCalls.class.getDeclaredField("responses");
            responses.setAccessible(true);
            var response=((Map<?,?>)responses.get(null)).get(mob);
            var clock=response.getClass().getDeclaredField("firstArrival");
            clock.setAccessible(true);
            return clock.getLong(response);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Missing visit clock",ex); }
    }

    private static void require(boolean value,String reason) {
        if (!value) throw PokemonProbe.fail(reason);
    }
}
