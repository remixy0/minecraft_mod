package com.example;

import com.example.controler.Controler;
import com.example.network.MojSerwer;
import com.example.ruch.NaturalnyRuch;
import com.example.ruch.ReakcjaNaObrot;
import com.example.ruch.SpacerWokol;
import com.example.stats.Statystyki;
import com.example.discord.DiscordWebhook;
import com.example.discord.DiscordSterowanie;
import com.example.discord.PanelSterowania;
import com.example.sekwencje.MenuSerwera;
import com.example.sekwencje.Sekwencje;
import com.example.sekwencje.Serwery;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.tags.ItemTags;
import com.example.kopanie.AutoKopanie;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents; // Potrzebne do pętli!
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput; // Ważne: Rodzaj kliknięcia (w 26.2+ zamiast ClickType)
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.abs;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class ExampleModClient implements ClientModInitializer {

    private volatile boolean active = false;
    private volatile boolean drewno = false;
    private volatile boolean postawiono_sadzonke = false;
    private int timer = 0;
    Minecraft mc = Minecraft.getInstance();
    MojSerwer mojSerwer = new MojSerwer();
    Controler controler = new Controler(mc);
    final double[] odleglosc = {0};

    // Ręczne przejęcie sterowania: jeśli ruszysz się klawiszami albo myszką, bot się wyłącza
    private static final float PROG_OBROTU = 0.5f; // stopnie - mniejsze drgnięcia myszki są ignorowane
    private float ostatniYaw = 0;
    private float ostatniPitch = 0;
    private boolean maObrot = false;

    // Praca w tle (np. na drugim monitorze): gdy bot działa, gra nie pauzuje się po utracie fokusu
    private Boolean pauzaPrzedBotem = null; // zapamiętane ustawienie gracza, przywracane po wyłączeniu bota

    // Naturalny ruch kamery podczas łowienia
    private final NaturalnyRuch ruch = new NaturalnyRuch();

    // Reakcja, gdy serwer (komenda/teleport) obróci Ci głowę
    private final ReakcjaNaObrot reakcja = new ReakcjaNaObrot();

    // Losowe małe spacery na kucaka (max 1 blok od miejsca startu) podczas czekania na branie
    private final SpacerWokol spacer = new SpacerWokol();

    // Powrót na łowisko: gdy zostaniesz przeniesiony daleko, bot się rozgląda i po ~5 s wpisuje /home ryby
    private static final double MAX_ODLEGLOSC_OD_LOWISKA = 20.0; // bloki
    private String komendaPowrotu = "home ryby";               // bez ukośnika; zmienia się przez /ry home lub Discorda
    private double lowiskoX, lowiskoY, lowiskoZ;
    private ResourceKey<Level> lowiskoWymiar = null;
    private boolean wracaDoDomu = false;
    private int powrotLicznik = 0;
    private int powrotProby = 0;
    private int czekamNaNoweLowisko = 0;      // >0: wysłano /home z Discorda/komendy - po teleporcie nowe łowisko
    private boolean swiezoPrzeniesiono = false;
    private double poprzX, poprzY, poprzZ;
    private ResourceKey<Level> poprzWymiar = null;

    // Discord: status co minutę + powiadomienia o ważnych zdarzeniach (/ry discord <link do webhooka>)
    private final DiscordWebhook discord = new DiscordWebhook();

    // Auto-kopanie (/kop)
    private final AutoKopanie kopanie = new AutoKopanie();

    // Sterowanie przez Discorda (bot czyta polecenia "!nick akcja" z kanału)
    private final DiscordSterowanie sterowanie = new DiscordSterowanie();

    // Sekwencje startowa/końcowa (osobne dla każdego nicku) i hasło do /login - ustawiane w panelu na Discordzie
    private final Sekwencje sekwencje = new Sekwencje();
    private static final int DISCORD_CO_ILE = 1200; // co ile ticków wysyłać status (1200 = 1 minuta)
    private int discordLicznik = 0;
    private boolean bylAktywny = false;
    private int ticksOdWylaczenia = 1000;     // ile ticków minęło od wyłączenia łowienia
    private static final float PROG_REAKCJI = 3f; // mniejsze poprawki obrotu od serwera są przyjmowane po cichu
    private int opoznienieZarzutu = -1; // -1 = nie zaplanowano, >0 = ile ticków jeszcze czekać
    private int blokadaZarzutu = 0;     // chwila po zarzuceniu, żeby nie zarzucić drugi raz, zanim spławik doleci

    // Tryb turbo: kilka gier z rzędu bez pomyłek (maksymalna nagroda)
    private static final int TURBO_ILE = 5;
    private int turboGry = 0;           // ile gier turbo jeszcze zostało
    private int turboRazem = TURBO_ILE; // ile gier turbo zamówiono (do napisu "gra 2/5")
    private int startPoziomLowienia = -1; // poziom EXP przy włączeniu łowienia
    private boolean graTurbo = false;   // czy obecna gra jest grą turbo

    // Statystyki (/ry stats, /stats)
    private final Statystyki staty = new Statystyki();
    private static final int STATY_CO_ILE_GIER = 5; // automatycznie pokazuj statystyki co tyle gier (0 = nigdy)
    private boolean pokazHud = true;                // panel w lewym górnym rogu (/ry hud)
    private KeyMapping klawiszReset;                // skrót do zerowania statystyk (domyślnie K)
    private int potwierdzResetDo = 0;               // ile ticków jeszcze czekamy na drugie naciśnięcie

    // Ochrona wędki przed zepsuciem
    private static final int MIN_WYTRZYMALOSC = 10;       // poniżej tylu użyć nie zarzucamy / nie startujemy
    private static final int AWARYJNA_WYTRZYMALOSC = 3;   // przy tylu przerywamy nawet w trakcie minigry

    // Zużyta wędka -> przełącz na slot 1 i wpisz /w, potem (jeśli w ręce jest sprawna wędka) łów dalej
    private static final int SLOT_PO_ZUZYCIU = 0;          // 0 = pierwszy slot paska
    private static final String KOMENDA_WEDKA = "w";        // bez ukośnika
    private int wymianaTick = -1;                           // -1 = nie trwa; inaczej ticki od początku
    private int wymianaSlotO, wymianaKomendaO, wymianaKoniec;

    // Celowe pomyłki w minigrze (za szybkie kliknięcie na wylosowanym etapie)
    private static final double SZANSA_PORAZKA_WCZESNIEJ = 0.05; //  5% gier: pomyłka na 1. lub 2. kliknięciu
    private static final double SZANSA_PORAZKA_NA_3 = 0.25;      // 25% gier: pomyłka na 3. kliknięciu
    private static final double SZANSA_PORAZKA_NA_4 = 0.40;      // 40% gier: pomyłka na 4. kliknięciu
    // reszta (30%) - bez pomyłki, dochodzi do 5. kliknięcia
    private boolean bylTytul = false;      // czy trwa minigra (tytuł był wyświetlany)
    private int bezTytuluTicki = 0;        // ile ticków z rzędu nie ma tytułu
    private static final int KONIEC_GRY_PO = 20; // gra skończona dopiero po 2 s bez tytułu (krótkie przerwy między etapami się nie liczą)
    private static final boolean POKAZUJ_PLAN = true; // pokazuj w czacie plan każdej gry (do sprawdzania szans)
    private int etapPorazki = 0;           // na którym kliknięciu się pomylić (0 = wcale)
    private int klikniecia = 0;            // ile poprawnych kliknięć było w tej minigrze
    private int zaSzybkoZa = 0;            // po ilu tickach kliknąć w złym momencie na etapie pomyłki
    private final AtomicBoolean czyZlowiono = new AtomicBoolean(false); // przyszło "Wędkarstwo » Wyłowiono" (do następnego zarzutu)
    private Component tytulPoGrze = null;  // napis minigry, który jeszcze gaśnie po złowieniu - nie czekamy na niego
    private int zlowionoZHaczykiem = 0;    // ile ticków po "Wyłowiono" haczyk nadal jest w wodzie (zabezpieczenie)

    @Override
    public void onInitializeClient() {

        discord.wczytaj();
        kopanie.wczytaj();
        sterowanie.wczytaj();
        sterowanie.ustawSekwencje(sekwencje);
        sterowanie.start(this::obsluzPolecenie, this::obsluzAkcje, this::stanPanelu);
        // zamykanie gry: panel na Discordzie zmienia się na "gra wyłączona"
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> sterowanie.zamknij());

        // licznik wykopanych bloków
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> kopanie.zliczBlok());

        // Skrót klawiszowy: zerowanie statystyk (Opcje -> Sterowanie -> Auto-łowienie)
        KeyMapping.Category kategoria = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("modid", "autolowienie"));
        klawiszReset = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.modid.reset_stats", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, kategoria));

        // Panel ze statystykami w lewym górnym rogu
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("modid", "statystyki"),
                (graphics, deltaTracker) -> rysujPanel(graphics));



        Thread watekSieciowy = new Thread(() -> {
            while (true) {
                if (!mojSerwer.polacz()) {
                    // port zajęty (np. przez drugą grę) - próbujemy później, zamiast sypać błędami co 2 s
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                boolean nowaWartosc = false;
                while (true) { // czytamy komendy, dopóki klient jest połączony
                    try {
                        // Pobieramy dane z serwera
                        Boolean wartosc = mojSerwer.getValue();
                        if (wartosc != null) {
                            nowaWartosc = wartosc;
                        }

                        if (drewno != nowaWartosc) {
                            System.out.println("Zdalna zmiana stanu na: " + nowaWartosc);
                            drewno = nowaWartosc;
                        }

                        Thread.sleep(100);

                    } catch (InterruptedException e) {
                        return;
                    } catch (java.io.IOException e) {
                        // klient się rozłączył - wracamy do czekania na nowe połączenie
                        System.out.println("[modid] Zdalne sterowanie TCP: " + e.getMessage() + " - czekam na nowe połączenie.");
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                        mojSerwer.rozlacz();
                        break;
                    }
                }
            }
        }, "modid-sterowanie-tcp");
        watekSieciowy.setDaemon(true);
        watekSieciowy.start();


        final double[][] blok = new double[1][3];
        final int[] stan_drewna = {0};

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {


            String tekst = message.getString();

            if (tekst.contains("Wędkarstwo") && tekst.contains("Wyłowiono")) {
                czyZlowiono.set(true);


            }
        });

        AtomicInteger counter = new AtomicInteger();
        AtomicInteger counterZlowiono = new AtomicInteger();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            counter.getAndIncrement();
            counterZlowiono.getAndIncrement();
            // zabezpieczenie: po "Wyłowiono" haczyk powinien zniknąć - jeśli dalej jest w wodzie > 3 s, to już nowa runda
            if (czyZlowiono.get() && client.player != null && client.player.fishing != null) {
                if (++zlowionoZHaczykiem > 60) {
                    czyZlowiono.set(false);
                    zlowionoZHaczykiem = 0;
                }
            } else {
                zlowionoZHaczykiem = 0;
            }
            // napis minigry; po złowieniu ryby (i gdy stary napis jeszcze gaśnie) = null, więc od razu zarzucamy dalej
            Component tytulGry = tytulMinigry(czyZlowiono.get());

            // Sekwencja startowa/końcowa (wejście na serwer, login, home, kopanie...) - działa też w menu gry
            sekwencje.tick(client, client.getUser().getName(), this::krokSekwencji, this::raportSekwencji);
            // Okno logowania serwera ("Wprowadź swoje hasło...") - bot sam wpisuje hasło i klika "Zaloguj się"
            sekwencje.tickLogowania(client, client.getUser().getName(), this::raportSekwencji);
            // Harmonogram ("pn-pt 07:30 start" itp.) - ustawiany w ⚙ na Discordzie, działa też w menu gry
            sekwencje.tickHarmonogramu(client, client.getUser().getName(), this::raportSekwencji);

            // Wyszliśmy z serwera (rozłączenie / sekwencja) - łowienie i kopanie nie mogą "wisieć" do następnego wejścia
            if (client.player == null && (active || kopanie.aktywne())) {
                active = false;
                kopanie.stop();
            }

            // Bot włączony -> wyłączamy "pauzuj po utracie fokusu", żeby grał, gdy robisz coś w innym oknie.
            // Bot wyłączony -> przywracamy Twoje ustawienie.
            if (active && pauzaPrzedBotem == null) {
                pauzaPrzedBotem = client.options.pauseOnLostFocus;
                client.options.pauseOnLostFocus = false;
            } else if (!active && pauzaPrzedBotem != null) {
                client.options.pauseOnLostFocus = pauzaPrzedBotem;
                pauzaPrzedBotem = null;
            }

            // Skrót: zerowanie statystyk - naciśnij 2 razy w ciągu 3 s (żeby nie wyzerować przypadkiem)
            if (potwierdzResetDo > 0) {
                potwierdzResetDo--;
            }
            while (klawiszReset.consumeClick()) {
                if (potwierdzResetDo > 0) {
                    staty.reset();
                    potwierdzResetDo = 0;
                    wiadomosc(Component.literal("§eStatystyki wyzerowane."));
                } else {
                    potwierdzResetDo = 60;
                    wiadomosc(Component.literal("§7Naciśnij §f" + klawiszReset.getTranslatedKeyMessage().getString()
                            + "§7 jeszcze raz w ciągu 3 s, żeby wyzerować statystyki."));
                }
            }

            ticksOdWylaczenia = active ? 0 : Math.min(ticksOdWylaczenia + 1, 100000);

            // Statystyki: czas pracy bota i wykrywanie nowych ryb w ekwipunku (co 0.5 s)
            if (client.player != null) {
                if (active) {
                    staty.tickAktywny();
                }
                if (counter.get() % 10 == 0) {
                    // klucze liczymy jako zdobyte podczas łowienia i do 10 s po jego wyłączeniu
                    staty.skanujEkwipunek(client.player, this::wiadomosc, active || ticksOdWylaczenia < 200);
                }
            }

            // Discord: status co minutę, gdy bot działa; status końcowy po wyłączeniu
            boolean dziala = active || kopanie.aktywne();
            if (discord.wlaczony() && client.player != null) {
                if (dziala && !bylAktywny) {
                    discordLicznik = 0;
                    discord.zdarzenie(active ? "Auto-łowienie włączone" : "Auto-kopanie włączone", DiscordWebhook.ZIELONY, stopkaDiscord());
                } else if (dziala && ++discordLicznik >= DISCORD_CO_ILE) {
                    discordLicznik = 0;
                    if (!sterowanie.panelDziala()) {
                        // gdy działa panel z przyciskami, pokazuje on stan na bieżąco - nie zaśmiecamy kanału co minutę
                        wyslijStatusDiscord();
                    }
                } else if (!dziala && bylAktywny) {
                    wyslijStatusDiscord();
                }
            }
            bylAktywny = dziala;

            // Auto-kopanie: pilnowanie kilofa (zmiana / zatrzymanie) i trzymanie przycisku kopania
            kopanie.tick(client, t -> alert(t, t.startsWith("§c") ? DiscordWebhook.CZERWONY : DiscordWebhook.POMARANCZOWY));

            // Wymiana zużytej wędki: slot 1 -> /w -> sprawdzenie
            if (client.player != null && active && wymianaTick >= 0) {
                tickWymianyWedki(client.player);
            } else if (!active) {
                wymianaTick = -1;
            }

            // Pilnowanie łowiska: daleki teleport / inny wymiar -> rozejrzenie się i /home ryby
            swiezoPrzeniesiono = false;
            if (client.player != null && active) {
                pilnujLowiska(client.player, client.options);
            } else {
                wracaDoDomu = false;
            }

            // Ruch myszką/klawiszami -> oddajemy sterowanie. Obrót przez serwer -> reakcja i powrót do łowienia.
            boolean obrotZSerwera = ObrotSerwera.pobierz();
            if (client.player != null) {
                float yaw = client.player.getYRot();
                float pitch = client.player.getXRot();
                if (active && maObrot) {
                    if (obrotZSerwera) {
                        float zmiana = roznicaObrotu(yaw, pitch);
                        if (zmiana > PROG_REAKCJI && !wracaDoDomu && !swiezoPrzeniesiono && czekamNaNoweLowisko == 0) {
                            spacer.przerwij(client.options); // klawisze przejmuje reakcja
                            reakcja.start(client.options);
                            ruch.reakcja(yaw, pitch);
                            alert("§eSerwer obrócił Ci głowę - reaguję i za chwilę wracam do łowienia.", DiscordWebhook.POMARANCZOWY);
                        } else if (zmiana > 0.01f) {
                            ruch.przejmij(yaw, pitch);
                        }
                    } else if (graczSieRusza(client) || obrotSieZmienil(yaw, pitch)) {
                        active = false;
                        alert("§eSterujesz sam - auto-łowienie zatrzymane. Wpisz /ry, żeby wznowić.", DiscordWebhook.POMARANCZOWY);
                    }
                }

                // skoki / kucanie podczas reakcji (a gdy bot się wyłączył - puszczamy klawisze)
                if (active) {
                    reakcja.tick(client.options);
                } else if (reakcja.trwa()) {
                    reakcja.przerwij(client.options);
                }

                // Spacer: chodzi tylko gdy czekamy na branie; w minigrze stoi; przed zarzuceniem wraca na miejsce
                if (active && client.gui.screen() == null && !reaguje() && !wracaDoDomu && wymianaTick < 0 && czekamNaNoweLowisko == 0) {
                    SpacerWokol.Tryb tryb;
                    if (tytulGry != null) {
                        tryb = SpacerWokol.Tryb.STOJ;
                    } else if (client.player.fishing != null) {
                        tryb = SpacerWokol.Tryb.SPACER;
                    } else {
                        tryb = SpacerWokol.Tryb.WRACAJ;
                    }
                    spacer.tick(client.player, client.options, tryb);
                } else {
                    spacer.przerwij(client.options); // puszcza tylko klawisze trzymane przez bota
                }

                // Naturalny ruch kamery (tylko gdy bot działa i nie masz otwartego ekranu)
                if (active && client.gui.screen() == null) {
                    boolean minigra = tytulGry != null;
                    boolean czeka = client.player.fishing != null && !minigra;
                    ruch.tick(client.player, czeka);
                }

                // Zapamiętujemy obrót PO ruchu bota - w następnym ticku wykryjemy tylko Twój ruch myszką
                ostatniYaw = client.player.getYRot();
                ostatniPitch = client.player.getXRot();
                maObrot = true;
            } else {
                maObrot = false;
            }

            try {
                {
                    if (mc.player != null && mc.gui != null && active && !reaguje() && !wracaDoDomu && wymianaTick < 0) {
                        Component title = tytulGry;
                        if (title != null) {
                            List<String> list = new ArrayList<String>();
                            String rawText = title.getString();

                            title.visit((style, text) -> {

                                if (style.getColor() != null) {
                                    String colorName = style.getColor().toString();
                                    list.add(colorName);
                                }
                                return java.util.Optional.empty();
                            }, net.minecraft.network.chat.Style.EMPTY);

                            if (client.options.keyUse.isDown()) {
                                client.options.keyUse.setDown(false);
                            }

                            // Nowa minigra (tytuł właśnie się pojawił) - losujemy, na którym kliknięciu się pomylić
                            bezTytuluTicki = 0;
                            if (!bylTytul) {
                                bylTytul = true;
                                klikniecia = 0;
                                double los = java.util.concurrent.ThreadLocalRandom.current().nextDouble();
                                graTurbo = turboGry > 0;
                                if (turboGry > 0) {
                                    // TURBO: gra bez pomyłki, do 5. kliknięcia
                                    etapPorazki = 0;
                                    int nr = turboRazem - turboGry + 1;
                                    turboGry--;
                                    wiadomosc(Component.literal("§6TURBO: gra " + nr + "/" + turboRazem + " - bez pomyłek"
                                            + (turboGry == 0 ? " (ostatnia)" : "")));
                                } else if (los < SZANSA_PORAZKA_WCZESNIEJ) {
                                    etapPorazki = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : 2;
                                } else if (los < SZANSA_PORAZKA_WCZESNIEJ + SZANSA_PORAZKA_NA_3) {
                                    etapPorazki = 3;
                                } else if (los < SZANSA_PORAZKA_WCZESNIEJ + SZANSA_PORAZKA_NA_3 + SZANSA_PORAZKA_NA_4) {
                                    etapPorazki = 4;
                                } else {
                                    etapPorazki = 0;
                                }
                                zaSzybkoZa = losowyZlyMoment();
                                if (POKAZUJ_PLAN) {
                                    wiadomosc(Component.literal(etapPorazki == 0
                                            ? "§7Nowa gra - plan: bez pomyłki"
                                            : "§7Nowa gra - plan: pomyłka na " + etapPorazki + ". kliknięciu"));
                                }
                            }

                            // Wędka prawie zepsuta - przerywamy od razu, nawet w trakcie gry
                            ItemStack wedkaTeraz = mc.player.getMainHandItem();
                            if (wedkaTeraz.is(Items.FISHING_ROD) && wytrzymalosc(wedkaTeraz) <= AWARYJNA_WYTRZYMALOSC) {
                                zacznijWymianeWedki(wedkaTeraz, true);
                                return;
                            }

                            long kolory = list.stream().distinct().count();
                            if (!czyZlowiono.get() && counterZlowiono.get() > 10) {
                                if (klikniecia + 1 == etapPorazki) {
                                    // Etap pomyłki: klikamy w losowym momencie, ale NIE wtedy, gdy są trzy kolory
                                    // (to byłby dobry moment). Jeśli gra dalej trwa, po chwili znowu klikamy źle,
                                    // aż minigra się skończy - dzięki temu nic się nie zawiesza.
                                    zaSzybkoZa--;
                                    if (zaSzybkoZa <= 0 && kolory != 3) {
                                        wiadomosc(Component.literal("§cZły moment! (klik " + etapPorazki + ")"));
                                        kliknijWedka();
                                        counterZlowiono.set(0);
                                        zaSzybkoZa = losowyZlyMoment();
                                    }
                                } else if (kolory == 3) {
                                    klikniecia++;
                                    wiadomosc(Component.literal("§aTERAZ! (klik " + klikniecia + ")"));
                                    kliknijWedka();
                                    counterZlowiono.set(0);
                                    zaSzybkoZa = losowyZlyMoment();
                                }
                            }


                        } else {
                            // minigra skończona: od razu po "Wyłowiono", a bez tej wiadomości - gdy napisu nie ma przez dłuższą chwilę
                            if (bylTytul && (czyZlowiono.get() || ++bezTytuluTicki >= KONIEC_GRY_PO)) {
                                bylTytul = false;
                                staty.koniecGry(etapPorazki, graTurbo, klikniecia);
                                if (STATY_CO_ILE_GIER > 0 && staty.gry() % STATY_CO_ILE_GIER == 0) {
                                    staty.pokaz(this::wiadomosc);
                                }
                            }
                            var player = mc.player;
                            ItemStack item = player.getMainHandItem();
                            if (mc.player != null && mc.gameMode != null && item.getItem() == Items.FISHING_ROD) {
                                if (blokadaZarzutu > 0) {
                                    blokadaZarzutu--;
                                } else if (mc.player.fishing == null) {
                                    if (wytrzymalosc(item) <= MIN_WYTRZYMALOSC) {
                                        // nie zarzucamy - wędka mogłaby się zepsuć -> slot 1 + /w
                                        zacznijWymianeWedki(item, false);
                                    } else if (opoznienieZarzutu < 0) {
                                        // krótka losowa przerwa przed zarzuceniem: 0.1 - 0.35 s
                                        opoznienieZarzutu = java.util.concurrent.ThreadLocalRandom.current().nextInt(2, 8);
                                        ruch.wrocDoSrodka();
                                    } else if (opoznienieZarzutu > 0) {
                                        opoznienieZarzutu--;
                                    } else if (ruch.czyGotowyDoZarzucenia() && spacer.naMiejscu(mc.player)) {
                                        opoznienieZarzutu = -1;
                                        wiadomosc(Component.literal("§aZARZUCAM WEDKE!"));
                                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                                        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getInteractAnimation(), false);
                                        czyZlowiono.set(false);
                                        blokadaZarzutu = 20; // 1 s na pojawienie się spławika
                                    }
                                }
                            }else{
                                alert("§aZgubilem wedke!", DiscordWebhook.CZERWONY);
                                active = false;

                            }
                        }
                    }
                }
            }catch (Exception e) {
                e.printStackTrace();
            }

        });


        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("ry")
                    .requires(FabricClientCommandSource::attended)
                    .then(literal("stats")
                            .executes(context -> {
                                pokazStaty(context.getSource()::sendFeedback);
                                return 1;
                            })
                            .then(literal("reset").executes(context -> {
                                staty.reset();
                                context.getSource().sendFeedback(Component.literal("§eStatystyki wyzerowane."));
                                return 1;
                            })))
                    .then(literal("home").then(argument("nazwa", StringArgumentType.greedyString()).executes(context -> {
                        komendaPowrotu = "home " + StringArgumentType.getString(context, "nazwa").trim();
                        context.getSource().sendFeedback(Component.literal("§aPo dalekim teleporcie bot wpisze: /" + komendaPowrotu));
                        return 1;
                    })))
                    .then(literal("discord")
                            .executes(context -> {
                                context.getSource().sendFeedback(Component.literal(discord.wlaczony()
                                        ? "§aDiscord (statusy): włączony §7(co minutę). /ry discord test | /ry discord off"
                                        : "§7Discord (statusy): wyłączony. Ustaw: §f/ry discord <link do webhooka>"));
                                context.getSource().sendFeedback(Component.literal(sterowanie.gotowy()
                                        ? "§aDiscord (sterowanie): §f" + sterowanie.opisPolaczenia()
                                        + " §7- panel z przyciskami jest na kanale (/ry discord panel - wstaw go na dół)"
                                        : "§7Discord (sterowanie): brakuje: §f" + sterowanie.brakujace()));
                                context.getSource().sendFeedback(Component.literal("§7Kanał alertów: " + (sterowanie.kanalAlertow().isEmpty()
                                        ? "§fbrak §7(alerty tylko na panelu; ustaw: /ry discord alerty <id> albo napisz !alerty na kanale)"
                                        : "§f" + sterowanie.kanalAlertow() + " §7(/ry discord alerty off)")));
                                context.getSource().sendFeedback(Component.literal("§7Kanał ustawień: " + (sterowanie.kanalUstawien().isEmpty()
                                        ? "§fbrak §7(⚙ na panelu → 🏗 Utwórz kanały)" : "§f" + sterowanie.kanalUstawien())));
                                context.getSource().sendFeedback(Component.literal("§7Panel zawsze na dole: "
                                        + (sterowanie.przyklejony() ? "§aWŁ" : "§cWYŁ") + " §7(przełącz: /ry discord przyklej)"));
                                return 1;
                            })
                            .then(literal("alerty").then(argument("id", StringArgumentType.word()).executes(context -> {
                                String id = StringArgumentType.getString(context, "id").trim();
                                if (id.equalsIgnoreCase("off")) {
                                    sterowanie.ustawKanalAlertow("");
                                    context.getSource().sendFeedback(Component.literal("§7Alerty wyłączone - zostają tylko na panelu."));
                                } else {
                                    sterowanie.ustawKanalAlertow(id);
                                    context.getSource().sendFeedback(Component.literal("§aAlerty będą przychodzić na kanał " + id + "."));
                                }
                                return 1;
                            })))
                            .then(literal("przyklej").executes(context -> {
                                sterowanie.ustawPrzyklejony(!sterowanie.przyklejony());
                                context.getSource().sendFeedback(Component.literal(sterowanie.przyklejony()
                                        ? "§aPanel zawsze na dole: WŁ §7- gdy ktoś coś napisze, panel wraca na dół kanału"
                                        : "§cPanel zawsze na dole: WYŁ"));
                                return 1;
                            }))
                            .then(literal("panel").executes(context -> {
                                if (!sterowanie.gotowy()) {
                                    context.getSource().sendFeedback(Component.literal("§cNajpierw ustaw: §f" + sterowanie.brakujace()));
                                    return 0;
                                }
                                sterowanie.przeniesPanelNaDol();
                                context.getSource().sendFeedback(Component.literal("§aPanel sterowania pojawi się na dole kanału."));
                                return 1;
                            }))
                            .then(literal("bot").then(argument("token", StringArgumentType.greedyString()).executes(context -> {
                                sterowanie.ustawToken(StringArgumentType.getString(context, "token"));
                                context.getSource().sendFeedback(Component.literal(sterowanie.gotowy()
                                        ? "§aToken bota zapisany - sterowanie z Discorda działa."
                                        : "§aToken bota zapisany. §7Jeszcze brakuje: §f" + sterowanie.brakujace()));
                                return 1;
                            })))
                            .then(literal("kanal").then(argument("id", StringArgumentType.word()).executes(context -> {
                                sterowanie.ustawKanal(StringArgumentType.getString(context, "id"));
                                context.getSource().sendFeedback(Component.literal(sterowanie.gotowy()
                                        ? "§aKanał zapisany - sterowanie z Discorda działa."
                                        : "§aKanał zapisany. §7Jeszcze brakuje: §f" + sterowanie.brakujace()));
                                return 1;
                            })))
                            .then(literal("wlasciciel").then(argument("id", StringArgumentType.word()).executes(context -> {
                                sterowanie.ustawWlasciciela(StringArgumentType.getString(context, "id"));
                                context.getSource().sendFeedback(Component.literal(sterowanie.gotowy()
                                        ? "§aWłaściciel zapisany - sterowanie z Discorda działa."
                                        : "§aWłaściciel zapisany. §7Jeszcze brakuje: §f" + sterowanie.brakujace()));
                                return 1;
                            })))
                            .then(literal("sterowanie").then(literal("off").executes(context -> {
                                sterowanie.wylacz();
                                context.getSource().sendFeedback(Component.literal("§7Sterowanie z Discorda wyłączone (token usunięty)."));
                                return 1;
                            })))
                            .then(literal("off").executes(context -> {
                                discord.ustawUrl("");
                                context.getSource().sendFeedback(Component.literal("§7Discord wyłączony (link usunięty)."));
                                return 1;
                            }))
                            .then(literal("test").executes(context -> {
                                if (!discord.wlaczony()) {
                                    context.getSource().sendFeedback(Component.literal("§cNajpierw ustaw link: /ry discord <link do webhooka>"));
                                    return 0;
                                }
                                wyslijStatusDiscord();
                                context.getSource().sendFeedback(Component.literal("§aWysłano status na Discorda."));
                                return 1;
                            }))
                            .then(argument("link", StringArgumentType.greedyString()).executes(context -> {
                                String link = StringArgumentType.getString(context, "link").trim();
                                if (!DiscordWebhook.poprawnyLink(link)) {
                                    context.getSource().sendFeedback(Component.literal("§cTo nie jest link do webhooka Discorda (powinien zaczynać się od https://discord.com/api/webhooks/)."));
                                    return 0;
                                }
                                discord.ustawUrl(link);
                                discord.zdarzenie("Połączono z Minecraftem - tu będą przychodzić statusy łowienia", DiscordWebhook.NIEBIESKI, stopkaDiscord());
                                context.getSource().sendFeedback(Component.literal("§aDiscord ustawiony! Sprawdź kanał - powinna przyjść wiadomość testowa."));
                                return 1;
                            })))
                    .then(literal("hud").executes(context -> {
                        pokazHud = !pokazHud;
                        context.getSource().sendFeedback(Component.literal(pokazHud ? "§aPanel statystyk: włączony" : "§7Panel statystyk: wyłączony"));
                        return 1;
                    }))
                    .then(literal("turbo").executes(context -> {
                        // /ry turbo - następne 5 gier bez pomyłek; włącza bota, jeśli był wyłączony
                        if (!active && !wlaczBota(context.getSource()::sendFeedback)) {
                            return 0;
                        }
                        turboGry = TURBO_ILE;
                        turboRazem = TURBO_ILE;
                        context.getSource().sendFeedback(Component.literal("§6TURBO włączone: następne " + TURBO_ILE + " gier bez pomyłek!"));
                        return 1;
                    }))
                    .executes(context -> {

                        if (active) {
                            active = false;
                            context.getSource().sendFeedback(Component.literal("§cZatrzymano."));
                        } else if (wlaczBota(context.getSource()::sendFeedback)) {
                            context.getSource().sendFeedback(Component.literal("§aWłączyłeś auto-lowienie!"));
                        }

                        return 1;
                    }));

            // /kop - auto-kopanie
            dispatcher.register(literal("kop")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> {
                        if (kopanie.aktywne()) {
                            kopanie.stop();
                            context.getSource().sendFeedback(Component.literal("§cZatrzymano kopanie."));
                            return 1;
                        }
                        if (active) {
                            active = false;
                            context.getSource().sendFeedback(Component.literal("§7Wyłączono auto-łowienie (/ry)."));
                        }
                        if (kopanie.start(mc, t -> context.getSource().sendFeedback(Component.literal(t)))) {
                            context.getSource().sendFeedback(Component.literal("§aWłączono auto-kopanie! §7Zatrzymanie: /kop | ustawienia: /kop info"));
                        }
                        return 1;
                    })
                    .then(literal("info").executes(context -> {
                        context.getSource().sendFeedback(Component.literal("§6⛏ Auto-kopanie: " + (kopanie.aktywne() ? "§aWŁ" : "§7WYŁ")));
                        context.getSource().sendFeedback(Component.literal("§7Zatrzymaj/zmień kilof przy wytrzymałości: §f" + kopanie.minWytrzymalosc()
                                + " §7(zmień: /kop prog <liczba>)"));
                        context.getSource().sendFeedback(Component.literal("§7Zmiana na inny kilof z paska: " + (kopanie.zmieniajKilof() ? "§aWŁ" : "§cWYŁ")
                                + " §7(przełącz: /kop zmiana)"));
                        return 1;
                    }))
                    .then(literal("prog").then(argument("ile", IntegerArgumentType.integer(2, 5000)).executes(context -> {
                        kopanie.ustawMinWytrzymalosc(IntegerArgumentType.getInteger(context, "ile"));
                        context.getSource().sendFeedback(Component.literal("§aKilof będzie zmieniany/zatrzymany przy wytrzymałości " + kopanie.minWytrzymalosc() + "."));
                        return 1;
                    })))
                    .then(literal("zmiana").executes(context -> {
                        kopanie.ustawZmieniajKilof(!kopanie.zmieniajKilof());
                        context.getSource().sendFeedback(Component.literal(kopanie.zmieniajKilof()
                                ? "§aZmiana na inny kilof z paska: WŁĄCZONA"
                                : "§cZmiana na inny kilof z paska: WYŁĄCZONA §7(przy zużyciu kopanie się zatrzyma)"));
                        return 1;
                    })));

            // Skrót: /stats
            dispatcher.register(literal("stats")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> {
                        pokazStaty(context.getSource()::sendFeedback);
                        return 1;
                    }));
        });

    }



    /**
     * Napis minigry albo null. Od złowienia ryby do następnego zarzutu napis się nie liczy (gra skończona),
     * a po zarzuceniu ignorujemy stary napis, dopóki do końca nie zgaśnie.
     */
    private Component tytulMinigry(boolean zlowiono) {
        Component t = TytulHelper.getTitle(mc);
        if (zlowiono) {
            if (t != null) tytulPoGrze = t;
            return null;
        }
        if (t != null && t == tytulPoGrze) {
            return null;
        }
        tytulPoGrze = null;
        return t;
    }

    /** Włącza bota. Zwraca false (i pisze dlaczego), jeśli nie można zacząć. */
    private boolean wlaczBota(Consumer<Component> out) {
        if (mc.player == null) {
            return false;
        }
        if (kopanie.aktywne()) {
            kopanie.stop();
            out.accept(Component.literal("§7Wyłączono auto-kopanie (/kop)."));
        }
        // start łowienia sam bierze wędkę do ręki (z paska albo z ekwipunku)
        String wzieto = wezWedke();
        if (wzieto != null) {
            out.accept(Component.literal("§7" + wzieto));
        }
        ItemStack wedka = mc.player.getMainHandItem();
        if (!wedka.is(Items.FISHING_ROD)) {
            out.accept(Component.literal("§cWeź wędkę do ręki, zanim włączysz auto-łowienie."));
            return false;
        }
        if (wytrzymalosc(wedka) <= MIN_WYTRZYMALOSC) {
            out.accept(Component.literal("§cWędka ma za mało wytrzymałości (" + opisWytrzymalosci(wedka)
                    + "). Napraw ją albo weź inną - nie zaczynam, żeby jej nie zepsuć."));
            return false;
        }
        active = true;
        timer = 0;
        czyZlowiono.set(false);
        tytulPoGrze = null;
        opoznienieZarzutu = -1;
        blokadaZarzutu = 0;
        if (mc.player != null) {
            // kierunek, w którym teraz patrzysz, staje się punktem startowym
            ruch.start(mc.player.getYRot(), mc.player.getXRot());
            spacer.start(mc.player); // obecne miejsce = miejsce startu spacerów
            // obecne miejsce = łowisko (do sprawdzania dalekich teleportów)
            lowiskoX = mc.player.getX();
            lowiskoY = mc.player.getY();
            lowiskoZ = mc.player.getZ();
            lowiskoWymiar = mc.player.level().dimension();
            wracaDoDomu = false;
            czekamNaNoweLowisko = 0;
            startPoziomLowienia = mc.player.experienceLevel;
            wymianaTick = -1;
        }
        return true;
    }

    /** Ile użyć zostało wędce (Integer.MAX_VALUE, jeśli jest niezniszczalna). */
    private static int wytrzymalosc(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    private static String opisWytrzymalosci(ItemStack stack) {
        return stack.isDamageableItem() ? wytrzymalosc(stack) + "/" + stack.getMaxDamage() : "niezniszczalna";
    }

    /** Panel w lewym górnym rogu. */
    private void rysujPanel(GuiGraphicsExtractor g) {
        if (!pokazHud || mc.player == null || mc.gui == null || mc.gui.hud == null) return;
        if (mc.gui.hud.isHidden() || mc.gui.hud.getDebugOverlay().showDebugScreen()) return; // F1 / F3

        boolean trybKopania = kopanie.aktywne() && !active;
        List<String> linie = new ArrayList<>();
        if (trybKopania) {
            linie.add("§b⛏ Auto-kopanie: WŁ");
        } else {
            linie.add(active ? "§a● Auto-łowienie: WŁ" : "§7○ Auto-łowienie: WYŁ");
            if (active && wracaDoDomu) {
                linie.add("§e↺ Wracam na łowisko (/" + komendaPowrotu + ")");
            }
            if (active && czekamNaNoweLowisko > 0) {
                linie.add("§e↻ Czekam na teleport (/" + komendaPowrotu + ")");
            }
            if (active && wymianaTick >= 0) {
                linie.add("§e⚒ Wymiana wędki (/" + KOMENDA_WEDKA + ")");
            }
            if (turboGry > 0 || (graTurbo && bylTytul)) {
                linie.add("§6TURBO §7(zostało gier: §6" + turboGry + "§7)");
            }
        }

        // Narzędzie w ręce (wędka albo kilof) + pasek wytrzymałości
        ItemStack narzedzie = mc.player.getMainHandItem();
        String etykieta = trybKopania ? "Kilof" : "Wędka";
        boolean pasuje = trybKopania ? narzedzie.is(ItemTags.PICKAXES) : narzedzie.is(Items.FISHING_ROD);
        int prog = trybKopania ? kopanie.minWytrzymalosc() : MIN_WYTRZYMALOSC;
        int liniaPaska = -1;
        float czescWytrzymalosci = 0;
        int kolorPaska = 0xFF55FF55;
        if (pasuje) {
            if (narzedzie.isDamageableItem()) {
                int zostalo = wytrzymalosc(narzedzie);
                int max = narzedzie.getMaxDamage();
                czescWytrzymalosci = max > 0 ? (float) zostalo / max : 0;
                String kolor;
                if (zostalo <= prog) {
                    kolor = "§c";
                    kolorPaska = 0xFFFF5555;
                } else if (zostalo <= prog * 2) {
                    kolor = "§e";
                    kolorPaska = 0xFFFFFF55;
                } else {
                    kolor = "§a";
                }
                linie.add("§7" + etykieta + ": " + kolor + zostalo + "/" + max + " §7(" + kolor + Math.round(czescWytrzymalosci * 100) + "%§7)");
                liniaPaska = linie.size();
                linie.add(""); // miejsce na pasek
            } else {
                linie.add("§7" + etykieta + ": §aniezniszczaln" + (trybKopania ? "y" : "a"));
            }
        } else {
            linie.add("§7" + etykieta + ": §8nie trzymasz " + (trybKopania ? "kilofa" : "wędki"));
        }

        if (trybKopania) {
            linie.addAll(kopanie.liniePanelu(mc));
        } else {
            linie.addAll(staty.liniePanelu());
            linie.add(poziomExpLowienia());
            linie.add("§8[" + klawiszReset.getTranslatedKeyMessage().getString() + "] x2 - reset statystyk");
        }

        int wysLinii = mc.font.lineHeight + 1;
        int szer = 60;
        for (String l : linie) {
            szer = Math.max(szer, mc.font.width(l));
        }
        int x = 4, y = 4;
        g.fill(x - 3, y - 3, x + szer + 3, y + linie.size() * wysLinii + 2, 0x90000000);
        for (int i = 0; i < linie.size(); i++) {
            int ly = y + i * wysLinii;
            if (i == liniaPaska) {
                // pasek wytrzymałości
                g.fill(x, ly + 1, x + szer, ly + 6, 0xFF333333);
                g.fill(x, ly + 1, x + Math.max(1, Math.round(szer * czescWytrzymalosci)), ly + 6, kolorPaska);
            } else {
                g.text(mc.font, linie.get(i), x, ly, 0xFFFFFFFF, true);
            }
        }
    }

    /** Losowy moment złego kliknięcia: 0.1 - 1.25 s (w tickach) */
    private int losowyZlyMoment() {
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(2, 26);
    }

    private void kliknijWedka() {
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getInteractAnimation(), false);
    }

    private void wiadomosc(Component tekst) {
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.getChat().addClientSystemMessage(tekst);
        }
    }

    private boolean graczSieRusza(Minecraft client) {
        var o = client.options;
        // klawisze wciskane przez samego bota (spacer / reakcja) nie liczą się jako Twój ruch
        boolean botChodzi = spacer.trzymaKlawisze();
        boolean botSkaczeKuca = reakcja.trwa() || spacer.trzymaKlawisze();
        return (!botChodzi && (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()))
                || (!botSkaczeKuca && (o.keyJump.isDown() || o.keyShift.isDown()));
    }

    /**
     * Gdy jesteś daleko od łowiska (>20 bloków albo inny wymiar): rozgląda się, po ~5 s wpisuje /home ryby,
     * czeka na teleport (do 15 s), w razie potrzeby próbuje jeszcze raz (max 3 razy), potem się wyłącza.
     */
    private void pilnujLowiska(LocalPlayer p, Options o) {
        if (lowiskoWymiar == null) return;
        boolean innyWymiar = !p.level().dimension().equals(lowiskoWymiar);
        double dx = p.getX() - lowiskoX, dy = p.getY() - lowiskoY, dz = p.getZ() - lowiskoZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean daleko = innyWymiar || dist > MAX_ODLEGLOSC_OD_LOWISKA;

        // teleport = skok pozycji o >2 bloki w jednym ticku albo zmiana wymiaru
        boolean skok = poprzWymiar != null && (!p.level().dimension().equals(poprzWymiar)
                || Math.abs(p.getX() - poprzX) + Math.abs(p.getY() - poprzY) + Math.abs(p.getZ() - poprzZ) > 2.0);
        poprzX = p.getX();
        poprzY = p.getY();
        poprzZ = p.getZ();
        poprzWymiar = p.level().dimension();

        if (czekamNaNoweLowisko > 0) {
            czekamNaNoweLowisko--;
            if (skok) {
                // przeniesiono nas (/home z Discorda) - tu jest nowe łowisko
                czekamNaNoweLowisko = 0;
                wracaDoDomu = false;
                ustawLowisko(p);
                swiezoPrzeniesiono = true;
                alert("§aNowe łowisko ustawione po /" + komendaPowrotu + " - łowię dalej.", DiscordWebhook.ZIELONY);
            } else if (czekamNaNoweLowisko == 0) {
                alert("§eTeleport po /" + komendaPowrotu + " nie nastąpił - łowię dalej w starym miejscu.", DiscordWebhook.POMARANCZOWY);
            }
            return; // w czasie czekania na teleport nie wracamy na stare łowisko
        }

        if (!wracaDoDomu) {
            if (daleko) {
                wracaDoDomu = true;
                powrotProby = 0;
                powrotLicznik = java.util.concurrent.ThreadLocalRandom.current().nextInt(80, 121); // 4-6 s
                reakcja.przerwij(o);
                spacer.przerwij(o);
                ruch.rozejrzyjSie(p.getYRot(), p.getXRot()); // najpierw się rozglądamy
                alert(innyWymiar
                        ? "§eJesteś w innym wymiarze - rozglądam się i za chwilę /" + komendaPowrotu
                        : "§eZostałeś przeniesiony " + Math.round(dist) + " bloków od łowiska - rozglądam się i za chwilę /" + komendaPowrotu, DiscordWebhook.POMARANCZOWY);
            }
            return;
        }

        if (!daleko) {
            // jesteśmy z powrotem
            wracaDoDomu = false;
            opoznienieZarzutu = -1;
            blokadaZarzutu = java.util.concurrent.ThreadLocalRandom.current().nextInt(20, 41); // 1-2 s przed zarzuceniem
            alert("§aJesteś z powrotem na łowisku - łowię dalej.", DiscordWebhook.ZIELONY);
            return;
        }

        powrotLicznik--;
        // komenda dopiero po rozejrzeniu się (najpóźniej 3 s po czasie)
        if (powrotLicznik > 0 || (ruch.czyReakcjaTrwa() && powrotLicznik > -60)) return;

        if (powrotProby >= 3) {
            active = false;
            wracaDoDomu = false;
            alert("§cNie udało się wrócić na łowisko (/" + komendaPowrotu + ") - zatrzymano auto-łowienie.", DiscordWebhook.CZERWONY);
            return;
        }
        powrotProby++;
        p.connection.sendCommand(komendaPowrotu);
        powrotLicznik = java.util.concurrent.ThreadLocalRandom.current().nextInt(240, 301); // czekamy 12-15 s na teleport
    }

    /** Wędka zużyta: za chwilę przełączamy na slot 1 i wpisujemy /w. */
    private void zacznijWymianeWedki(ItemStack wedka, boolean awaryjnie) {
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        wymianaTick = 0;
        wymianaSlotO = rnd.nextInt(8, 17);                        // ~0.4-0.8 s - zmiana slotu
        wymianaKomendaO = wymianaSlotO + rnd.nextInt(10, 21);     // ~0.5-1 s później - /w
        wymianaKoniec = wymianaKomendaO + rnd.nextInt(40, 61);    // 2-3 s na odpowiedź serwera
        opoznienieZarzutu = -1;
        alert((awaryjnie ? "§c§lWędka prawie zepsuta (" : "§eWędka ma mało wytrzymałości (") + opisWytrzymalosci(wedka)
                + ") - przełączam na slot " + (SLOT_PO_ZUZYCIU + 1) + " i wpisuję /" + KOMENDA_WEDKA, DiscordWebhook.POMARANCZOWY);
    }

    private void tickWymianyWedki(LocalPlayer p) {
        wymianaTick++;
        if (wymianaTick == wymianaSlotO) {
            p.getInventory().setSelectedSlot(SLOT_PO_ZUZYCIU);
        } else if (wymianaTick == wymianaKomendaO) {
            p.connection.sendCommand(KOMENDA_WEDKA);
        } else if (wymianaTick >= wymianaKoniec) {
            wymianaTick = -1;
            ItemStack wRece = p.getMainHandItem();
            if (wRece.is(Items.FISHING_ROD) && wytrzymalosc(wRece) > MIN_WYTRZYMALOSC) {
                opoznienieZarzutu = -1;
                blokadaZarzutu = java.util.concurrent.ThreadLocalRandom.current().nextInt(10, 31);
                alert("§aWędka gotowa (" + opisWytrzymalosci(wRece) + ") - łowię dalej.", DiscordWebhook.ZIELONY);
            } else {
                active = false;
                alert("§cPo /" + KOMENDA_WEDKA + " nadal nie ma sprawnej wędki w ręce - zatrzymano auto-łowienie.", DiscordWebhook.CZERWONY);
            }
        }
    }

    /** Poziom EXP podczas łowienia, np. "Poziom EXP: 30 (45%) | +2". */
    private String poziomExpLowienia() {
        if (mc.player == null) return "§7Poziom EXP: -";
        int lvl = mc.player.experienceLevel;
        int proc = Math.round(mc.player.experienceProgress * 100);
        return "§7Poziom EXP: §a" + lvl + " §7(" + proc + "%)"
                + (startPoziomLowienia >= 0 ? " §7| §a+" + (lvl - startPoziomLowienia) : "");
    }

    /** /stats: statystyki kopania (gdy działa /kop) albo łowienia. */
    private void pokazStaty(Consumer<Component> out) {
        if (kopanie.aktywne() && !active) {
            kopanie.pokaz(mc, out);
        } else {
            staty.pokaz(out);
            out.accept(Component.literal(poziomExpLowienia()));
        }
    }

    /** Ustawia obecne miejsce i kierunek patrzenia jako łowisko. */
    private void ustawLowisko(LocalPlayer p) {
        lowiskoX = p.getX();
        lowiskoY = p.getY();
        lowiskoZ = p.getZ();
        lowiskoWymiar = p.level().dimension();
        spacer.start(p);
        ruch.start(p.getYRot(), p.getXRot());
        opoznienieZarzutu = -1;
        blokadaZarzutu = 30;
    }

    // ---------------- Sterowanie z Discorda ----------------

    /** Wykonuje polecenie tekstowe "!..." z Discorda (wywoływane w wątku gry). */
    private void obsluzPolecenie(DiscordSterowanie.Polecenie p) {
        String nick = mc.player != null ? mc.player.getPlainTextName() : mc.getUser().getName();
        String cel = p.cel();
        boolean doWszystkich = cel == null || cel.equalsIgnoreCase("wszyscy") || cel.equalsIgnoreCase("all") || cel.equals("*");
        if (!doWszystkich && !cel.equalsIgnoreCase(nick)) return; // polecenie dla innego gracza

        String akcja = p.akcja();
        String arg = p.argument();
        StringBuilder szczegoly = new StringBuilder();
        Consumer<Component> out = zbieraj(szczegoly);

        String odp;
        switch (akcja) {
            case "lista", "kto" -> odp = statusKrotki();
            case "status" -> {
                wyslijStatusDiscord();
                odp = statusKrotki();
            }
            case "sekwencja", "seq" -> {
                String a = arg.toLowerCase();
                String n = mc.getUser().getName();
                odp = a.startsWith("kon") ? sekwencje.uruchom(n, Sekwencje.KONIEC)
                        : a.startsWith("stop") || a.startsWith("przerw") ? sekwencje.przerwij()
                        : a.contains("zera") || a.startsWith("zero") ? sekwencje.uruchom(n, Sekwencje.START)
                        : a.contains("serwer") ? sekwencje.uruchom(n, Sekwencje.SERWER)
                        : sekwencje.uruchomStart(mc, n);
            }
            case "koniec" -> odp = sekwencje.uruchom(mc.getUser().getName(), false);
            case "serwer", "wejdz" -> odp = wejdzNaSerwer(arg);
            case "wyjdz" -> odp = wejdzNaSerwer("!wyjdz");
            case "zaloguj" -> odp = uruchomKroki("zaloguj " + arg, "logowanie i wejście do gry");
            case "login" -> odp = sekwencje.zaloguj(mc, mc.getUser().getName());
            case "graj" -> odp = uruchomKroki("graj " + arg, "wejście do gry");
            case "kliknij" -> odp = uruchomKroki("kliknij " + arg, "kliknięcie w menu");
            case "gui", "menu" -> odp = "📋 menu: " + MenuSerwera.lista(mc);
            case "panel" -> {
                // panel z przyciskami wstawiany na dół kanału - sam w sobie jest odpowiedzią
                sterowanie.przeniesPanelNaDol();
                wiadomosc(Component.literal("§9[Discord] §7!panel"));
                return;
            }
            case "pomoc", "help" -> {
                if (cel == null) return; // pomoc tylko dla konkretnego nicku - żeby wszyscy nie odpowiadali naraz
                odp = "polecenia (zamiast nicku można napisać **wszyscy**):\n"
                        + "`!lista` - kto jest w grze i co robi\n"
                        + "`!panel` - wstaw panele z przyciskami na dół kanału\n"
                        + "`!alerty` - napisz na kanale, na który mają przychodzić alerty (`!alerty off` - wyłącz)\n"
                        + "`!" + nick + " status` - pełny status\n"
                        + "`!" + nick + " start` / `start kop` - włącz łowienie / kopanie\n"
                        + "`!" + nick + " stop` - zatrzymaj łowienie i kopanie\n"
                        + "`!" + nick + " turbo 5` - następne 5 gier bez pomyłek\n"
                        + "`!" + nick + " kilof` - lista kilofów | `kilof 3` - weź kilof ze slotu 3\n"
                        + "`!" + nick + " slot 2` - weź do ręki slot 2 paska\n"
                        + "`!" + nick + " w` - wyślij /" + KOMENDA_WEDKA + "\n"
                        + "`!" + nick + " home ryby2` - wyślij /home ryby2 (łowienie przeniesie się tam)\n"
                        + "`!" + nick + " komenda spawn` - wyślij dowolną komendę (tu: /spawn)\n"
                        + "`!" + nick + " serwer skyup.pl` / `wyjdz` - wejdź na serwer / wyjdź\n"
                        + "`!" + nick + " zaloguj` - hasło + wejście do gry (prawy klik → „Graj!”) | `login` - samo hasło\n"
                        + "`!" + nick + " gui` - co jest w otwartym menu serwera | `kliknij Graj` / `kliknij #13` - kliknij w menu\n"
                        + "`!wszyscy sekwencja` - start (sam wybiera: `od zera` w menu gry / `na serwerze`), `sekwencja od zera`, `sekwencja serwer`\n"
                        + "`!wszyscy koniec` - sekwencja końcowa (sekwencje i harmonogram ustawiasz w ⚙ na panelu)";
            }
            default -> {
                if (akcja.equals("stop") && sekwencje.trwa()) sekwencje.przerwij();
                odp = mc.player == null ? "⚫ nie jestem na serwerze (menu gry)" : wykonaj(akcja, arg, out, nick);
            }
        }
        if (szczegoly.length() > 0) {
            odp += "\n> " + szczegoly;
        }
        sterowanie.odpowiedz(p, "**" + nick + "**: " + odp);
        wiadomosc(Component.literal("§9[Discord] §7!" + akcja + (arg.isEmpty() ? "" : " " + arg)));
    }

    /** Kliknięcie w panelu na Discordzie (wywoływane w wątku gry). Zwraca opis wyniku pokazywany na panelu albo null. */
    private String obsluzAkcje(DiscordSterowanie.Akcja a) {
        String nickKonta = mc.getUser().getName();
        String wartosc = a.wartosc();
        wiadomosc(Component.literal("§9[Discord] §7panel: " + a.akcja()
                + (wartosc.isEmpty() || a.akcja().equals("komendaok") ? "" : " " + wartosc)));

        // akcje, które działają też w menu gry (bez wejścia na serwer)
        switch (a.akcja()) {
            case "odswiez" -> {
                return null;
            }
            case "seqstart", "seqstartall" -> {
                return sekwencje.uruchomStart(mc, nickKonta); // sam wybiera: od zera / na serwerze
            }
            case "seqzero" -> {
                return sekwencje.uruchom(nickKonta, Sekwencje.START);
            }
            case "seqserwer" -> {
                return sekwencje.uruchom(nickKonta, Sekwencje.SERWER);
            }
            case "seqkoniec", "seqkoniecall" -> {
                return sekwencje.uruchom(nickKonta, false);
            }
            case "seqstop" -> {
                return sekwencje.przerwij();
            }
            case "serwer", "serwerok" -> {
                return wejdzNaSerwer(wartosc.startsWith("s:") ? wartosc.substring(2) : wartosc);
            }
            case "zaloguj" -> {
                return uruchomKroki("zaloguj", "logowanie i wejście do gry");
            }
            case "stop", "stopall" -> {
                if (sekwencje.trwa()) sekwencje.przerwij();
                if (mc.player == null) {
                    return "⏹ zatrzymano";
                }
            }
            default -> {
            }
        }

        if (mc.player == null) return "⚫ bot nie jest teraz na serwerze";
        String nick = mc.player.getPlainTextName();
        StringBuilder szczegoly = new StringBuilder();
        Consumer<Component> out = zbieraj(szczegoly);
        String odp = switch (a.akcja()) {
            case "start" -> wykonaj("start", "", out, nick);
            case "startkop" -> wykonaj("start", "kop", out, nick);
            case "stop" -> wykonaj("stop", "", out, nick);
            case "stopall" -> "🛑 STOP wszystkich: " + wykonaj("stop", "", out, nick);
            case "turbook" -> wykonaj("turbo", wartosc, out, nick);
            case "slot" -> wykonaj("slot", wartosc, out, nick);
            case "w" -> wykonaj("w", "", out, nick);
            case "homeok" -> wykonaj("home", wartosc, out, nick);
            case "komendaok" -> wykonaj("komenda", wartosc, out, nick);
            default -> "❓ nieznany przycisk `" + a.akcja() + "`";
        };
        if (szczegoly.length() > 0) {
            odp += " — " + szczegoly;
        }
        return odp;
    }

    /** Uruchamia kroki podane tekstem (jak w sekwencji), np. "zaloguj" albo "kliknij Graj". */
    private String uruchomKroki(String tekst, String nazwa) {
        List<String> bledy = new ArrayList<>();
        List<Sekwencje.Krok> kroki = Sekwencje.parsuj(tekst.strip(), bledy);
        if (!bledy.isEmpty()) return "❌ " + bledy.get(0);
        if (kroki.isEmpty()) return "❌ nic do zrobienia";
        return sekwencje.uruchom(kroki, nazwa);
    }

    /** Wejście na serwer (albo wyjście, gdy cel to "!wyjdz") - jako jednokrokowa sekwencja, żeby poczekać na rozłączenie. */
    private String wejdzNaSerwer(String cel) {
        String c = cel == null ? "" : cel.trim();
        if (c.equals("!wyjdz")) {
            if (mc.level == null) return "ℹ️ nie jestem na serwerze";
            sekwencje.uruchom(List.of(new Sekwencje.Krok("wyjdz", "", "wyjdz")), "wyjście z serwera");
            return "⏏ wychodzę z serwera";
        }
        if (c.isEmpty()) return "❌ podaj adres serwera";
        String adres = Serwery.adres(mc, c);
        if (Serwery.naSerwerze(mc, adres)) return "ℹ️ już jestem na " + adres;
        sekwencje.uruchom(List.of(new Sekwencje.Krok("serwer", adres, "serwer " + adres)), "wejście na serwer");
        return "🖥 wchodzę na " + adres;
    }

    /** Krok sekwencji startowej/końcowej, który dotyczy gry (wątek gry). */
    private String krokSekwencji(String akcja, String arg) {
        if (mc.player == null) return "❌ nie jestem na serwerze";
        String nick = mc.player.getPlainTextName();
        StringBuilder szczegoly = new StringBuilder();
        Consumer<Component> out = zbieraj(szczegoly);
        String wynik = switch (akcja) {
            case "kop" -> wykonaj("start", "kop", out, nick);
            case "lowienie" -> {
                wezWedke();
                yield wykonaj("start", "", out, nick);
            }
            default -> wykonaj(akcja, arg, out, nick); // turbo, slot, w, home, stop
        };
        if (wynik.startsWith("❌") && szczegoly.length() > 0) {
            wynik += " (" + szczegoly + ")";
        }
        return wynik;
    }

    /**
     * Bierze wędkę do ręki: sprawną z paska, a gdy na pasku jej nie ma - przekłada z ekwipunku na obecny slot.
     * Zwraca opis, co zrobiono, albo null (wędka już w ręce / nie ma sprawnej wędki).
     */
    private String wezWedke() {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return null;
        ItemStack reka = p.getMainHandItem();
        if (reka.is(Items.FISHING_ROD) && wytrzymalosc(reka) > MIN_WYTRZYMALOSC) return null;
        net.minecraft.world.entity.player.Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.FISHING_ROD) && wytrzymalosc(s) > MIN_WYTRZYMALOSC) {
                inv.setSelectedSlot(i);
                return "🎣 wziąłem wędkę ze slotu " + (i + 1);
            }
        }
        if (p.containerMenu != p.inventoryMenu) return null; // otwarte inne okno - nie przekładamy
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.FISHING_ROD) && wytrzymalosc(s) > MIN_WYTRZYMALOSC) {
                int slotPaska = inv.getSelectedSlot();
                // sloty 9-35 ekwipunku mają w oknie gracza te same numery; SWAP zamienia je ze slotem paska
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, i, slotPaska, ContainerInput.SWAP, p);
                return "🎣 przełożyłem wędkę z ekwipunku na slot " + (slotPaska + 1);
            }
        }
        return null;
    }

    /** Wynik sekwencji: czat + panel na Discordzie. */
    private void raportSekwencji(String tekst) {
        wiadomosc(Component.literal("§9[Sekwencja] §f" + tekst.replace("`", "")));
        sterowanie.ustawOstatniaAkcje(tekst);
    }

    /** Akcje wspólne dla poleceń "!nick ..." i przycisków panelu. Zwraca odpowiedź do pokazania na Discordzie. */
    private String wykonaj(String akcja, String arg, Consumer<Component> out, String nick) {
        switch (akcja) {
            case "start" -> {
                if (arg.equalsIgnoreCase("kop")) {
                    if (kopanie.aktywne()) {
                        return "ℹ️ kopanie już działa";
                    }
                    active = false;
                    return kopanie.start(mc, t -> out.accept(Component.literal(t))) ? "✅ włączono kopanie" : "❌ nie udało się włączyć kopania";
                }
                if (active) {
                    return "ℹ️ łowienie już działa";
                }
                return wlaczBota(out) ? "✅ włączono łowienie" : "❌ nie udało się włączyć łowienia";
            }
            case "stop" -> {
                boolean cosDzialalo = active || kopanie.aktywne();
                active = false;
                kopanie.stop();
                return cosDzialalo ? "⏹ zatrzymano łowienie i kopanie" : "ℹ️ nic nie działało";
            }
            case "turbo" -> {
                int n = liczba(arg, 1, 100);
                if (n < 0) {
                    return "❌ podaj liczbę gier 1-100, np. `!" + nick + " turbo 5`";
                }
                if (!active && !wlaczBota(out)) {
                    return "❌ nie udało się włączyć łowienia";
                }
                turboGry = n;
                turboRazem = n;
                return "⚡ turbo: następne " + n + " gier bez pomyłek";
            }
            case "kilof" -> {
                if (arg.isEmpty()) {
                    return listaKilofow();
                }
                int slot = liczba(arg, 1, 9);
                if (slot < 0) {
                    return "❌ podaj slot 1-9, np. `!" + nick + " kilof 3`";
                }
                mc.player.getInventory().setSelectedSlot(slot - 1);
                ItemStack s = mc.player.getInventory().getItem(slot - 1);
                return s.is(ItemTags.PICKAXES)
                        ? "⛏ wzięto kilof ze slotu " + slot + ": " + opisPrzedmiotu(s)
                        : "⚠️ wybrano slot " + slot + ", ale nie ma tam kilofa (" + (s.isEmpty() ? "pusty" : s.getHoverName().getString()) + ")";
            }
            case "slot" -> {
                int slot = liczba(arg, 1, 9);
                if (slot < 0) {
                    return "❌ podaj slot 1-9, np. `!" + nick + " slot 2`";
                }
                mc.player.getInventory().setSelectedSlot(slot - 1);
                ItemStack s = mc.player.getInventory().getItem(slot - 1);
                String odp = "🖐 slot " + slot + ": " + (s.isEmpty() ? "pusty" : opisPrzedmiotu(s));
                if (active && !s.is(Items.FISHING_ROD)) {
                    active = false; // bez wędki łowienie i tak by się zatrzymało - robimy to po cichu
                    odp += " (łowienie zatrzymane - w ręce nie ma wędki)";
                }
                return odp;
            }
            case "w" -> {
                mc.player.connection.sendCommand(KOMENDA_WEDKA);
                return "✅ wysłano /" + KOMENDA_WEDKA;
            }
            case "home" -> {
                if (arg.isEmpty()) {
                    return "❌ podaj nazwę home, np. `!" + nick + " home ryby`";
                }
                komendaPowrotu = "home " + arg;
                mc.player.connection.sendCommand(komendaPowrotu);
                if (active) {
                    czekamNaNoweLowisko = 600; // do 30 s na teleport
                    wracaDoDomu = false;
                }
                return "🏠 wysłano /" + komendaPowrotu + (active ? " - po teleporcie łowię dalej w nowym miejscu" : "");
            }
            case "komenda" -> {
                String k = arg.startsWith("/") ? arg.substring(1).trim() : arg.trim();
                if (k.isEmpty()) {
                    return "❌ podaj komendę, np. `!" + nick + " komenda spawn`";
                }
                mc.player.connection.sendCommand(k);
                return "⌨ wysłano /" + k;
            }
            default -> {
                return "❓ nieznane polecenie `" + akcja + "` (pomoc: `!" + nick + " pomoc`)";
            }
        }
    }

    /** Zbiera komunikaty z czatu (np. "Weź wędkę do ręki") do jednej linijki na Discorda. */
    private static Consumer<Component> zbieraj(StringBuilder szczegoly) {
        return c -> {
            if (szczegoly.length() > 0) szczegoly.append(" | ");
            szczegoly.append(c.getString().replaceAll("§.", ""));
        };
    }

    /** Migawka stanu bota do panelu na Discordzie (wywoływane w wątku gry co kilka sekund). */
    private PanelSterowania.Stan stanPanelu() {
        String nick = mc.getUser().getName();
        String home = komendaPowrotu.startsWith("home ") ? komendaPowrotu.substring(5) : komendaPowrotu;
        LocalPlayer p = mc.player;
        List<String[]> listaSerwerow = Serwery.lista(mc);
        String nastepny = sekwencje.nastepnyHarmonogram(nick);
        if (p == null) {
            List<String> uwagiMenu = new ArrayList<>();
            if (sekwencje.postep() != null) uwagiMenu.add(sekwencje.postep());
            List<String[]> polaMenu = new ArrayList<>();
            if (nastepny != null) polaMenu.add(new String[]{"⏰ Harmonogram", nastepny});
            return new PanelSterowania.Stan(nick, false, null, false, false, "⚫ Poza serwerem (menu gry)",
                    uwagiMenu, DiscordWebhook.SZARY, polaMenu, List.of(), List.of(), -1, home, listaSerwerow, sekwencje.trwa());
        }

        boolean kopie = kopanie.aktywne();
        boolean trybKopania = kopie && !active;
        List<String> uwagi = new ArrayList<>();
        String status;
        int kolor;
        if (active) {
            status = "🎣 Łowi";
            kolor = DiscordWebhook.ZIELONY;
            if (wymianaTick >= 0) {
                uwagi.add("🔧 Wymiana wędki (/" + KOMENDA_WEDKA + ")");
                kolor = DiscordWebhook.POMARANCZOWY;
            }
            if (wracaDoDomu) {
                uwagi.add("↺ Wracam na łowisko (/" + komendaPowrotu + ")");
                kolor = DiscordWebhook.POMARANCZOWY;
            }
            if (czekamNaNoweLowisko > 0) {
                uwagi.add("↻ Czekam na teleport (/" + komendaPowrotu + ")");
            }
            if (reaguje()) {
                uwagi.add("👀 Reaguję na obrót od serwera");
                kolor = DiscordWebhook.POMARANCZOWY;
            }
            if (turboGry > 0) {
                uwagi.add("⚡ Turbo: zostało gier: " + turboGry);
            }
        } else if (kopie) {
            status = "⛏ Kopie";
            kolor = DiscordWebhook.NIEBIESKI;
        } else {
            status = "💤 Nic nie robi";
            kolor = DiscordWebhook.SZARY;
        }
        if (sekwencje.postep() != null) {
            uwagi.add(sekwencje.postep());
        }

        ItemStack reka = p.getMainHandItem();
        List<String[]> pola = new ArrayList<>();
        pola.add(new String[]{"🖐 W ręce", reka.isEmpty() ? "nic" : opisPrzedmiotu(reka)});
        pola.add(new String[]{"📗 Poziom EXP", poziomExpLowienia().replaceAll("§.", "").replace("Poziom EXP: ", "")});
        pola.add(new String[]{"🏠 Home", "/" + komendaPowrotu});
        if (nastepny != null) {
            pola.add(new String[]{"⏰ Harmonogram", nastepny});
        }
        List<String[]> statyLowienia = staty.polaDiscord();
        List<String[]> statyKopania = kopie ? kopanie.polaDiscord(mc) : List.of();
        Set<String> skrot = trybKopania
                ? Set.of("⏱ Czas kopania", "🧱 Wykopane bloki", "⏳ Do zmiany kilofa")
                : Set.of("⏱ Czas pracy", "🎮 Gry", "🐟 Ryby", "💰 Wartość razem", "📈 Tempo", "🔑 Klucze");
        for (String[] f : trybKopania ? statyKopania : statyLowienia) {
            if (skrot.contains(f[0])) pola.add(f);
        }

        List<String[]> pelne = new ArrayList<>();
        if (trybKopania) {
            pelne.addAll(statyKopania);
            pelne.addAll(statyLowienia);
        } else {
            pelne.addAll(statyLowienia);
            pelne.addAll(statyKopania);
        }

        List<String> pasek = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            pasek.add((i + 1) + ": " + (s.isEmpty() ? "pusty" : opisPrzedmiotu(s)));
        }

        String serwer = mc.getCurrentServer() != null ? mc.getCurrentServer().ip
                : mc.hasSingleplayerServer() ? "świat jednoosobowy" : null;
        return new PanelSterowania.Stan(nick, true, serwer, active, kopie, status, uwagi, kolor,
                pola, pelne, pasek, p.getInventory().getSelectedSlot(), home, listaSerwerow, sekwencje.trwa());
    }

    /** Krótki opis stanu do !lista. */
    private String statusKrotki() {
        String sek = sekwencje.postep() != null ? " | " + sekwencje.postep() : "";
        if (mc.player == null) return "⚫ poza serwerem (menu gry)" + sek;
        String s;
        if (active) {
            s = "🎣 łowi" + (wracaDoDomu ? " (wraca na łowisko)" : "") + (turboGry > 0 ? " | turbo: " + turboGry : "");
        } else if (kopanie.aktywne()) {
            s = "⛏ kopie";
        } else {
            s = "💤 nic nie robi";
        }
        ItemStack h = mc.player.getMainHandItem();
        s += " | w ręce: " + (h.isEmpty() ? "nic" : opisPrzedmiotu(h));
        s += " | poziom " + mc.player.experienceLevel;
        return s + sek;
    }

    private String listaKilofow() {
        StringBuilder sb = new StringBuilder("kilofy na pasku: ");
        int wybrany = mc.player.getInventory().getSelectedSlot();
        boolean jest = false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(ItemTags.PICKAXES)) {
                sb.append("\n").append(i == wybrany ? "▶ " : "• ").append(i + 1).append(": ").append(opisPrzedmiotu(s));
                jest = true;
            }
        }
        return jest ? sb.toString() : "brak kilofów na pasku";
    }

    private static String opisPrzedmiotu(ItemStack s) {
        String n = s.getHoverName().getString();
        return s.isDamageableItem() ? n + " (" + wytrzymalosc(s) + "/" + s.getMaxDamage() + ")" : n;
    }

    /** Liczba z tekstu w zakresie [min, max], albo -1. */
    private static int liczba(String t, int min, int max) {
        try {
            int v = Integer.parseInt(t.trim());
            return v >= min && v <= max ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Wiadomość w czacie + powiadomienie na Discordzie (dla ważnych zdarzeń). */
    private void alert(String tekst, int kolor) {
        wiadomosc(Component.literal(tekst));
        String czysty = tekst.replaceAll("§.", "");
        if (discord.wlaczony()) {
            discord.zdarzenie(czysty, kolor, stopkaDiscord());
        }
        // ważne zdarzenia (np. zgubiona wędka): na panelu ("Ostatnia akcja") i na kanale alertów, jeśli jest ustawiony
        sterowanie.ustawOstatniaAkcje("🔔 " + czysty, false);
        sterowanie.alert(mc.getUser().getName(), czysty, kolor);
    }

    private String stopkaDiscord() {
        return mc.player != null ? "Gracz: " + mc.player.getPlainTextName() : "Auto-łowienie";
    }

    private void wyslijStatusDiscord() {
        if (mc.player == null) return;
        if (kopanie.aktywne() && !active) {
            List<String[]> pk = new ArrayList<>();
            pk.add(new String[]{"⛏ Auto-kopanie", "🟢 Kopię"});
            pk.addAll(kopanie.polaDiscord(mc));
            discord.wyslij("⛏ Auto-kopanie - status", null, DiscordWebhook.ZIELONY, pk, stopkaDiscord());
            return;
        }
        List<String[]> pola = new ArrayList<>();
        String status;
        int kolor;
        if (!active) {
            status = "🔴 Wyłączone";
            kolor = kopanie.aktywne() ? DiscordWebhook.ZIELONY : DiscordWebhook.SZARY;
        } else if (wymianaTick >= 0) {
            status = "🔧 Wymiana wędki (/" + KOMENDA_WEDKA + ")";
            kolor = DiscordWebhook.POMARANCZOWY;
        } else if (wracaDoDomu) {
            status = "↺ Wracam na łowisko";
            kolor = DiscordWebhook.POMARANCZOWY;
        } else if (reaguje()) {
            status = "👀 Reaguję na obrót";
            kolor = DiscordWebhook.POMARANCZOWY;
        } else {
            status = "🟢 Łowię";
            kolor = DiscordWebhook.ZIELONY;
        }
        pola.add(new String[]{"🎣 Łowienie", status});
        pola.add(new String[]{"⛏ Kopanie", kopanie.aktywne() ? "🟢 Kopię | kilof: " + kopanie.opisKilofaWRece(mc) : "wyłączone"});
        ItemStack wedka = mc.player.getMainHandItem();
        String opisWedki;
        if (!wedka.is(Items.FISHING_ROD)) {
            opisWedki = "brak w ręce";
        } else if (!wedka.isDamageableItem()) {
            opisWedki = "niezniszczalna";
        } else {
            int zostalo = wytrzymalosc(wedka);
            opisWedki = zostalo + "/" + wedka.getMaxDamage() + " (" + Math.round(100f * zostalo / wedka.getMaxDamage()) + "%)"
                    + (zostalo <= MIN_WYTRZYMALOSC ? " ⚠" : "");
        }
        pola.add(new String[]{"🎣 Wędka", opisWedki});
        if (turboGry > 0) {
            pola.add(new String[]{"⚡ Turbo", "zostało gier: " + turboGry});
        }
        pola.add(new String[]{"📗 Poziom EXP", poziomExpLowienia().replaceAll("§.", "").replace("Poziom EXP: ", "")});
        pola.addAll(staty.polaDiscord());
        discord.wyslij("🎣 Auto-łowienie - status", null, kolor, pola, stopkaDiscord());
    }

    /** Czy trwa reakcja na obrót przez serwer (wtedy nie łowimy). */
    private boolean reaguje() {
        return reakcja.trwa() || ruch.czyReakcjaTrwa();
    }

    /** Jak bardzo obrót różni się od zapamiętanego (stopnie). */
    private float roznicaObrotu(float yaw, float pitch) {
        float dYaw = (yaw - ostatniYaw) % 360f;
        if (dYaw > 180f) dYaw -= 360f;
        if (dYaw < -180f) dYaw += 360f;
        return Math.max(Math.abs(dYaw), Math.abs(pitch - ostatniPitch));
    }

    private boolean obrotSieZmienil(float yaw, float pitch) {
        float dYaw = (yaw - ostatniYaw) % 360f;
        if (dYaw > 180f) dYaw -= 360f;
        if (dYaw < -180f) dYaw += 360f;
        return Math.abs(dYaw) > PROG_OBROTU || Math.abs(pitch - ostatniPitch) > PROG_OBROTU;
    }

    private boolean sadzonka(double[] coordinates, Minecraft client) {
        LocalPlayer player = mc.player;
        Vec3 playerPos = player.getEyePosition();
        net.minecraft.world.entity.player.Inventory eq = mc.player.getInventory();

        double distance = Math.sqrt((coordinates[0] - playerPos.x) * (coordinates[0] - playerPos.x) + (coordinates[2] - playerPos.z) * (coordinates[2] - playerPos.z));
//        double offset = distance/10 * 2;
        if(odleglosc[0] != distance) {
            wiadomosc(Component.literal("§cDystans " + String.format("%.2f", distance)));
            odleglosc[0] = distance;
        }

        controler.lookAt(coordinates[0] - 0.5,playerPos.y - 2 ,coordinates[2] - 0.5, false);
        String blockSeen = controler.coWidze();
//       wiadomosc(Component.literal("§3distance" + distance));
        if(distance > 2) {
            client.options.keyUp.setDown(true);
            client.options.keyAttack.setDown(true);
        }
        else {
            client.options.keyUp.setDown(false);
            client.options.keyAttack.setDown(false);

            if (blockSeen.equals("minecraft:spruce_log") || blockSeen.equals("minecraft:spruce_leaves")) {
                client.options.keyAttack.setDown(true);
                client.options.keyUse.setDown(false);
            } else if (blockSeen.equals("minecraft:dirt") || blockSeen.equals("minecraft:grass_block")) {
                client.options.keyAttack.setDown(false);

                if(controler.znajdzSlotZPrzedmiotem(Items.SPRUCE_SAPLING) != -1){
                    eq.setSelectedSlot(controler.znajdzSlotZPrzedmiotem(Items.SPRUCE_SAPLING));
                }

                client.options.keyUse.setDown(true);
                return true;

            } else {
                client.options.keyAttack.setDown(false);
                client.options.keyUse.setDown(false);
            }

        }
        return false;
    }




    private float durabilityCheck() {
        var player = mc.player;

        ItemStack item = player.getMainHandItem();

        int maxDamage = item.getMaxDamage();
        int currentDamage = item.getDamageValue();
        int left = maxDamage - currentDamage;

        float percent = (float) left / maxDamage;

        return percent * 100;
    }


    private void clickSlot(int windowId, int slotId, int button, ContainerInput type) {
        if (mc.gameMode != null) {
            mc.gameMode.handleContainerInput(windowId, slotId, button, type, mc.player);
        }
    }

    private int znajdzSlotZPrzedmiotem() {
        var menu = mc.player.containerMenu;

        for (int i = 9; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.hasItem() && slot.getItem().getItem() == Items.STONE) {
                return i;
            }
        }
        return -1;
    }


    private void crafting() {

        if (mc.player.containerMenu == null) {
            return;
        }
        int windowId = mc.player.containerMenu.containerId;

        int slotZDiamentem = znajdzSlotZPrzedmiotem();
        clickSlot(windowId, slotZDiamentem, 0,ContainerInput.PICKUP);
        for(int i = 0; i <= 64; i++) {
            for (int craftingSlot = 1; craftingSlot <= 4; craftingSlot++) {
                clickSlot(windowId, craftingSlot, 1, ContainerInput.PICKUP);
            }
        }

        clickSlot(windowId, 0, 0,ContainerInput.QUICK_MOVE);
        return;

    }

    private double scanner_drewno(int RADIUS) {
        var player = mc.player;
        var level = mc.level;
        var playerPos = player.blockPosition();
        double target_x, target_y, target_z;

        for(int radius = 1; radius <= RADIUS; radius++) {
        for (int x = 0; x <= radius; x++) {
            for (int y = 0; y <= radius; y++) {
                for (int z = 0; z <= radius; z++) {
                    for (int znak = -1; znak <= 1; znak+=2) {
                        BlockPos checkPos = playerPos.offset(x*znak , y*znak, z*znak);
                        BlockState state = level.getBlockState(checkPos);

                        if (state.is(Blocks.SPRUCE_LOG)) {
                            return lookAt(checkPos.getX(), checkPos.getY(), checkPos.getZ());
                        }
                    }
                }
            }
        }
        }
        return -1;
    }




    private double lookAt(double targetX, double targetY, double targetZ) {

        float pi = (float) Math.PI;
        LocalPlayer player = mc.player;
        Vec3 playerPos = player.getEyePosition();

        double dx = targetX + 0.5 - playerPos.x;
        double dy = targetY+ 0.5 - playerPos.y;
        double dz = targetZ + 0.5 - playerPos.z;

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.atan2(dz, dx) / (2*pi) * 360 - 90;

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

        player.setYRot(yaw);
        player.setXRot(pitch);


        player.yRotO = yaw;
        player.xRotO = pitch;
        return distanceXZ;
    }


}
