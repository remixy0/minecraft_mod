package com.example.mixin.client;

import java.io.*;
import java.net.*;

public class MojSerwer{
    public static void main(String[] args) {
        try {
            // 1. Otwieramy port serwera (np. 6666)
            ServerSocket serverSocket = new ServerSocket(6666);
            System.out.println("Serwer uruchomiony. Czekam na połączenie...");

            // 2. Czekamy na klienta (to blokuje program do momentu połączenia)
            Socket clientSocket = serverSocket.accept();
            System.out.println("Klient się połączył!");

            // 3. Tworzymy strumień do odbierania danych (czytanie tekstu)
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream())
            );

            // 4. Odczytujemy wiadomość od klienta
            String wiadomosc = in.readLine();
            System.out.println("Otrzymano komendę: " + wiadomosc);

            // --- TU ZACZYNA SIĘ STEROWANIE ---
            if ("START".equals(wiadomosc)) {
                System.out.println(">>> Uruchamiam procedurę startową...");
                // tu wywołujesz swoją metodę np. uruchomSilnik();
            } else if ("STOP".equals(wiadomosc)) {
                System.out.println(">>> Zatrzymuję wszystko.");
            } else {
                System.out.println("Nieznana komenda.");
            }
            // ---------------------------------

            // 5. Zamykamy wszystko
            serverSocket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
