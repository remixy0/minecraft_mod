package com.example.kopanie;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * /kop - automatyczne kopanie: trzyma przycisk kopania (działa też, gdy okno gry nie jest aktywne).
 * - gdy kilof ma mało wytrzymałości -> przełącza na inny sprawny kilof z paska (jeśli włączone),
 * - gdy nie ma żadnego sprawnego kilofa -> zatrzymuje się, żeby żadnego nie zepsuć.
 * Ustawienia są zapisywane w config/modid-kopanie.properties.
 */
public class AutoKopanie {
    /** Czytane przez KopanieMixin: czy wymuszać trzymanie przycisku kopania. */
    private static volatile boolean wymuszaj = false;

    public static boolean wymus() {
        return wymuszaj;
    }

    private final Path plik = FabricLoader.getInstance().getConfigDir().resolve("modid-kopanie.properties");
    private boolean aktywne = false;

    // --- ustawienia ---
    private int minWytrzymalosc = 10;   // przy tylu (lub mniej) użyciach kilof jest "zużyty"
    private boolean zmieniajKilof = true; // przełączaj na inny kilof z paska

    // --- statystyki kopania (od /kop) ---
    private static final Locale PL = Locale.forLanguageTag("pl-PL");
    private long tickiKopania = 0;      // ile ticków faktycznie kopał
    private int bloki = 0;              // wykopane bloki
    private int zuzycieNetto = 0;       // ile wytrzymałości ubyło (minus naprawy, np. Mending)
    private int startPoziom = -1;       // poziom EXP przy starcie
    private int ostatniSlot = -1, ostatniDamage = -1;

