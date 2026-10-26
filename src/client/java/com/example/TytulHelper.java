package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Odczytuje aktualnie wyświetlany tytuł (title) z HUD-a.
 *
 * W 26.2+ tytuł przeniesiono z klasy Gui do nowej klasy Hud (mc.gui.hud).
 * Odczyt przez refleksję zamiast @Accessor: jeśli Mojang zmieni nazwę pola,
 * gra się nie wysypie przy starcie - dostaniesz tylko ostrzeżenie w logu.
 */
public final class TytulHelper {
    private static Field poleTytulu;
    private static boolean szukano = false;

    private TytulHelper() {
    }

    @Nullable
    public static Component getTitle(Minecraft mc) {
        if (mc.gui == null || mc.gui.hud == null) return null;
        Object hud = mc.gui.hud;

        if (!szukano) {
            szukano = true;
            poleTytulu = znajdzPole(hud.getClass());
            if (poleTytulu == null) {
                System.err.println("[modid] Nie znaleziono pola 'title' w klasie Hud - wykrywanie tytułu nie działa.");
            }
        }
        if (poleTytulu == null) return null;

        try {
            return (Component) poleTytulu.get(hud);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Nullable
    private static Field znajdzPole(Class<?> klasa) {
        for (Class<?> c = klasa; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("title");
                if (Component.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
