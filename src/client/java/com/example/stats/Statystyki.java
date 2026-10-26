package com.example.stats;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statystyki łowienia: gry w minigrze + złowione ryby (waga, jakość, cena).
 *
 * Ryby są wykrywane w ekwipunku po opisie (lore) przedmiotu, np.:
 *   Wyłowił » bartoszelos
 *   Waga » 48 616,29g (mamuci)
 *   Jakość » 38% (niesmaczny)
 *   Sprzedaj u Wędkarza za 184,74 SC.
 * Liczone są tylko ryby z Twoim nickiem w "Wyłowił".
 */
public class Statystyki {
    private static final Locale PL = Locale.forLanguageTag("pl-PL");
    private static final String LICZBA = "([0-9][0-9 \\u00a0\\u202f.]*(?:,[0-9]+)?)";
    private static final Pattern WYLOWIL = Pattern.compile("Wyłowił\\s*»\\s*(\\S+)");
    private static final Pattern WAGA = Pattern.compile("Waga\\s*»\\s*" + LICZBA + "\\s*(k?g)");
    private static final Pattern JAKOSC = Pattern.compile("Jakość\\s*»\\s*([0-9]+(?:,[0-9]+)?)\\s*%");
    private static final Pattern CENA = Pattern.compile("za\\s+" + LICZBA + "\\s*SC");

    // --- gry ---
    private int gry = 0;
    private int gryIdealne = 0;
    private int gryTurbo = 0;
    private final int[] pomylkaNa = new int[6]; // pomylkaNa[n] = ile gier z pomyłką na n-tym kliknięciu
    private int poprawneKlikniecia = 0;

    // --- ryby ---
    private int ryby = 0;
    private double sumaCena = 0, sumaWagaG = 0, sumaJakosc = 0;
    private int rybyZJakoscia = 0, rybyZWaga = 0;
    private double maxCena = -1;
    private String najlepszaRyba = "-";
    private double minCena = Double.MAX_VALUE;

    // --- klucze (Klucz do Rybackiego SkyCase) ---
    private static final String NAZWA_KLUCZA = "Klucz do Rybackiego"; // fragment nazwy przedmiotu
    private int kluczeTeraz = 0;        // ile kluczy masz w ekwipunku
    private int kluczeZdobyte = 0;      // ile przybyło podczas łowienia
    private int kluczePoprzednio = -1;  // -1 = jeszcze nie liczono

    // --- czas (tylko gdy bot działa) ---
    private long aktywneTicki = 0;

    // --- wykrywanie nowych ryb ---
    private final Set<String> widziane = new HashSet<>();
    private boolean pierwszySkan = true;

    public void tickAktywny() {
        aktywneTicki++;
    }

    public int gry() {
        return gry;
    }

    /** Wywołaj, gdy minigra się skończyła. etapPorazki: 0 = bez pomyłki, 1-5 = pomyłka na tym kliknięciu. */
    public void koniecGry(int etapPorazki, boolean turbo, int klikniecia) {
        gry++;
        poprawneKlikniecia += klikniecia;
        if (turbo) gryTurbo++;
        if (etapPorazki == 0) {
            gryIdealne++;
        } else if (etapPorazki < pomylkaNa.length) {
            pomylkaNa[etapPorazki]++;
        }
    }

    /**
     * Przegląda ekwipunek i zlicza nowe ryby.
     * @param ogloszenie dokąd wysłać info o nowej rybie (np. do czatu)
     */
    /**
     * @param liczKlucze czy nowe klucze w ekwipunku liczyć jako zdobyte (tylko podczas łowienia,
     *                   żeby np. wyjęcie kluczy ze skrzyni nie psuło statystyk)
     */
    public void skanujEkwipunek(Player player, Consumer<Component> ogloszenie, boolean liczKlucze) {
        String mojNick = player.getPlainTextName();
        Inventory inv = player.getInventory();
        int klucze = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (czysc(stack.getHoverName().getString()).contains(NAZWA_KLUCZA)) {
                klucze += stack.getCount();
                continue;
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            for (Component linia : lore.lines()) {
                sb.append(czysc(linia.getString())).append('\n');
            }
            String opis = sb.toString();
            if (!opis.contains("Sprzedaj u Wędkarza")) continue; // to nie ryba

            String nazwa = czysc(stack.getHoverName().getString());
            String podpis = nazwa + "|" + opis;
            if (!widziane.add(podpis)) continue;    // już policzona
            if (pierwszySkan) continue;             // ryby, które miałeś przed startem, nie liczą się

            Matcher m = WYLOWIL.matcher(opis);
            if (m.find() && !m.group(1).equalsIgnoreCase(mojNick)) continue; // ryba kogoś innego

            dodajRybe(nazwa, opis, ogloszenie);
        }
        pierwszySkan = false;

        // klucze: przyrost w ekwipunku = zdobyte klucze
        if (kluczePoprzednio >= 0 && klucze > kluczePoprzednio && liczKlucze) {
            int nowe = klucze - kluczePoprzednio;
            kluczeZdobyte += nowe;
            ogloszenie.accept(Component.literal("§d✦ +" + nowe + " klucz" + (nowe == 1 ? "" : "e")
                    + " §7(masz: §d" + klucze + "§7, zdobyte: §d" + kluczeZdobyte + "§7)"));
        }
        kluczePoprzednio = klucze;
        kluczeTeraz = klucze;
    }

