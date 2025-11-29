package com.example;

import com.example.controler.Controler;
import com.example.network.MojSerwer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents; // Potrzebne do pętli!
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType; // Ważne: Rodzaj kliknięcia
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;


import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ExampleModClient implements ClientModInitializer {

    private volatile boolean active = false;
    boolean drewno = false;
    private int timer = 0;
    Minecraft mc = Minecraft.getInstance();
    MojSerwer mojSerwer = new MojSerwer();
    Controler controler = new Controler(mc);

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

                        if (active != nowaWartosc) {
                            System.out.println("Zdalna zmiana stanu na: " + nowaWartosc);
                            active = nowaWartosc;
                        }

                        // WAŻNE: Odpocznij chwilę, żeby nie spalić procesora (np. 10 razy na sekundę)
                        Thread.sleep(100);

                    } catch (Exception e) {
                        e.printStackTrace();
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ex) {
                        } // Jak błąd, poczekaj dłużej
                    }
                }
            }
        });
        watekSieciowy.setDaemon(true); // Wątek zginie razem z zamknięciem gry
        watekSieciowy.start();


        // 1. Rejestrujemy PĘTLĘ GRY (To wykonuje się 20 razy na sekundę)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.options != null) {
                client.options.pauseOnLostFocus = false;
            }


            if (drewno) { // (Zakładam, że ta zmienna włącza bota ametystowego)

                double[] dane = controler.scanner_ametyst(10);
                if (dane[0] != -1) {
                    double dystans = Math.sqrt(dane[0] * dane[0] + dane[1] * dane[1]);
                    client.options.keyAttack.setDown(false);

                    if (dystans > 3.5) {
                        client.options.keyUp.setDown(true);

                        String cel = controler.coWidze();

                        if ("minecraft:oak_log".equals(cel) || "minecraft:oak_leaves".equals(cel)) {
                            client.options.keyAttack.setDown(true);
                        } else {
                            client.options.keyAttack.setDown(false);
                            client.options.keyUp.setDown(true);
                        }

                    } else {
                        client.options.keyUp.setDown(false);

                        String cel = controler.coWidze();

                        if ("minecraft:oak_log".equals(cel) || "minecraft:oak_leaves".equals(cel)) {
                            client.options.keyAttack.setDown(true);
                        } else {
                            client.options.keyAttack.setDown(false);
                            client.options.keyUp.setDown(true);
                        }
                    }
                    if (dane[1] < 1) {
                        controler.lookAt(dane[0],dane[1]-1,dane[2]);

                        String coWidze = controler.coWidze();
                        if (!"minecraft:dirt".equals(coWidze)) {
                            client.options.keyAttack.setDown(true);
                            return;
                        } else {
                            client.options.keyAttack.setDown(false);
                            client.options.keyUse.setDown(true);
                            return;
                        }
                    }
                }
            }

            if (!active || client.player == null) return;
            timer++;

            client.player.setYRot(90);
            client.player.setXRot(0);
            client.options.keyAttack.setDown(true);
            if(durabilityCheck() < 20){client.player.connection.sendCommand("repair");}

            if (timer < 7) {
                client.player.setDeltaMovement(0.0, client.player.getDeltaMovement().y, 0.3);

            } else if (timer < 14) {
                client.player.setDeltaMovement(0.0, client.player.getDeltaMovement().y, -0.3);

            } else {

                timer = 0;
            }
        });


        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("kop")
                    .executes(context -> {

                        active = !active;

                        timer = 0;
                        if (active) {
                            context.getSource().sendFeedback(Component.literal("§aWłączyłeś auto-kopanie!"));
                        } else {
                            context.getSource().sendFeedback(Component.literal("§cZatrzymano."));
                        }

                        return 1;
                    }));

            dispatcher.register(literal("craftuj")
                    .executes(context -> {

                            crafting();

                        return 1;
                    }));

            dispatcher.register(literal("drewno")
                    .executes(context -> {
                        drewno = !drewno;

                        if (drewno) {
                            context.getSource().sendFeedback(Component.literal("§aWłączyłeś kopanie drewna!"));
                        } else {
                            context.getSource().sendFeedback(Component.literal("§cZatrzymano."));
                        }
                        return 1;
                    }));


        });

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
        net.minecraft.client.player.LocalPlayer player = mc.player;
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
