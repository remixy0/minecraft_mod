package com.example.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.example.sekwencje.Sekwencje;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.Reader;
import java.io.Writer;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sterowanie przez Discorda.
 * <p>
 * Bot łączy się z Discordem na stałe (Gateway - websocket), zamiast co 3 s pytać o nowe wiadomości.
 * Dzięki temu działają:
 * <ul>
 *   <li><b>panel sterowania</b> - dla każdego bota (każdego nicku) osobna wiadomość na kanale z aktualnym
 *       stanem i przyciskami (start/stop, kopanie, turbo, slot, /home, komenda, statystyki, STOP wszystkich),</li>
 *   <li>polecenia tekstowe "!nick akcja" jak wcześniej (wymagają "Message Content Intent" w ustawieniach bota).</li>
 * </ul>
 * Polecenia i kliknięcia przyjmowane są TYLKO od właściciela (Twoje ID użytkownika Discorda).
 * Ustawienia (config/modid-discord-bot.properties): token bota, ID kanału, ID właściciela.
 */
public class DiscordSterowanie {
    // adresy można podmienić (-Dmodid.discord.api=...) tylko do testów
    private static final String API = System.getProperty("modid.discord.api", "https://discord.com/api/v10");
    private static final String GATEWAY = System.getProperty("modid.discord.gateway", "wss://gateway.discord.gg");
    private static final String UA = "DiscordBot (https://github.com/FabricMC/fabric-example-mod, 2.0)";

    private static final int INTENT_GUILDS = 1;          // kanały serwera (do #panele / #ustawienia / #alerty)
    private static final int INTENT_GUILD_MESSAGES = 1 << 9;
    private static final int INTENT_MESSAGE_CONTENT = 1 << 15;

    private static final long PANEL_MIN_ODSTEP_MS = 10_000;   // najczęściej co tyle zmieniamy panel sam z siebie
    private static final long PANEL_ODSWIEZ_MS = 120_000;     // a co tyle - nawet jak nic się nie zmieniło

    /** Polecenie tekstowe z Discorda. cel == null dla poleceń bez nicku (np. !lista). */
    public record Polecenie(String idWiadomosci, String cel, String akcja, String argument, String kanal) {
    }

    /** Kliknięcie w panelu. wartosc: wybrana opcja z listy albo tekst wpisany w okienku. */
    public record Akcja(String nick, String akcja, String wartosc, boolean zInnegoPanelu) {
    }

    private final Path plik = FabricLoader.getInstance().getConfigDir().resolve("modid-discord-bot.properties");

    /** Wszystko, co dotyczy połączenia i panelu, dzieje się w tym jednym wątku. */
    private final ScheduledExecutorService watek = Executors.newSingleThreadScheduledExecutor(r -> watekTla(r, "modid-discord-bot"));
    private final ExecutorService watkiHttp = Executors.newCachedThreadPool(r -> watekTla(r, "modid-discord-http"));
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .executor(watkiHttp)
            .build();

    private volatile String token = "", kanal = "", wlasciciel = "";
    private volatile String kanalAlertow = "";       // pusty = alerty tylko na panelu ("Ostatnia akcja")
    private volatile String kanalUstawien = "";      // pusty = menu ⚙ i statystyki jako prywatne wiadomości
    private volatile String gildia = null;           // serwer Discorda z kanałem paneli
    private final java.util.Map<String, JsonObject> kanalyGildii = new java.util.HashMap<>(); // tylko wątek "watek"
    private volatile boolean kanalyGotowe = false;
    private volatile boolean przyklejony = true;     // panel zawsze na dole kanału
    private volatile String botId = null;
    private ScheduledFuture<?> przyklejanie;

    private Consumer<Polecenie> odbiorcaPolecen = p -> { };
    private Function<Akcja, String> odbiorcaAkcji = a -> null;
    private Supplier<PanelSterowania.Stan> dostawcaStanu = () -> null;
    private boolean uruchomiony = false;

    // --- połączenie (tylko w wątku "watek", chyba że volatile) ---
    private volatile WebSocket ws;
    private volatile int polaczenieNr = 0;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> ponowne;
    private boolean ackOtrzymany = true;
    private volatile Integer sekwencja = null;
    private String sesja = null, adresWznowienia = null;
    private volatile String aplikacjaId = null;
    private volatile boolean polaczony = false;    // po READY / RESUMED
    private boolean bezTresci = false;             // brak "Message Content Intent" - działają tylko przyciski
    private int etapIntencji = 0;                  // 0: wiadomości + treść, 1: same wiadomości (bez treści), 2: nic
    private boolean zatrzymany = false;            // błąd, którego nie naprawi ponowne łączenie (np. zły token)
    private int proby = 0;
    private boolean bladZgloszony = false;
    private boolean powitanieWyslane = false;
    private volatile String stanPolaczenia = "nie połączono";

    // --- panel (tylko w wątku "watek", chyba że volatile) ---
    private volatile String mojNick = null;
    private volatile PanelSterowania.Stan ostatniStan = null;
    private volatile String panelNick = null;
    private volatile String panelId = null;
    private String ostatniPodpis = null;
    private long ostatniaEdycja = 0;
    private volatile String ostatniaAkcja = null;
    private volatile long ostatniaAkcjaCzas = 0;
    private final AtomicBoolean czekamNaStan = new AtomicBoolean(false);
    private boolean bladPaneluZgloszony = false;
    private volatile boolean wymusPanel = false;
    private volatile Sekwencje sekwencje = null;

    // ------------------------------------------------------------------
    // Ustawienia
    // ------------------------------------------------------------------

