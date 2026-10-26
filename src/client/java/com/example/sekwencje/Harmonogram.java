package com.example.sekwencje;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harmonogram: o której godzinie (i w które dni) bot ma coś zrobić. Jeden wpis w linii:
 * <pre>
 * pn-pt 07:30 start        - sekwencja startowa
 * pn-pt 15:00 koniec       - sekwencja końcowa
 * sb,nd 10:00 start
 * 22:00 stop; wyjdz        - dowolne kroki sekwencji (oddzielone ;), codziennie
 * </pre>
 * Czas = zegar komputera, na którym działa gra.
 */
public final class Harmonogram {
    private Harmonogram() {
    }

    public record Wpis(EnumSet<DayOfWeek> dni, int godzina, int minuta, String co, String linia) {
        public boolean pasuje(LocalDateTime t) {
            return dni.contains(t.getDayOfWeek()) && t.getHour() == godzina && t.getMinute() == minuta;
        }
    }

    public static final String POMOC = String.join("\n",
            "`[dni] GG:MM co` – jeden wpis w linii, czas wg zegara komputera",
            "dni: `pn wt sr cz pt sb nd`, `pn-pt`, `sb,nd`, `weekend` (bez dni = codziennie)",
            "co: `start` (sam wybierze), `start od zera`, `start na serwerze`, `koniec` albo kroki, np. `stop; wyjdz`",
            "np. `pn-pt 07:30 start` · `22:00 koniec`");

    public static final String PRZYKLAD = String.join("\n",
            "# Przykład - usuń # na początku linii, żeby wpis działał:",
            "# pn-pt 07:30 start",
            "# pn-pt 15:00 koniec",
            "# sb,nd 10:00 start",
            "# 22:00 koniec");

    private static final Pattern CZAS = Pattern.compile("(\\d{1,2})[:.](\\d{2})");
    private static final String[] SKROTY = {"pn", "wt", "śr", "cz", "pt", "sb", "nd"};

    public static List<Wpis> parsuj(String tekst, List<String> bledy) {
        List<Wpis> wynik = new ArrayList<>();
        if (tekst == null) return wynik;
        String[] linie = tekst.replace("\r", "").split("\n");
        for (int i = 0; i < linie.length; i++) {
            String l = linie[i].strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String blad = wpis(l, wynik);
            if (blad != null) bledy.add("linia " + (i + 1) + ": " + blad);
        }
        if (wynik.size() > 50) bledy.add("za dużo wpisów (max 50)");
        return wynik;
    }

    /** Dodaje wpis z linii; zwraca opis błędu albo null. */
    private static String wpis(String l, List<Wpis> wynik) {
        String[] cz = l.split("\\s+", 3);
        int i = 0;
        EnumSet<DayOfWeek> dni = EnumSet.allOf(DayOfWeek.class);
        if (!CZAS.matcher(cz[0]).matches()) {
            dni = dni(cz[0]);
            if (dni == null) return "nie rozumiem dni `" + cz[0] + "` (np. `pn-pt`, `sb,nd`, `weekend`)";
            i = 1;
        }
        if (cz.length <= i) return "brak godziny, np. `07:30 start`";
        // po dniach/godzinie reszta linii to "co"
        String reszta = l.substring(l.indexOf(cz[i]) + cz[i].length()).strip();
        Matcher m = CZAS.matcher(cz[i]);
        if (!m.matches()) return "zła godzina `" + cz[i] + "` (np. `07:30`)";
        int g = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        if (g > 23 || min > 59) return "zła godzina `" + cz[i] + "`";
        if (reszta.isEmpty()) return "co ma się stać o " + cz[i] + "? (np. `start`, `koniec`, `stop`)";
        String co = reszta;
        if (!czySekwencja(co)) {
            List<String> b = new ArrayList<>();
            List<Sekwencje.Krok> k = Sekwencje.parsuj(co.replace(';', '\n'), b);
            if (!b.isEmpty()) return b.get(0).replaceFirst("^linia \\d+: ", "");
            if (k.isEmpty()) return "co ma się stać o " + cz[i] + "?";
        }
        wynik.add(new Wpis(dni, g, min, co, l));
        return null;
    }

