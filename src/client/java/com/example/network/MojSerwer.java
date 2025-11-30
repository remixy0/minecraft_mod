package com.example.network;

import org.apache.logging.log4j.core.jmx.Server;

import java.net.InetAddress;
import java.io.*;
import java.net.*;

public class MojSerwer{
    Socket socket2;
    ServerSocket socket;
    BufferedReader in;
    PrintWriter out;
    public MojSerwer(){
    }
    public void polacz(){
        try {
            socket = new ServerSocket(6666, 50, InetAddress.getByName("0.0.0.0"));
            System.out.println("Serwer uruchomiony. Czekam na połączenie...");
            Socket clientSocket = socket.accept();
            System.out.println("Klient się połączył!");

            in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream())
            );

            out = new PrintWriter(clientSocket.getOutputStream(), true);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void wyslij(String wiadomosc) throws IOException {
        out.println(wiadomosc);
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