    public void wczytaj() {
        try {
            if (Files.exists(plik)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(plik, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                token = p.getProperty("token", "").trim();
                kanal = p.getProperty("kanal", "").trim();
                wlasciciel = p.getProperty("wlasciciel", "").trim();
                kanalAlertow = p.getProperty("alerty", "").trim();
                kanalUstawien = p.getProperty("ustawienia", "").trim();
                przyklejony = !"false".equalsIgnoreCase(p.getProperty("przyklejony", "true").trim());
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się wczytać ustawień bota Discorda: " + e);
        }
    }

    private void zapisz() {
        try {
            Properties p = new Properties();
            p.setProperty("token", token);
            p.setProperty("kanal", kanal);
            p.setProperty("wlasciciel", wlasciciel);
            p.setProperty("alerty", kanalAlertow);
            p.setProperty("ustawienia", kanalUstawien);
            p.setProperty("przyklejony", String.valueOf(przyklejony));
            Files.createDirectories(plik.getParent());
            try (Writer w = Files.newBufferedWriter(plik, StandardCharsets.UTF_8)) {
                p.store(w, "Bot Discorda do sterowania (NIE udostepniaj tokenu nikomu)");
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się zapisać ustawień bota Discorda: " + e);
        }
    }

    public boolean gotowy() {
        return !token.isEmpty() && !kanal.isEmpty() && !wlasciciel.isEmpty();
    }

    public String brakujace() {
        List<String> b = new ArrayList<>();
        if (token.isEmpty()) b.add("token (/ry discord bot <token>)");
        if (kanal.isEmpty()) b.add("kanał (/ry discord kanal <id>)");
        if (wlasciciel.isEmpty()) b.add("właściciel (/ry discord wlasciciel <id>)");
        return String.join(", ", b);
    }

    public void ustawToken(String t) {
        token = t.trim();
        zapisz();
        watek.execute(() -> {
            sesja = null;
            bezTresci = false;
            etapIntencji = 0;
            zatrzymany = false;
            polacz();
        });
    }

    public void ustawKanal(String k) {
        kanal = k.trim();
        zapisz();
        watek.execute(() -> {
            panelNick = null; // panel trzeba założyć na nowym kanale
            panelId = null;
            ostatniPodpis = null;
            if (!polaczony) {
                zatrzymany = false;
                polacz();
            }
        });
    }

    public void ustawWlasciciela(String w) {
        wlasciciel = w.trim();
        zapisz();
        watek.execute(() -> {
            if (!polaczony) {
                zatrzymany = false;
                polacz();
            }
        });
    }

    public void wylacz() {
        token = "";
        zapisz();
        watek.execute(() -> {
            zamknijWs();
            polaczony = false;
            stanPolaczenia = "wyłączone";
        });
    }

    /** Opis połączenia do /ry discord. */
    public String opisPolaczenia() {
        return stanPolaczenia + (bezTresci && polaczony ? " (bez poleceń !, działają przyciski)" : "");
    }

    /** Czy panel na kanale jest aktywny (wtedy nie trzeba wysyłać statusu co minutę). */
    public boolean panelDziala() {
        return polaczony && panelId != null;
    }

    /** Sekwencje startowe/końcowe i hasła - do menu ⚙ w panelu. */
    public void ustawSekwencje(Sekwencje s) {
        this.sekwencje = s;
    }

    /** Pokazuje na panelu informację jako "ostatnią akcję" (np. wynik sekwencji) i od razu odświeża panel. */
    public void ustawOstatniaAkcje(String tekst) {
        ustawOstatniaAkcje(tekst, true);
    }

    /** odswiezOdRazu = false: panel zmieni się przy najbliższym zwykłym odświeżeniu (max co 10 s - bez ryzyka limitów Discorda). */
    public void ustawOstatniaAkcje(String tekst, boolean odswiezOdRazu) {
        ostatniaAkcja = tekst;
        ostatniaAkcjaCzas = System.currentTimeMillis();
        if (odswiezOdRazu) {
            wymusPanel = true;
            watek.execute(this::poprosOStan);
        }
    }

    // ------------------------------------------------------------------
    // Start / koniec
    // ------------------------------------------------------------------

    /**
     * Uruchamia bota.
     *
     * @param polecenia polecenia tekstowe "!..." (wywoływane w wątku gry)
     * @param akcje     kliknięcia w panelu; zwraca opis wyniku albo null (wywoływane w wątku gry)
     * @param stan      migawka stanu do panelu (wywoływane w wątku gry)
     */
    public void start(Consumer<Polecenie> polecenia, Function<Akcja, String> akcje, Supplier<PanelSterowania.Stan> stan) {
        this.odbiorcaPolecen = polecenia;
        this.odbiorcaAkcji = akcje;
        this.dostawcaStanu = stan;
        if (uruchomiony) return;
        uruchomiony = true;
        // losowe opóźnienie - gdy kilka gier startuje naraz, nie logują się do Discorda w tej samej sekundzie
        watek.schedule(this::polacz, 3000 + ThreadLocalRandom.current().nextInt(4000), TimeUnit.MILLISECONDS);
        watek.scheduleWithFixedDelay(this::poprosOStan, 8, 3, TimeUnit.SECONDS);
    }

    /** Przy zamykaniu gry: panel zmienia się na "gra wyłączona" (bez przycisków). Czeka max ~3 s. */
    public void zamknij() {
        String id = panelId, k = kanal, nick = panelNick;
        if (polaczony && id != null && nick != null && !token.isEmpty()) {
            try {
                HttpRequest r = zapytanieRest("PATCH", "/channels/" + k + "/messages/" + id,
                        PanelSterowania.offline(nick).toString()).timeout(Duration.ofSeconds(3)).build();
                http.send(r, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                System.err.println("[modid] Discord: nie udało się oznaczyć panelu jako offline: " + opisBledu(e));
            }
        }
        WebSocket w = ws;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "gra wyłączona");
            } catch (Exception ignored) {
            }
        }
    }

    /** Usuwa stary panel i wstawia nowy na dole kanału (np. gdy zginął pod innymi wiadomościami). */
    public void przeniesPanelNaDol() {
        watek.execute(() -> {
            String stary = panelId;
            panelId = null;
            ostatniPodpis = null;
            if (stary != null) {
                rest("DELETE", "/channels/" + kanal + "/messages/" + stary, null);
            }
            zapiszPanel();
            poprosOStan();
        });
    }

    /** Odpowiedź na polecenie tekstowe - na tym kanale, na którym je napisano. */
    public void odpowiedz(Polecenie p, String tekst) {
        odpowiedzNaKanale(p.kanal() != null ? p.kanal() : kanal, p.idWiadomosci(), tekst);
    }

    /** Odpowiedź na polecenie tekstowe (jako "odpowiedź" do wiadomości na Discordzie). */
    public void odpowiedz(String idWiadomosci, String tekst) {
        odpowiedzNaKanale(kanal, idWiadomosci, tekst);
    }

    private void odpowiedzNaKanale(String k, String idWiadomosci, String tekst) {
        if (!gotowy()) return;
        JsonObject o = new JsonObject();
        o.addProperty("content", PanelSterowania.przytnij(tekst, 1990));
        if (idWiadomosci != null) {
            JsonObject ref = new JsonObject();
            ref.addProperty("message_id", idWiadomosci);
            ref.addProperty("fail_if_not_exists", false);
            o.add("message_reference", ref);
        }
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        allowed.addProperty("replied_user", false);
        o.add("allowed_mentions", allowed);
        String json = o.toString();
        watek.execute(() -> rest("POST", "/channels/" + k + "/messages", json));
    }

    // ------------------------------------------------------------------
    // Kanał alertów i "panel zawsze na dole"
    // ------------------------------------------------------------------

    public String kanalAlertow() {
        return kanalAlertow;
    }

    public void ustawKanalAlertow(String k) {
        kanalAlertow = k == null ? "" : k.trim();
        zapisz();
    }

    public boolean przyklejony() {
        return przyklejony;
    }

    public void ustawPrzyklejony(boolean p) {
        przyklejony = p;
        zapisz();
    }

    /** Ważne zdarzenie (np. zgubiona wędka) jako karta na kanale alertów. Bez ustawionego kanału - nic nie wysyła. */
    public void alert(String nick, String tekst, int kolor) {
        String k = kanalAlertow;
        if (k.isEmpty() || !gotowy()) return;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", PanelSterowania.przytnij("🔔 " + nick, 256));
        embed.addProperty("description", PanelSterowania.przytnij(tekst, 4000));
        embed.addProperty("color", kolor);
        embed.addProperty("timestamp", java.time.Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject o = new JsonObject();
        o.add("embeds", embeds);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        o.add("allowed_mentions", allowed);
        String json = o.toString();
        watek.execute(() -> rest("POST", "/channels/" + k + "/messages", json));
    }

    /** Czy wiadomość to panel któregoś z naszych botów (wtedy nie przenosimy swojego panelu - inaczej panele by się przeskakiwały). */
    private boolean toPanel(JsonObject w) {
        JsonObject a = w.getAsJsonObject("author");
        String b = botId;
        if (a == null || b == null || !b.equals(str(a, "id")) || !w.has("components")) return false;
        return w.get("components").toString().contains("\"custom_id\":\"mc|");
    }

    /** Ktoś napisał na kanale panelu - za kilka sekund (gdy zrobi się cicho) przenosimy panel na dół. W wątku "watek". */
    private void zaplanujPrzyklejenie() {
        if (panelId == null) return;
        if (przyklejanie != null) przyklejanie.cancel(false);
        long ms = 4000 + ThreadLocalRandom.current().nextInt(2500); // losowo - kilka botów nie przenosi paneli w tej samej chwili
        przyklejanie = watek.schedule(() -> {
            przyklejanie = null;
            if (przyklejony && polaczony && panelId != null) przeniesPanelNaDol();
        }, ms, TimeUnit.MILLISECONDS);
    }

    private void reakcja(String k, String idWiadomosci, String emoji) {
        String e = java.net.URLEncoder.encode(emoji, StandardCharsets.UTF_8);
        watek.execute(() -> rest("PUT", "/channels/" + k + "/messages/" + idWiadomosci + "/reactions/" + e + "/@me", null));
    }

    // ------------------------------------------------------------------
    // Gateway (websocket)
    // ------------------------------------------------------------------

    private void polacz() {
        if (ponowne != null) {
            ponowne.cancel(false);
            ponowne = null;
        }
        zamknijWs();
        polaczony = false;
        if (!gotowy()) {
            stanPolaczenia = "brakuje: " + brakujace();
            return;
        }
        if (zatrzymany) return;
        int nr = ++polaczenieNr;
        String baza = sesja != null && adresWznowienia != null ? adresWznowienia : GATEWAY;
        stanPolaczenia = "łączę…";
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(baza + "/?v=10&encoding=json"), new Sluchacz(nr))
                .whenComplete((w, blad) -> watek.execute(() -> {
                    if (nr != polaczenieNr) {
                        if (w != null) w.abort();
                        return;
                    }
                    if (blad != null) {
                        rozlaczono(-1, opisBledu(blad));
                    } else {
                        ws = w;
                    }
                }));
    }

    private void zamknijWs() {
        polaczenieNr++; // wiadomości ze starego połączenia będą ignorowane
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
            }
            ws = null;
        }
    }

    private final class Sluchacz implements WebSocket.Listener {
        private final int nr;
        private final StringBuilder bufor = new StringBuilder();

        Sluchacz(int nr) {
            this.nr = nr;
        }

        @Override
        public void onOpen(WebSocket w) {
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence dane, boolean ostatni) {
            bufor.append(dane);
            if (ostatni) {
                String tekst = bufor.toString();
                bufor.setLength(0);
                try {
                    odebrano(w, tekst);
                } catch (Exception e) {
                    System.err.println("[modid] Discord: błąd obsługi wiadomości: " + e);
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket w, ByteBuffer dane, boolean ostatni) {
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int kod, String powod) {
            watek.execute(() -> {
                if (nr == polaczenieNr) rozlaczono(kod, powod);
            });
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable blad) {
            // chwilę czekamy - jeśli Discord przysłał kod zamknięcia (np. zły token), on jest ważniejszy
            watek.schedule(() -> {
                if (nr == polaczenieNr) rozlaczono(-1, opisBledu(blad));
            }, 300, TimeUnit.MILLISECONDS);
        }

        /** Wywoływane w wątku websocketa. Kliknięcia obsługujemy od razu (Discord daje tylko 3 s na odpowiedź). */
        private void odebrano(WebSocket w, String tekst) {
            if (nr != polaczenieNr) return;
            JsonObject o = JsonParser.parseString(tekst).getAsJsonObject();
            if (o.has("s") && !o.get("s").isJsonNull()) {
                sekwencja = o.get("s").getAsInt();
            }
            int op = o.get("op").getAsInt();
            String typ = o.has("t") && !o.get("t").isJsonNull() ? o.get("t").getAsString() : "";
            if (op == 0 && typ.equals("INTERACTION_CREATE")) {
                obsluzInterakcje(o.getAsJsonObject("d"));
                return;
            }
            watek.execute(() -> {
                if (nr != polaczenieNr) return;
                if (ws == null) ws = w;
                obsluzGateway(op, typ, o.get("d"));
            });
        }
    }

    /** W wątku "watek". */
    private void obsluzGateway(int op, String typ, JsonElement d) {
        switch (op) {
            case 10 -> { // HELLO
                long co = d.getAsJsonObject().get("heartbeat_interval").getAsLong();
                startHeartbeat(co);
                if (sesja != null) {
                    JsonObject r = new JsonObject();
                    r.addProperty("token", token);
                    r.addProperty("session_id", sesja);
                    if (sekwencja != null) r.addProperty("seq", sekwencja);
                    wyslij(6, r); // RESUME
                } else {
                    JsonObject id = new JsonObject();
                    id.addProperty("token", token);
                    id.addProperty("intents", INTENT_GUILDS | (etapIntencji == 0 ? INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT
                            : etapIntencji == 1 ? INTENT_GUILD_MESSAGES : 0));
                    JsonObject wl = new JsonObject();
                    wl.addProperty("os", System.getProperty("os.name", "java"));
                    wl.addProperty("browser", "modid");
                    wl.addProperty("device", "modid");
                    id.add("properties", wl);
                    wyslij(2, id); // IDENTIFY
                }
            }
            case 11 -> ackOtrzymany = true; // HEARTBEAT ACK
            case 1 -> wyslijHeartbeat();    // Discord prosi o heartbeat teraz
            case 7 -> {                     // RECONNECT - połącz się jeszcze raz i wznów sesję
                zamknijWs();
                zaplanujPolaczenie(500);
            }
            case 9 -> {                     // INVALID SESSION
                boolean moznaWznowic = d != null && d.isJsonPrimitive() && d.getAsBoolean();
                if (!moznaWznowic) {
                    sesja = null;
                    adresWznowienia = null;
                    sekwencja = null;
                }
                zamknijWs();
                zaplanujPolaczenie(1000 + ThreadLocalRandom.current().nextInt(4000));
            }
            case 0 -> zdarzenie(typ, d == null || d.isJsonNull() ? new JsonObject() : d.getAsJsonObject());
            default -> {
            }
        }
    }

    private void zdarzenie(String typ, JsonObject d) {
        switch (typ) {
            case "READY" -> {
                sesja = str(d, "session_id");
                adresWznowienia = str(d, "resume_gateway_url");
                if (d.has("application")) aplikacjaId = str(d.getAsJsonObject("application"), "id");
                String nazwa = d.has("user") ? str(d.getAsJsonObject("user"), "username") : "bot";
                if (d.has("user")) botId = str(d.getAsJsonObject("user"), "id");
                polaczonoOK("połączony jako " + nazwa);
                if (!powitanieWyslane) {
                    powitanieWyslane = true;
                    doCzatu("§a[Discord] Bot połączony jako §f" + nazwa + "§a - panel sterowania jest na kanale."
                            + (bezTresci ? "" : " §7Polecenia: !lista, !<nick> pomoc"));
                }
                poprosOStan();
            }
            case "RESUMED" -> polaczonoOK(stanPolaczenia.startsWith("połączony") ? stanPolaczenia : "połączony");
            case "MESSAGE_CREATE" -> wiadomoscNaKanale(d);
            case "GUILD_CREATE" -> gildia(d);
            case "CHANNEL_CREATE", "CHANNEL_UPDATE" -> kanalZmieniony(d, false);
            case "CHANNEL_DELETE" -> kanalZmieniony(d, true);
            default -> {
            }
        }
    }

    private void polaczonoOK(String opis) {
        polaczony = true;
        proby = 0;
        stanPolaczenia = opis;
        if (bladZgloszony) {
            bladZgloszony = false;
            doCzatu("§a[Discord] Znowu połączono z Discordem.");
        }
    }

    private void startHeartbeat(long coIle) {
        if (heartbeat != null) heartbeat.cancel(false);
        ackOtrzymany = true;
        long pierwszy = (long) (coIle * ThreadLocalRandom.current().nextDouble());
        heartbeat = watek.scheduleAtFixedRate(() -> {
            if (!ackOtrzymany) {
                // Discord nie odpowiedział na poprzedni heartbeat - połączenie jest martwe
                rozlaczono(-2, "Discord przestał odpowiadać");
                return;
            }
            ackOtrzymany = false;
            wyslijHeartbeat();
        }, pierwszy, coIle, TimeUnit.MILLISECONDS);
    }

    private void wyslijHeartbeat() {
        JsonObject o = new JsonObject();
        o.addProperty("op", 1);
        Integer s = sekwencja;
        if (s != null) o.addProperty("d", s);
        else o.add("d", JsonNull.INSTANCE);
        wyslijTekst(o.toString());
    }

    private void wyslij(int op, JsonObject d) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("d", d);
        wyslijTekst(o.toString());
    }

    private void wyslijTekst(String json) {
        WebSocket w = ws;
        if (w == null) return;
        try {
            w.sendText(json, true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // zwykle znaczy, że Discord właśnie zamyka połączenie - jego kod zamknięcia (onClose) ma pierwszeństwo
            int nr = polaczenieNr;
            String opis = opisBledu(e);
            watek.schedule(() -> {
                if (nr == polaczenieNr) rozlaczono(-1, opis);
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    /** Połączenie się zerwało. Decydujemy, czy i kiedy łączyć się ponownie. */
    private void rozlaczono(int kod, String powod) {
        zamknijWs();
        polaczony = false;
        String opis = powod == null || powod.isBlank() ? "kod " + kod : powod;
        System.err.println("[modid] Discord: rozłączono (" + kod + "): " + opis);
        switch (kod) {
            case 4004 -> {
                zatrzymany = true;
                stanPolaczenia = "zły token bota";
                doCzatu("§c[Discord] Zły token bota - ustaw nowy: /ry discord bot <token>");
                return;
            }
            case 4013, 4014 -> {
                // "Message Content Intent" nie jest włączony - łączymy się bez niego (przyciski nadal działają)
                etapIntencji = Math.min(2, etapIntencji + 1);
                if (!bezTresci) {
                    bezTresci = true;
                    doCzatu("§e[Discord] Polecenia !... nie zadziałają: w Discord Developer Portal → Twoja aplikacja → Bot"
                            + " włącz §fMessage Content Intent§e. Przyciski w panelu działają i bez tego.");
                }
                sesja = null;
                zaplanujPolaczenie(1000);
                return;
            }
            case 4010, 4011, 4012 -> {
                zatrzymany = true;
                stanPolaczenia = "Discord odrzucił połączenie (" + kod + ")";
                doCzatu("§c[Discord] Discord odrzucił połączenie bota (kod " + kod + ").");
                return;
            }
            case 4007, 4009 -> sesja = null; // nie da się wznowić - zaloguj od nowa
            default -> {
            }
        }
        proby++;
        stanPolaczenia = "brak połączenia: " + opis + " - łączę ponownie…";
        if (!bladZgloszony && proby >= 2) {
            // pierwszy krótki zryw ignorujemy - piszemy dopiero, gdy ponowne łączenie się nie udało
            bladZgloszony = true;
            doCzatu("§c[Discord] Brak połączenia z Discordem: " + opis + ". §7Łączę ponownie w tle.");
        }
        long ms = Math.min(60_000, 1000L << Math.min(proby, 6)) + ThreadLocalRandom.current().nextInt(1000);
        zaplanujPolaczenie(ms);
    }

    private void zaplanujPolaczenie(long ms) {
        if (ponowne != null) ponowne.cancel(false);
        ponowne = watek.schedule(this::polacz, ms, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Kanały: kategoria "MINECRAFT BOTY" z #panele, #ustawienia, #alerty
    // ------------------------------------------------------------------

    public static final String KATEGORIA = "MINECRAFT BOTY";
    private static final String[][] KANALY = {
            {"panele", "Panele sterowania botów Minecraft – każdy bot ma tu swój panel"},
            {"ustawienia", "Sekwencje, hasła, harmonogram i statystyki botów"},
            {"alerty", "Ważne zdarzenia z gry (zgubiona wędka, zatrzymanie bota...)"}};

    public String kanalUstawien() {
        return kanalUstawien;
    }

    /** GUILD_CREATE: zapamiętujemy kanały serwera Discorda, na którym jest kanał paneli. W wątku "watek". */
    private void gildia(JsonObject d) {
        JsonArray ch = d.getAsJsonArray("channels");
        if (ch == null) return;
        boolean nasza = false;
        for (JsonElement e : ch) {
            String id = str(e.getAsJsonObject(), "id");
            if (id != null && (id.equals(kanal) || id.equals(kanalUstawien) || id.equals(kanalAlertow))) nasza = true;
        }
        if (!nasza) return;
        gildia = str(d, "id");
        kanalyGildii.clear();
        for (JsonElement e : ch) {
            JsonObject c = e.getAsJsonObject();
            if (str(c, "id") != null) kanalyGildii.put(str(c, "id"), c);
        }
        dopasujKanaly();
    }

    /** CHANNEL_CREATE / UPDATE / DELETE - np. gdy inny bot utworzył kanały. W wątku "watek". */
    private void kanalZmieniony(JsonObject d, boolean usuniety) {
        String g = gildia;
        if (g == null || !g.equals(str(d, "guild_id")) || str(d, "id") == null) return;
        if (usuniety) kanalyGildii.remove(str(d, "id"));
        else kanalyGildii.put(str(d, "id"), d);
        dopasujKanaly();
    }

    private String kategoria() {
        for (JsonObject c : kanalyGildii.values()) {
            if (typKanalu(c) == 4 && KATEGORIA.equalsIgnoreCase(str(c, "name"))) return str(c, "id");
        }
        return null;
    }

    private String kanalWKategorii(String kat, String nazwa) {
        if (kat == null) return null;
        for (JsonObject c : kanalyGildii.values()) {
            if (typKanalu(c) == 0 && kat.equals(str(c, "parent_id")) && nazwa.equalsIgnoreCase(str(c, "name"))) return str(c, "id");
        }
        return null;
    }

    private static int typKanalu(JsonObject c) {
        return c.has("type") && !c.get("type").isJsonNull() ? c.get("type").getAsInt() : -1;
    }

    /** Jeśli są nasze kanały w kategorii - używamy ich (panele przenoszą się na #panele). */
    private void dopasujKanaly() {
        String kat = kategoria();
        String pan = kanalWKategorii(kat, "panele"), ust = kanalWKategorii(kat, "ustawienia"), ale = kanalWKategorii(kat, "alerty");
        boolean zmiana = false;
        if (ust != null && !ust.equals(kanalUstawien)) {
            kanalUstawien = ust;
            zmiana = true;
        }
        if (ale != null && !ale.equals(kanalAlertow)) {
            kanalAlertow = ale;
            zmiana = true;
        }
        if (pan != null && !pan.equals(kanal)) {
            przeniesNaKanal(pan);
            zmiana = true;
        }
        kanalyGotowe = pan != null && ust != null && ale != null;
        if (zmiana) {
            zapisz();
            doCzatu("§a[Discord] Używam kanałów z kategorii " + KATEGORIA + ": #panele, #ustawienia, #alerty.");
        }
    }

    /** Przenosi panel na inny kanał: stary usuwamy, nowy powstanie przy najbliższym odświeżeniu. W wątku "watek". */
    private void przeniesNaKanal(String nowy) {
        String stary = panelId, staryKanal = kanal;
        kanal = nowy;
        panelId = null;
        ostatniPodpis = null;
        zapiszPanel();
        if (stary != null && !staryKanal.isEmpty()) {
            rest("DELETE", "/channels/" + staryKanal + "/messages/" + stary, null);
        }
        poprosOStan();
    }

    /** Tworzy kategorię i brakujące kanały. Zwraca opis wyniku. W wątku "watek". */
    private String utworzKanaly() {
        String g = gildia;
        if (g == null) return "❌ nie znalazłem serwera Discorda, na którym jest kanał paneli";
        String kat = kategoria();
        if (kat == null) {
            JsonObject b = new JsonObject();
            b.addProperty("name", KATEGORIA);
            b.addProperty("type", 4);
            Odp r = rest("POST", "/guilds/" + g + "/channels", b.toString());
            if (!r.ok()) return bladKanalow(r);
            JsonObject c = JsonParser.parseString(r.tresc()).getAsJsonObject();
            kanalyGildii.put(str(c, "id"), c);
            kat = str(c, "id");
        }
        for (String[] k : KANALY) {
            if (kanalWKategorii(kat, k[0]) != null) continue;
            JsonObject b = new JsonObject();
            b.addProperty("name", k[0]);
            b.addProperty("type", 0);
            b.addProperty("parent_id", kat);
            b.addProperty("topic", k[1]);
            Odp r = rest("POST", "/guilds/" + g + "/channels", b.toString());
            if (!r.ok()) return bladKanalow(r);
            JsonObject c = JsonParser.parseString(r.tresc()).getAsJsonObject();
            kanalyGildii.put(str(c, "id"), c);
        }
        dopasujKanaly();
        return "🏗 kanały gotowe: <#" + kanal + "> · <#" + kanalUstawien + "> · <#" + kanalAlertow + "> – panele są teraz na <#" + kanal + ">";
    }

    private static String bladKanalow(Odp r) {
        return r.kod() == 403
                ? "❌ bot nie ma uprawnienia „Zarządzanie kanałami” – otwórz nowy link zaproszenia (permissions=85072)"
                : "❌ nie udało się utworzyć kanałów (" + (r.kod() == -1 ? r.tresc() : "kod " + r.kod()) + ")";
    }

    // ------------------------------------------------------------------
    // Polecenia tekstowe
    // ------------------------------------------------------------------

    private void wiadomoscNaKanale(JsonObject w) {
        String kanalWiad = str(w, "channel_id");
        if (kanalWiad == null) return;
        boolean naPanelu = kanal.equals(kanalWiad);
        boolean naAlertach = !kanalAlertow.isEmpty() && kanalAlertow.equals(kanalWiad);
        JsonObject autor = w.getAsJsonObject("author");
        if (autor == null) return;
        if (naPanelu && przyklejony && !toPanel(w)) {
            zaplanujPrzyklejenie(); // nowa wiadomość pod panelem - panel wróci na dół
        }
        if (autor.has("bot") && autor.get("bot").getAsBoolean()) return;
        if (!wlasciciel.equals(str(autor, "id"))) return; // tylko Ty możesz sterować
        String tresc = w.has("content") ? w.get("content").getAsString().trim() : "";

        // "!alerty" na dowolnym kanale: tu mają przychodzić alerty ("!alerty off" - wyłącz)
        String male = tresc.toLowerCase(java.util.Locale.ROOT);
        if (male.equals("!alerty") || male.startsWith("!alerty ")) {
            String arg = male.substring(7).trim();
            if (arg.equals("off") || arg.startsWith("wyl") || arg.startsWith("wył")) {
                ustawKanalAlertow("");
                doCzatu("§7[Discord] Alerty wyłączone (zostają tylko na panelu).");
            } else {
                ustawKanalAlertow(kanalWiad);
                doCzatu("§a[Discord] Alerty będą przychodzić na kanał " + kanalWiad + ".");
            }
            reakcja(kanalWiad, str(w, "id"), "✅");
            return;
        }
        if (!naPanelu && !naAlertach) return;
        Polecenie p = parsuj(str(w, "id"), tresc, kanalWiad);
        if (p != null) {
            Minecraft.getInstance().execute(() -> odbiorcaPolecen.accept(p));
        }
    }

    /** "!leosik13 turbo 5" -> cel=leosik13, akcja=turbo, argument=5;  "!lista" -> cel=null, akcja=lista */
    private static Polecenie parsuj(String id, String tresc, String kanalWiad) {
        if (!tresc.startsWith("!") || tresc.length() < 2) return null;
        String[] cz = tresc.substring(1).trim().split("\\s+", 3);
        if (cz.length == 1) {
            return new Polecenie(id, null, cz[0].toLowerCase(), "", kanalWiad);
        }
        return new Polecenie(id, cz[0], cz[1].toLowerCase(), cz.length > 2 ? cz[2].trim() : "", kanalWiad);
    }

    // ------------------------------------------------------------------
    // Kliknięcia w panelu
    // ------------------------------------------------------------------

    /** Akcje "dla wszystkich botów" - wykonuje je każdy klient, odpowiada tylko bot, do którego należy kliknięty panel. */
    private static boolean dlaWszystkich(String akcja) {
        return akcja.equals("stopall") || akcja.equals("seqstartall") || akcja.equals("seqkoniecall");
    }

    /** Wywoływane w wątku websocketa, od razu po przyjściu kliknięcia. */
    private void obsluzInterakcje(JsonObject d) {
        int typ = d.has("type") ? d.get("type").getAsInt() : 0;
        if (typ != 3 && typ != 5) return; // 3 = przycisk/lista, 5 = wysłane okienko
        JsonObject dane = d.getAsJsonObject("data");
        String customId = dane == null ? null : str(dane, "custom_id");
        if (customId == null || !customId.startsWith("mc|")) return;
        String[] cz = customId.split("\\|", 3);
        if (cz.length < 3) return;
        String nickPanelu = cz[1];
        boolean zUstawien = cz[2].startsWith("u:"); // kliknięcie w menu ⚙ (prywatna wiadomość)
        String akcja = zUstawien ? cz[2].substring(2) : cz[2];

        String moj = mojNick;
        boolean mojPanel = moj != null && moj.equalsIgnoreCase(nickPanelu);
        if (!mojPanel && !dlaWszystkich(akcja)) return; // panel innego bota - on odpowie

        String id = str(d, "id"), tokenInterakcji = str(d, "token");
        String kto = d.has("member") ? str(d.getAsJsonObject("member").getAsJsonObject("user"), "id")
                : d.has("user") ? str(d.getAsJsonObject("user"), "id") : null;
        if (!wlasciciel.equals(kto)) {
            if (mojPanel) {
                odpowiedzInterakcji(id, tokenInterakcji, PanelSterowania.tekstPrywatny("⛔ Tylko właściciel bota może nim sterować."));
            }
            return;
        }

        String wartosc = "";
        if (typ == 3 && dane.has("values")) {
            JsonArray v = dane.getAsJsonArray("values");
            if (!v.isEmpty()) wartosc = v.get(0).getAsString();
        } else if (typ == 5) {
            wartosc = wartoscZOkienka(dane);
        }

        if (mojPanel && odpowiedzOdRazu(typ, akcja, nickPanelu, wartosc, id, tokenInterakcji)) return;

        // Pozostałe: od razu potwierdzamy, a wiadomość zmieniamy, gdy gra wykona akcję
        CompletableFuture<?> potwierdzono = mojPanel
                ? odpowiedzInterakcji(id, tokenInterakcji, PanelSterowania.potwierdzenie())
                : CompletableFuture.completedFuture(null);
        String appId = str(d, "application_id");
        String idWiadomosci = d.has("message") ? str(d.getAsJsonObject("message"), "id") : null;

        String kanalWiad = str(d, "channel_id");
        if (akcja.equals("nadol")) {
            if (!mojPanel) return;
            przeniesPanelNaDol();
            if (zUstawien) {
                watek.execute(() -> zmienUstawienia(appId, tokenInterakcji, potwierdzono, nickPanelu, "📌 panel przeniesiony na dół kanału",
                        kanalWiad, idWiadomosci));
            }
            return;
        }
        if (akcja.equals("usun")) {
            if (!mojPanel) return;
            watek.execute(() -> {
                czekaj(potwierdzono);
                Odp r = kanalWiad != null && idWiadomosci != null
                        ? rest("DELETE", "/channels/" + kanalWiad + "/messages/" + idWiadomosci, null) : new Odp(-1, "");
                if (!r.ok() && appId != null) {
                    rest("DELETE", "/webhooks/" + appId + "/" + tokenInterakcji + "/messages/@original", null); // np. prywatna wiadomość
                }
            });
            return;
        }
        if (akcja.equals("kanaly")) {
            if (!mojPanel) return;
            watek.execute(() -> zmienUstawienia(appId, tokenInterakcji, potwierdzono, nickPanelu, utworzKanaly(), kanalWiad, idWiadomosci));
            return;
        }
        Akcja a = new Akcja(nickPanelu, akcja, wartosc, !mojPanel);
        Minecraft.getInstance().execute(() -> {
            String wynik;
            try {
                wynik = odbiorcaAkcji.apply(a);
            } catch (Exception e) {
                wynik = "❌ błąd: " + e.getMessage();
                e.printStackTrace();
            }
            PanelSterowania.Stan s = bezpiecznyStan();
            String w = wynik;
            watek.execute(() -> poAkcji(mojPanel, zUstawien, appId, tokenInterakcji, kanalWiad, idWiadomosci, potwierdzono, w, s));
        });
    }

    /** Odpowiedzi, które nie potrzebują gry: okienka, statystyki, menu ⚙. Zwraca true, jeśli obsłużono. */
    private boolean odpowiedzOdRazu(int typ, String akcja, String nick, String wartosc, String id, String tok) {
        Sekwencje sek = sekwencje;
        JsonObject odp = null;
        if (typ == 3) {
            PanelSterowania.Stan s = ostatniStan;
            odp = switch (akcja) {
                case "home" -> PanelSterowania.okienkoHome(nick, s != null ? s.home() : "");
                case "komenda" -> PanelSterowania.okienkoKomendy(nick);
                case "turbo" -> PanelSterowania.okienkoTurbo(nick);
                case "staty" -> {
                    if (s == null) yield PanelSterowania.tekstPrywatny("Chwilka - bot jeszcze zbiera dane.");
                    String ku = kanalUstawien;
                    if (ku.isEmpty()) yield PanelSterowania.statystykiPrywatne(s);
                    String json = PanelSterowania.statystykiDane(s, true).toString();
                    watek.execute(() -> rest("POST", "/channels/" + ku + "/messages", json));
                    yield PanelSterowania.tekstPrywatny("📊 Statystyki **" + nick + "** są na <#" + ku + ">");
                }
                case "serwer" -> wartosc.equals("!inny") ? PanelSterowania.okienkoSerwera(nick) : null;
                case "ustawienia" -> {
                    if (sek == null) yield null;
                    String ku = kanalUstawien;
                    if (ku.isEmpty()) yield PanelSterowania.odpowiedzUstawienia(4, ustawienia(nick, null));
                    String json = ustawienia(nick, null).toString();
                    watek.execute(() -> rest("POST", "/channels/" + ku + "/messages", json));
                    yield PanelSterowania.tekstPrywatny("⚙ Ustawienia **" + nick + "** są na <#" + ku + ">");
                }
                case "harm" -> sek == null ? null : PanelSterowania.okienkoHarmonogramu(nick, sek.tekstHarmonogramu(nick));
                case "edstart", "edserwer", "edkoniec" -> {
                    if (sek == null) yield null;
                    String jaka = akcja.equals("edserwer") ? Sekwencje.SERWER : akcja.equals("edkoniec") ? Sekwencje.KONIEC : Sekwencje.START;
                    yield PanelSterowania.okienkoSekwencji(nick, jaka, sek.tekst(nick, jaka));
                }
                case "haslo" -> PanelSterowania.okienkoHasla(nick);
                default -> null;
            };
        } else if (sek != null) {
            switch (akcja) {
                case "edstartok", "edserwerok", "edkoniecok" -> {
                    String jaka = akcja.equals("edserwerok") ? Sekwencje.SERWER : akcja.equals("edkoniecok") ? Sekwencje.KONIEC : Sekwencje.START;
                    String bledy = sek.zapisz(nick, jaka, wartosc);
                    String kom = bledy == null
                            ? "✅ zapisano sekwencję " + Sekwencje.nazwa(jaka)
                            : "❌ **nie zapisano** - popraw i wklej jeszcze raz:\n" + bledy
                            + "\n\nTwój tekst:\n```\n" + wartosc.replace("```", "'''") + "\n```";
                    odp = PanelSterowania.odpowiedzUstawienia(7, ustawienia(nick, kom));
                }
                case "harmok" -> {
                    String bledy = sek.zapiszHarmonogram(nick, wartosc);
                    String kom = bledy == null
                            ? "✅ zapisano harmonogram" + (sek.nastepnyHarmonogram(nick) != null ? " – następne: **" + sek.nastepnyHarmonogram(nick) + "**" : "")
                            : "❌ **nie zapisano** - popraw i wklej jeszcze raz:\n" + bledy
                            + "\n\nTwój tekst:\n```\n" + wartosc.replace("```", "'''") + "\n```";
                    odp = PanelSterowania.odpowiedzUstawienia(7, ustawienia(nick, kom));
                }
                case "haslook" -> {
                    sek.ustawHaslo(nick, wartosc);
                    odp = PanelSterowania.odpowiedzUstawienia(7, ustawienia(nick, wartosc.isBlank() ? "🔑 hasło usunięte" : "🔑 hasło zapisane"));
                }
                default -> {
                }
            }
        }
        if (odp == null) return false;
        odpowiedzInterakcji(id, tok, odp);
        return true;
    }

    private JsonObject ustawienia(String nick, String komunikat) {
        Sekwencje sek = sekwencje;
        return PanelSterowania.ustawienia(new PanelSterowania.DaneUstawien(nick, sek.tekst(nick, Sekwencje.START),
                sek.tekst(nick, Sekwencje.SERWER), sek.tekst(nick, Sekwencje.KONIEC),
                sek.tekstHarmonogramu(nick), sek.nastepnyHarmonogram(nick), sek.maHaslo(nick), sek.postep(),
                kanal, kanalUstawien, kanalAlertow, kanalyGotowe, komunikat));
    }

    /** W wątku "watek": zmienia menu ⚙, w którym kliknięto (prywatne albo na #ustawienia). */
    private void zmienUstawienia(String appId, String tokenInterakcji, CompletableFuture<?> potwierdzono, String nick, String komunikat,
                                 String kanalWiad, String idWiadomosci) {
        if (appId == null || sekwencje == null) return;
        czekaj(potwierdzono);
        String json = ustawienia(nick, komunikat).toString();
        Odp r = rest("PATCH", "/webhooks/" + appId + "/" + tokenInterakcji + "/messages/@original", json);
        if (!r.ok() && kanalWiad != null && idWiadomosci != null) {
            rest("PATCH", "/channels/" + kanalWiad + "/messages/" + idWiadomosci, json);
        }
    }

    private static void czekaj(CompletableFuture<?> f) {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** W wątku "watek": zapamiętuje wynik i odświeża panel (albo menu ⚙). */
    private void poAkcji(boolean mojPanel, boolean zUstawien, String appId, String tokenInterakcji, String kanalWiad, String idWiadomosci,
                         CompletableFuture<?> potwierdzono, String wynik, PanelSterowania.Stan s) {
        if (wynik != null) {
            ostatniaAkcja = wynik;
            ostatniaAkcjaCzas = System.currentTimeMillis();
        }
        if (s != null) {
            ostatniStan = s;
            mojNick = s.nick();
        }
        if (mojPanel && zUstawien) {
            zmienUstawienia(appId, tokenInterakcji, potwierdzono, s != null ? s.nick() : mojNick, wynik, kanalWiad, idWiadomosci);
        } else if (mojPanel && s != null && appId != null && idWiadomosci != null && !idWiadomosci.equals(panelId)) {
            // kliknięto w stary panel (np. sprzed przeniesienia) - też go odświeżamy
            try {
                potwierdzono.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            JsonObject tresc = PanelSterowania.wiadomosc(s, ostatniaAkcja, ostatniaAkcjaCzas);
            PanelSterowania.dodajCzas(tresc);
            rest("PATCH", "/webhooks/" + appId + "/" + tokenInterakcji + "/messages/@original", tresc.toString());
        }
        if (s != null) odswiezPanel(s, true);
    }

    private static String wartoscZOkienka(JsonObject dane) {
        if (!dane.has("components")) return "";
        for (JsonElement rzad : dane.getAsJsonArray("components")) {
            JsonObject r = rzad.getAsJsonObject();
            // stary format: rząd z polem tekstowym; nowy: "label" z polem w "component"
            List<JsonObject> pola = new ArrayList<>();
            if (r.has("components")) {
                for (JsonElement e : r.getAsJsonArray("components")) pola.add(e.getAsJsonObject());
            }
            if (r.has("component")) pola.add(r.getAsJsonObject("component"));
            for (JsonObject p : pola) {
                if ("wartosc".equals(str(p, "custom_id")) && p.has("value")) {
                    return p.get("value").getAsString().trim();
                }
            }
        }
        return "";
    }

    /** Odpowiedź na kliknięcie (musi przyjść w ciągu 3 s). Wysyłana od razu, bez czekania w kolejce. */
    private CompletableFuture<?> odpowiedzInterakcji(String id, String tokenInterakcji, JsonObject odp) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(API + "/interactions/" + id + "/" + tokenInterakcji + "/callback"))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(odp.toString()))
                .build();
        return http.sendAsync(r, HttpResponse.BodyHandlers.ofString()).whenComplete((res, blad) -> {
            if (blad != null) {
                System.err.println("[modid] Discord: nie udało się odpowiedzieć na kliknięcie: " + opisBledu(blad));
            } else if (res.statusCode() >= 400) {
                System.err.println("[modid] Discord: odpowiedź na kliknięcie odrzucona (" + res.statusCode() + "): "
                        + PanelSterowania.przytnij(res.body(), 300));
            }
        });
    }

    // ------------------------------------------------------------------
    // Panel
    // ------------------------------------------------------------------

    /** Co kilka sekund: pobieramy stan z gry i odświeżamy panel, jeśli coś się zmieniło. */
    private void poprosOStan() {
        if (!polaczony || !czekamNaStan.compareAndSet(false, true)) return;
        Minecraft.getInstance().execute(() -> {
            PanelSterowania.Stan s = bezpiecznyStan();
            czekamNaStan.set(false);
            if (s != null) watek.execute(() -> odswiezPanel(s, false));
        });
    }

    private PanelSterowania.Stan bezpiecznyStan() {
        try {
            return dostawcaStanu.get();
        } catch (Exception e) {
            System.err.println("[modid] Discord: nie udało się przygotować panelu: " + e);
            return null;
        }
    }

    /** W wątku "watek". */
    private void odswiezPanel(PanelSterowania.Stan s, boolean wymus) {
        if (s == null || s.nick() == null) return;
        ostatniStan = s;
        mojNick = s.nick();
        if (!polaczony) return;
        if (!s.nick().equals(panelNick)) {
            panelNick = s.nick();
            panelId = wczytajPanel(s.nick());
            ostatniPodpis = null;
        }
        JsonObject tresc = PanelSterowania.wiadomosc(s, ostatniaAkcja, ostatniaAkcjaCzas);
        String podpis = tresc.toString();
        PanelSterowania.dodajCzas(tresc);
        long teraz = System.currentTimeMillis();

        if (panelId == null) {
            utworzPanel(tresc, podpis);
            return;
        }
        if (wymusPanel) {
            wymusPanel = false;
            wymus = true;
        }
        if (!wymus) {
            boolean zmiana = !podpis.equals(ostatniPodpis);
            if (zmiana && teraz - ostatniaEdycja < PANEL_MIN_ODSTEP_MS) return;
            if (!zmiana && teraz - ostatniaEdycja < PANEL_ODSWIEZ_MS) return;
        }
        Odp r = rest("PATCH", "/channels/" + kanal + "/messages/" + panelId, tresc.toString());
        if (r.kod() == 404) {
            // ktoś usunął panel - zakładamy nowy
            panelId = null;
            utworzPanel(tresc, podpis);
        } else if (r.ok()) {
            ostatniPodpis = podpis;
            ostatniaEdycja = teraz;
            bladPaneluZgloszony = false;
        } else {
            ostatniaEdycja = teraz; // nie próbujemy co 3 s
            zglosBladPanelu(r);
        }
    }

    private void utworzPanel(JsonObject tresc, String podpis) {
        Odp r = rest("POST", "/channels/" + kanal + "/messages", tresc.toString());
        ostatniaEdycja = System.currentTimeMillis();
        if (!r.ok()) {
            zglosBladPanelu(r);
            return;
        }
        try {
            panelId = JsonParser.parseString(r.tresc()).getAsJsonObject().get("id").getAsString();
            ostatniPodpis = podpis;
            bladPaneluZgloszony = false;
            zapiszPanel();
        } catch (Exception e) {
            System.err.println("[modid] Discord: dziwna odpowiedź przy tworzeniu panelu: " + e);
        }
    }

    private void zglosBladPanelu(Odp r) {
        if (bladPaneluZgloszony) return;
        bladPaneluZgloszony = true;
        String opis = switch (r.kod()) {
            case 401 -> "zły token bota";
            case 403 -> "bot nie ma uprawnień na kanale - daj mu: Wyświetlanie kanału, Wysyłanie wiadomości,"
                    + " Osadzanie linków (Embed Links) i Czytanie historii wiadomości";
            case 404 -> "nie ma takiego kanału (sprawdź ID: /ry discord kanal <id>)";
            case -1 -> r.tresc();
            default -> "kod " + r.kod() + " " + PanelSterowania.przytnij(r.tresc(), 150);
        };
        doCzatu("§c[Discord] Nie udało się pokazać panelu: " + opis);
    }

    /** ID panelu zapisujemy osobno dla każdego nicku (kilka gier może mieć ten sam folder config). */
    private Path plikPanelu(String nick) {
        return plik.resolveSibling("modid-discord-panel-" + nick.replaceAll("[^A-Za-z0-9_-]", "_") + ".properties");
    }

    private String wczytajPanel(String nick) {
        try {
            Path p = plikPanelu(nick);
            if (!Files.exists(p)) return null;
            Properties pr = new Properties();
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                pr.load(r);
            }
            String id = pr.getProperty("panel", "").trim();
            return !id.isEmpty() && kanal.equals(pr.getProperty("kanal", "").trim()) ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void zapiszPanel() {
        if (panelNick == null) return;
        try {
            Properties pr = new Properties();
            pr.setProperty("kanal", kanal);
            pr.setProperty("panel", panelId == null ? "" : panelId);
            try (Writer w = Files.newBufferedWriter(plikPanelu(panelNick), StandardCharsets.UTF_8)) {
                pr.store(w, "ID wiadomosci z panelem sterowania dla " + panelNick);
            }
        } catch (Exception e) {
            System.err.println("[modid] Discord: nie udało się zapisać ID panelu: " + e);
        }
    }

    // ------------------------------------------------------------------
    // REST
    // ------------------------------------------------------------------

    private record Odp(int kod, String tresc) {
        boolean ok() {
            return kod >= 200 && kod < 300;
        }
    }

    private HttpRequest.Builder zapytanieRest(String metoda, String sciezka, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + sciezka))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", UA);
        if (json != null) {
            b.header("Content-Type", "application/json")
                    .method(metoda, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            b.method(metoda, HttpRequest.BodyPublishers.noBody());
        }
        return b;
    }

    /** Zapytanie do API Discorda (w wątku "watek"). Przy limicie zapytań czeka, ile każe Discord, i próbuje raz jeszcze. */
    private Odp rest(String metoda, String sciezka, String json) {
        try {
            for (int proba = 0; ; proba++) {
                HttpResponse<String> r = http.send(zapytanieRest(metoda, sciezka, json).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() == 429 && proba == 0) {
                    double sek = 1;
                    try {
                        sek = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble();
                    } catch (Exception ignored) {
                        sek = r.headers().firstValue("Retry-After").map(Double::parseDouble).orElse(1.0);
                    }
                    Thread.sleep((long) (Math.min(sek, 10) * 1000) + 100);
                    continue;
                }
                if (r.statusCode() >= 400) {
                    System.err.println("[modid] Discord: " + metoda + " " + sciezka.replaceAll("/webhooks/.*", "/webhooks/…")
                            + " -> " + r.statusCode() + " " + PanelSterowania.przytnij(r.body(), 200));
                }
                return new Odp(r.statusCode(), r.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Odp(-1, "przerwano");
        } catch (Exception e) {
            String opis = opisBledu(e);
            System.err.println("[modid] Discord: " + metoda + " nie powiodło się: " + opis);
            return new Odp(-1, opis);
        }
    }

    // ------------------------------------------------------------------

    private static String str(JsonObject o, String klucz) {
        if (o == null || !o.has(klucz) || o.get(klucz).isJsonNull()) return null;
        return o.get(klucz).getAsString();
    }

    /** Zrozumiały opis błędu sieci. */
    private static String opisBledu(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return "nie można znaleźć discord.com (brak internetu albo Discord jest zablokowany w tej sieci)";
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return "Discord nie odpowiada (przekroczono czas)";
            }
            if (t instanceof WebSocketHandshakeException) {
                return "Discord odrzucił połączenie";
            }
            if (t instanceof ConnectException) {
                return "nie można połączyć się z Discordem";
            }
            if (t.getCause() == null) {
                return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
        }
        return "nieznany błąd";
    }

    private static void doCzatu(String tekst) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null && mc.gui.hud != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal(tekst));
            }
        });
    }

    private static Thread watekTla(Runnable r, String nazwa) {
        Thread t = new Thread(r, nazwa);
        t.setDaemon(true);
        return t;
    }
}
