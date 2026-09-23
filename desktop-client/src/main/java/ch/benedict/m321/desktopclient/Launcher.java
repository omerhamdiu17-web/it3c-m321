package ch.benedict.m321.desktopclient;

import javafx.application.Application;

/**
 * Startpunkt des Desktop-Clients.
 *
 * Warum eine eigene Klasse und nicht main() direkt in der
 * JavaFX-Application: startet man eine Klasse, die von Application erbt,
 * verlangt Java, dass JavaFX als Modul geladen ist, und bricht sonst mit
 * "JavaFX runtime components are missing" ab. Über diesen Umweg reicht
 * es, dass JavaFX auf dem Klassenpfad liegt.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(DesktopClientApplication.class, args);
    }
}
