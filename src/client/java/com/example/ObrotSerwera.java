package com.example;

/**
 * Flaga ustawiana przez mixin, gdy serwer zmienił obrót gracza
 * (teleport, /tp z obrotem, komenda "look at" itp.) - żeby odróżnić to od ruchu myszką.
 */
public final class ObrotSerwera {
    private static volatile boolean flaga = false;

    private ObrotSerwera() {
    }

    public static void zglos() {
        flaga = true;
    }

    /** Zwraca, czy serwer obrócił gracza od ostatniego sprawdzenia, i czyści flagę. */
    public static boolean pobierz() {
        boolean f = flaga;
        flaga = false;
        return f;
    }
}
