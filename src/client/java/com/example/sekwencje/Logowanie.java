package com.example.sekwencje;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Logowanie w oknie serwera ("Wprowadź swoje hasło, aby kontynuować" + pole "Hasło" + przycisk "Zaloguj się").
 * Bot wpisuje hasło w pole i naciska przycisk - tak jak człowiek. Gdy takiego okna nie ma, można użyć /login.
 * Wszystko w wątku gry.
 */
public class Logowanie {
    /** Przyciski, które uznajemy za "zaloguj" (małymi literami, wystarczy fragment). */
    private static final String[] PRZYCISKI = {"zaloguj", "login", "log in", "sign in"};
    private static final int MAX_AUTO_PROB = 2;      // tyle razy na jedno wejście na serwer - przy złym haśle nie próbujemy w kółko

    public record Okno(Screen ekran, EditBox pole, AbstractButton przycisk) {
    }

    private Screen obsluzonyEkran = null;   // ekran, w którym już wpisaliśmy hasło
    private Screen widzianyEkran = null;
    private int odczekaj = 0;
    private int autoProby = 0;
    private long ostatnieLogowanie = 0;

    /** Okno logowania na ekranie albo null. */
    public static Okno znajdz(Minecraft mc) {
        Screen s = mc.gui.screen();
        if (s == null || s instanceof ChatScreen) return null;
        List<EditBox> pola = new ArrayList<>();
        List<AbstractButton> przyciski = new ArrayList<>();
        zbierz(s.children(), pola, przyciski, 0);
        if (pola.isEmpty()) return null;
        for (AbstractButton b : przyciski) {
            if (!b.visible || !b.isActive()) continue;
            String t = b.getMessage().getString().toLowerCase(Locale.ROOT);
            for (String p : PRZYCISKI) {
                if (t.contains(p)) return new Okno(s, pola.get(0), b);
            }
        }
        return null;
    }

    private static void zbierz(List<? extends GuiEventListener> lista, List<EditBox> pola, List<AbstractButton> przyciski, int glebokosc) {
        if (glebokosc > 8) return;
        for (GuiEventListener g : lista) {
            if (g instanceof EditBox e && e.isVisible()) {
                pola.add(e);
            } else if (g instanceof AbstractButton b) {
                przyciski.add(b);
            }
            if (g instanceof ContainerEventHandler c) {
                zbierz(c.children(), pola, przyciski, glebokosc + 1);
            }
        }
    }

    /** Wpisuje hasło i naciska "Zaloguj się". */
    public void wpisz(Okno o, String haslo) {
        o.pole().setValue(haslo);
        o.przycisk().onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        obsluzonyEkran = o.ekran();
        ostatnieLogowanie = System.currentTimeMillis();
    }

    /** Czy przed chwilą (w ostatnich ms milisekundach) bot się zalogował. */
    public boolean zalogowanoNiedawno(long ms) {
        return System.currentTimeMillis() - ostatnieLogowanie < ms;
    }

    /** Okno logowania, w którym bot jeszcze nie wpisywał hasła, albo null. */
    public Okno noweOkno(Minecraft mc) {
        Okno o = znajdz(mc);
        return o != null && o.ekran() != obsluzonyEkran ? o : null;
    }

    /**
     * Co tick: gdy pojawi się okno logowania, a hasło jest ustawione - po 1-2 s (jak człowiek) wpisuje je i klika.
     */
    public void tick(Minecraft mc, String haslo, Consumer<String> raport) {
        if (mc.level == null) {
            // poza serwerem - przy następnym wejściu znowu można się zalogować
            autoProby = 0;
            widzianyEkran = null;
            obsluzonyEkran = null;
            ostatnieLogowanie = 0;
            return;
        }
        if (haslo == null || haslo.isEmpty()) return;
        Okno o = noweOkno(mc);
        if (o == null) {
            widzianyEkran = null;
            return;
        }
        if (o.ekran() != widzianyEkran) {
            widzianyEkran = o.ekran();
            odczekaj = ThreadLocalRandom.current().nextInt(20, 41);
            return;
        }
        if (odczekaj > 0) {
            odczekaj--;
            return;
        }
        if (autoProby >= MAX_AUTO_PROB) {
            if (autoProby == MAX_AUTO_PROB) {
                autoProby++;
                raport.accept("❌ okno logowania pojawiło się znowu - hasło chyba jest złe. Popraw je w ⚙ Sekwencje → 🔑 Hasło");
            }
            return;
        }
        autoProby++;
        wpisz(o, haslo);
        raport.accept("🔑 wpisałem hasło w okno serwera i kliknąłem „" + o.przycisk().getMessage().getString() + "”");
    }
}
