package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents; // Potrzebne do pętli!
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType; // Ważne: Rodzaj kliknięcia
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;


import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ExampleModClient implements ClientModInitializer {

    private boolean active = false;
    private int timer = 0;
    Minecraft mc = Minecraft.getInstance();

    @Override
    public void onInitializeClient() {

        // 1. Rejestrujemy PĘTLĘ GRY (To wykonuje się 20 razy na sekundę)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (!active || client.player == null) return;
            timer++;

            client.player.setYRot(90);
            client.player.setXRot(0);
            client.options.keyAttack.setDown(true);
            if(durabilityCheck() < 20){client.player.connection.sendCommand("repair");}

            if (timer < 15) {
                client.player.setDeltaMovement(0.0, client.player.getDeltaMovement().y, 0.3);

            } else if (timer < 30) {
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
                            context.getSource().sendFeedback(Component.literal("§aWłączyłeś auto-chodzenie!"));
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

}