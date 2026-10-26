package com.example.ruch;

import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Losowe, małe spacery podczas czekania na branie:
 * - idzie na kucaka (shift) w losowym kierunku, najwyżej 1 blok od miejsca startu w każdą stronę,
 * - najczęściej chodzi na boki (lewo-prawo względem kamery), czasem w losowe miejsca,
 * - robi kilka kroków z krótkimi przerwami,
 * - zawsze wraca na miejsce startu (przed zarzuceniem wędki na pewno),
 * - na kucaka nie spadnie z krawędzi, a cel jest wybierany tylko na bezpiecznym, suchym podłożu.
 */
public class SpacerWokol {
    // --- Ustawienia (możesz je zmieniać) ---
    private static final double ZASIEG = 1.0;           // max odległość od startu w każdej osi (bloki)
    private static final int SPACER_CO_MIN = 80;        // spacer najwcześniej co 4 s (ticki)
    private static final int SPACER_CO_MAX = 240;      // najpóźniej co 12 s
    private static final double SZANSA_BOK = 0.65;      // szansa, że spacer będzie "na boki" (lewo-prawo)
    private static final int KROKI_BOK_MIN = 1;         // ile dodatkowych kroków na boki (w drugą stronę itd.)
    private static final int KROKI_BOK_MAX = 4;
    private static final double SZANSA_KOLEJNY_KROK = 0.55; // zwykły spacer: szansa na jeszcze jeden krok przed powrotem
    private static final double NA_MIEJSCU = 0.25;      // jak blisko startu = "na miejscu"

    public enum Tryb {
        SPACER,  // czekamy na branie - można chodzić
        WRACAJ,  // zaraz zarzucamy - wracamy na miejsce
        STOJ     // minigra - stoimy
    }

    private enum Stan { NIC, IDZIE, PAUZA, WRACA }

    private final Random rng = new Random();
    private double startX, startZ;
    private double celX, celZ;
    private Stan stan = Stan.NIC;
    private int doSpaceru;
    private int licznik;           // pauza / limit czasu kroku
    private int bezPostepu;        // ile ticków bez ruchu (utknął?)
    private double ostX, ostZ;
    private boolean trzyma = false; // czy bot trzyma klawisze ruchu
    private boolean kucam = false;
    private boolean kucaWPauzie;
    private boolean boczny;        // spacer na boki
    private int strona;            // +1 = w prawo, -1 = w lewo
    private int krokiBoczne;       // ile jeszcze kroków na boki

    public void start(LocalPlayer p) {
        startX = p.getX();
        startZ = p.getZ();
        stan = Stan.NIC;
        doSpaceru = losuj(SPACER_CO_MIN / 2, SPACER_CO_MAX / 2); // pierwszy spacer trochę wcześniej
    }

