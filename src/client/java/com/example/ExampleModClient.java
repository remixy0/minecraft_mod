package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents; // Potrzebne do pętli!
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;


import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ExampleModClient implements ClientModInitializer {

    // Te zmienne muszą być TUTAJ (jako pola klasy), żeby "żyły" cały czas
    private boolean active = false; // Czy automat jest włączony?
    private int timer = 0;
    Minecraft mc = Minecraft.getInstance();

    @Override
    public void onInitializeClient() {

        // 1. Rejestrujemy PĘTLĘ GRY (To wykonuje się 20 razy na sekundę)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // Jeśli automat jest wyłączony LUB nie ma gracza (np. menu główne) -> nic nie rób
            if (!active || client.player == null) return;

            // Zwiększamy licznik o 1 w każdej klatce
            timer++;

            // Logika ruchu
            // 20 ticków = 1 sekunda

            client.player.setYRot(90);
            client.player.setXRot(0);
            client.options.keyAttack.setDown(true);

            if (timer < 20) {
                // ETAP 1 (Pierwsza sekunda): Idź w PRAWO (dodatni X)
                // Ustawiamy ruch na 0.3 w osi X, zachowując obecny ruch w pionie (Y)
                client.player.setDeltaMovement(0.0, client.player.getDeltaMovement().y, 0.3);

            } else if (timer < 40) {
                // ETAP 2 (Druga sekunda): Idź w LEWO (ujemny X)
                client.player.setDeltaMovement(0.0, client.player.getDeltaMovement().y, -0.3);

            } else {
                // KONIEC CYKLU: Resetujemy licznik
                timer = 0;
            }
        });


        // 2. Rejestrujemy KOMENDĘ do włączania/wyłączania
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            dispatcher.register(literal("taniec")
                    .executes(context -> {


                        // Zmieniamy stan na przeciwny (Włącz -> Wyłącz, Wyłącz -> Włącz)
                        active = !active;

                        // Resetujemy timer dla bezpieczeństwa
                        timer = 0;


                        if(durabilityCheck() < 20){mc.player.connection.sendCommand("repair");}

                        if (active) {
                            context.getSource().sendFeedback(Component.literal("§aWłączyłeś auto-chodzenie!"));
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



}