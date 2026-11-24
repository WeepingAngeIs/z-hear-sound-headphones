package dev.mobsear.packprobe;

import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.mobsear.MobsEar;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;

/** Use normal client inputs. The server independently checks the visitor, original dropped entity, HP and inventory. */
final class PackVisitFeeding {
    private enum Offer {
        HEALTHY_HAND(8,false,true), INJURED_HAND(8,false,false), INJURED_FLOOR(101,true,false), EXPIRED_FLOOR(120,true,false);

        final int earliestTick;
        final boolean ground, healthy;
        Offer(int earliestTick,boolean ground,boolean healthy) {
            this.earliestTick=earliestTick; this.ground=ground; this.healthy=healthy;
        }
        int stackSize() { return ground?3:2; }
    }

    private final boolean testFloor="1".equals(System.getenv("MOBSEAR_TEST_VISIT_BERRIES"));
    private Offer next=testFloor?Offer.HEALTHY_HAND:Offer.INJURED_HAND;
    private volatile Offer requested;
    private Offer sent;
    private volatile boolean passed;
    private long arrival=-1, reservedAt=-1;
    private int start, aimTicks, settled;
    private boolean expiredInReach, tracedLanding;
    private ItemEntity actualDrop;
    private ItemStack originalHand;
    private volatile boolean aimReceived;
    private ServerPlayer visitingCaller;

    void tossed(ItemTossEvent event) {
        var offer=requested;
        if (passed || offer==null || !offer.ground || event.getPlayer()!=visitingCaller) return;
        var drop=event.getEntity();
        if (actualDrop!=null || drop.getOwner()!=visitingCaller || !drop.getItem().is(CobblemonItems.ORAN_BERRY) || drop.getItem().getCount()!=1)
            throw new IllegalStateException("Expected one single-Q Oran berry from the visitor's caller");
        actualDrop=drop;
        System.out.println("ACOUSTIC_PACK_BERRY_THROW offer="+offer+" spawn="+drop.position()+" motion="+drop.getDeltaMovement()+
                " playerPitch="+visitingCaller.getXRot());
        if (visitingCaller.getXRot()<85) throw new IllegalStateException("The test caller was not looking down when Q reached the server");
    }

