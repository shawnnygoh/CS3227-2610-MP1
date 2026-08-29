package koko;

import java.io.IOException;
import java.net.URL;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX application shell for Koko's vocabulary library.
 */
public class KokoApplication extends Application {

    private static final String MAIN_WINDOW_RESOURCE = "/koko/view/MainWindow.fxml";
    private static final String STYLESHEET_RESOURCE = "/koko/css/koko.css";
    private static final double INITIAL_WIDTH = 720;
    private static final double INITIAL_HEIGHT = 480;
    private static final double MINIMUM_WIDTH = 420;
    private static final double MINIMUM_HEIGHT = 280;

    /**
     * Loads the root view and stylesheet, then displays the primary stage.
     *
     * @param stage primary stage supplied by JavaFX
     */
    @Override
    public void start(Stage stage) {
        Parent root = loadRootView();
        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(requireResource(STYLESHEET_RESOURCE).toExternalForm());

        stage.setTitle("Koko");
        stage.setMinWidth(MINIMUM_WIDTH);
        stage.setMinHeight(MINIMUM_HEIGHT);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Loads the main FXML view from the application classpath.
     *
     * @return the root node defined by the FXML view
     * @throws IllegalStateException if the view is missing or cannot be parsed
     */
    private Parent loadRootView() {
        try {
            FXMLLoader loader = new FXMLLoader(requireResource(MAIN_WINDOW_RESOURCE));
            return loader.load();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load FXML resource: " + MAIN_WINDOW_RESOURCE,
                    exception);
        }
    }

    /**
     * Finds a required resource on the application classpath.
     *
     * @param resourcePath absolute classpath path to the resource
     * @return the resource URL
     * @throws IllegalStateException if the resource is not on the classpath
     */
    private URL requireResource(String resourcePath) {
        URL resource = KokoApplication.class.getResource(resourcePath);
        if (resource == null) {
            throw new IllegalStateException("Required resource not found on classpath: " + resourcePath);
        }
        return resource;
    }
}
