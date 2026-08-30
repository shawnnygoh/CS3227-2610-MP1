package koko;

import javafx.application.Application;

/**
 * Plain entry point that launches the JavaFX application.
 */
public class Launcher {

    /**
     * Starts the Koko JavaFX application.
     *
     * @param args command-line arguments passed to JavaFX.
     */
    public static void main(String[] args) {
        Application.launch(KokoApplication.class, args);
    }
}