    public void tick(LocalPlayer p, Options o, Tryb tryb) {
        // jeśli serwer nas przeniósł daleko (teleport) - nowe miejsce staje się startem
        if (odleglosc(p, startX, startZ) > 3.0) {
            puscKlawisze(o);
            startX = p.getX();
            startZ = p.getZ();
            stan = Stan.NIC;
        }

        if (tryb == Tryb.STOJ) {
            puscKlawisze(o);
            return;
        }
        if (tryb == Tryb.WRACAJ && stan != Stan.WRACA) {
            if (odleglosc(p, startX, startZ) > NA_MIEJSCU) {
                zacznijPowrot();
            } else {
                puscKlawisze(o);
                stan = Stan.NIC;
                return;
            }
        }

        switch (stan) {
            case NIC -> {
                puscKlawisze(o);
                if (tryb == Tryb.SPACER && --doSpaceru <= 0) {
                    if (zacznijSpacer(p)) {
                        stan = Stan.IDZIE;
                        licznik = 60;
                    } else {
                        doSpaceru = losuj(40, 100); // brak bezpiecznego miejsca - spróbujemy później
                    }
                }
            }
            case IDZIE -> {
                if (idzDo(p, o, celX, celZ, 0.15) || --licznik <= 0 || utknal(p)) {
                    stan = Stan.PAUZA;
                    licznik = boczny ? losuj(4, 25) : losuj(10, 60);
                    kucaWPauzie = rng.nextDouble() < 0.6;
                }
            }
            case PAUZA -> {
                ustawRuch(o, false, false, false, false);
                ustawKucanie(o, kucaWPauzie);
                trzyma = kucaWPauzie;
                if (--licznik <= 0) {
                    boolean dalej;
                    if (boczny && krokiBoczne > 0) {
                        krokiBoczne--;
                        strona = -strona; // teraz w drugą stronę
                        dalej = wybierzCelBoczny(p);
                    } else {
                        dalej = !boczny && rng.nextDouble() < SZANSA_KOLEJNY_KROK && wybierzCel(p);
                    }
                    if (dalej) {
                        stan = Stan.IDZIE;
                        licznik = 60;
                    } else {
                        zacznijPowrot();
                    }
                }
            }
            case WRACA -> {
                if (idzDo(p, o, startX, startZ, 0.12) || --licznik <= 0) {
                    puscKlawisze(o);
                    stan = Stan.NIC;
                    doSpaceru = losuj(SPACER_CO_MIN, SPACER_CO_MAX);
                } else if (utknal(p)) {
                    // nie da się wrócić dokładnie - przyjmujemy obecne miejsce jako start
                    startX = p.getX();
                    startZ = p.getZ();
                    puscKlawisze(o);
                    stan = Stan.NIC;
                    doSpaceru = losuj(SPACER_CO_MIN, SPACER_CO_MAX);
                }
            }
        }
    }

    /** Czy stoimy na miejscu startu (można zarzucać). */
    public boolean naMiejscu(LocalPlayer p) {
        return stan == Stan.NIC && odleglosc(p, startX, startZ) <= NA_MIEJSCU;
    }

    /** Czy bot trzyma teraz klawisze ruchu/kucania (wtedy nie traktujemy ich jako Twojego ruchu). */
    public boolean trzymaKlawisze() {
        return trzyma;
    }

    /** Puszcza klawisze (tylko te, które trzymał bot) i kończy spacer. */
    public void przerwij(Options o) {
        puscKlawisze(o);
        if (stan == Stan.IDZIE || stan == Stan.PAUZA) {
            stan = Stan.WRACA; // gdy znowu będzie można, wróci na miejsce
            licznik = 80;
        }
    }

    // ------------------------------------------------------------------

    private void zacznijPowrot() {
        stan = Stan.WRACA;
        licznik = 80;
        bezPostepu = 0;
    }

    /** Wybiera rodzaj spaceru (na boki albo losowy) i pierwszy cel. */
    private boolean zacznijSpacer(LocalPlayer p) {
        boczny = rng.nextDouble() < SZANSA_BOK;
        if (boczny) {
            strona = rng.nextBoolean() ? 1 : -1;
            krokiBoczne = losuj(KROKI_BOK_MIN, KROKI_BOK_MAX);
            if (wybierzCelBoczny(p)) return true;
            strona = -strona;
            if (wybierzCelBoczny(p)) return true;
            boczny = false; // na boki się nie da - zwykły spacer
        }
        return wybierzCel(p);
    }

    /** Cel w lewo/prawo od miejsca startu (względem kierunku patrzenia), max ZASIEG w każdej osi. */
    private boolean wybierzCelBoczny(LocalPlayer p) {
        Level level = p.level();
        double yaw = Math.toRadians(p.getYRot());
        double prawoX = -Math.cos(yaw), prawoZ = -Math.sin(yaw);
        double przodX = -Math.sin(yaw), przodZ = Math.cos(yaw);
        for (int proba = 0; proba < 6; proba++) {
            double bok = zakres(0.45, ZASIEG) * strona;
            double przod = zakres(-0.15, 0.15); // odrobinę nierówno, jak człowiek
            double dx = Math.max(-ZASIEG, Math.min(ZASIEG, prawoX * bok + przodX * przod));
            double dz = Math.max(-ZASIEG, Math.min(ZASIEG, prawoZ * bok + przodZ * przod));
            double x = startX + dx, z = startZ + dz;
            if (bezpieczne(level, x, p.getY(), z) && Math.hypot(x - p.getX(), z - p.getZ()) > 0.3) {
                celX = x;
                celZ = z;
                bezPostepu = 0;
                return true;
            }
        }
        return false;
    }

