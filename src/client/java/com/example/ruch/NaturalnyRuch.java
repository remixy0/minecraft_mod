package com.example.ruch;

import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Naturalnie wyglądający ruch kamery podczas łowienia - naśladuje rękę na myszce:
 *
 * - ruchy są pojedynczymi "pociągnięciami" myszki: szybki start, płynne wyhamowanie (profil minimum-jerk),
 *   lekko po łuku, a nie po prostej,
 * - czas ruchu zależy od odległości (prawo Fittsa): małe ruchy są krótkie, duże trwają dłużej,
 * - przy dużych ruchach ręka lekko "przestrzeliwuje" cel i po chwili się poprawia,
 * - między ruchami są przerwy (człowiek nie rusza myszką bez przerwy),
 * - cały czas jest delikatne drżenie ręki,
 * - co jakiś czas spojrzenie w bok (czasem w jedną, potem w drugą stronę) albo pod nogi / na pasek,
 * - podczas minigry ruchy są mniejsze (skupienie), bez rozglądania się.
 *
 * Wszystko dzieje się względem "kotwicy" - kierunku, w którym patrzyłeś po wpisaniu /ry,
 * a przed każdym zarzuceniem kamera wraca w okolice kotwicy, więc spławik leci w to samo miejsce.
 */
public class NaturalnyRuch {
    // --- Ustawienia (możesz je zmieniać) ---
    private static final float DRYF_YAW = 14f;           // zwykłe ruchy: max odchylenie poziome od środka (stopnie)
    private static final float DRYF_PITCH = 7f;          // zwykłe ruchy: max odchylenie pionowe
    private static final float KOLYSANIE_YAW = 1.8f;     // ciągłe, powolne kołysanie głowy (nawet w przerwach)
    private static final float KOLYSANIE_PITCH = 0.9f;
    private static final float ROZGLAD_YAW_MIN = 25f;    // spojrzenie w bok: od ... stopni
    private static final float ROZGLAD_YAW_MAX = 70f;    // ... do ... stopni
    private static final int ROZGLAD_CO_MIN = 300;       // rozglądanie najwcześniej co 15 s (ticki)
    private static final int ROZGLAD_CO_MAX = 900;       // najpóźniej co 45 s
    private static final double SZANSA_DRUGA_STRONA = 0.3; // po spojrzeniu w bok - spojrzenie jeszcze w drugą stronę
    private static final double SZANSA_POD_NOGI = 0.2;     // zamiast w bok - spojrzenie w dół (pod nogi / na pasek)
    private static final float DRZENIE = 0.35f;          // siła drżenia ręki (stopnie)
    private static final float GOTOWY_YAW = 4f;          // jak blisko środka trzeba być, żeby zarzucić
    private static final float GOTOWY_PITCH = 3f;

    private final Random rng = new Random();
    private final ArrayDeque<Ruch> kolejka = new ArrayDeque<>();

    private float kotwicaYaw, kotwicaPitch;   // środek (kierunek po /ry)
    private float offYaw, offPitch;           // aktualne przesunięcie względem środka
    private Ruch ruch;                        // trwający ruch
    private int pauza;                        // ile ticków stać bez ruchu
    private int doRozgladania;                // ticki do następnego rozglądania
    private boolean pozaSrodkiem;             // trwa rozglądanie się
    private boolean wraca;                    // trwa powrót do środka
    private boolean wReakcji;                 // trwa reakcja na obrót przez serwer
    private float drzYaw, drzPitch, drzVYaw, drzVPitch; // drżenie ręki
    private float kolYaw, kolPitch;                       // aktualne kołysanie
    private float faza1, faza2, czest1, czest2;           // parametry kołysania
    private long czas;

    private static final class Ruch {
        final float celYaw, celPitch;
        final float tempo;      // 1 = sprawny ruch, >1 = leniwy, wolniejszy
        final int pauzaPo;      // przerwa po ruchu (ticki)
        final boolean korekta;  // ruch poprawiający (bez kolejnego przestrzelenia)
        float startYaw, startPitch, luk;
        int czas, uplynelo;

