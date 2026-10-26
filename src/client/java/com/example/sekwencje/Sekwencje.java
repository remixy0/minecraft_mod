package com.example.sekwencje;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Sekwencja startowa i końcowa - osobna dla każdego nicku - oraz hasło do /login.
 * <p>
 * Sekwencja to lista kroków, jeden w linii, np.:
 * <pre>
 * serwer skyup.pl
 * czekaj 5
 * login
 * home kopalnia
 * kop
 * </pre>
 * Zapis: config/modid-sekwencje-&lt;nick&gt;.properties. Wykonywanie: {@link #tick} w każdym ticku gry.
 */
public class Sekwencje {

    /** Akcje gry (łowienie, kopanie, slot...), które wykonuje główna klasa moda. Zwraca opis wyniku. */
    public interface Wykonawca {
        String wykonaj(String akcja, String arg);
    }

    public record Krok(String typ, String arg, String linia) {
    }

    public static final String POMOC = String.join("\n",
            "`serwer <adres|nazwa>` – wejdź na serwer",
            "`czekaj <sekundy>` – poczekaj",
            "`zaloguj` – hasło w okno logowania + wejście do gry (prawy klik przedmiotem → „Graj!”)",
            "`login` – tylko hasło (okno logowania albo /login)",
            "`graj [slot]` – prawy klik przedmiotem i klik w „Graj!” w menu",
            "`uzyj [slot]` / `kliknij <napis|#nr>` / `zamknij` – prawy klik, klik w menu, zamknij menu",
            "`home <nazwa>` – /home <nazwa>",
            "`komenda <tekst>` albo `/<tekst>` – dowolna komenda ({haslo} = hasło)",
            "`slot <1-9>` – weź przedmiot z paska",
            "`kop` / `lowienie` / `turbo <n>` / `w` / `stop`",
            "`wyjdz` – wyjdź z serwera",
            "`# ...` – komentarz");

    public static final String PRZYKLAD = String.join("\n",
            "# Przykład - usuń # na początku linii, żeby krok działał:",
            "# serwer skyup.pl",
            "# czekaj 5",
            "# zaloguj",
            "# czekaj 3",
            "# home kopalnia",
            "# czekaj 6",
            "# kop");

    public static final String PRZYKLAD_SERWER = String.join("\n",
            "# Przykład (bot jest już na serwerze) - usuń # na początku linii:",
            "# home kopalnia",
            "# czekaj 6",
            "# kop");

    public static final String PRZYKLAD_KONIEC = String.join("\n",
            "# Przykład - usuń # na początku linii:",
            "# stop",
            "# czekaj 2",
            "# komenda spawn",
            "# czekaj 5",
            "# wyjdz");

    public static String przyklad(String jaka) {
        return switch (jaka) {
            case SERWER -> PRZYKLAD_SERWER;
            case KONIEC -> PRZYKLAD_KONIEC;
            default -> PRZYKLAD;
        };
    }

    private static final int MAX_KROKOW = 50;
    private static final int CZEKAJ_NA_GRE = 60 * 20;    // max 60 s na wejście na serwer
    private static final int CZEKAJ_NA_WYJSCIE = 15 * 20;
    private static final int PO_WEJSCIU = 3 * 20;        // chwila po wejściu na serwer, zanim pójdą komendy

    private final Path katalog = FabricLoader.getInstance().getConfigDir();
    private final Logowanie logowanie = new Logowanie();
    private static final int CZEKAJ_NA_OKNO_LOGOWANIA = 15 * 20;
    private static final int CZEKAJ_NA_MENU = 5 * 20;

    // ---- zapisane dane (dostęp z kilku wątków -> synchronized) ----
    private String nickDanych = null;
    private String start = "", startSerwer = "", koniec = "", haslo = "", harmonogram = "";

    // ---- harmonogram (tylko wątek gry) ----
    private int licznikHarmonogramu = 0;
    private String ostatniaMinuta = null;
    private String harmonogramZParsowania = null;
    private List<Harmonogram.Wpis> wpisy = List.of();

    // ---- wykonywanie (tylko wątek gry) ----
    private List<Krok> kroki = null;
    private String nazwa = "";
    private int nr = 0;
    private int faza = 0;
    private int pauza = 0;
    private int licznik = 0;
    private boolean bylPozaGra = false;
    private volatile String postep = null;

    // ------------------------------------------------------------------
    // Dane
    // ------------------------------------------------------------------

    /** Rodzaje sekwencji: START = od zera (z menu gry), SERWER = start będąc już na serwerze, KONIEC = końcowa. */
    public static final String START = "start", SERWER = "serwer", KONIEC = "koniec";

    public static String nazwa(String jaka) {
        return switch (jaka) {
            case SERWER -> "startowa (na serwerze)";
            case KONIEC -> "końcowa";
            default -> "startowa (od zera)";
        };
    }

    public synchronized String tekst(String nick, String jaka) {
        wczytaj(nick);
        return switch (jaka) {
            case SERWER -> startSerwer;
            case KONIEC -> koniec;
            default -> start;
        };
    }

    public String tekst(String nick, boolean startowa) {
        return tekst(nick, startowa ? START : KONIEC);
    }

    public synchronized boolean maHaslo(String nick) {
        wczytaj(nick);
        return !haslo.isEmpty();
    }

    public synchronized String haslo(String nick) {
        wczytaj(nick);
        return haslo;
    }

    public synchronized void ustawHaslo(String nick, String h) {
        wczytaj(nick);
        haslo = h == null ? "" : h.trim();
        zapisz();
    }

    public synchronized String tekstHarmonogramu(String nick) {
        wczytaj(nick);
        return harmonogram;
    }

    /** Zapisuje harmonogram. Zwraca null, gdy wszystko ok, albo opis błędów (wtedy nic nie jest zapisywane). */
    public synchronized String zapiszHarmonogram(String nick, String tekst) {
        wczytaj(nick);
        List<String> bledy = new ArrayList<>();
        Harmonogram.parsuj(tekst, bledy);
        if (!bledy.isEmpty()) return String.join("\n", bledy);
        harmonogram = tekst == null ? "" : tekst.replace("\r", "").strip();
        zapisz();
        return null;
    }

    /** Np. "jutro 07:30 → start" albo null, gdy harmonogram jest pusty. */
    public String nastepnyHarmonogram(String nick) {
        return Harmonogram.opisNastepnego(wpisy(nick), java.time.LocalDateTime.now());
    }

    private synchronized List<Harmonogram.Wpis> wpisy(String nick) {
        wczytaj(nick);
        if (!harmonogram.equals(harmonogramZParsowania)) {
            harmonogramZParsowania = harmonogram;
            wpisy = Harmonogram.parsuj(harmonogram, new ArrayList<>());
        }
        return wpisy;
    }

    /** Co tick (sprawdza raz na sekundę): gdy wybije godzina z harmonogramu - uruchamia, co trzeba. */
    public void tickHarmonogramu(Minecraft mc, String nick, Consumer<String> raport) {
        if (++licznikHarmonogramu < 20) return;
        licznikHarmonogramu = 0;
        java.time.LocalDateTime t = java.time.LocalDateTime.now().withSecond(0).withNano(0);
        String minuta = t.toString();
        if (minuta.equals(ostatniaMinuta)) return;
        ostatniaMinuta = minuta;
        for (Harmonogram.Wpis w : wpisy(nick)) {
            if (!w.pasuje(t)) continue;
            String godz = String.format(Locale.ROOT, "%02d:%02d", w.godzina(), w.minuta());
            String seq = Harmonogram.sekwencja(w.co());
            String wynik;
            if (seq != null) {
                wynik = seq.equals("auto") ? uruchomStart(mc, nick) : uruchom(nick, seq);
            } else {
                List<String> b = new ArrayList<>();
                List<Krok> k = parsuj(w.co().replace(';', '\n'), b);
                wynik = b.isEmpty() && !k.isEmpty() ? uruchom(k, "harmonogram " + godz) : "❌ " + String.join(", ", b);
            }
            raport.accept("⏰ " + godz + " (harmonogram): " + wynik);
        }
    }

    /** Zapisuje sekwencję. Zwraca null, gdy wszystko ok, albo opis błędów (wtedy nic nie jest zapisywane). */
    public synchronized String zapisz(String nick, String jaka, String tekst) {
        wczytaj(nick);
        List<String> bledy = new ArrayList<>();
        parsuj(tekst, bledy);
        if (!bledy.isEmpty()) return String.join("\n", bledy);
        String t = tekst == null ? "" : tekst.replace("\r", "").strip();
        switch (jaka) {
            case SERWER -> startSerwer = t;
            case KONIEC -> koniec = t;
            default -> start = t;
        }
        zapisz();
        return null;
    }

    public String zapisz(String nick, boolean startowa, String tekst) {
        return zapisz(nick, startowa ? START : KONIEC, tekst);
    }

    private void wczytaj(String nick) {
        if (nick == null || nick.equals(nickDanych)) return;
        nickDanych = nick;
        start = startSerwer = koniec = haslo = harmonogram = "";
        try {
            Path p = plik(nick);
            if (Files.exists(p)) {
                Properties pr = new Properties();
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    pr.load(r);
                }
                start = pr.getProperty("start", "");
                startSerwer = pr.getProperty("startSerwer", "");
                koniec = pr.getProperty("koniec", "");
                haslo = pr.getProperty("haslo", "");
                harmonogram = pr.getProperty("harmonogram", "");
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się wczytać sekwencji: " + e);
        }
    }

    private void zapisz() {
        if (nickDanych == null) return;
        try {
            Properties pr = new Properties();
            pr.setProperty("start", start);
            pr.setProperty("startSerwer", startSerwer);
            pr.setProperty("koniec", koniec);
            pr.setProperty("haslo", haslo);
            pr.setProperty("harmonogram", harmonogram);
            Files.createDirectories(katalog);
            try (Writer w = Files.newBufferedWriter(plik(nickDanych), StandardCharsets.UTF_8)) {
                pr.store(w, "Sekwencje startowa/koncowa i haslo do /login dla " + nickDanych + " (NIE udostepniaj)");
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się zapisać sekwencji: " + e);
        }
    }

    private Path plik(String nick) {
        return katalog.resolve("modid-sekwencje-" + nick.replaceAll("[^A-Za-z0-9_-]", "_") + ".properties");
    }

    // ------------------------------------------------------------------
    // Parsowanie
    // ------------------------------------------------------------------

    public static List<Krok> parsuj(String tekst, List<String> bledy) {
        List<Krok> wynik = new ArrayList<>();
        if (tekst == null) return wynik;
        String[] linie = tekst.replace("\r", "").split("\n");
        for (int i = 0; i < linie.length; i++) {
            String l = linie[i].strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            Krok k = krok(l);
            if (k == null) {
                bledy.add("linia " + (i + 1) + ": nie rozumiem `" + l + "`");
            } else if (k.typ().startsWith("!")) {
                bledy.add("linia " + (i + 1) + ": " + k.typ().substring(1));
            } else if (k.typ().equals("zaloguj")) {
                // "zaloguj" = hasło + wejście do gry z lobby
                wynik.add(new Krok("login", "", l));
                wynik.add(new Krok("graj", k.arg(), l));
            } else {
                wynik.add(k);
            }
        }
        if (wynik.size() > MAX_KROKOW) bledy.add("za dużo kroków (max " + MAX_KROKOW + ")");
        return wynik;
    }

    /** Jedna linia -> krok. Krok z typem "!opis" oznacza błąd w argumencie. */
    private static Krok krok(String l) {
        if (l.startsWith("/")) {
            String k = l.substring(1).strip();
            return k.isEmpty() ? blad("pusta komenda", l) : new Krok("komenda", k, l);
        }
        String[] cz = l.split("\\s+", 2);
        String slowo = cz[0].toLowerCase(Locale.ROOT)
                .replace('ą', 'a').replace('ć', 'c').replace('ę', 'e').replace('ł', 'l').replace('ń', 'n')
                .replace('ó', 'o').replace('ś', 's').replace('ź', 'z').replace('ż', 'z');
        String arg = cz.length > 1 ? cz[1].strip() : "";
        return switch (slowo) {
            case "serwer", "server", "wejdz", "polacz", "join" ->
                    arg.isEmpty() ? blad("podaj adres serwera, np. `serwer skyup.pl`", l) : new Krok("serwer", arg, l);
            case "wyjdz", "rozlacz", "leave", "quit" -> new Krok("wyjdz", "", l);
            case "czekaj", "poczekaj", "wait", "sleep" -> {
                try {
                    double s = Double.parseDouble(arg.replace(',', '.').replace("s", ""));
                    yield s >= 0 && s <= 3600 ? new Krok("czekaj", String.valueOf(s), l) : blad("czas 0-3600 sekund", l);
                } catch (NumberFormatException e) {
                    yield blad("podaj liczbę sekund, np. `czekaj 5`", l);
                }
            }
            case "login", "haslo" -> new Krok("login", "", l);
            case "zaloguj", "zaloguj_i_graj" -> arg.isEmpty() || liczba(arg, 1, 9) > 0
                    ? new Krok("zaloguj", arg, l) : blad("zaloguj albo zaloguj <slot 1-9 przedmiotu z menu>", l);
            case "graj", "lobby", "wejscie" -> arg.isEmpty() || liczba(arg, 1, 9) > 0
                    ? new Krok("graj", arg, l) : blad("graj albo graj <slot 1-9 przedmiotu z menu>", l);
            case "uzyj", "prawy", "ppm" -> arg.isEmpty() || liczba(arg, 1, 9) > 0
                    ? new Krok("uzyj", arg, l) : blad("uzyj albo uzyj <slot 1-9>", l);
            case "kliknij", "lewy", "lpm" -> arg.isEmpty() ? blad("podaj napis z przycisku albo numer slotu, np. `kliknij Graj` / `kliknij #13`", l)
                    : new Krok("kliknij", arg, l);
            case "zamknij" -> new Krok("zamknij", "", l);
            case "komenda", "cmd", "command" ->
                    arg.isEmpty() ? blad("podaj komendę, np. `komenda spawn`", l) : new Krok("komenda", arg.startsWith("/") ? arg.substring(1) : arg, l);
            case "home", "dom" -> arg.isEmpty() ? blad("podaj nazwę home, np. `home kopalnia`", l) : new Krok("home", arg, l);
            case "kop", "kopanie", "kopie" -> new Krok("kop", "", l);
            case "lowienie", "low", "lowi", "ryby", "start" -> new Krok("lowienie", "", l);
            case "turbo" -> liczba(arg, 1, 100) < 0 ? blad("turbo 1-100, np. `turbo 5`", l) : new Krok("turbo", arg, l);
            case "slot" -> liczba(arg, 1, 9) < 0 ? blad("slot 1-9, np. `slot 2`", l) : new Krok("slot", arg, l);
            case "w", "wedka" -> new Krok("w", "", l);
            case "stop" -> new Krok("stop", "", l);
            default -> null;
        };
    }

    private static Krok blad(String opis, String linia) {
        return new Krok("!" + opis, "", linia);
    }

    private static int liczba(String t, int min, int max) {
        try {
            int v = Integer.parseInt(t.trim());
            return v >= min && v <= max ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Wykonywanie (wątek gry)
    // ------------------------------------------------------------------

    /** Uruchamia zapisaną sekwencję. Zwraca opis do pokazania. */
    public String uruchom(String nick, String jaka) {
        String nazwaSek = nazwa(jaka);
        List<String> bledy = new ArrayList<>();
        List<Krok> k = parsuj(tekst(nick, jaka), bledy);
        if (!bledy.isEmpty()) return "❌ sekwencja " + nazwaSek + " ma błędy: " + bledy.get(0);
        if (k.isEmpty()) return "ℹ️ sekwencja " + nazwaSek + " jest pusta - ustaw ją w ⚙ Ustawienia";
        return uruchom(k, "sekwencja " + nazwaSek);
    }

    public String uruchom(String nick, boolean startowa) {
        return uruchom(nick, startowa ? START : KONIEC);
    }

    /**
     * Start "sam wybiera": na serwerze - sekwencja "na serwerze" (a gdy pusta - "od zera"),
     * w menu gry - sekwencja "od zera".
     */
    public String uruchomStart(Minecraft mc, String nick) {
        boolean naSerwerze = Serwery.wGrze(mc);
        boolean jestSerwer = !tekst(nick, SERWER).isBlank(), jestZero = !tekst(nick, START).isBlank();
        if (naSerwerze && jestSerwer) return uruchom(nick, SERWER);
        if (!naSerwerze && !jestZero && jestSerwer) {
            return "ℹ️ jestem w menu gry, a jest ustawiona tylko sekwencja „na serwerze” – ustaw też „od zera” w ⚙ Ustawienia";
        }
        return uruchom(nick, START);
    }

    /** Uruchamia podane kroki (np. jeden krok "serwer" z panelu). */
    public String uruchom(List<Krok> k, String jakaNazwa) {
        String bylo = kroki != null ? " (przerwano: " + nazwa + ")" : "";
        kroki = new ArrayList<>(k);
        nazwa = jakaNazwa;
        nr = 0;
        faza = 0;
        pauza = 0;
        licznik = 0;
        bylPozaGra = false;
        aktualizujPostep();
        return "▶ " + nazwa + " uruchomiona (" + k.size() + " " + krokow(k.size()) + ")" + bylo;
    }

    public boolean trwa() {
        return kroki != null;
    }

    /** Np. "▶ sekwencja startowa: krok 2/5 – czekaj 5", albo null, gdy nic nie trwa. */
    public String postep() {
        return postep;
    }

    public String przerwij() {
        if (kroki == null) return "ℹ️ żadna sekwencja nie trwa";
        String n = nazwa;
        kroki = null;
        postep = null;
        return "⏹ przerwano: " + n;
    }

    /** Logowanie teraz (przycisk w panelu): hasło w okno serwera, a gdy okna nie ma - /login. */
    public String zaloguj(Minecraft mc, String nick) {
        if (!Serwery.wGrze(mc)) return "❌ nie jestem na serwerze";
        String h = haslo(nick);
        if (h.isEmpty()) return "❌ brak hasła - ustaw je w ⚙ Sekwencje → 🔑 Hasło";
        Logowanie.Okno o = Logowanie.znajdz(mc);
        if (o != null) {
            logowanie.wpisz(o, h);
            return "🔑 wpisałem hasło w okno logowania i kliknąłem „" + o.przycisk().getMessage().getString() + "”";
        }
        mc.player.connection.sendCommand("login " + h);
        return "🔑 nie ma okna logowania - wysłałem /login ••••";
    }

    /** Co tick: automatyczne logowanie, gdy serwer pokaże okno z hasłem (działa też bez sekwencji). */
    public void tickLogowania(Minecraft mc, String nick, Consumer<String> raport) {
        logowanie.tick(mc, haslo(nick), raport);
    }

    public void tick(Minecraft mc, String nick, Wykonawca w, Consumer<String> raport) {
        if (kroki == null) return;
        if (pauza > 0) {
            pauza--;
            return;
        }
        if (nr >= kroki.size()) {
            String n = nazwa;
            kroki = null;
            postep = null;
            raport.accept("✅ " + n + " zakończona");
            return;
        }
        Krok k = kroki.get(nr);
        boolean wGrze = Serwery.wGrze(mc);
        try {
            switch (faza) {
                case 0 -> {
                    boolean potrzebaGry = !k.typ().equals("czekaj") && !k.typ().equals("serwer") && !k.typ().equals("wyjdz");
                    if (potrzebaGry && !wGrze) {
                        bylPozaGra = true;
                        if (++licznik > CZEKAJ_NA_GRE) blad(raport, k, "nie jestem na serwerze");
                        return;
                    }
                    if (potrzebaGry && bylPozaGra) {
                        bylPozaGra = false;
                        licznik = 0;
                        pauza = PO_WEJSCIU;
                        return;
                    }
                    wykonajKrok(mc, nick, k, w, raport);
                }
                case 1 -> { // serwer: wyszliśmy z poprzedniego, czekamy na menu
                    if (mc.level == null) {
                        String o = Serwery.polacz(mc, k.arg());
                        if (o.startsWith("❌")) {
                            blad(raport, k, o.substring(2));
                            return;
                        }
                        faza = 2;
                        licznik = 0;
                    } else if (++licznik > CZEKAJ_NA_WYJSCIE) {
                        blad(raport, k, "nie udało się wyjść z poprzedniego serwera");
                    }
                }
                case 2 -> { // serwer: czekamy, aż wejdziemy
                    if (wGrze) {
                        licznik = 0;
                        nastepny(PO_WEJSCIU);
                    } else if (mc.gui.screen() instanceof DisconnectedScreen) {
                        blad(raport, k, "serwer rozłączył (" + mc.gui.screen().getTitle().getString() + ")");
                    } else if (++licznik > CZEKAJ_NA_GRE) {
                        blad(raport, k, "nie udało się wejść na serwer w 60 s");
                    }
                }
                case 4 -> { // login: czekamy na okno logowania
                    String h = haslo(nick);
                    if (!wGrze) {
                        blad(raport, k, "rozłączono z serwerem");
                    } else if (logowanie.zalogowanoNiedawno(600_000)) {
                        nastepny(40); // już zalogowane automatycznie
                    } else {
                        Logowanie.Okno o = logowanie.noweOkno(mc);
                        if (o != null) {
                            logowanie.wpisz(o, h);
                            nastepny(40);
                        } else if (++licznik > CZEKAJ_NA_OKNO_LOGOWANIA) {
                            // serwer nie pokazał okna - próbujemy zwykłą komendą
                            mc.player.connection.sendCommand("login " + h);
                            nastepny(-1);
                        }
                    }
                }
                case 5 -> { // graj/uzyj: po prawym kliku czekamy na menu serwera
                    if (!wGrze) {
                        blad(raport, k, "rozłączono z serwerem");
                    } else if (MenuSerwera.otwarte(mc)) {
                        faza = 6;
                        licznik = 0;
                        pauza = 10; // chwila, żeby serwer przysłał przedmioty do okna
                    } else if (++licznik > CZEKAJ_NA_MENU) {
                        blad(raport, k, "po prawym kliku nie otworzyło się menu serwera");
                    }
                }
                case 6 -> { // graj/kliknij: szukamy przycisku w menu i klikamy
                    if (!wGrze) {
                        blad(raport, k, "rozłączono z serwerem");
                        return;
                    }
                    if (!MenuSerwera.otwarte(mc)) {
                        if (++licznik > CZEKAJ_NA_MENU) blad(raport, k, "nie ma otwartego menu serwera");
                        return;
                    }
                    net.minecraft.world.inventory.Slot slot = k.typ().equals("kliknij")
                            ? MenuSerwera.znajdz(mc, k.arg()) : MenuSerwera.przyciskGraj(mc);
                    if (slot != null) {
                        MenuSerwera.kliknij(mc, slot);
                        nastepny(40); // 2 s na przeniesienie / zamknięcie menu
                    } else if (++licznik > 40) {
                        blad(raport, k, (k.typ().equals("kliknij") ? "nie znalazłem `" + k.arg() + "` w menu" : "nie wiem, który przycisk to „Graj”")
                                + ". W menu jest: " + MenuSerwera.lista(mc) + " – użyj `kliknij <napis>` albo `kliknij #numer`");
                    }
                }
                case 3 -> { // wyjdz: czekamy na menu
                    if (mc.level == null) {
                        nastepny(20);
                    } else if (++licznik > CZEKAJ_NA_WYJSCIE) {
                        blad(raport, k, "nie udało się wyjść z serwera");
                    }
                }
                default -> faza = 0;
            }
        } catch (Exception e) {
            e.printStackTrace();
            blad(raport, k, e.toString());
        }
    }

    private void wykonajKrok(Minecraft mc, String nick, Krok k, Wykonawca w, Consumer<String> raport) {
        switch (k.typ()) {
            case "czekaj" -> {
                pauza = (int) Math.round(Double.parseDouble(k.arg()) * 20);
                nastepny(0);
            }
            case "serwer" -> {
                String adres = Serwery.adres(mc, k.arg());
                if (Serwery.naSerwerze(mc, adres)) {
                    nastepny(-1); // już tu jesteśmy
                } else if (mc.level != null) {
                    Serwery.wyjdz(mc);
                    faza = 1;
                    licznik = 0;
                    pauza = 20;
                } else {
                    String o = Serwery.polacz(mc, adres);
                    if (o.startsWith("❌")) {
                        blad(raport, k, o.substring(2));
                        return;
                    }
                    faza = 2;
                    licznik = 0;
                }
            }
            case "wyjdz" -> {
                if (mc.level == null) {
                    nastepny(-1);
                } else {
                    Serwery.wyjdz(mc);
                    faza = 3;
                    licznik = 0;
                }
            }
            case "login" -> {
                String h = haslo(nick);
                if (h.isEmpty()) {
                    blad(raport, k, "brak hasła - ustaw je w ⚙ Sekwencje → 🔑 Hasło");
                    return;
                }
                if (logowanie.zalogowanoNiedawno(600_000)) {
                    nastepny(40); // okno logowania obsłużyło już automatyczne logowanie
                    return;
                }
                Logowanie.Okno o = logowanie.noweOkno(mc);
                if (o != null) {
                    logowanie.wpisz(o, h);
                    nastepny(40);
                } else {
                    faza = 4; // czekamy, aż serwer pokaże okno logowania (max 15 s, potem /login)
                    licznik = 0;
                }
            }
            case "graj" -> {
                if (MenuSerwera.otwarte(mc)) {
                    faza = 6; // menu już jest otwarte - od razu szukamy „Graj”
                    licznik = 0;
                    return;
                }
                if (k.arg().isEmpty() && mc.player.getMainHandItem().isEmpty() && ++licznik <= CZEKAJ_NA_MENU) {
                    return; // serwer jeszcze nie dał przedmiotów lobby - chwilę czekamy
                }
                String b = MenuSerwera.uzyj(mc, k.arg().isEmpty() ? 0 : Integer.parseInt(k.arg()), true);
                if (b != null) {
                    blad(raport, k, b);
                    return;
                }
                faza = 5;
                licznik = 0;
            }
            case "uzyj" -> {
                String b = MenuSerwera.uzyj(mc, k.arg().isEmpty() ? 0 : Integer.parseInt(k.arg()), false);
                if (b != null) {
                    blad(raport, k, b);
                    return;
                }
                nastepny(20);
            }
            case "kliknij" -> {
                faza = 6;
                licznik = 0;
            }
            case "zamknij" -> {
                if (MenuSerwera.otwarte(mc)) mc.player.closeContainer();
                nastepny(-1);
            }
            case "komenda" -> {
                mc.player.connection.sendCommand(k.arg().replace("{haslo}", haslo(nick)));
                nastepny(-1);
            }
            default -> {
                String wynik = w.wykonaj(k.typ(), k.arg());
                if (wynik != null && wynik.startsWith("❌")) {
                    blad(raport, k, wynik.substring(1).strip());
                    return;
                }
                nastepny(-1);
            }
        }
    }

    /** Przejście do następnego kroku. pauzaTicki < 0 = krótka losowa przerwa (0.5-1.2 s), jak u człowieka. */
    private void nastepny(int pauzaTicki) {
        nr++;
        faza = 0;
        licznik = 0;
        if (pauzaTicki < 0) {
            pauza = ThreadLocalRandom.current().nextInt(10, 25);
        } else if (pauzaTicki > 0) {
            pauza = Math.max(pauza, pauzaTicki);
        }
        aktualizujPostep();
    }

    private void blad(Consumer<String> raport, Krok k, String opis) {
        String n = nazwa;
        int ktory = nr + 1;
        kroki = null;
        postep = null;
        raport.accept("❌ " + n + " przerwana na kroku " + ktory + " (`" + k.linia() + "`): " + opis);
    }

    private void aktualizujPostep() {
        List<Krok> k = kroki;
        if (k == null) {
            postep = null;
        } else if (nr >= k.size()) {
            postep = "▶ " + nazwa + ": kończę…";
        } else {
            postep = "▶ " + nazwa + ": krok " + (nr + 1) + "/" + k.size() + " – " + k.get(nr).linia();
        }
    }

    private static String krokow(int n) {
        if (n == 1) return "krok";
        int r10 = n % 10, r100 = n % 100;
        return r10 >= 2 && r10 <= 4 && (r100 < 12 || r100 > 14) ? "kroki" : "kroków";
    }
}