    void server(PokemonEntity mob,ServerPlayer caller,boolean reacting) throws ReflectiveOperationException {
        if (passed) return;
        if (testFloor && arrival<0) {
            var events=(java.util.Map<?,?>)Boundary.field(Class.forName("dev.mobsear.server.CobblemonCalls"),"responses").get(null);
            var response=events.get(mob);
            if (response!=null) arrival=(long)Boundary.get(response,"firstArrival");
            if (arrival<0) return;
        }
        long age=caller.serverLevel().getServer().overworld().getGameTime()-arrival;
        if (testFloor && (age==119 || age==120)) {
            var visiting=Class.forName("dev.mobsear.server.CobblemonCalls").getDeclaredMethod("visiting",PokemonEntity.class,ServerPlayer.class);
            visiting.setAccessible(true);
            if (reservedAt<0 || (boolean)visiting.invoke(null,mob,caller)!=(age==119))
                throw new IllegalStateException("Missing late feeding or exact visit boundary at "+age+" offer="+next);
        }
        if (requested==null) {
            if (testFloor && age<next.earliestTick) return;
            if (originalHand==null) {
                if (!reacting || mob.distanceTo(caller)>2.5 || !mob.getNavigation().isDone()) return;
                originalHand=caller.getMainHandItem().copy();
                visitingCaller=caller;
            }
            var pokemon=mob.getPokemon();
            start=pokemon.getMaxHealth()-(next.healthy?0:next.ground?14:11);
            pokemon.setCurrentHealth(start);
            if (next.healthy) pokemon.setCurrentFullness(0);
            if (next==Offer.INJURED_FLOOR) {
                // Arrange only the tester on a flat mark beside the arrived Pokémon. A vertical Q
                // here stays outside the airborne catch box and lands inside its ground reach.
                double dx=caller.getX()-mob.getX(), dz=caller.getZ()-mob.getZ();
                double offset=Math.min(2.38,mob.getBbWidth()*.5+1.55);
                boolean onX=Math.abs(dx)>=Math.abs(dz);
                double x=mob.getX()+(onX?Math.copySign(offset,dx):0);
                double z=mob.getZ()+(onX?0:Math.copySign(offset,dz));
                caller.teleportTo(caller.serverLevel(),x,caller.getY(),z,caller.getYRot(),90);
            }
            caller.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CobblemonItems.ORAN_BERRY,next.stackSize()));
            caller.inventoryMenu.broadcastChanges();
            actualDrop=null;
            reservedAt=-1;
            aimReceived=false;
            tracedLanding=false;
            requested=next;
            return;
        }
        var offer=requested;
        var pokemon=mob.getPokemon();
        int hp=pokemon.getCurrentHealth();
        if (offer.ground) aimReceived=caller.getXRot()>=89;
        if (actualDrop!=null && actualDrop.onGround() && !tracedLanding) {
            tracedLanding=true;
            System.out.println("ACOUSTIC_PACK_BERRY_LANDED offer="+offer+" tick="+age+" "+observedDrop(mob,caller));
        }
        if (offer==Offer.EXPIRED_FLOOR) {
            if (hp!=start) throw new IllegalStateException("Expired ground berry changed HP");
            if (actualDrop!=null && actualDrop.onGround()) {
                if (!actualDrop.isAlive() || actualDrop.getItem().getCount()!=1 || caller.getMainHandItem().getCount()!=2)
                    throw new IllegalStateException("Expired ground berry was spent");
                expiredInReach |= reachable(mob,actualDrop);
                if (++settled==5) {
                    if (!expiredInReach) throw new IllegalStateException("Expired berry never reached the former visitor");
                    System.out.println("ACOUSTIC_PACK_BERRY_EXPIRY closedAt=120 landed=true withinReach=true hp="+start+" hand=2 ground=1");
                    actualDrop.discard();
                    finish(caller);
                }
            }
            if (!passed && age>210) throw new IllegalStateException("Missing expired ground drop "+observedDrop(mob,caller));
            return;
        }
        if (offer.healthy && hp!=start) throw new IllegalStateException("A healthy visitor's HP changed");
        boolean ate=offer.healthy?pokemon.getCurrentFullness()==1:hp==start+10;
        boolean reserved=offer.ground ? actualDrop!=null && actualDrop.isRemoved() : caller.getMainHandItem().getCount()==offer.stackSize()-1;
        if (reserved && reservedAt<0) {
            if (ate || (testFloor && age>=120)) throw new IllegalStateException("Berry must be reserved before the effect and within the visit: "+offer);
            reservedAt=age;
        }
        if (!ate) {
            if (testFloor && age>=(offer.healthy?94:165)) throw new IllegalStateException("Visitor ignored "+offer+" at "+age+
                    " hp="+hp+" fullness="+pokemon.getCurrentFullness()+" "+observedDrop(mob,caller));
            return;
        }
        if (testFloor && (reservedAt<0 || age-reservedAt<39 || age-reservedAt>41)) throw new IllegalStateException("Wrong chew duration "+(age-reservedAt));
        if (testFloor) System.out.println("ACOUSTIC_PACK_BERRY_CHEW offer="+offer+" reservedAt="+reservedAt+" effectAt="+age+" duration="+(age-reservedAt));
        if (caller.getMainHandItem().getCount()!=offer.stackSize()-1) throw new IllegalStateException("Wrong number of berries spent by "+offer);
        if (offer.ground && (actualDrop==null || !actualDrop.onGround() || !actualDrop.isRemoved() || !reachable(mob,actualDrop) ||
                mob.getBoundingBox().inflate(.9).intersects(actualDrop.getBoundingBox())))
            throw new IllegalStateException("Expected the original single-Q berry to land before eating "+offer+" at "+age+
                    (actualDrop==null?" no recorded item":" ground="+actualDrop.onGround()+" removed="+actualDrop.isRemoved()+
                            " reachable="+reachable(mob,actualDrop)+" clearance="+Math.sqrt(mob.getBoundingBox().distanceToSqr(actualDrop.position()))));
        String method=offer.ground?"single-Q-on-floor":"right-click";
        String measured=" visitTick="+age+" hp="+start+"->"+hp+" totalBerries="+offer.stackSize()+"->"+(offer.stackSize()-1);
        if (offer.healthy)
            System.out.println("ACOUSTIC_PACK_HEALTHY_TREAT input="+method+measured+" fullness=0->1");
        else if (offer.ground)
            System.out.println("ACOUSTIC_PACK_GROUND_BERRY_PASS input=single-Q landed=true"+measured+" clearance="+
                    Math.sqrt(mob.getBoundingBox().distanceToSqr(actualDrop.position())));
        else System.out.println("ACOUSTIC_PACK_BERRY_PASS species=Lopunny hp="+start+"->"+hp+" stack=2->1"+(testFloor?" visitTick="+age:""));
        if (!testFloor) { finish(caller); return; }
        next=offer==Offer.HEALTHY_HAND?Offer.INJURED_FLOOR:Offer.EXPIRED_FLOOR;
        requested=null;
    }

    private boolean reachable(PokemonEntity mob,ItemEntity drop) {
        return mob.getBoundingBox().distanceToSqr(drop.position())<=4 && Math.abs(mob.getY()-drop.getY())<=.75 && mob.hasLineOfSight(drop);
    }

    private String observedDrop(PokemonEntity mob,ServerPlayer caller) {
        String info="hand="+caller.getMainHandItem().getCount()+" aimReceived="+aimReceived+
                " caller="+caller.position()+" visitor="+mob.position();
        if (actualDrop==null) return info+" no original item";
        return info+" item="+actualDrop.position()+" removed="+actualDrop.getRemovalReason()+" grounded="+actualDrop.onGround()+
                " inReach="+reachable(mob,actualDrop)+" clearance="+Math.sqrt(mob.getBoundingBox().distanceToSqr(actualDrop.position()));
    }

    private void finish(ServerPlayer caller) {
        caller.setItemInHand(InteractionHand.MAIN_HAND,originalHand);
        caller.inventoryMenu.broadcastChanges();
        passed=true;
    }

    void client(Minecraft mc,PokemonEntity mob) {
        var offer=requested;
        if (passed || offer==null || offer==sent || !mc.player.getMainHandItem().is(CobblemonItems.ORAN_BERRY)
                || mc.player.getMainHandItem().getCount()!=offer.stackSize()) return;
        if (offer.ground) {
            // Use Minecraft's normal rotation message directly before its normal drop action.
            // The headless pack camera can keep showing the visitor while the server still has
            // its initial five-degree pitch. Only an actual downward throw counts as this trial.
            mc.player.setXRot(90);
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(),90,mc.player.onGround()));
            if (++aimTicks<3 || !aimReceived) return;
            if (!mc.player.drop(false)) throw new IllegalStateException("Normal single-item Q failed");
            aimTicks=0;
        } else {
            if (!MobsEar.localVisitor.test(mob.getUUID())) return;
            var hit=new EntityHitResult(mob,mob.getEyePosition());
            if (!mc.gameMode.interactAt(mc.player,mob,hit,InteractionHand.MAIN_HAND).consumesAction())
                throw new IllegalStateException("Pack did not handle the visitor's berry click");
        }
        sent=offer;
    }

    boolean passed() { return passed; }

    boolean aimingAtFloor() {
        var offer=requested;
        return offer!=null && offer.ground && offer!=sent;
    }
}
