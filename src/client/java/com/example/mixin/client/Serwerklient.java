package com.example.mixin.client;

import java.io.*;
import java.net.*;

public class Serwerklient {
    public static void main(String[] args) {
        try {
            // 1. Łączymy się z serwerem (localhost, port 6666)
            Socket socket = new Socket("localhost", 6666);

            // 2. Tworzymy strumień do wysyłania danych
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            // 3. Wysyłamy komendę (to "wrzucanie informacji")
            String komenda = "START";
            System.out.println("Wysyłam: " + komenda);

            out.println(komenda);

            // 4. Zamykamy połączenie
            socket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}