        Ruch(float celYaw, float celPitch, float tempo, int pauzaPo, boolean korekta) {
            this.celYaw = celYaw;
            this.celPitch = celPitch;
            this.tempo = tempo;
            this.pauzaPo = pauzaPo;
            this.korekta = korekta;
        }
    }

    public void start(float yaw, float pitch) {
        kotwicaYaw = yaw;
        kotwicaPitch = pitch;
        offYaw = offPitch = 0;
        drzYaw = drzPitch = drzVYaw = drzVPitch = 0;
        kolYaw = kolPitch = 0;
        faza1 = zakres(0f, 6.28f);
        faza2 = zakres(0f, 6.28f);
        czest1 = 6.2832f / zakres(80f, 160f);  // jeden "wahnięcie" co 4-8 s
        czest2 = 6.2832f / zakres(45f, 100f);
        czas = 0;
        ruch = null;
        kolejka.clear();
        pauza = losuj(5, 20);
        doRozgladania = losuj(ROZGLAD_CO_MIN, ROZGLAD_CO_MAX);
        pozaSrodkiem = false;
        wraca = false;
        wReakcji = false;
    }

    /** Serwer tylko lekko poprawił obrót (np. korekta pozycji) - przejmujemy go po cichu, bez skoku kamery. */
    public void przejmij(float yaw, float pitch) {
        offYaw += zawin(yaw - (kotwicaYaw + offYaw + drzYaw + kolYaw));
        offPitch = pitch - kotwicaPitch - drzPitch - kolPitch;
        ruch = null; // trwający ruch zaczął się z innego miejsca - przerywamy go
    }

    /**
     * Serwer obrócił głowę (komenda / teleport). Reagujemy jak człowiek:
     * chwila na zauważenie, powrót wzrokiem na łowisko, rozejrzenie się, powrót do łowienia.
     */
    public void reakcja(float yaw, float pitch) {
        przejmij(yaw, pitch);
        kolejka.clear();
        pozaSrodkiem = false;
        wraca = false;
        wReakcji = true;
        pauza = losuj(6, 16); // człowiek potrzebuje chwili, żeby zauważyć
        // 1) powrót na łowisko
        kolejka.add(new Ruch(zakres(-3f, 3f), zakres(-2f, 2f), 1.0f, losuj(5, 15), false));
        // 2) rozejrzenie się ("co się stało?")
        int ile = losuj(1, 3);
        for (int i = 0; i < ile; i++) {
            float strona = rng.nextBoolean() ? 1f : -1f;
            kolejka.add(new Ruch(strona * zakres(15f, 60f), zakres(-20f, 12f), zakres(0.9f, 1.3f), losuj(8, 25), false));
        }
        // 3) powrót do łowienia
        kolejka.add(new Ruch(zakres(-2f, 2f), zakres(-1.2f, 1.2f), 1.1f, losuj(10, 20), false));
        doRozgladania = Math.max(doRozgladania, losuj(200, 400)); // zaraz po tym nie rozglądamy się znowu
    }

    /** Rozglądanie się w nowym miejscu (np. po teleporcie): kilka spojrzeń w różne strony. */
    public void rozejrzyjSie(float yaw, float pitch) {
        przejmij(yaw, pitch);
        kolejka.clear();
        ruch = null;
        pozaSrodkiem = false;
        wraca = false;
        wReakcji = true;
        pauza = losuj(8, 20); // chwila na zorientowanie się
        float strona = rng.nextBoolean() ? 1f : -1f;
        float y = offYaw;
        int ile = losuj(2, 4);
        for (int i = 0; i < ile; i++) {
            y += strona * zakres(30f, 90f);
            if (rng.nextDouble() < 0.6) strona = -strona;
            kolejka.add(new Ruch(y, zakres(-25f, 15f) - kotwicaPitch, zakres(1.0f, 1.5f), losuj(10, 30), false));
        }
    }

    public boolean czyReakcjaTrwa() {
        return wReakcji;
    }