    /**
     * Zapisana sekwencja: "auto" (start - sam wybiera od zera / na serwerze), "start" (od zera), "serwer" (na serwerze),
     * "koniec"; null, gdy to zwykłe kroki.
     */
    public static String sekwencja(String co) {
        String c = MenuSerwera.proste(co).strip().replaceAll("\\s+", " ");
        return switch (c) {
            case "start", "sekwencja", "sekwencja start", "startowa" -> "auto";
            case "start od zera", "od zera", "start zero", "zero" -> Sekwencje.START;
            case "start na serwerze", "na serwerze", "start serwer" -> Sekwencje.SERWER;
            case "koniec", "sekwencja koniec", "koncowa" -> Sekwencje.KONIEC;
            default -> null;
        };
    }

    private static boolean czySekwencja(String co) {
        return sekwencja(co) != null;
    }

    /** "pn-pt", "sb,nd", "weekend", "codziennie"... albo null, gdy nie da się odczytać. */
    static EnumSet<DayOfWeek> dni(String t) {
        String s = MenuSerwera.proste(t);
        switch (s) {
            case "codziennie", "zawsze", "kazdy", "*" -> {
                return EnumSet.allOf(DayOfWeek.class);
            }
            case "weekend", "weekendy" -> {
                return EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            }
            case "robocze", "tydzien" -> {
                return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            }
            default -> {
            }
        }
        EnumSet<DayOfWeek> w = EnumSet.noneOf(DayOfWeek.class);
        for (String czesc : s.split(",")) {
            if (czesc.isBlank()) continue;
            String[] zakres = czesc.split("-");
            if (zakres.length == 1) {
                DayOfWeek d = dzien(zakres[0]);
                if (d == null) return null;
                w.add(d);
            } else if (zakres.length == 2) {
                DayOfWeek od = dzien(zakres[0]), doo = dzien(zakres[1]);
                if (od == null || doo == null) return null;
                for (DayOfWeek d = od; ; d = d.plus(1)) { // np. pt-pn przechodzi przez weekend
                    w.add(d);
                    if (d == doo) break;
                }
            } else {
                return null;
            }
        }
        return w.isEmpty() ? null : w;
    }

    private static DayOfWeek dzien(String t) {
        return switch (t.strip()) {
            case "pn", "pon", "poniedzialek", "mon", "monday" -> DayOfWeek.MONDAY;
            case "wt", "wto", "wtorek", "tue", "tuesday" -> DayOfWeek.TUESDAY;
            case "sr", "sro", "sroda", "wed", "wednesday" -> DayOfWeek.WEDNESDAY;
            case "cz", "czw", "czwartek", "thu", "thursday" -> DayOfWeek.THURSDAY;
            case "pt", "pia", "piatek", "fri", "friday" -> DayOfWeek.FRIDAY;
            case "sb", "sob", "sobota", "sat", "saturday" -> DayOfWeek.SATURDAY;
            case "nd", "nie", "ndz", "niedziela", "sun", "sunday" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** Najbliższy wpis po chwili "teraz" (w ciągu tygodnia) albo null. */
    public static LocalDateTime nastepny(List<Wpis> wpisy, LocalDateTime teraz, Wpis[] ktory) {
        LocalDateTime najlepszy = null;
        for (Wpis w : wpisy) {
            for (int dzien = 0; dzien <= 7; dzien++) {
                LocalDate data = teraz.toLocalDate().plusDays(dzien);
                if (!w.dni().contains(data.getDayOfWeek())) continue;
                LocalDateTime t = data.atTime(LocalTime.of(w.godzina(), w.minuta()));
                if (!t.isAfter(teraz)) continue;
                if (najlepszy == null || t.isBefore(najlepszy)) {
                    najlepszy = t;
                    if (ktory != null) ktory[0] = w;
                }
                break;
            }
        }
        return najlepszy;
    }

    /** Np. "dziś 15:00 → koniec", "jutro 07:30 → start", "pt 07:30 → start". */
    public static String opisNastepnego(List<Wpis> wpisy, LocalDateTime teraz) {
        Wpis[] ktory = new Wpis[1];
        LocalDateTime t = nastepny(wpisy, teraz, ktory);
        if (t == null) return null;
        long dni = t.toLocalDate().toEpochDay() - teraz.toLocalDate().toEpochDay();
        String kiedy = dni == 0 ? "dziś" : dni == 1 ? "jutro" : SKROTY[t.getDayOfWeek().getValue() - 1];
        return kiedy + " " + String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute()) + " → " + ktory[0].co();
    }
}
