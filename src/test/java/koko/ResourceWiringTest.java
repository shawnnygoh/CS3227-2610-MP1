package koko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javafx.fxml.FXML;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import koko.controller.MainController;

/**
 * Verifies that views and stylesheets needed by the application are packaged.
 *
 * <p>This test checks classpath resources and FXML/controller declarations without
 * starting JavaFX. It does not test rendering or user interaction.
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

    @Test
    void transferMenuActionsUseTheExpectedControllerMembersAndHandlers() throws Exception {
        Document document = parseResource("/koko/view/MainWindow.fxml");
        assertEquals(MainController.class.getName(), document.getDocumentElement()
                .getAttributeNS("http://javafx.com/fxml/1", "controller"));

        Element transferMenu = elementWithFxId(document, "transferMenuButton");
        assertNotNull(transferMenu);
        assertEquals("MenuButton", transferMenu.getTagName());
        assertInjectedField("transferMenuButton", MenuButton.class);
        assertAction(document, "importDeckMenuItem", "#importDeck");
        assertAction(document, "exportSelectedDeckMenuItem", "#exportSelectedDeck");
    }

    /**
     * Checks that a menu item's FXML action resolves to an annotated controller handler.
     *
     * @param document parsed main view.
     * @param fxId menu item identifier.
     * @param action expected FXML action reference.
     * @throws NoSuchFieldException if the controller field is missing.
     * @throws NoSuchMethodException if the controller handler is missing.
     */
    private static void assertAction(Document document, String fxId, String action)
            throws NoSuchFieldException, NoSuchMethodException {
        Element element = elementWithFxId(document, fxId);
        assertNotNull(element);
        assertEquals("MenuItem", element.getTagName());
        assertEquals(action, element.getAttribute("onAction"));
        assertInjectedField(fxId, MenuItem.class);
        Method handler = MainController.class.getDeclaredMethod(action.substring(1));
        assertTrue(handler.isAnnotationPresent(FXML.class));
        assertEquals(void.class, handler.getReturnType());
    }

    private static void assertInjectedField(String fxId, Class<?> expectedType) throws NoSuchFieldException {
        Field field = MainController.class.getDeclaredField(fxId);
        assertEquals(expectedType, field.getType());
        assertTrue(field.isAnnotationPresent(FXML.class));
    }

    private static Element elementWithFxId(Document document, String fxId) {
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (fxId.equals(element.getAttributeNS("http://javafx.com/fxml/1", "id"))) {
                return element;
            }
        }
        return null;
    }

    private static Document parseResource(String path)
            throws IOException, ParserConfigurationException, SAXException {
        try (InputStream input = KokoApplication.class.getResourceAsStream(path)) {
            assertNotNull(input);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Object resource(String path) {
        return KokoApplication.class.getResource(path);
    }
}
