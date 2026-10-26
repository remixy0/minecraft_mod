package com.example.sekwencje;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu serwera (np. wybór trybu w lobby): prawy klik przedmiotem z paska, potem lewy klik w przycisk w otwartym oknie.
 * Wszystko w wątku gry.
 */
public final class MenuSerwera {
    private MenuSerwera() {
    }

    /** Słowa, po których rozpoznajemy przycisk wejścia do gry (bez polskich znaków, małymi literami). */
    private static final String[] SLOWA_GRAJ = {"graj", "zagraj", "play", "dolacz", "join", "wejdz", "start"};

    /** Czy jest otwarte menu serwera (skrzynia / własne okno), a nie zwykły ekwipunek. */
    public static boolean otwarte(Minecraft mc) {
        LocalPlayer p = mc.player;
        return p != null && p.containerMenu != null && p.containerMenu != p.inventoryMenu;
    }

    /** Sloty menu (bez ekwipunku gracza na dole okna). */
    public static List<Slot> sloty(Minecraft mc) {
        List<Slot> w = new ArrayList<>();
        if (!otwarte(mc)) return w;
        for (Slot s : mc.player.containerMenu.slots) {
            if (s.container != mc.player.getInventory()) w.add(s);
        }
        return w;
    }

    /** Nazwa + opis (lore) przedmiotu, bez kolorów. */
    public static String opis(ItemStack s) {
        StringBuilder b = new StringBuilder(s.getHoverName().getString());
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore != null) {
            for (Component c : lore.lines()) b.append(" ").append(c.getString());
        }
        return b.toString().replaceAll("§.", "").strip();
    }

    /** Slot z przyciskiem: "#13" = numer slotu, inaczej fragment nazwy/opisu przedmiotu. */
    public static Slot znajdz(Minecraft mc, String co) {
        List<Slot> sloty = sloty(mc);
        String c = co.strip();
        if (c.startsWith("#")) {
            try {
                int nr = Integer.parseInt(c.substring(1).strip());
                for (Slot s : sloty) if (s.index == nr) return s;
            } catch (NumberFormatException ignored) {
            }
            return null;
        }
        String szukane = proste(c);
        for (Slot s : sloty) {
            if (s.hasItem() && proste(opis(s.getItem())).contains(szukane)) return s;
        }
        return null;
    }

    /** Przycisk "Graj!": przedmiot z nazwą/opisem graj/play/dołącz..., a gdy w menu jest tylko jeden przedmiot - ten. */
    public static Slot przyciskGraj(Minecraft mc) {
        List<Slot> zPrzedmiotem = new ArrayList<>();
        for (Slot s : sloty(mc)) {
            if (!s.hasItem()) continue;
            zPrzedmiotem.add(s);
            String t = proste(opis(s.getItem()));
            for (String slowo : SLOWA_GRAJ) {
                if (t.contains(slowo)) return s;
            }
        }
        return zPrzedmiotem.size() == 1 ? zPrzedmiotem.get(0) : null;
    }

    /** Lewy klik w slot menu. */
    public static void kliknij(Minecraft mc, Slot s) {
        AbstractContainerMenu m = mc.player.containerMenu;
        mc.gameMode.handleContainerInput(m.containerId, s.index, 0, ContainerInput.PICKUP, mc.player);
    }

    /** Opis zawartości menu, np. "#13 Graj! | #15 Sklep" - żeby wiedzieć, co wpisać w `kliknij`. */
    public static String lista(Minecraft mc) {
        if (!otwarte(mc)) return "nie ma otwartego menu";
        List<String> w = new ArrayList<>();
        for (Slot s : sloty(mc)) {
            if (s.hasItem()) w.add("#" + s.index + " " + opis(s.getItem()));
        }
        return w.isEmpty() ? "menu jest otwarte, ale puste" : String.join(" | ", w);
    }

    /**
     * Prawy klik przedmiotem. slot 1-9 = najpierw weź ten slot z paska; 0 = przedmiot, który jest w ręce.
     * Zwraca null, gdy się udało, albo opis błędu.
     */
    public static String uzyj(Minecraft mc, int slot, boolean tylkoMenu) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return "nie jestem na serwerze";
        if (slot >= 1 && slot <= 9) {
            p.getInventory().setSelectedSlot(slot - 1);
        }
        ItemStack s = p.getMainHandItem();
        if (s.isEmpty()) return "w ręce nic nie ma (podaj slot, np. `graj 5`)";
        if (tylkoMenu && slot == 0 && (s.is(Items.FISHING_ROD) || s.is(ItemTags.PICKAXES))) {
            // zabezpieczenie: w lobby nie trzyma się wędki ani kilofa - nie zarzucamy przypadkiem wędki
            return "w ręce jest " + s.getHoverName().getString() + " - to chyba nie lobby (podaj slot przedmiotu, np. `graj 5`)";
        }
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND, s.getInteractAnimation(), false);
        return null;
    }

    /** Małe litery, bez polskich znaków i kolorów - do porównywania nazw. */
    static String proste(String t) {
        String s = Normalizer.normalize(t.replaceAll("§.", "").replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return s.toLowerCase(Locale.ROOT);
    }
}
