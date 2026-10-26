package com.example.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wysyłanie wiadomości na kanał Discorda przez webhook (wygląda jak bot na serwerze).
 * Link do webhooka jest zapisywany w config/modid-discord.properties.
 * Wysyłanie odbywa się w tle, żeby gra się nie zacinała.
 */
public class DiscordWebhook {
    public static final int ZIELONY = 0x2ECC71;
    public static final int SZARY = 0x95A5A6;
    public static final int POMARANCZOWY = 0xE67E22;
    public static final int CZERWONY = 0xE74C3C;
    public static final int NIEBIESKI = 0x3498DB;

    private static final String NAZWA_BOTA = "Auto-łowienie";

    private final Path plik = FabricLoader.getInstance().getConfigDir().resolve("modid-discord.properties");
    private final ExecutorService watek = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "modid-discord");
        t.setDaemon(true);
        return t;
    });
    private volatile String url = "";
    private volatile boolean bladZgloszony = false;

    public void wczytaj() {
        try {
            if (Files.exists(plik)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(plik, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                url = p.getProperty("webhook", "").trim();
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się wczytać ustawień Discorda: " + e);
        }
    }

    private void zapisz() {
        try {
            Properties p = new Properties();
            p.setProperty("webhook", url);
            Files.createDirectories(plik.getParent());
            try (Writer w = Files.newBufferedWriter(plik, StandardCharsets.UTF_8)) {
                p.store(w, "Auto-lowienie: link do webhooka Discorda (NIE udostepniaj go nikomu)");
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się zapisać ustawień Discorda: " + e);
        }
    }

    public static boolean poprawnyLink(String u) {
        return u.startsWith("https://discord.com/api/webhooks/")
                || u.startsWith("https://discordapp.com/api/webhooks/")
                || u.startsWith("https://ptb.discord.com/api/webhooks/")
                || u.startsWith("https://canary.discord.com/api/webhooks/");
    }

    public boolean wlaczony() {
        return !url.isEmpty();
    }

    public void ustawUrl(String u) {
        url = u == null ? "" : u.trim();
        bladZgloszony = false;
        zapisz();
    }

    /** Zwykła wiadomość z kartą (embed). pola: {nazwa, wartość} */
    public void wyslij(String tytul, String opis, int kolor, List<String[]> pola, String stopka) {
        if (!wlaczony()) return;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", przytnij(tytul, 256));
        if (opis != null && !opis.isEmpty()) {
            embed.addProperty("description", przytnij(opis, 4000));
        }
        embed.addProperty("color", kolor);
        embed.addProperty("timestamp", Instant.now().toString());
        if (pola != null && !pola.isEmpty()) {
            JsonArray fields = new JsonArray();
            for (int i = 0; i < pola.size() && i < 25; i++) {
                String[] p = pola.get(i);
                JsonObject f = new JsonObject();
                f.addProperty("name", przytnij(p[0], 256));
                String v = p[1] == null || p[1].isBlank() ? "-" : p[1];
                f.addProperty("value", przytnij(v, 1024));
                f.addProperty("inline", v.length() <= 32);
                fields.add(f);
            }
            embed.add("fields", fields);
        }
        if (stopka != null && !stopka.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", przytnij(stopka, 2048));
            embed.add("footer", footer);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("username", NAZWA_BOTA);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // bez pingowania @everyone itp.
        payload.add("allowed_mentions", allowed);

        String adres = url;
        String json = payload.toString();
        watek.submit(() -> post(adres, json));
    }

    /** Krótkie powiadomienie o zdarzeniu (np. zatrzymanie bota). */
    public void zdarzenie(String tekst, int kolor, String stopka) {
        wyslij("🔔 " + tekst, null, kolor, null, stopka);
    }

    private void post(String adres, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(adres).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "modid-autolowienie/1.0");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int kod = c.getResponseCode();
            if (kod == 429) {
                // za dużo wiadomości naraz - Discord prosi o chwilę przerwy; ta wiadomość przepada
                return;
            }
            if (kod >= 400) {
                String tresc = "";
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) tresc = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                zglosBlad("Discord odrzucił wiadomość (kod " + kod + ")"
                        + (kod == 401 || kod == 404 ? " - sprawdź link do webhooka (/ry discord <link>)." : ". " + przytnij(tresc, 150)));
            } else {
                bladZgloszony = false;
            }
        } catch (Exception e) {
            zglosBlad("Nie udało się połączyć z Discordem: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Błąd pokazujemy w czacie tylko raz (aż do następnego udanego wysłania). */
    private void zglosBlad(String tekst) {
        System.err.println("[modid] " + tekst);
        if (bladZgloszony) return;
        bladZgloszony = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null && mc.gui.hud != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§c[Discord] " + tekst));
            }
        });
    }

    private static String przytnij(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