    /** Losuje bezpieczny cel w promieniu ZASIEG od startu. */
    private boolean wybierzCel(LocalPlayer p) {
        Level level = p.level();
        for (int proba = 0; proba < 10; proba++) {
            double dx = zakres(-ZASIEG, ZASIEG);
            double dz = zakres(-ZASIEG, ZASIEG);
            if (Math.hypot(dx, dz) < 0.35) continue; // za blisko - to nie spacer
            double x = startX + dx, z = startZ + dz;
            if (bezpieczne(level, x, p.getY(), z) && Math.hypot(x - p.getX(), z - p.getZ()) > 0.3) {
                celX = x;
                celZ = z;
                bezPostepu = 0;
                return true;
            }
        }
        return false;
    }

    /** Twarde, suche podłoże pod nogami i wolne miejsce na nogi i głowę. */
    private static boolean bezpieczne(Level level, double x, double y, double z) {
        BlockPos stopy = BlockPos.containing(x, y + 0.1, z);
        BlockState pod = level.getBlockState(stopy.below());
        BlockState nogi = level.getBlockState(stopy);
        BlockState glowa = level.getBlockState(stopy.above());
        return !pod.isAir() && pod.getFluidState().isEmpty()
                && nogi.isAir() && glowa.isAir();
    }

    /** Idzie (na kucaka) w stronę punktu. Zwraca true, gdy dotarł. */
    private boolean idzDo(LocalPlayer p, Options o, double x, double z, double dokladnosc) {
        double dx = x - p.getX();
        double dz = z - p.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist <= dokladnosc) {
            ustawRuch(o, false, false, false, false);
            return true;
        }
        // przeliczenie kierunku świata na klawisze względem tego, gdzie patrzy kamera
        double yaw = Math.toRadians(p.getYRot());
        double przodX = -Math.sin(yaw), przodZ = Math.cos(yaw);
        double prawoX = -Math.cos(yaw), prawoZ = -Math.sin(yaw);
        double przod = (dx * przodX + dz * przodZ) / dist;
        double prawo = (dx * prawoX + dz * prawoZ) / dist;

        ustawKucanie(o, true);
        ustawRuch(o, przod > 0.38, przod < -0.38, prawo < -0.38, prawo > 0.38);
        trzyma = true;
        return false;
    }

    private boolean utknal(LocalPlayer p) {
        double ruch = Math.hypot(p.getX() - ostX, p.getZ() - ostZ);
        ostX = p.getX();
        ostZ = p.getZ();
        bezPostepu = ruch < 0.005 ? bezPostepu + 1 : 0;
        return bezPostepu > 20;
    }

    private void ustawRuch(Options o, boolean w, boolean s, boolean a, boolean d) {
        o.keyUp.setDown(w);
        o.keyDown.setDown(s);
        o.keyLeft.setDown(a);
        o.keyRight.setDown(d);
    }

    private void puscKlawisze(Options o) {
        if (trzyma || kucam) {
            ustawRuch(o, false, false, false, false);
            ustawKucanie(o, false);
        }
        trzyma = false;
    }

    private void ustawKucanie(Options o, boolean chce) {
        if (chce == kucam) return;
        if (o.toggleCrouch().get()) {
            o.keyShift.setDown(true); // tryb "przełącz": każde wciśnięcie zmienia stan
        } else {
            o.keyShift.setDown(chce);
        }
        kucam = chce;
    }

    private static double odleglosc(LocalPlayer p, double x, double z) {
        return Math.hypot(p.getX() - x, p.getZ() - z);
    }

    private double zakres(double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    private int losuj(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }
}