    /**
     * Wywoływane co tick, gdy bot jest aktywny.
     *
     * @param mozeRozgladac true gdy spławik jest w wodzie i nie trwa minigra - wtedy wolno się rozglądać
     */
    public void tick(LocalPlayer player, boolean mozeRozgladac) {
        if (mozeRozgladac) {
            doRozgladania--;
        } else if (pozaSrodkiem && !wraca) {
            wrocDoSrodka(); // zaczęło się branie / minigra - wracamy wzrokiem
        }

        if (ruch != null) {
            krokRuchu();
        } else if (pauza > 0) {
            pauza--;
        } else if (!kolejka.isEmpty()) {
            startRuchu(kolejka.pollFirst());
        } else {
            // wszystko zrobione - planujemy coś nowego
            pozaSrodkiem = false;
            wraca = false;
            wReakcji = false;
            if (mozeRozgladac && doRozgladania <= 0) {
                zaplanujRozgladanie();
            } else {
                zaplanujDryf(mozeRozgladac ? 1f : 0.5f);
            }
            startRuchu(kolejka.pollFirst());
        }

        // drżenie ręki: powolny, losowy "szum" o małej amplitudzie
        float k = DRZENIE / 0.25f;
        drzVYaw = (drzVYaw + (rng.nextFloat() - 0.5f) * 0.05f * k) * 0.85f;
        drzVPitch = (drzVPitch + (rng.nextFloat() - 0.5f) * 0.035f * k) * 0.85f;
        drzYaw = ogranicz((drzYaw + drzVYaw) * 0.96f, DRZENIE);
        drzPitch = ogranicz((drzPitch + drzVPitch) * 0.96f, DRZENIE * 0.7f);

        // ciągłe kołysanie - dwie nałożone wolne fale, więc głowa prawie nigdy nie stoi idealnie w miejscu
        czas++;
        kolYaw = KOLYSANIE_YAW * (0.6f * (float) Math.sin(faza1 + czas * czest1) + 0.4f * (float) Math.sin(faza2 + czas * czest2));
        kolPitch = KOLYSANIE_PITCH * (float) Math.sin(faza2 * 1.3f + czas * czest2 * 0.8f);

        player.setYRot(kotwicaYaw + offYaw + drzYaw + kolYaw);
        player.setXRot(Math.max(-90f, Math.min(90f, kotwicaPitch + offPitch + drzPitch + kolPitch)));
    }

    /** Przerywa rozglądanie i płynnie wraca w okolice środka (np. przed zarzuceniem). */
    public void wrocDoSrodka() {
        if (ruch == null && kolejka.isEmpty() && Math.abs(offYaw) <= GOTOWY_YAW && Math.abs(offPitch) <= GOTOWY_PITCH) {
            return; // już jesteśmy blisko
        }
        kolejka.clear();
        pauza = 0;
        ruch = null;
        wraca = true;
        startRuchu(new Ruch(zakres(-2f, 2f), zakres(-1.2f, 1.2f), 1.0f, losuj(2, 8), false));
    }

    /** Czy kamera stoi w okolicy środka, tak że można zarzucić wędkę. */
    public boolean czyGotowyDoZarzucenia() {
        return ruch == null && kolejka.isEmpty()
                && Math.abs(offYaw) <= GOTOWY_YAW && Math.abs(offPitch) <= GOTOWY_PITCH;
    }

    // ------------------------------------------------------------------

    private void zaplanujDryf(float skala) {
        if (rng.nextFloat() < 0.25f) {
            // drobny ruch "z nudów" niedaleko obecnej pozycji
            float y = ogranicz(offYaw + zakres(-3.5f, 3.5f), DRYF_YAW * skala);
            float p = ogranicz(offPitch + zakres(-2f, 2f), DRYF_PITCH * skala);
            kolejka.add(new Ruch(y, p, zakres(1.0f, 1.6f), losuj(2, 15), false));
        } else {
            // spokojne przesunięcie wzroku w inne miejsce wokół spławika
            kolejka.add(new Ruch(zakres(-DRYF_YAW, DRYF_YAW) * skala, zakres(-DRYF_PITCH, DRYF_PITCH) * skala,
                    zakres(1.8f, 3.0f), losuj(3, 20), false));
        }
    }

