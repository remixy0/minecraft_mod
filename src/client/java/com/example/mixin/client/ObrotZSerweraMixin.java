package com.example.mixin.client;

import com.example.ObrotSerwera;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wykrywa, kiedy to serwer (a nie myszka) zmienia obrót gracza. */
@Mixin(ClientPacketListener.class)
public class ObrotZSerweraMixin {
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void modid$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ObrotSerwera.zglos();
    }

    @Inject(method = "handleRotatePlayer", at = @At("RETURN"))
    private void modid$obrot(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        ObrotSerwera.zglos();
    }

    @Inject(method = "handleLookAt", at = @At("RETURN"))
    private void modid$patrzNa(ClientboundPlayerLookAtPacket packet, CallbackInfo ci) {
        ObrotSerwera.zglos();
    }
}
