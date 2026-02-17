package com.example;

import com.example.controler.Controler;
import com.example.mixin.client.InGameHudAccessor;
import com.example.network.MojSerwer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents; // Potrzebne do pętli!
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType; // Ważne: Rodzaj kliknięcia
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.abs;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ExampleModClient implements ClientModInitializer {

    private volatile boolean active = false;
    private volatile boolean drewno = false;
    private volatile boolean postawiono_sadzonke = false;
    private int timer = 0;
    Minecraft mc = Minecraft.getInstance();
    MojSerwer mojSerwer = new MojSerwer();
    Controler controler = new Controler(mc);
    final double[] odleglosc = {0};

    @Override
    public void onInitializeClient() {



        Thread watekSieciowy = new Thread(() -> {
            while (true) {
                mojSerwer.polacz();
                boolean nowaWartosc = false;
                while (true) { // Nieskończona pętla w tle
                    try {
                        // Pobieramy dane z serwera
                        Boolean wartosc = mojSerwer.getValue();
                        if (wartosc != null) {
                            nowaWartosc = wartosc;
                        }

                        if (drewno != nowaWartosc) {
                            System.out.println("Zdalna zmiana stanu na: " + nowaWartosc);
                            drewno = nowaWartosc;
                        }

                        Thread.sleep(100);

                    } catch (Exception e) {
                        e.printStackTrace();
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ex) {
                        }
                    }
                }
            }
        });
        watekSieciowy.setDaemon(true);
        watekSieciowy.start();


        final double[][] blok = new double[1][3];
        final int[] stan_drewna = {0};

        AtomicBoolean czyZlowiono = new AtomicBoolean(false);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {


            String tekst = message.getString();

            if (tekst.contains("Wędkarstwo") && tekst.contains("Wyłowiono")) {
                czyZlowiono.set(true);


            }
        });

        AtomicInteger counter = new AtomicInteger();
        AtomicInteger counterZlowiono = new AtomicInteger();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            counter.getAndIncrement();
            counterZlowiono.getAndIncrement();
            try {
                if (counterZlowiono.get() > 10) {
                    if (mc.player != null && mc.gui != null && active) {
                        Component title = ((InGameHudAccessor) mc.gui).getTitle();
                        if (title != null) {
                            List<String> list = new ArrayList<String>();
                            String rawText = title.getString();

                            title.visit((style, text) -> {

                                if (style.getColor() != null) {
                                    String colorName = style.getColor().toString();
                                    list.add(colorName);
                                    System.out.println("Znak: '" + text + "' ma kolor: " + colorName);
                                }
                                return java.util.Optional.empty();
                            }, net.minecraft.network.chat.Style.EMPTY);

                            if (client.options.keyUse.isDown()) {
                                client.options.keyUse.setDown(false);
                            }

                            if (list.stream().distinct().count() == 3 & !czyZlowiono.get()) {
                                client.player.displayClientMessage(Component.literal("§aTERAZ LOWIC!"), false);
                                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                                mc.player.swing(InteractionHand.MAIN_HAND);
                                counterZlowiono.set(0);
                            }


                        } else {
                            if (mc.player != null && mc.gameMode != null) {

                                if (mc.player.fishing == null) {
                                    client.player.displayClientMessage(Component.literal("§aZARZUCAM WEDKE!"), false);
                                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                                    mc.player.swing(InteractionHand.MAIN_HAND);
                                    czyZlowiono.set(false);
                                }
                            }
                        }
                    }
                }
            }catch (Exception e) {
                e.printStackTrace();
            }

        });


        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("rybki")
                    .executes(context -> {

                        active = !active;

                        timer = 0;
                        if (active) {
                            context.getSource().sendFeedback(Component.literal("§aWłączyłeś auto-lowienie!"));
                        } else {
                            context.getSource().sendFeedback(Component.literal("§cZatrzymano."));
                        }

                        return 1;
                    }));





        });

    }



    private boolean sadzonka(double[] coordinates, Minecraft client) {
        LocalPlayer player = mc.player;
        Vec3 playerPos = player.getEyePosition();
        net.minecraft.world.entity.player.Inventory eq = mc.player.getInventory();

        double distance = Math.sqrt((coordinates[0] - playerPos.x) * (coordinates[0] - playerPos.x) + (coordinates[2] - playerPos.z) * (coordinates[2] - playerPos.z));
//        double offset = distance/10 * 2;
        if(odleglosc[0] != distance) {
            client.player.displayClientMessage(Component.literal("§cDystans " + String.format("%.2f", distance)), false);
            odleglosc[0] = distance;
        }

        controler.lookAt(coordinates[0] - 0.5,playerPos.y - 2 ,coordinates[2] - 0.5, false);
        String blockSeen = controler.coWidze();
//       client.player.displayClientMessage(Component.literal("§3distance" + distance), false);
        if(distance > 2) {
            client.options.keyUp.setDown(true);
            client.options.keyAttack.setDown(true);
        }
        else {
            client.options.keyUp.setDown(false);
            client.options.keyAttack.setDown(false);

            if (blockSeen.equals("minecraft:spruce_log") || blockSeen.equals("minecraft:spruce_leaves")) {
                client.options.keyAttack.setDown(true);
                client.options.keyUse.setDown(false);
            } else if (blockSeen.equals("minecraft:dirt") || blockSeen.equals("minecraft:grass_block")) {
                client.options.keyAttack.setDown(false);

                if(controler.znajdzSlotZPrzedmiotem(Items.SPRUCE_SAPLING) != -1){
                    eq.setSelectedSlot(controler.znajdzSlotZPrzedmiotem(Items.SPRUCE_SAPLING));
                }

                client.options.keyUse.setDown(true);
                return true;

            } else {
                client.options.keyAttack.setDown(false);
                client.options.keyUse.setDown(false);
            }

        }
        return false;
    }




    private float durabilityCheck() {
        var player = mc.player;

        ItemStack item = player.getMainHandItem();

        int maxDamage = item.getMaxDamage();
        int currentDamage = item.getDamageValue();
        int left = maxDamage - currentDamage;

        float percent = (float) left / maxDamage;

        return percent * 100;
    }


    private void clickSlot(int windowId, int slotId, int button, ClickType type) {
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(windowId, slotId, button, type, mc.player);
        }
    }

    private int znajdzSlotZPrzedmiotem() {
        var menu = mc.player.containerMenu;

        for (int i = 9; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.hasItem() && slot.getItem().getItem() == Items.STONE) {
                return i;
            }
        }
        return -1;
    }


    private void crafting() {

        if (mc.player.containerMenu == null) {
            return;
        }
        int windowId = mc.player.containerMenu.containerId;

        int slotZDiamentem = znajdzSlotZPrzedmiotem();
        clickSlot(windowId, slotZDiamentem, 0,ClickType.PICKUP);
        for(int i = 0; i <= 64; i++) {
            for (int craftingSlot = 1; craftingSlot <= 4; craftingSlot++) {
                clickSlot(windowId, craftingSlot, 1, ClickType.PICKUP);
            }
        }

        clickSlot(windowId, 0, 0,ClickType.QUICK_MOVE);
        return;

    }

    private double scanner_drewno(int RADIUS) {
        var player = mc.player;
        var level = mc.level;
        var playerPos = player.blockPosition();
        double target_x, target_y, target_z;

        for(int radius = 1; radius <= RADIUS; radius++) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    for (int znak = -1; znak <= 1; znak+=2) {
                        BlockPos checkPos = playerPos.offset(x*znak , y*znak, z*znak);
                        BlockState state = level.getBlockState(checkPos);

                        if (state.is(Blocks.SPRUCE_LOG)) {
                            return lookAt(checkPos.getX(), checkPos.getY(), checkPos.getZ());
                        }
                    }
                }
            }
        }
        }
        return -1;
    }




    private double lookAt(double targetX, double targetY, double targetZ) {

        float pi = (float) Math.PI;
        LocalPlayer player = mc.player;
        Vec3 playerPos = player.getEyePosition();

        double dx = targetX + 0.5 - playerPos.x;
        double dy = targetY+ 0.5 - playerPos.y;
        double dz = targetZ + 0.5 - playerPos.z;

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.atan2(dz, dx) / (2*pi) * 360 - 90;

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

        player.setYRot(yaw);
        player.setXRot(pitch);


        player.yRotO = yaw;
        player.xRotO = pitch;
        return distanceXZ;
    }


}
