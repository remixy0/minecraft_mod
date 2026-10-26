package com.example.mixin.client;

import com.example.kopanie.AutoKopanie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * /kop: gra normalnie kopie tylko wtedy, gdy okno jest aktywne i myszka "złapana".
 * Tu podmieniamy "czy przycisk kopania jest wciśnięty" na true, gdy działa auto-kopanie -
 * dzięki temu kopanie idzie zwykłą drogą gry (normalna prędkość) także w tle.
 */
@Mixin(Minecraft.class)
public class KopanieMixin {
    @Shadow
    public int missTime;

    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
    private boolean modid$trzymajKopanie(boolean down) {
        if (AutoKopanie.wymus()) {
            this.missTime = 0;
            return true;
        }
        return down;
    }
}