    public void wczytaj() {
        try {
            if (Files.exists(plik)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(plik, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                minWytrzymalosc = Math.max(2, Integer.parseInt(p.getProperty("minWytrzymalosc", "10").trim()));
                zmieniajKilof = Boolean.parseBoolean(p.getProperty("zmieniajKilof", "true").trim());
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się wczytać ustawień kopania: " + e);
        }
    }

    private void zapisz() {
        try {
            Properties p = new Properties();
            p.setProperty("minWytrzymalosc", String.valueOf(minWytrzymalosc));
            p.setProperty("zmieniajKilof", String.valueOf(zmieniajKilof));
            Files.createDirectories(plik.getParent());
            try (Writer w = Files.newBufferedWriter(plik, StandardCharsets.UTF_8)) {
                p.store(w, "Auto-kopanie (/kop)");
            }
        } catch (Exception e) {
            System.err.println("[modid] Nie udało się zapisać ustawień kopania: " + e);
        }
    }

    public boolean aktywne() {
        return aktywne;
    }

    public int minWytrzymalosc() {
        return minWytrzymalosc;
    }

    public boolean zmieniajKilof() {
        return zmieniajKilof;
    }

    public void ustawMinWytrzymalosc(int v) {
        minWytrzymalosc = Math.max(2, v);
        zapisz();
    }

    public void ustawZmieniajKilof(boolean v) {
        zmieniajKilof = v;
        zapisz();
    }

    /** Włącza kopanie. Zwraca false (i pisze dlaczego), jeśli nie ma sprawnego kilofa. */
    public boolean start(Minecraft mc, Consumer<String> out) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        ItemStack wRece = p.getMainHandItem();
        if (!sprawnyKilof(wRece)) {
            int slot = zmieniajKilof ? znajdzKilof(p) : -1;
            if (slot < 0) {
                out.accept(jestKilofem(wRece)
                        ? "§cKilof ma za mało wytrzymałości (" + opis(wRece) + ") i nie ma innego sprawnego na pasku - nie zaczynam."
                        : "§cWeź kilof do ręki (albo połóż sprawny kilof na pasku), zanim włączysz /kop.");
                return false;
            }
            p.getInventory().setSelectedSlot(slot);
            out.accept("§7Wzięto kilof ze slotu " + (slot + 1) + ".");
        }
        aktywne = true;
        tickiKopania = 0;
        bloki = 0;
        zuzycieNetto = 0;
        startPoziom = p.experienceLevel;
        ostatniSlot = -1;
        ostatniDamage = -1;
        return true;
    }

    /** Wywoływane po zniszczeniu bloku przez gracza. */
    public void zliczBlok() {
        if (aktywne) bloki++;
    }

    public void stop() {
        aktywne = false;
        wymuszaj = false;
    }

    /**
     * Co tick. alert: wiadomość o ważnym zdarzeniu (zmiana kilofa / zatrzymanie).
     */
    public void tick(Minecraft mc, Consumer<String> alert) {
        if (!aktywne) {
            wymuszaj = false;
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null) {
            stop();
            return;
        }
        ItemStack wRece = p.getMainHandItem();

        // zużycie kilofa w czasie (do przewidywania, kiedy trzeba będzie go zmienić)
        int slotTeraz = p.getInventory().getSelectedSlot();
        if (jestKilofem(wRece) && wRece.isDamageableItem()) {
            int dmg = wRece.getDamageValue();
            if (slotTeraz == ostatniSlot && ostatniDamage >= 0 && Math.abs(dmg - ostatniDamage) < 100) {
                zuzycieNetto += dmg - ostatniDamage;
            }
            ostatniSlot = slotTeraz;
            ostatniDamage = dmg;
        } else {
            ostatniDamage = -1;
        }
        if (wymuszaj) {
            tickiKopania++;
        }

        if (!sprawnyKilof(wRece)) {
            int slot = zmieniajKilof ? znajdzKilof(p) : -1;
            if (slot >= 0) {
                wymuszaj = false; // na ten tick puszczamy, żeby zmiana przebiegła czysto
                p.getInventory().setSelectedSlot(slot);
                alert.accept("§e⛏ " + (jestKilofem(wRece) ? "Kilof zużyty (" + opis(wRece) + ")" : "Brak kilofa w ręce")
                        + " - przełączam na kilof ze slotu " + (slot + 1) + " (" + opis(p.getInventory().getItem(slot)) + ").");
                return;
            }
            stop();
            alert.accept(jestKilofem(wRece)
                    ? "§c⛏ Kilof prawie zepsuty (" + opis(wRece) + ") i brak innego sprawnego - zatrzymano kopanie."
                    : "§c⛏ Brak kilofa w ręce - zatrzymano kopanie.");
            return;
        }
        // przy otwartym ekranie (czat, ekwipunek) nie kopiemy
        wymuszaj = mc.gui.screen() == null;
    }

    /** Opis kilofa w ręce do panelu, np. "52/250 (21%)". */
    public String opisKilofaWRece(Minecraft mc) {
        if (mc.player == null) return "-";
        ItemStack s = mc.player.getMainHandItem();
        return jestKilofem(s) ? opis(s) : "brak w ręce";
    }

    // --- statystyki do panelu, czatu i Discorda ---

    /** Linie do panelu (bez wiersza z wytrzymałością - ten rysuje panel razem z paskiem). */
    public List<String> liniePanelu(Minecraft mc) {
        List<String> l = new ArrayList<>();
        ItemStack s = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        l.add("§7Zaklęcia: §d" + zaklecia(s));
        l.add("§7Czas kopania: §f" + czas(tickiKopania));
        l.add("§7Wykopane bloki: §f" + bloki + " §7(" + naMinute() + "/min)");
        l.add("§7Do zmiany kilofa: §e" + doZmiany(mc));
        l.add("§7Zapasowe kilofy: §f" + zapasowe(mc));
        l.add(poziomExp(mc, "§7"));
        return l;
    }

    public List<String[]> polaDiscord(Minecraft mc) {
        List<String[]> p = new ArrayList<>();
        ItemStack s = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        p.add(new String[]{"⛏ Kilof", jestKilofem(s) ? s.getHoverName().getString() + " - " + opis(s) : "brak w ręce"});
        p.add(new String[]{"✨ Zaklęcia", zaklecia(s)});
        p.add(new String[]{"⏱ Czas kopania", czas(tickiKopania)});
        p.add(new String[]{"🧱 Wykopane bloki", bloki + " (" + naMinute() + "/min)"});
        p.add(new String[]{"⏳ Do zmiany kilofa", doZmiany(mc)});
        p.add(new String[]{"🔁 Zapasowe kilofy", String.valueOf(zapasowe(mc))});
        p.add(new String[]{"📗 Poziom EXP", poziomExp(mc, "").replaceAll("§.", "").replace("Poziom EXP: ", "")});
        return p;
    }

    public void pokaz(Minecraft mc, java.util.function.Consumer<Component> out) {
        out.accept(Component.literal("§6§l===== Statystyki kopania ====="));
        out.accept(Component.literal("§7Kilof: §f" + opisKilofaWRece(mc)));
        for (String linia : liniePanelu(mc)) {
            out.accept(Component.literal(linia));
        }
    }

    /** Linia z poziomem EXP, np. "Poziom EXP: 30 (45%) | +2 od startu". */
    public String poziomExp(Minecraft mc, String kolor) {
        if (mc.player == null) return kolor + "Poziom EXP: -";
        int lvl = mc.player.experienceLevel;
        int proc = Math.round(mc.player.experienceProgress * 100);
        return kolor + "Poziom EXP: §a" + lvl + " §7(" + proc + "%)"
                + (startPoziom >= 0 ? " §7| §a+" + (lvl - startPoziom) : "");
    }

    private String naMinute() {
        double minuty = tickiKopania / 1200.0;
        return minuty > 0.05 ? String.format(PL, "%.1f", bloki / minuty) : "-";
    }

    /** Szacowany czas, aż kilof w ręce spadnie do progu zmiany. */
    private String doZmiany(Minecraft mc) {
        if (mc.player == null) return "-";
        ItemStack s = mc.player.getMainHandItem();
        if (!jestKilofem(s)) return "-";
        if (!s.isDamageableItem()) return "nigdy (niezniszczalny)";
        if (tickiKopania < 200 || zuzycieNetto <= 0) {
            return tickiKopania < 200 ? "liczę..." : "nie zużywa się (naprawa)";
        }
        double naTick = (double) zuzycieNetto / tickiKopania;
        double zostalo = wytrzymalosc(s) - minWytrzymalosc;
        if (zostalo <= 0) return "teraz";
        long ticki = Math.round(zostalo / naTick);
        return "~" + czasSlownie(ticki);
    }

    private int zapasowe(Minecraft mc) {
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int sel = inv.getSelectedSlot();
        int n = 0;
        for (int i = 0; i < 9; i++) {
            if (i != sel && sprawnyKilof(inv.getItem(i))) n++;
        }
        return n;
    }

    private static String zaklecia(ItemStack s) {
        if (s.isEmpty()) return "-";
        ItemEnchantments e = s.getEnchantments();
        if (e.isEmpty()) return "brak";
        StringBuilder sb = new StringBuilder();
        for (Holder<Enchantment> h : e.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Enchantment.getFullname(h, e.getLevel(h)).getString());
        }
        return sb.toString();
    }