    private void zaplanujRozgladanie() {
        doRozgladania = losuj(ROZGLAD_CO_MIN, ROZGLAD_CO_MAX);
        pozaSrodkiem = true;
        if (rng.nextDouble() < SZANSA_POD_NOGI) {
            // spojrzenie w dół - pod nogi / na pasek z przedmiotami
            float celPitchAbs = zakres(40f, 65f);
            kolejka.add(new Ruch(offYaw + zakres(-12f, 12f), celPitchAbs - kotwicaPitch, 1.0f, losuj(15, 45), false));
        } else {
            float strona = rng.nextBoolean() ? 1f : -1f;
            kolejka.add(new Ruch(strona * zakres(ROZGLAD_YAW_MIN, ROZGLAD_YAW_MAX), zakres(-15f, 8f), 1.0f, losuj(20, 70), false));
            if (rng.nextDouble() < SZANSA_DRUGA_STRONA) {
                kolejka.add(new Ruch(-strona * zakres(ROZGLAD_YAW_MIN * 0.7f, ROZGLAD_YAW_MAX * 0.8f), zakres(-12f, 6f),
                        1.1f, losuj(15, 50), false));
            }
        }
        // powrót w okolice środka
        kolejka.add(new Ruch(zakres(-2.5f, 2.5f), zakres(-1.5f, 1.5f), 1.2f, losuj(10, 30), false));
    }

    private void startRuchu(Ruch r) {
        float dy = r.celYaw - offYaw;
        float dp = r.celPitch - offPitch;
        float dist = (float) Math.hypot(dy, dp);

        // przy dużych ruchach ręka często lekko przestrzeliwuje i potem się poprawia
        if (!r.korekta && dist > 12f && rng.nextFloat() < 0.7f) {
            float przestrzal = zakres(0.04f, 0.12f);
            kolejka.addFirst(new Ruch(r.celYaw, r.celPitch, 0.8f, r.pauzaPo, true));
            r = new Ruch(r.celYaw + dy * przestrzal, r.celPitch + dp * przestrzal, r.tempo, losuj(1, 4), true);
            dy = r.celYaw - offYaw;
            dp = r.celPitch - offPitch;
            dist = (float) Math.hypot(dy, dp);
        }

        r.startYaw = offYaw;
        r.startPitch = offPitch;
        // prawo Fittsa: dłuższy ruch = więcej czasu, z losowym rozrzutem
        double bazowy = 3 + 2.2 * (Math.log(1 + dist / 2.0) / Math.log(2));
        r.czas = Math.max(2, (int) Math.round(bazowy * r.tempo * zakres(0.85f, 1.2f)));
        r.luk = dist * zakres(-0.08f, 0.08f); // lekki łuk zamiast prostej linii
        r.uplynelo = 0;
        ruch = r;
    }

    private void krokRuchu() {
        Ruch r = ruch;
        r.uplynelo++;
        float t = Math.min(1f, r.uplynelo / (float) r.czas);
        float s = t * t * t * (10f - 15f * t + 6f * t * t); // minimum-jerk: płynny start i hamowanie

        float dy = r.celYaw - r.startYaw;
        float dp = r.celPitch - r.startPitch;
        float dist = (float) Math.hypot(dy, dp);
        float luk = r.luk * (float) Math.sin(Math.PI * t);
        float px = dist > 0.001f ? -dp / dist : 0f;
        float py = dist > 0.001f ? dy / dist : 0f;

        offYaw = r.startYaw + dy * s + px * luk;
        offPitch = r.startPitch + dp * s + py * luk;

        if (t >= 1f) {
            pauza = r.pauzaPo;
            ruch = null;
        }
    }

    private static float zawin(float d) {
        d = d % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float ogranicz(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    private float zakres(float min, float max) {
        return min + rng.nextFloat() * (max - min);
    }

    private int losuj(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }
}