    /** Tempo zdobywania kluczy, np. "2,4/h (co ~25 min)". */
    private String tempoKluczy() {
        double godziny = aktywneTicki / 72000.0;
        if (godziny < 0.01 || kluczeZdobyte == 0) return "-";
        double naGodzine = kluczeZdobyte / godziny;
        double coMinut = 60.0 / naGodzine;
        return String.format(PL, "%.1f/h", naGodzine)
                + (coMinut >= 1 ? String.format(PL, " (co ~%.0f min)", coMinut) : String.format(PL, " (co ~%.0f s)", coMinut * 60));
    }

    private void dodajRybe(String nazwa, String opis, Consumer<Component> ogloszenie) {
        ryby++;
        Double cena = liczba(CENA, opis);
        Double jakosc = liczba(JAKOSC, opis);
        Matcher mw = WAGA.matcher(opis);
        if (mw.find()) {
            Double w = parsuj(mw.group(1));
            if (w != null) {
                if (mw.group(2).equals("kg")) w *= 1000;
                sumaWagaG += w;
                rybyZWaga++;
            }
        }
        if (jakosc != null) {
            sumaJakosc += jakosc;
            rybyZJakoscia++;
        }
        if (cena != null) {
            sumaCena += cena;
            if (cena > maxCena) {
                maxCena = cena;
                najlepszaRyba = nazwa;
            }
            if (cena < minCena) minCena = cena;
        }
        ogloszenie.accept(Component.literal("§b+ " + nazwa + " §7- §e" + (cena != null ? sc(cena) : "? SC")
                + " §7(razem: §e" + sc(sumaCena) + "§7)"));
    }

    public void pokaz(Consumer<Component> out) {
        double godziny = aktywneTicki / 72000.0;
        out.accept(Component.literal("§6§l===== Statystyki łowienia ====="));
        out.accept(Component.literal("§7Czas pracy bota: §f" + czas(aktywneTicki)));
        out.accept(Component.literal("§7Gry: §f" + gry
                + " §7| idealne: §a" + gryIdealne + procent(gryIdealne, gry)
                + (gryTurbo > 0 ? " §7| turbo: §6" + gryTurbo : "")));
        out.accept(Component.literal("§7Pomyłki: §c1-2. klik: " + (pomylkaNa[1] + pomylkaNa[2])
                + " §7| §c3. klik: " + pomylkaNa[3] + " §7| §c4. klik: " + pomylkaNa[4]));
        out.accept(Component.literal("§7Ryby: §f" + ryby
                + (gry > 0 ? " §7(" + String.format(PL, "%.2f", (double) ryby / gry) + " na grę)" : "")));
        out.accept(Component.literal("§7Wartość razem: §e" + sc(sumaCena)
                + (ryby > 0 ? " §7| średnio: §e" + sc(sumaCena / ryby) : "")));
        if (maxCena >= 0) {
            out.accept(Component.literal("§7Najdroższa: §d" + najlepszaRyba + " §7- §e" + sc(maxCena)
                    + " §7| najtańsza: §e" + sc(minCena)));
        }
        if (rybyZWaga > 0 || rybyZJakoscia > 0) {
            out.accept(Component.literal("§7Średnia waga: §f" + waga(rybyZWaga > 0 ? sumaWagaG / rybyZWaga : 0)
                    + " §7| średnia jakość: §f" + String.format(PL, "%.1f", rybyZJakoscia > 0 ? sumaJakosc / rybyZJakoscia : 0) + "%"));
        }
        if (godziny > 0.01) {
            out.accept(Component.literal("§7Tempo: §e" + sc(sumaCena / godziny) + "/h §7| §f"
                    + String.format(PL, "%.0f", ryby / godziny) + " ryb/h"));
        }
        out.accept(Component.literal("§7Klucze: §dmasz " + kluczeTeraz + " §7| zdobyte: §d" + kluczeZdobyte
                + " §7| tempo: §d" + tempoKluczy()
                + (kluczeZdobyte > 0 && gry > 0 ? String.format(PL, " §7| 1 klucz na ~%.0f gier", (double) gry / kluczeZdobyte) : "")));
    }

