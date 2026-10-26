package com.example.sekwencje;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.ArrayList;
import java.util.List;

/** Wchodzenie na serwer / wychodzenie z serwera (wszystko w wątku gry). */
public final class Serwery {
    private Serwery() {
    }

    private static List<String[]> lista = List.of();
    private static long listaCzas = 0;

    /** Serwery z listy "Gra wieloosobowa": {nazwa, adres}. Odczyt z dysku najwyżej co 30 s. */
    public static List<String[]> lista(Minecraft mc) {
        long teraz = System.currentTimeMillis();
        if (teraz - listaCzas > 30_000) {
            listaCzas = teraz;
            try {
                ServerList l = new ServerList(mc);
                l.load();
                List<String[]> nowa = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) {
                    ServerData d = l.get(i);
                    if (d != null && d.ip != null && !d.ip.isBlank()) {
                        nowa.add(new String[]{d.name == null || d.name.isBlank() ? d.ip : d.name, d.ip});
                    }
                }
                lista = nowa;
            } catch (Exception e) {
                System.err.println("[modid] Nie udało się wczytać listy serwerów: " + e);
            }
        }
        return lista;
    }

    /** Adres serwera: z listy (po nazwie albo adresie) albo wpisany ręcznie. */
    public static String adres(Minecraft mc, String nazwaLubAdres) {
        String c = nazwaLubAdres.trim();
        for (String[] s : lista(mc)) {
            if (s[0].equalsIgnoreCase(c) || s[1].equalsIgnoreCase(c)) return s[1];
        }
        return c;
    }

    public static boolean wGrze(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.getConnection() != null;
    }

    /** Adres serwera, na którym jesteśmy (null w menu / w świecie jednoosobowym). */
    public static String obecny(Minecraft mc) {
        ServerData d = mc.getCurrentServer();
        return wGrze(mc) && d != null ? d.ip : null;
    }

    public static boolean naSerwerze(Minecraft mc, String adres) {
        String o = obecny(mc);
        return o != null && bezPortu(o).equalsIgnoreCase(bezPortu(adres));
    }

    /** Zaczyna łączenie (trzeba być w menu - najpierw wyjdź z obecnego serwera). */
    public static String polacz(Minecraft mc, String nazwaLubAdres) {
        String adres = adres(mc, nazwaLubAdres);
        if (adres.isEmpty() || !ServerAddress.isValidAddress(adres)) {
            return "❌ zły adres serwera: " + adres;
        }
        ServerData dane = null;
        try {
            ServerList l = new ServerList(mc);
            l.load();
            for (int i = 0; i < l.size(); i++) {
                if (adres.equalsIgnoreCase(l.get(i).ip)) {
                    dane = l.get(i);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (dane == null) {
            dane = new ServerData(adres, adres, ServerData.Type.OTHER);
            // paczka zasobów od serwera bez pytania - inaczej okienko zatrzymałoby bota
            dane.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
        }
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(adres), dane, false, null);
        return "🖥 łączę z " + adres;
    }

    public static String wyjdz(Minecraft mc) {
        if (mc.level == null) return "ℹ️ nie jestem na serwerze";
        String o = obecny(mc);
        mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        return "⏏ wyszedłem z serwera" + (o != null ? " " + o : "");
    }

    private static String bezPortu(String a) {
        String s = a.trim().toLowerCase();
        return s.endsWith(":25565") ? s.substring(0, s.length() - 6) : s;
    }
}
