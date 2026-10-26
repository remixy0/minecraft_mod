package com.example.discord;

import com.google.gson.JsonArray;
import com.example.sekwencje.Harmonogram;
import com.example.sekwencje.Sekwencje;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.List;

/**
 * Buduje wiadomość z panelem sterowania jednym botem (karta ze stanem + przyciski i listy).
 * Każdy przycisk ma custom_id w formacie "mc|<nick>|<akcja>", dzięki czemu przy kilku botach
 * na jednym kanale kliknięcie obsługuje tylko ten klient, do którego należy panel.
 */
public final class PanelSterowania {
    private PanelSterowania() {
    }

    /** Migawka stanu bota (budowana w wątku gry, potem tylko czytana). */
    public record Stan(
            String nick,
            boolean wGrze,             // czy jesteśmy na serwerze / w świecie
            String serwer,             // adres serwera albo null
            boolean lowi,
            boolean kopie,
            String status,             // główna linia, np. "🟢 Łowi"
            List<String> uwagi,        // dodatkowe linie pod statusem
            int kolor,
            List<String[]> pola,       // krótkie pola na panelu {nazwa, wartość}
            List<String[]> pelnePola,  // pełne statystyki (przycisk 📊)
            List<String> pasek,        // 9 opisów slotów paska
            int wybranySlot,           // 0-8
            String home,               // nazwa home, np. "ryby"
            List<String[]> serwery,    // lista serwerów z gry {nazwa, adres}
            boolean sekwencja          // czy trwa sekwencja startowa/końcowa
    ) {
    }

    // style przycisków Discorda
    private static final int NIEBIESKI = 1, SZARY_PRZYCISK = 2, ZIELONY = 3, CZERWONY = 4;

    /** Pełna wiadomość panelu (embed + komponenty) - BEZ znacznika czasu, żeby dało się porównywać zmiany. */
    public static JsonObject wiadomosc(Stan s, String ostatniaAkcja, long czasAkcjiMs) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", przytnij("🎮 " + s.nick(), 256));
        StringBuilder opis = new StringBuilder("**").append(s.status()).append("**");
        for (String u : s.uwagi()) {
            opis.append("\n").append(u);
        }
        embed.addProperty("description", przytnij(opis.toString(), 4000));
        embed.addProperty("color", s.kolor());

        JsonArray fields = new JsonArray();
        for (String[] p : s.pola()) {
            if (fields.size() >= 23) break;
            fields.add(pole(p[0], p[1], true));
        }
        if (s.serwer() != null) {
            fields.add(pole("🖥 Serwer", s.serwer(), true));
        }
        if (ostatniaAkcja != null && !ostatniaAkcja.isEmpty()) {
            fields.add(pole("🕹 Ostatnia akcja", ostatniaAkcja + " · <t:" + (czasAkcjiMs / 1000) + ":R>", false));
        }
        embed.add("fields", fields);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Panel sterowania · odświeża się sam · klikać może tylko właściciel");
        embed.add("footer", footer);