    /** Pola do wiadomości na Discordzie: {nazwa, wartość}. */
    public List<String[]> polaDiscord() {
        List<String[]> p = new ArrayList<>();
        double godziny = aktywneTicki / 72000.0;
        p.add(new String[]{"⏱ Czas pracy", czas(aktywneTicki)});
        p.add(new String[]{"🎮 Gry", gry + " (idealne: " + gryIdealne + procent(gryIdealne, gry) + ")"});
        if (gryTurbo > 0) {
            p.add(new String[]{"⚡ Gry turbo", String.valueOf(gryTurbo)});
        }
        p.add(new String[]{"❌ Pomyłki", "1-2. klik: " + (pomylkaNa[1] + pomylkaNa[2])
                + " | 3. klik: " + pomylkaNa[3] + " | 4. klik: " + pomylkaNa[4]});
        p.add(new String[]{"🐟 Ryby", ryby + (gry > 0 ? " (" + String.format(PL, "%.2f", (double) ryby / gry) + " na grę)" : "")});
        p.add(new String[]{"💰 Wartość razem", sc(sumaCena)});
        p.add(new String[]{"📊 Średnio za rybę", ryby > 0 ? sc(sumaCena / ryby) : "-"});
        if (maxCena >= 0) {
            p.add(new String[]{"🏆 Najdroższa", najlepszaRyba + " - " + sc(maxCena)});
            p.add(new String[]{"🔻 Najtańsza", sc(minCena)});
        }
        if (rybyZWaga > 0 || rybyZJakoscia > 0) {
            p.add(new String[]{"⚖ Średnia waga / jakość", waga(rybyZWaga > 0 ? sumaWagaG / rybyZWaga : 0) + " / "
                    + String.format(PL, "%.1f", rybyZJakoscia > 0 ? sumaJakosc / rybyZJakoscia : 0) + "%"});
        }
        if (godziny > 0.01) {
            p.add(new String[]{"📈 Tempo", sc(sumaCena / godziny) + "/h | " + String.format(PL, "%.0f", ryby / godziny) + " ryb/h"});
        }
        p.add(new String[]{"🔑 Klucze", "masz: " + kluczeTeraz + " | zdobyte: " + kluczeZdobyte});
        p.add(new String[]{"🔑 Tempo kluczy", tempoKluczy()
                + (kluczeZdobyte > 0 && gry > 0 ? String.format(PL, " | 1 na ~%.0f gier", (double) gry / kluczeZdobyte) : "")});
        return p;
    }

    /** Krótkie linie do panelu w lewym górnym rogu. */
    public List<String> liniePanelu() {
        List<String> l = new ArrayList<>();
        l.add("§7Czas: §f" + czas(aktywneTicki));
        l.add("§7Gry: §f" + gry + " §7(idealne §a" + gryIdealne + "§7" + procent(gryIdealne, gry) + ")");
        l.add("§7Ryby: §f" + ryby + " §7| §e" + sc(sumaCena));
        if (ryby > 0) {
            l.add("§7Średnio: §e" + sc(sumaCena / ryby));
        }
        if (maxCena >= 0) {
            l.add("§7Najlepsza: §e" + sc(maxCena));
        }
        double godziny = aktywneTicki / 72000.0;
        if (godziny > 0.01) {
            l.add("§7Tempo: §e" + sc(sumaCena / godziny) + "/h");
        }
        l.add("§7Klucze: §d" + kluczeTeraz + " §7(+" + kluczeZdobyte + ") §7| §d" + tempoKluczy());
        return l;
    }

    public void reset() {
        gry = gryIdealne = gryTurbo = poprawneKlikniecia = 0;
        java.util.Arrays.fill(pomylkaNa, 0);
        ryby = rybyZJakoscia = rybyZWaga = 0;
        sumaCena = sumaWagaG = sumaJakosc = 0;
        maxCena = -1;
        minCena = Double.MAX_VALUE;
        najlepszaRyba = "-";
        aktywneTicki = 0;
        kluczeZdobyte = 0; // "masz" zostaje - to stan ekwipunku
        // "widziane" zostaje - ryby w ekwipunku nie zostaną policzone drugi raz
    }

    // --- pomocnicze ---

    private static String czysc(String s) {
        return s.replaceAll("§.", "").trim();
    }

    private static Double liczba(Pattern p, String tekst) {
        Matcher m = p.matcher(tekst);
        return m.find() ? parsuj(m.group(1)) : null;
    }

    /** "48 616,29" -> 48616.29 */
    private static Double parsuj(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[ \\u00a0\\u202f.]", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sc(double v) {
        return String.format(PL, "%,.2f SC", v).replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    private static String waga(double g) {
        String t = g >= 1000 ? String.format(PL, "%,.2f kg", g / 1000) : String.format(PL, "%,.0f g", g);
        return t.replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    private static String procent(int a, int b) {
        return b > 0 ? String.format(PL, " (%.0f%%)", 100.0 * a / b) : "";
    }

    private static String czas(long ticki) {
        long s = ticki / 20;
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