    private static String czas(long ticki) {
        long s = ticki / 20;
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String czasSlownie(long ticki) {
        long min = ticki / 1200;
        if (min < 1) return (ticki / 20) + " s";
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    // ------------------------------------------------------------------

    private boolean sprawnyKilof(ItemStack s) {
        return jestKilofem(s) && wytrzymalosc(s) > minWytrzymalosc;
    }

    /** Szuka na pasku (sloty 1-9) sprawnego kilofa; najpierw tego z największą wytrzymałością. */
    private int znajdzKilof(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int najlepszy = -1;
        int najlepszaWytrzymalosc = minWytrzymalosc;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (jestKilofem(s)) {
                int w = wytrzymalosc(s);
                if (w > najlepszaWytrzymalosc) {
                    najlepszaWytrzymalosc = w;
                    najlepszy = i;
                }
            }
        }
        return najlepszy;
    }

    private static boolean jestKilofem(ItemStack s) {
        return !s.isEmpty() && s.is(ItemTags.PICKAXES);
    }

    private static int wytrzymalosc(ItemStack s) {
        if (!s.isDamageableItem()) return Integer.MAX_VALUE;
        return s.getMaxDamage() - s.getDamageValue();
    }

    private static String opis(ItemStack s) {
        if (!s.isDamageableItem()) return "niezniszczalny";
        int w = wytrzymalosc(s);
        return w + "/" + s.getMaxDamage() + " (" + Math.round(100f * w / s.getMaxDamage()) + "%)";
    }
}