        JsonObject msg = new JsonObject();
        msg.addProperty("content", "");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        msg.add("embeds", embeds);
        msg.add("components", komponenty(s));
        msg.add("allowed_mentions", bezPingow());
        return msg;
    }

    /** Dodaje znacznik czasu ("dzisiaj o 11:45") do karty panelu. */
    public static void dodajCzas(JsonObject wiadomosc) {
        JsonArray embeds = wiadomosc.getAsJsonArray("embeds");
        if (embeds != null && !embeds.isEmpty()) {
            embeds.get(0).getAsJsonObject().addProperty("timestamp", Instant.now().toString());
        }
    }

    /** Panel po wyłączeniu gry: szara karta bez przycisków. */
    public static JsonObject offline(String nick) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", przytnij("🎮 " + nick, 256));
        embed.addProperty("description", "**⚫ Gra wyłączona**\nPanel wróci sam, gdy znowu uruchomisz Minecrafta.");
        embed.addProperty("color", 0x2F3136);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonObject msg = new JsonObject();
        msg.addProperty("content", "");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        msg.add("embeds", embeds);
        msg.add("components", new JsonArray());
        return msg;
    }

    /** Okienko do wpisania nazwy home (odpowiedź typu 9 = MODAL). */
    public static JsonObject okienkoHome(String nick, String home) {
        return okienko("mc|" + nick + "|homeok", "🏠 Teleport: /home", "Nazwa home (np. ryby)",
                "ryby", home, 40);
    }

    /** Okienko do wpisania dowolnej komendy serwera. */
    public static JsonObject okienkoKomendy(String nick) {
        return okienko("mc|" + nick + "|komendaok", "⌨ Wyślij komendę na serwer", "Komenda (bez /)",
                "np. w bank wplac wszystko", null, 255);
    }

    /** Karta z pełnymi statystykami (z przyciskiem 🗑, gdy idzie jako zwykła wiadomość na #ustawienia). */
    public static JsonObject statystykiDane(Stan s, boolean zPrzyciskiemUsun) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", przytnij("📊 " + s.nick() + " – statystyki", 256));
        embed.addProperty("color", s.kolor());
        JsonArray fields = new JsonArray();
        for (String[] p : s.pelnePola()) {
            if (fields.size() >= 25) break;
            fields.add(pole(p[0], p[1], p[1] == null || p[1].length() <= 32));
        }
        if (fields.isEmpty()) {
            embed.addProperty("description", s.wGrze() ? "Brak statystyk - jeszcze nic nie złowiono." : "Bot nie jest teraz na serwerze.");
        }
        embed.add("fields", fields);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject data = new JsonObject();
        data.add("embeds", embeds);
        if (zPrzyciskiemUsun) {
            JsonArray rzedy = new JsonArray();
            rzedy.add(rzad(przycisk("mc|" + s.nick() + "|u:usun", "Usuń", "🗑️", SZARY_PRZYCISK, false)));
            data.add("components", rzedy);
        }
        data.add("allowed_mentions", bezPingow());
        return data;
    }

    /** Prywatna (widoczna tylko dla klikającego) karta z pełnymi statystykami. */
    public static JsonObject statystykiPrywatne(Stan s) {
        JsonObject data = statystykiDane(s, false);
        data.addProperty("flags", 64); // EPHEMERAL
        return odpowiedz(4, data);
    }

    /** Krótka prywatna wiadomość (np. brak uprawnień). */
    public static JsonObject tekstPrywatny(String tekst) {
        JsonObject data = new JsonObject();
        data.addProperty("content", przytnij(tekst, 1990));
        data.addProperty("flags", 64);
        return odpowiedz(4, data);
    }

    /** "Przyjęto - za chwilę zmienię wiadomość" (typ 6 = DEFERRED_UPDATE_MESSAGE). */
    public static JsonObject potwierdzenie() {
        JsonObject o = new JsonObject();
        o.addProperty("type", 6);
        return o;
    }

    // ------------------------------------------------------------------

    private static JsonArray komponenty(Stan s) {
        String id = "mc|" + s.nick() + "|";
        boolean off = !s.wGrze();
        JsonArray rzedy = new JsonArray();

        // 1: najważniejsze przyciski
        rzedy.add(rzad(
                przycisk(id + "start", "Łów", "🎣", ZIELONY, off || s.lowi()),
                przycisk(id + "startkop", "Kop", "⛏️", NIEBIESKI, off || s.kopie()),
                przycisk(id + "stop", "Stop", "⏹️", CZERWONY, !s.sekwencja() && (off || (!s.lowi() && !s.kopie()))),
                przycisk(id + "turbo", "Turbo…", "⚡", SZARY_PRZYCISK, off),
                przycisk(id + "staty", "Statystyki", "📊", SZARY_PRZYCISK, false)));

        // 2: wejście na serwer / wyjście
        JsonArray serwery = new JsonArray();
        for (String[] sv : s.serwery()) {
            if (serwery.size() >= 22) break;
            boolean tu = s.serwer() != null && s.serwer().equalsIgnoreCase(sv[1]);
            serwery.add(opcja(sv[0].equals(sv[1]) ? sv[0] : sv[0] + " (" + sv[1] + ")", "s:" + sv[1], tu ? "▶️" : "🖥️", false));
        }
        if (!off) serwery.add(opcja("Wyjdź z serwera", "!wyjdz", "⏏️", false));
        serwery.add(opcja("Inny adres…", "!inny", "✏️", false));
        rzedy.add(rzad(lista(id + "serwer", off ? "🖥 Wejdź na serwer…" : "🖥 Zmień serwer / wyjdź…", serwery, false)));

        // 3: slot paska (wędka / kilof / cokolwiek)
        JsonArray sloty = new JsonArray();
        if (s.pasek().isEmpty()) {
            sloty.add(opcja("brak danych", "0", null, false));
        } else {
            for (int i = 0; i < s.pasek().size() && i < 9; i++) {
                sloty.add(opcja(s.pasek().get(i), String.valueOf(i + 1), i == s.wybranySlot() ? "▶️" : null, false));
            }
        }
        rzedy.add(rzad(lista(id + "slot", "🖐 Weź do ręki przedmiot z paska…", sloty, off)));

        // 4: komendy
        rzedy.add(rzad(
                przycisk(id + "home", "Home…", "🏠", SZARY_PRZYCISK, off),
                przycisk(id + "w", "/w (wędka)", "🪝", SZARY_PRZYCISK, off),
                przycisk(id + "komenda", "Komenda…", "⌨️", SZARY_PRZYCISK, off),
                przycisk(id + "zaloguj", "Zaloguj i graj", "🔑", SZARY_PRZYCISK, off),
                przycisk(id + "ustawienia", "Ustawienia…", "⚙️", SZARY_PRZYCISK, false)));

        // 5: sekwencje (ten bot / wszystkie boty) i awaryjne zatrzymanie wszystkich
        rzedy.add(rzad(
                przycisk(id + "seqstart", "Start", "▶️", ZIELONY, false),
                przycisk(id + "seqkoniec", "Koniec", "⏏️", SZARY_PRZYCISK, false),
                przycisk(id + "seqstartall", "START wszyscy", "⏩", ZIELONY, false),
                przycisk(id + "seqkoniecall", "KONIEC wszyscy", "⏏️", NIEBIESKI, false),
                przycisk(id + "stopall", "STOP wszyscy", "🛑", CZERWONY, false)));
        return rzedy;
    }

    /** Dane do menu ⚙ (wiadomość na #ustawienia albo prywatna, gdy kanału nie ma). */
    public record DaneUstawien(String nick, String start, String startSerwer, String koniec, String harmonogram, String nastepny,
                               boolean maHaslo, String postep, String kanalPaneli, String kanalUstawien,
                               String kanalAlertow, boolean kanalyGotowe, String komunikat) {
    }

    /** Menu ⚙: sekwencje startowa/końcowa, harmonogram, hasło, kanały. */
    public static JsonObject ustawienia(DaneUstawien u) {
        String id = "mc|" + u.nick() + "|u:";
        JsonObject embed = new JsonObject();
        embed.addProperty("title", przytnij("⚙ Ustawienia – " + u.nick(), 256));
        StringBuilder opis = new StringBuilder();
        if (u.komunikat() != null && !u.komunikat().isEmpty()) opis.append(przytnij(u.komunikat(), 1500)).append("\n\n");
        opis.append(u.postep() != null ? u.postep() : "Żadna sekwencja teraz nie trwa.");
        embed.addProperty("description", przytnij(opis.toString(), 1800));
        embed.addProperty("color", 0x5865F2);
        JsonArray fields = new JsonArray();
        fields.add(pole("▶ Start od zera (z menu gry)", kod(u.start(), 600), false));
        fields.add(pole("▶ Start na serwerze (już w grze)", kod(u.startSerwer(), 600), false));
        fields.add(pole("⏏ Sekwencja końcowa", kod(u.koniec(), 600), false));
        fields.add(pole("⏰ Harmonogram", kod(u.harmonogram(), 600)
                + (u.nastepny() != null ? "\nNastępne: **" + u.nastepny() + "**" : ""), false));
        fields.add(pole("🔑 Hasło (logowanie)", u.maHaslo() ? "ustawione ✅" : "brak", true));
        fields.add(pole("📍 Kanały", u.kanalyGotowe()
                ? "panele <#" + u.kanalPaneli() + "> · ustawienia <#" + u.kanalUstawien() + "> · alerty <#" + u.kanalAlertow() + ">"
                : "brak – kliknij 🏗 **Utwórz kanały**", true));
        fields.add(pole("📝 Kroki sekwencji (jeden w linii)", Sekwencje.POMOC, false));
        fields.add(pole("📝 Harmonogram – jak pisać", Harmonogram.POMOC, false));
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Start na panelu sam wybiera: w menu gry – „od zera”, na serwerze – „na serwerze”");
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());

        JsonArray rzedy = new JsonArray();
        rzedy.add(rzad(
                przycisk(id + "edstart", "Od zera", "✏️", NIEBIESKI, false),
                przycisk(id + "edserwer", "Na serwerze", "✏️", NIEBIESKI, false),
                przycisk(id + "edkoniec", "Końcowa", "✏️", NIEBIESKI, false),
                przycisk(id + "harm", "Harmonogram", "⏰", NIEBIESKI, false),
                przycisk(id + "haslo", "Hasło…", "🔑", SZARY_PRZYCISK, false)));
        rzedy.add(rzad(
                przycisk(id + "seqzero", "Uruchom od zera", "▶️", ZIELONY, false),
                przycisk(id + "seqserwer", "Uruchom na serwerze", "▶️", ZIELONY, false),
                przycisk(id + "seqkoniec", "Uruchom końcową", "⏏️", SZARY_PRZYCISK, false),
                przycisk(id + "seqstop", "Przerwij", "⏹️", CZERWONY, u.postep() == null)));
        rzedy.add(rzad(
                przycisk(id + "odswiez", "Odśwież", "🔄", SZARY_PRZYCISK, false),
                przycisk(id + "nadol", "Panel na dół", "📌", SZARY_PRZYCISK, false),
                przycisk(id + "kanaly", u.kanalyGotowe() ? "Kanały gotowe" : "Utwórz kanały", "🏗️", SZARY_PRZYCISK, u.kanalyGotowe()),
                przycisk(id + "usun", "Usuń wiadomość", "🗑️", CZERWONY, false)));

        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        data.add("components", rzedy);
        data.add("allowed_mentions", bezPingow());
        return data;
    }

    public static JsonObject okienkoHarmonogramu(String nick, String tekst) {
        String t = tekst == null || tekst.isBlank() ? Harmonogram.PRZYKLAD : tekst;
        return okienko("mc|" + nick + "|u:harmok", "⏰ Harmonogram – " + nick,
                "Wpisy – jeden w linii (# = komentarz)", "pn-pt 07:30 start / 22:00 koniec", t, 4000, 2, false);
    }

    /** Odpowiedź z menu ⚙: typ 4 = nowa prywatna wiadomość, typ 7 = zmiana tej, w której kliknięto. */
    public static JsonObject odpowiedzUstawienia(int typ, JsonObject dane) {
        JsonObject d = dane.deepCopy();
        if (typ == 4) d.addProperty("flags", 64);
        return odpowiedz(typ, d);
    }

    /** jaka: Sekwencje.START (od zera), SERWER (na serwerze), KONIEC. */
    public static JsonObject okienkoSekwencji(String nick, String jaka, String tekst) {
        String t = tekst == null || tekst.isBlank() ? Sekwencje.przyklad(jaka) : tekst;
        String tytul = switch (jaka) {
            case Sekwencje.SERWER -> "▶ Start na serwerze – ";
            case Sekwencje.KONIEC -> "⏏ Sekwencja końcowa – ";
            default -> "▶ Start od zera – ";
        };
        String kod = switch (jaka) {
            case Sekwencje.SERWER -> "edserwerok";
            case Sekwencje.KONIEC -> "edkoniecok";
            default -> "edstartok";
        };
        return okienko("mc|" + nick + "|u:" + kod, tytul + nick,
                "Kroki – jeden w linii (# = komentarz)", "np. serwer skyup.pl / czekaj 5 / zaloguj / home kopalnia / kop",
                t, 4000, 2, false);
    }

    public static JsonObject okienkoHasla(String nick) {
        return okienko("mc|" + nick + "|u:haslook", "🔑 Hasło do logowania – " + nick, "Hasło (puste pole = usuń hasło)",
                "hasło z serwera", null, 100, 1, false);
    }

    public static JsonObject okienkoTurbo(String nick) {
        return okienko("mc|" + nick + "|turbook", "⚡ Turbo", "Ile następnych gier bez pomyłek? (1-100)", "5", "5", 3, 1, true);
    }

    public static JsonObject okienkoSerwera(String nick) {
        return okienko("mc|" + nick + "|serwerok", "🖥 Wejdź na serwer", "Adres serwera (np. skyup.pl)", "skyup.pl", null, 255, 1, true);
    }

    private static String kod(String tekst) {
        return kod(tekst, 1000);
    }

    private static String kod(String tekst, int max) {
        if (tekst == null || tekst.isBlank()) return "*(pusta)*";
        return "```\n" + przytnij(tekst.replace("```", "'''"), max) + "\n```";
    }

    private static JsonObject okienko(String customId, String tytul, String etykieta, String podpowiedz, String wartosc, int max) {
        return okienko(customId, tytul, etykieta, podpowiedz, wartosc, max, 1, true);
    }

    /** styl 1 = jedna linia, 2 = kilka linii. */
    private static JsonObject okienko(String customId, String tytul, String etykieta, String podpowiedz, String wartosc, int max,
                                      int styl, boolean wymagane) {
        JsonObject pole = new JsonObject();
        pole.addProperty("type", 4); // TEXT_INPUT
        pole.addProperty("custom_id", "wartosc");
        pole.addProperty("label", przytnij(etykieta, 45));
        pole.addProperty("style", styl);
        if (wymagane) pole.addProperty("min_length", 1);
        pole.addProperty("max_length", max);
        pole.addProperty("required", wymagane);
        pole.addProperty("placeholder", przytnij(podpowiedz, 100));
        if (wartosc != null && !wartosc.isBlank()) {
            pole.addProperty("value", przytnij(wartosc, max));
        }
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        data.addProperty("title", przytnij(tytul, 45));
        JsonArray rzedy = new JsonArray();
        rzedy.add(rzad(pole));
        data.add("components", rzedy);
        return odpowiedz(9, data);
    }

    private static JsonObject odpowiedz(int typ, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("type", typ);
        o.add("data", data);
        return o;
    }

    private static JsonObject rzad(JsonObject... elementy) {
        JsonObject r = new JsonObject();
        r.addProperty("type", 1); // ACTION_ROW
        JsonArray c = new JsonArray();
        for (JsonObject e : elementy) c.add(e);
        r.add("components", c);
        return r;
    }

    private static JsonObject przycisk(String customId, String etykieta, String emoji, int styl, boolean wylaczony) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2); // BUTTON
        b.addProperty("style", styl);
        b.addProperty("label", przytnij(etykieta, 80));
        b.addProperty("custom_id", przytnij(customId, 100));
        if (emoji != null) b.add("emoji", emoji(emoji));
        b.addProperty("disabled", wylaczony);
        return b;
    }

    private static JsonObject lista(String customId, String podpowiedz, JsonArray opcje, boolean wylaczona) {
        JsonObject m = new JsonObject();
        m.addProperty("type", 3); // STRING_SELECT
        m.addProperty("custom_id", przytnij(customId, 100));
        m.addProperty("placeholder", przytnij(podpowiedz, 150));
        m.add("options", opcje);
        m.addProperty("min_values", 1);
        m.addProperty("max_values", 1);
        m.addProperty("disabled", wylaczona);
        return m;
    }

    private static JsonObject opcja(String etykieta, String wartosc, String emoji, boolean domyslna) {
        JsonObject o = new JsonObject();
        o.addProperty("label", przytnij(etykieta, 100));
        o.addProperty("value", wartosc);
        if (emoji != null) o.add("emoji", emoji(emoji));
        if (domyslna) o.addProperty("default", true);
        return o;
    }

    private static JsonObject emoji(String znak) {
        JsonObject e = new JsonObject();
        e.addProperty("name", znak);
        return e;
    }

    private static JsonObject pole(String nazwa, String wartosc, boolean wLinii) {
        JsonObject f = new JsonObject();
        f.addProperty("name", przytnij(nazwa, 256));
        f.addProperty("value", przytnij(wartosc == null || wartosc.isBlank() ? "-" : wartosc, 1024));
        f.addProperty("inline", wLinii);
        return f;
    }

    private static JsonObject bezPingow() {
        JsonObject a = new JsonObject();
        a.add("parse", new JsonArray());
        return a;
    }

    /** 1 gra, 3 gry, 5 gier, 22 gry ... */
    private static String gier(int n) {
        if (n == 1) return "gra";
        int r10 = n % 10, r100 = n % 100;
        return (r10 >= 2 && r10 <= 4 && (r100 < 12 || r100 > 14)) ? "gry" : "gier";
    }

    static String przytnij(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
