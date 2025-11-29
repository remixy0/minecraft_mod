package com.example.network;

import java.net.InetAddress;
import java.io.*;
import java.net.*;

public class MojSerwer{
    BufferedReader in;
    public MojSerwer(){
    }
    public void polacz(){
        try {
            ServerSocket serverSocket = new ServerSocket(6666, 50, InetAddress.getByName("0.0.0.0"));
            System.out.println("Serwer uruchomiony. Czekam na połączenie...");
            Socket clientSocket = serverSocket.accept();
            System.out.println("Klient się połączył!");

            in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream())
            );

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Boolean getValue() throws IOException {
        String wiadomosc = in.readLine();
        if(wiadomosc != null) {
            System.out.println("Otrzymano komendę: " + wiadomosc);
        }

        if ("START".equals(wiadomosc)) {
            return  true;

        } else if ("STOP".equals(wiadomosc)) {
            System.out.println(">>> Zatrzymuję wszystko.");
            return false;
        }

        return null;
    }

}
