package dev.mobsear.packprobe;

import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.mobsear.MobsEar;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/** Feed the actual walker using the normal client click while all pack interaction handlers are installed. */
final class PackVisitFeeding {
    private final boolean testFloor="1".equals(System.getenv("MOBSEAR_BERRY_SOURCE_CHECK"));
    private int start;
    private ItemStack previouslyHeld;
    private volatile boolean ready, clicked, passed;
    private volatile int dropRequest;
    private int sentDrop, aimTicks, settled;
    private long arrival=-1;
    private boolean handPassed, ate, cutoff;
    private ItemEntity actualDrop;

    void server(PokemonEntity mob,ServerPlayer caller,boolean reacting) throws ReflectiveOperationException {
        if (passed) return;
        var pokemon=mob.getPokemon();
        long now=caller.serverLevel().getServer().overworld().getGameTime();
        if (testFloor && arrival<0) {
            var events=(java.util.Map<?,?>)Boundary.field(Class.forName("dev.mobsear.server.CobblemonCalls"),"responses").get(null);
            var response=events.get(mob);
            if (response!=null) arrival=(long)Boundary.get(response,"firstArrival");
            if (arrival<0) return;
        }
        long age=now-arrival;
        if (testFloor && age<96) return;
        if (!ready && reacting && mob.distanceTo(caller)<=2.5 && mob.getNavigation().isDone()) {
            previouslyHeld=caller.getMainHandItem().copy();
            start=pokemon.getMaxHealth()-11;
            pokemon.setCurrentHealth(start);
            caller.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CobblemonItems.ORAN_BERRY,2));
            caller.inventoryMenu.broadcastChanges();
            ready=true;
        } else if (clicked && !handPassed && caller.getMainHandItem().getCount()==1 && pokemon.getCurrentHealth()==start+10) {
            handPassed=true;
            System.out.println("ACOUSTIC_PACK_BERRY_PASS species=Lopunny hp="+start+"->"+pokemon.getCurrentHealth()+" stack=2->1"+
                    (testFloor?" visitTick="+age:""));
            if (!testFloor) finish(caller);
            else if (age>=120) throw new IllegalStateException("Late right-click healed after expiry");
        }
        if (!testFloor) return;
        if (handPassed && dropRequest==0 && age>=103) requestDrop(caller,mob,1);
        if (dropRequest>0 && actualDrop==null) {
            for (var drop:caller.serverLevel().getEntitiesOfClass(ItemEntity.class,caller.getBoundingBox().inflate(5),
                    d->d.getOwner()==caller && d.getItem().is(CobblemonItems.ORAN_BERRY) && d.getItem().getCount()==1)) {
                actualDrop=drop;
                break;
            }
        }
        if (dropRequest==1 && !ate && pokemon.getCurrentHealth()!=start) {
            if (age>=120 || actualDrop==null || !actualDrop.onGround() || !actualDrop.isRemoved() ||
                    mob.getBoundingBox().inflate(.9).intersects(actualDrop.getBoundingBox()) || pokemon.getCurrentHealth()!=start+10 ||
                    caller.getMainHandItem().getCount()!=2) throw new IllegalStateException("Grounded single-Q healing or consumption failed at "+age);
            ate=true;
            System.out.println("ACOUSTIC_PACK_GROUND_BERRY_PASS input=single-Q landed=true visitTick="+age+
                    " hp="+start+"->"+pokemon.getCurrentHealth()+" totalBerries=3->2 clearance="+
                    Math.sqrt(mob.getBoundingBox().distanceToSqr(actualDrop.position())));
        }
        if (age==120) {
            var visiting=Class.forName("dev.mobsear.server.CobblemonCalls").getDeclaredMethod("visiting",PokemonEntity.class,ServerPlayer.class);
            visiting.setAccessible(true);
            if (!ate || (boolean)visiting.invoke(null,mob,caller)) throw new IllegalStateException("Missed six-second cutoff or ground feed");
            cutoff=true;
            requestDrop(caller,mob,2);
        }
        if (cutoff && actualDrop!=null && actualDrop.onGround()) {
            if (!actualDrop.isAlive() || actualDrop.getItem().getCount()!=1 || caller.getMainHandItem().getCount()!=2 ||
                    pokemon.getCurrentHealth()!=start) throw new IllegalStateException("Expired ground berry was spent");
            if (++settled==5) {
                System.out.println("ACOUSTIC_PACK_BERRY_EXPIRY closedAt=120 landed=true hp="+start+" hand=2 ground=1");
                actualDrop.discard();
                finish(caller);
            }
        }
        if (age>154 && !passed) throw new IllegalStateException("Missing ground feeding evidence at "+age+" request="+dropRequest);
    }

    private void requestDrop(ServerPlayer caller,PokemonEntity mob,int number) {
        start=mob.getPokemon().getMaxHealth()-14;
        mob.getPokemon().setCurrentHealth(start);
        caller.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CobblemonItems.ORAN_BERRY,3));
        caller.inventoryMenu.broadcastChanges();
        actualDrop=null;
        dropRequest=number;
    }

    private void finish(ServerPlayer caller) {
        caller.setItemInHand(InteractionHand.MAIN_HAND,previouslyHeld);
        caller.inventoryMenu.broadcastChanges();
        passed=true;
    }

    void client(Minecraft mc,PokemonEntity mob) {
        if (dropRequest>sentDrop && mc.player.getMainHandItem().is(CobblemonItems.ORAN_BERRY) && mc.player.getMainHandItem().getCount()==3) {
            var away=mc.player.position().subtract(mob.position()).multiply(1,0,1).normalize();
            mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    mob.position().add(away.scale(mob.getBbWidth()*.5+1.6)));
            if (++aimTicks>=3) {
                if (!mc.player.drop(false)) throw new IllegalStateException("Normal single-item Q failed");
                sentDrop=dropRequest;
                aimTicks=0;
            }
            return;
        }
        if (!ready || clicked || !mc.player.getMainHandItem().is(CobblemonItems.ORAN_BERRY) ||
                !MobsEar.localVisitor.test(mob.getUUID())) return;
        var hit=new EntityHitResult(mob,mob.getEyePosition());
        if (!mc.gameMode.interactAt(mc.player,mob,hit,InteractionHand.MAIN_HAND).consumesAction())
            throw new IllegalStateException("Pack did not handle the visitor's berry click");
        clicked=true;
    }

    boolean passed() { return passed; }
}
