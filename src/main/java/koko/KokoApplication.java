package koko;

import java.io.IOException;
import java.net.URL;
import java.time.Clock;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import koko.controller.MainController;
import koko.service.KokoService;
import koko.storage.JsonStorage;
import koko.storage.StorageException;

/**
 * JavaFX application shell for Koko's vocabulary library.
 */
public class KokoApplication extends Application {

    private static final String MAIN_WINDOW_RESOURCE = "/koko/view/MainWindow.fxml";
    private static final String STYLESHEET_RESOURCE = "/koko/css/koko.css";
    private static final double INITIAL_WIDTH = 900;
    private static final double INITIAL_HEIGHT = 620;
    private static final double MINIMUM_WIDTH = 760;
    private static final double MINIMUM_HEIGHT = 560;

    /**
     * Loads the root view and stylesheet, then displays the primary stage.
     *
     * @param stage primary stage supplied by JavaFX
     */
    @Override
    public void start(Stage stage) {
        KokoService service = new KokoService(new JsonStorage(), Clock.systemDefaultZone());
        String startupError = loadService(service);
        FXMLLoader loader = createLoader(service, startupError);
        Parent root = loadRootView(loader);
        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(requireResource(STYLESHEET_RESOURCE).toExternalForm());

        stage.setTitle("Koko");
        stage.setMinWidth(MINIMUM_WIDTH);
        stage.setMinHeight(MINIMUM_HEIGHT);
        stage.setScene(scene);
        stage.show();
        loader.<MainController>getController().showStartupError();
    }

    /**
     * Loads the main FXML view from the application classpath.
     *
     * @return the root node defined by the FXML view
     * @throws IllegalStateException if the view is missing or cannot be parsed
     */
    private Parent loadRootView(FXMLLoader loader) {
        try {
            return loader.load();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load FXML resource: " + MAIN_WINDOW_RESOURCE,
                    exception);
        }
    }

    private FXMLLoader createLoader(KokoService service, String startupError) {
        FXMLLoader loader = new FXMLLoader(requireResource(MAIN_WINDOW_RESOURCE));
        loader.setControllerFactory(type -> {
            if (type == MainController.class) {
                return new MainController(service, startupError);
            }
            throw new IllegalStateException("Unexpected FXML controller: " + type.getName());
        });
        return loader;
    }

    private String loadService(KokoService service) {
        try {
            service.load();
            return null;
        } catch (StorageException exception) {
            return exception.getMessage();
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
