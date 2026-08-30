package koko;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Verifies that views and stylesheets needed by the application are packaged.
 *
 * <p>This test checks classpath wiring only; it does not test JavaFX rendering
 * or user interaction.
 */
class ResourceWiringTest {

    @Test
    void applicationResourcesArePackagedAtStablePaths() {
        assertNotNull(resource("/koko/view/MainWindow.fxml"));
        assertNotNull(resource("/koko/view/ReviewView.fxml"));
        assertNotNull(resource("/koko/view/TypingReviewView.fxml"));
        assertNotNull(resource("/koko/view/HelpView.fxml"));
        assertNotNull(resource("/koko/css/koko.css"));
    }

    private static Object resource(String path) {
        return KokoApplication.class.getResource(path);
    }
}
