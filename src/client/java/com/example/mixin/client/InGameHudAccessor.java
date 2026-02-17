package com.example.mixin.client; // Zmień na swoją paczkę

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(Gui.class)
public interface InGameHudAccessor {
    @Accessor
    @Nullable
    Component getTitle();
}