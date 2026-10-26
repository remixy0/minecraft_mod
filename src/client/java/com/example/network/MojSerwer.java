package com.example.network;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Proste zdalne sterowanie przez TCP (port 6666): klient wysyła linie "START" / "STOP".
 * <p>
 * Port jest otwierany raz i zostaje otwarty - po rozłączeniu klienta można połączyć się znowu.
 * Gdy portu nie da się otworzyć (np. zajmuje go druga uruchomiona gra), {@link #polacz()} zwraca false
 * zamiast zostawiać połączenie w stanie "null".
 */
public class MojSerwer {
    public static final int PORT = 6666;

    private ServerSocket socket;
    private Socket klient;
    private BufferedReader in;
    private PrintWriter out;
    private boolean bladPortuZgloszony = false;

    public MojSerwer() {
    }

    /**
     * Czeka (blokująco) na połączenie klienta.
     *
     * @return true, gdy klient się połączył; false, gdy nie da się otworzyć portu albo przyjąć połączenia
     */
    public boolean polacz() {
        rozlacz();
        try {
            if (socket == null || socket.isClosed()) {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 50);
                socket = s;
                bladPortuZgloszony = false;
                System.out.println("Serwer uruchomiony na porcie " + PORT + ". Czekam na połączenie...");
            }
            Socket k = socket.accept();
            in = new BufferedReader(new InputStreamReader(k.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(k.getOutputStream(), StandardCharsets.UTF_8), true);
            klient = k;
            System.out.println("Klient się połączył! (" + k.getRemoteSocketAddress() + ")");
            return true;
        } catch (IOException e) {
            // komunikat tylko raz - przy kolejnych próbach co 30 s nie zaśmiecamy logu
            if (!bladPortuZgloszony) {
                bladPortuZgloszony = true;
                System.out.println("[modid] Zdalne sterowanie TCP: nie można otworzyć portu " + PORT + " (" + e.getMessage()
                        + ") - pewnie działa już druga gra. Próbuję ponownie co 30 s.");
            }
            zamknijSerwer();
            return false;
        }
    }

    /** Czy jest połączony klient. */
    public boolean polaczony() {
        return in != null;
    }

    public void wyslij(String wiadomosc) {
        PrintWriter o = out;
        if (o != null) {
            o.println(wiadomosc);
        }
    }

    /**
     * Czeka na następną komendę od klienta.
     *
     * @return true = START, false = STOP, null = inna komenda
     * @throws IOException gdy nie ma klienta albo klient się rozłączył (wtedy trzeba znowu wywołać {@link #polacz()})
     */
    public Boolean getValue() throws IOException {
        BufferedReader r = in;
        if (r == null) {
            throw new IOException("brak połączonego klienta");
        }
        String wiadomosc = r.readLine();
        if (wiadomosc == null) {
            rozlacz();
            throw new EOFException("klient się rozłączył");
        }
        wiadomosc = wiadomosc.trim();
        System.out.println("Otrzymano komendę: " + wiadomosc);

        if ("START".equals(wiadomosc)) {
            return true;
        } else if ("STOP".equals(wiadomosc)) {
            System.out.println(">>> Zatrzymuję wszystko.");
            return false;
        }
        return null;
    }

    /** Rozłącza obecnego klienta (port zostaje otwarty dla następnego). */
    public void rozlacz() {
        Socket k = klient;
        klient = null;
        in = null;
        out = null;
        if (k != null) {
            try {
                k.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void zamknijSerwer() {
        rozlacz();
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
