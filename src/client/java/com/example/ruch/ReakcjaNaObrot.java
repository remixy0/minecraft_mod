package com.example.ruch;

import net.minecraft.client.Options;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reakcja "człowieka", gdy serwer obróci mu głowę: kilka skoków i kucnięć w losowej kolejności.
 * (Rozglądanie się i powrót wzroku robi NaturalnyRuch.reakcja.)
 */
public class ReakcjaNaObrot {
    private final Random rng = new Random();
    private int tick = -1;                         // -1 = reakcja nie trwa
    private int koniec;
    private final List<Integer> skoki = new ArrayList<>();
    private final List<int[]> kucniecia = new ArrayList<>(); // {start, koniec}
    private boolean kucam = false;

    public void start(Options o) {
        przerwij(o);
        skoki.clear();
        kucniecia.clear();

        int t = losuj(12, 30); // czas na zauważenie i powrót wzrokiem
        boolean najpierwSkok = rng.nextBoolean();
        int ileSkokow = losuj(1, 2);
        int ileKucniec = losuj(1, 3);

        if (najpierwSkok) {
            t = dodajSkoki(t, ileSkokow);
        }
        for (int i = 0; i < ileKucniec; i++) {
            int dlugosc = losuj(3, 8);
            kucniecia.add(new int[]{t, t + dlugosc});
            t += dlugosc + losuj(4, 10);
        }
        if (!najpierwSkok) {
            t = dodajSkoki(t, ileSkokow);
        }
        koniec = t + losuj(20, 50);
        tick = 0;
    }

    private int dodajSkoki(int t, int ile) {
        for (int i = 0; i < ile; i++) {
            skoki.add(t);
            t += losuj(8, 14);
        }
        return t;
    }

    public void tick(Options o) {
        if (tick < 0) return;

        boolean skok = false;
        for (int s : skoki) {
            if (tick >= s && tick < s + 2) skok = true;
        }
        o.keyJump.setDown(skok);

        boolean kucaj = false;
        for (int[] k : kucniecia) {
            if (tick >= k[0] && tick < k[1]) kucaj = true;
        }
        ustawKucanie(o, kucaj);

        tick++;
        if (tick > koniec) {
            przerwij(o);
        }
    }

    /** Kończy reakcję i puszcza wszystkie klawisze. */
    public void przerwij(Options o) {
        if (tick >= 0) {
            o.keyJump.setDown(false);
            ustawKucanie(o, false);
        }
        tick = -1;
    }

    public boolean trwa() {
        return tick >= 0;
    }

    private void ustawKucanie(Options o, boolean chce) {
        if (chce == kucam) return;
        if (o.toggleCrouch().get()) {
            o.keyShift.setDown(true); // w trybie "przełącz" każde wciśnięcie zmienia stan
        } else {
            o.keyShift.setDown(chce);
        }
        kucam = chce;
    }

    private int losuj(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }
}
