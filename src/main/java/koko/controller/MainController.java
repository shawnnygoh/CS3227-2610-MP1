package koko.controller;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Callback;
import koko.model.Deck;
import koko.model.Mode;
import koko.model.VocabularyCard;
import koko.review.FlashcardSession;
import koko.review.TypingSession;
import koko.service.KokoService;
import koko.storage.StorageException;
import koko.transfer.DeckTransfer;
import koko.transfer.DeckTransferException;
import koko.transfer.PortableDeck;

/**
 * Thin JavaFX controller for vocabulary and deck management.
 *
 * <p>All durable changes go through {@link KokoService}; this class only
 * handles selection, dialogs, feedback, and refreshing the views.
 */
public final class MainController {

    private static final String REVIEW_VIEW_RESOURCE = "/koko/view/ReviewView.fxml";
    private static final String TYPING_REVIEW_VIEW_RESOURCE = "/koko/view/TypingReviewView.fxml";
    private static final String HELP_VIEW_RESOURCE = "/koko/view/HelpView.fxml";

    private final KokoService service;
    private final String startupError;
    private final Clock clock;
    private final Callback<Class<?>, Object> controllerFactory;
    /** Groups the two review-mode choices into one selection. */
    private final ToggleGroup reviewModeGroup = new ToggleGroup();
    private FlashcardSession activeReview;
    private TypingSession activeTypingReview;
    /** The review mode selected on the management screen. */
    private Mode selectedMode = Mode.FLASHCARD;
    private Scene managementScene;

    @FXML
    private BorderPane managementRoot;
    @FXML
    private ListView<VocabularyCard> vocabularyList;
    @FXML
    private ListView<Deck> deckList;
    @FXML
    private ListView<VocabularyCard> deckCardList;
    @FXML
    private Label vocabularyEmptyState;
    @FXML
    private Label deckEmptyState;
    @FXML
    private Label selectedDeckLabel;
    @FXML
    private Label guidanceLabel;
    @FXML
    private RadioButton flashcardModeButton;
    @FXML
    private RadioButton typingModeButton;
    @FXML
    private MenuButton transferMenuButton;
    @FXML
    private MenuItem importDeckMenuItem;
    @FXML
    private MenuItem exportSelectedDeckMenuItem;
    @FXML
    private Button editCardButton;
    @FXML
    private Button deleteCardButton;
    @FXML
    private Button addCardButton;
    @FXML
    private Button reviewAllButton;
    @FXML
    private Button renameDeckButton;
    @FXML
    private Button createDeckButton;
    @FXML
    private Button deleteDeckButton;
    @FXML
    private Button addToDeckButton;
    @FXML
    private Button removeFromDeckButton;
    @FXML
    private Button reviewSelectedCardButton;
    @FXML
    private Button reviewDueButton;

    /**
     * Creates a controller with the shared application clock and FXML factory.
     *
     * @param service service used by this scene.
     * @param startupError controlled load error, or null when startup loaded normally.
     * @param clock clock used to establish review session dates.
     * @param controllerFactory factory used when loading the review view.
     * @throws NullPointerException if service, clock, or controllerFactory is null.
     *         The startupError may be null.
     */
    public MainController(KokoService service, String startupError, Clock clock,
            Callback<Class<?>, Object> controllerFactory) {
        this.service = Objects.requireNonNull(service, "Service cannot be null");
        this.startupError = startupError;
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.controllerFactory = Objects.requireNonNull(controllerFactory,
                "Controller factory cannot be null");
    }

    /**
     * Connects selection listeners and renders the initial state.
     */
    @FXML
    private void initialize() {
        flashcardModeButton.setToggleGroup(reviewModeGroup);
        typingModeButton.setToggleGroup(reviewModeGroup);
        flashcardModeButton.setSelected(true);
        reviewModeGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
            if (newToggle == flashcardModeButton) {
                selectedMode = Mode.FLASHCARD;
            } else if (newToggle == typingModeButton) {
                selectedMode = Mode.TYPING;
            }
        });
        vocabularyList.setCellFactory(cardCellFactory());
        deckList.setCellFactory(deckCellFactory());
        deckCardList.setCellFactory(cardCellFactory());

        vocabularyList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldCard, newCard) -> updateButtonStates());
        deckList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldDeck, newDeck) -> {
                    renderSelectedDeck();
                    updateButtonStates();
                });
        deckCardList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldCard, newCard) -> updateButtonStates());
        refreshViews();
        if (startupError != null) {
            setGuidance("Storage could not be loaded. The file was not replaced. " + startupError);
        }
    }

    /** Renders a vocabulary card as its three text fields on one wrapping line. */
    private static Callback<ListView<VocabularyCard>, ListCell<VocabularyCard>> cardCellFactory() {
        return list -> wrapping(new ListCell<>() {
            @Override
            protected void updateItem(VocabularyCard card, boolean empty) {
                super.updateItem(card, empty);
                setText(empty || card == null ? null
                        : card.hiragana() + "  ·  " + card.romaji()
                                + "  —  " + card.englishMeaning());
            }
        });
    }

    /** Renders a deck as its name on one wrapping line. */
    private static Callback<ListView<Deck>, ListCell<Deck>> deckCellFactory() {
        return list -> wrapping(new ListCell<>() {
            @Override
            protected void updateItem(Deck deck, boolean empty) {
                super.updateItem(deck, empty);
                setText(empty || deck == null ? null : deck.name());
            }
        });
    }

    /**
     * Lets a cell wrap instead of truncating when the window is narrow.
     *
     * @param cell cell to configure.
     * @param <T> list item type.
     * @return the same cell, configured for wrapping.
     */
    private static <T> ListCell<T> wrapping(ListCell<T> cell) {
        cell.setWrapText(true);
        cell.setMaxWidth(Double.MAX_VALUE);
        return cell;
    }

    /**
     * Shows a startup load error after the primary stage is visible.
     */
    public void showStartupError() {
        if (startupError != null) {
            showError("Koko data could not be loaded", startupError
                    + "\n\nThe invalid file was left untouched. Fix or restore it, then restart Koko.");
        }
    }

    @FXML
    private void addCard() {
        CardInput input = promptCard(null, null);
        while (input != null) {
            CardInput submitted = input;
            if (runMutation("Added " + submitted.hiragana() + " to the global vocabulary.", () ->
                    service.addVocabularyCard(submitted.hiragana(), submitted.romaji(),
                            submitted.englishMeaning()))) {
                return;
            }
            input = promptCard(null, input);
        }
    }

    @FXML
    private void reviewSelectedCard() {
        if (!interactionAllowed()) {
            return;
        }
        VocabularyCard selected = vocabularyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setGuidance("Select a global vocabulary card to review it.");
            return;
        }
        if (selectedMode == Mode.FLASHCARD) {
            startReview(() -> FlashcardSession.forCard(service, selected.id()),
                    "Reviewing the selected global card in Flashcard mode.");
        } else {
            startTypingReview(() -> TypingSession.forCard(service, selected.id()),
                    "Reviewing the selected global card in Typing mode.");
        }
    }

    @FXML
    private void reviewDueCards() {
        if (!interactionAllowed()) {
            return;
        }
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setGuidance("Select a deck to review its due cards.");
            return;
        }
        if (selectedMode == Mode.FLASHCARD) {
            startReview(() -> FlashcardSession.forDeck(service, selected.id(), clock),
                    "Reviewing due cards from “" + selected.name() + "” in Flashcard mode.");
        } else {
            startTypingReview(() -> TypingSession.forDeck(service, selected.id(), clock),
                    "Reviewing due cards from “" + selected.name() + "” in Typing mode.");
        }
    }

    @FXML
    private void reviewAllCards() {
        if (!interactionAllowed()) {
            return;
        }
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setGuidance("Select a deck to review all of its cards.");
            return;
        }
        if (selectedMode == Mode.FLASHCARD) {
            startReview(() -> FlashcardSession.forAllCardsInDeck(service, selected.id()),
                    "Reviewing all cards from “" + selected.name() + "” in Flashcard mode.");
        } else {
            startTypingReview(() -> TypingSession.forAllCardsInDeck(service, selected.id()),
                    "Reviewing all cards from “" + selected.name() + "” in Typing mode.");
        }
    }

    @FXML
    private void editCard() {
        VocabularyCard selected = vocabularyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        CardInput input = promptCard(selected, null);
        while (input != null) {
            CardInput submitted = input;
            if (runMutation("Updated " + submitted.hiragana()
                    + ". Identity and learning progress were kept.", () ->
                    service.editVocabularyCard(selected.id(), submitted.hiragana(),
                            submitted.romaji(), submitted.englishMeaning()))) {
                return;
            }
            input = promptCard(selected, input);
        }
    }

    @FXML
    private void deleteCard() {
        VocabularyCard selected = vocabularyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Delete global vocabulary?");
        confirmation.setHeaderText("Delete “" + selected.hiragana() + " — "
                + selected.englishMeaning() + "”?");
        confirmation.setContentText("This removes the card from the global vocabulary and every "
                + "deck that contains it.");
        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            setGuidance("Global deletion canceled. The card and its deck placements are unchanged.");
            return;
        }
        runMutation("Deleted " + selected.hiragana() + " globally and removed it from all decks.", () ->
                service.deleteVocabularyCard(selected.id()));
    }

    @FXML
    private void createDeck() {
        String name = promptDeckName(null);
        while (name != null) {
            String submittedName = name;
            if (runMutation("Created deck “" + submittedName.strip()
                    + "”. Select it to add cards.", () ->
                    service.createDeck(submittedName))) {
                return;
            }
            name = promptDeckName(name);
        }
    }

    @FXML
    private void renameDeck() {
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String name = promptDeckName(selected.name());
        while (name != null) {
            String submittedName = name;
            if (runMutation("Renamed deck to “" + submittedName.strip() + "”.", () ->
                    service.renameDeck(selected.id(), submittedName))) {
                return;
            }
            name = promptDeckName(name);
        }
    }

    @FXML
    private void deleteDeck() {
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Delete deck?");
        confirmation.setHeaderText("Delete “" + selected.name() + "”?");
        confirmation.setContentText("This removes the deck and its card list. Global "
                + "vocabulary cards remain available in the library and other decks.");
        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            setGuidance("Deck deletion canceled. The deck and its cards are unchanged.");
            return;
        }
        runMutation("Deleted deck “" + selected.name()
                + "”. Its global vocabulary cards were kept.", () ->
                service.deleteDeck(selected.id()));
    }

    @FXML
    private void addCardToDeck() {
        Deck selectedDeck = deckList.getSelectionModel().getSelectedItem();
        if (selectedDeck == null) {
            return;
        }
        var alreadyInDeck = selectedDeck.cardIds();
        var available = service.data().vocabularyCards().stream()
                .filter(card -> !alreadyInDeck.contains(card.id())).toList();
        if (available.isEmpty()) {
            showError("No cards available", "Every global vocabulary card is already in this deck.");
            return;
        }
        VocabularyCard choice = promptCardChoice(available, selectedDeck.name());
        if (choice == null) {
            return;
        }
        runMutation("Added " + choice.hiragana() + " to “" + selectedDeck.name() + "”.", () ->
                service.addCardToDeck(selectedDeck.id(), choice.id()));
    }

    @FXML
    private void removeCardFromDeck() {
        Deck selectedDeck = deckList.getSelectionModel().getSelectedItem();
        VocabularyCard selectedCard = deckCardList.getSelectionModel().getSelectedItem();
        if (selectedDeck == null || selectedCard == null) {
            return;
        }
        runMutation("Removed " + selectedCard.hiragana() + " from “" + selectedDeck.name()
                + "”. It remains in global vocabulary.", () ->
                service.removeCardFromDeck(selectedDeck.id(), selectedCard.id()));
    }

    /**
     * Imports one portable deck from a user-selected UTF-8 JSON file.
     *
     * <p>The service owns parsing, validation, conflict detection, persistence,
     * and publication. This handler only coordinates the chooser, feedback,
     * and refreshing the newly published service data.
     */
    @FXML
    private void importDeck() {
        if (!interactionAllowed()) {
            return;
        }
        Path source = chooseImportSource();
        if (source == null) {
            return;
        }
        PortableDeck document;
        try {
            document = service.prepareImport(source);
        } catch (DeckTransferException exception) {
            String header = isInvalidImportData(exception)
                    ? "Deck import rejected" : "Deck import could not be read";
            showTransferError(header, exception.getMessage());
            return;
        }

        Deck imported = promptImportName(source, document);
        if (imported == null) {
            return;
        }
        UUID importedDeckId = imported.id();
        refreshViews();
        restoreSelection(deckList, importedDeckId);
        deckList.scrollTo(deckList.getSelectionModel().getSelectedIndex());
        setGuidance("Imported and selected the deck. Existing vocabulary kept its progress; "
                + "new cards are due today.");
    }

    /**
     * Exports the currently selected deck to a portable UTF-8 JSON file.
     *
     * <p>The selected deck UUID is passed to the service, which resolves current
     * deck and card data. A non-null result from the native save chooser carries
     * its replacement confirmation; canceling the chooser carries no consent.
     */
    @FXML
    private void exportSelectedDeck() {
        if (!interactionAllowed()) {
            return;
        }
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        UUID selectedDeckId = selected.id();
        try {
            DeckTransfer.ConfirmedDestination destination = TransferFileNames.chooseExportDestination(
                    selected.name(), this::chooseExportDestination);
            if (destination == null) {
                return;
            }
            service.exportDeck(selectedDeckId, destination);
            setGuidance("Exported “" + selected.name() + "” to “" + destination.path()
                    + "” as UTF-8 JSON.");
        } catch (IllegalArgumentException exception) {
            showTransferError("Deck export destination is invalid", exception.getMessage());
        } catch (DeckTransferException exception) {
            showTransferError("Deck export was not completed", exception.getMessage()
                    + "\n\nThis recoverable failure did not change Koko's data. "
                    + "Choose the destination again after correcting the issue.");
        }
    }

    @FXML
    private void showHelp() {
        Parent content;
        try {
            content = FXMLLoader.load(requireResource(HELP_VIEW_RESOURCE));
        } catch (IOException | RuntimeException exception) {
            showError("Help could not open", exception.getMessage());
            return;
        }
        Dialog<Void> help = new Dialog<>();
        help.initOwner(managementRoot.getScene().getWindow());
        help.setTitle("Koko help");
        help.setHeaderText("How Koko works");
        help.setResizable(true);
        help.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        help.getDialogPane().getStylesheets().addAll(managementRoot.getScene().getStylesheets());
        help.getDialogPane().getStyleClass().add("help-dialog");
        help.getDialogPane().setContent(content);
        help.showAndWait();
        setGuidance("Tip: choose a review mode for how to answer, then choose which cards to review.");
    }

    private Path chooseImportSource() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import deck");
        chooser.getExtensionFilters().add(jsonFileFilter());
        File source = chooser.showOpenDialog(applicationWindow());
        return source == null ? null : source.toPath();
    }

    /**
     * Shows the native save chooser, optionally revisiting the final JSON filename.
     *
     * @param suggestion initial filename or full destination after suffix correction.
     * @return selected path, or null when canceled or closed.
     */
    private Path chooseExportDestination(Path suggestion) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export selected deck");
        chooser.setInitialFileName(suggestion.getFileName().toString());
        if (suggestion.getParent() != null) {
            chooser.setInitialDirectory(suggestion.getParent().toFile());
        }
        chooser.getExtensionFilters().add(jsonFileFilter());
        File destination = chooser.showSaveDialog(applicationWindow());
        return destination == null ? null : destination.toPath();
    }

    /**
     * Shows the owned confirmation dialog used to choose the imported deck name.
     *
     * <p>The prepared document is captured by the dialog handler, so retries
     * never reread a source file. Recoverable name and save failures update the
     * dialog's error label and keep the entered name available.
     *
     * @param source source path shown to the user.
     * @param document validated immutable document to apply.
     * @return the newly published deck, or null when the dialog is canceled or closed.
     */
    private Deck promptImportName(Path source, PortableDeck document) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(applicationWindow());
        dialog.setTitle("Import deck");
        dialog.setHeaderText("Confirm the imported deck name");
        ButtonType importButton = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, ButtonType.CANCEL);

        TextField nameField = new TextField(document.deckName());
        Label sourceLabel = new Label("Source file: " + source.getFileName());
        Label explanation = new Label("The filename and the deck name are separate. "
                + "Changing this name will not modify the source file.");
        explanation.setWrapText(true);
        Label errorLabel = new Label();
        errorLabel.setWrapText(true);
        errorLabel.getStyleClass().add("error-text");
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.setPadding(new Insets(12));
        form.add(sourceLabel, 0, 0, 2, 1);
        form.add(explanation, 0, 1, 2, 1);
        form.add(new Label("Deck name"), 0, 2);
        form.add(nameField, 1, 2);
        form.add(errorLabel, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(form);

        AtomicReference<Deck> imported = new AtomicReference<>();
        dialog.getDialogPane().lookupButton(importButton).addEventFilter(ActionEvent.ACTION,
                event -> {
                    try {
                        imported.set(service.importDeck(document, nameField.getText()));
                        dialog.close();
                    } catch (DeckTransferException exception) {
                        event.consume();
                        errorLabel.setText("The portable document is invalid: "
                                + exception.getMessage());
                    } catch (IllegalArgumentException exception) {
                        event.consume();
                        errorLabel.setText("Deck name is invalid or already in use: "
                                + exception.getMessage() + " Correct it and try again.");
                    } catch (StorageException exception) {
                        event.consume();
                        errorLabel.setText("The import could not be saved: " + exception.getMessage()
                                + " No changes were published; correct the issue and retry.");
                    }
                });
        dialog.showAndWait();
        return imported.get();
    }

    /**
     * Finds a required view resource on the application classpath.
     *
     * <p>A missing resource means the application was packaged incorrectly rather
     * than that the user did something wrong, so this reports an unchecked failure
     * that each caller turns into its own dialog.
     *
     * @param resourcePath absolute classpath path to the resource.
     * @return the resource URL.
     * @throws IllegalStateException if the resource is not on the classpath.
     */
    private static URL requireResource(String resourcePath) {
        URL resource = MainController.class.getResource(resourcePath);
        if (resource == null) {
            throw new IllegalStateException("Required resource not found on classpath: "
                    + resourcePath);
        }
        return resource;
    }

    private static FileChooser.ExtensionFilter jsonFileFilter() {
        return new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
    }

    private Window applicationWindow() {
        return managementRoot.getScene().getWindow();
    }

    private void refreshViews() {
        UUID selectedCardId = selectedId(vocabularyList);
        UUID selectedDeckId = selectedId(deckList);
        UUID selectedDeckCardId = selectedId(deckCardList);
        vocabularyList.setItems(FXCollections.observableArrayList(service.data().vocabularyCards()));
        deckList.setItems(FXCollections.observableArrayList(service.data().decks()));
        restoreSelection(vocabularyList, selectedCardId);
        restoreSelection(deckList, selectedDeckId);
        vocabularyEmptyState.setVisible(service.data().vocabularyCards().isEmpty());
        vocabularyEmptyState.setManaged(vocabularyEmptyState.isVisible());
        boolean hasDecks = !service.data().decks().isEmpty();
        deckList.setVisible(hasDecks);
        deckList.setManaged(hasDecks);
        deckEmptyState.setVisible(!hasDecks);
        deckEmptyState.setManaged(deckEmptyState.isVisible());
        renderSelectedDeck();
        restoreSelection(deckCardList, selectedDeckCardId);
        updateButtonStates();
    }

    private void renderSelectedDeck() {
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        selectedDeckLabel.setText(selected == null ? "Select a deck to see its cards"
                : selected.name() + " · " + selected.cardIds().size() + " card(s)");
        if (selected == null) {
            deckCardList.setItems(FXCollections.observableArrayList());
            deckCardList.setPlaceholder(new Label("Select a deck to see its cards."));
            return;
        }
        var cards = selected.cardIds().stream()
                .map(id -> service.data().findVocabularyCard(id).orElse(null))
                .filter(Objects::nonNull).toList();
        deckCardList.setItems(FXCollections.observableArrayList(cards));
        deckCardList.setPlaceholder(new Label("This deck is empty. Add an existing card above."));
    }

    private void updateButtonStates() {
        boolean hasCard = vocabularyList.getSelectionModel().getSelectedItem() != null;
        boolean hasDeck = deckList.getSelectionModel().getSelectedItem() != null;
        boolean hasDeckCard = deckCardList.getSelectionModel().getSelectedItem() != null;
        boolean storageReady = startupError == null;
        boolean reviewActive = reviewActive();
        flashcardModeButton.setDisable(!storageReady || reviewActive);
        typingModeButton.setDisable(!storageReady || reviewActive);
        transferMenuButton.setDisable(!storageReady || reviewActive);
        importDeckMenuItem.setDisable(!storageReady || reviewActive);
        exportSelectedDeckMenuItem.setDisable(!storageReady || !hasDeck || reviewActive);
        addCardButton.setDisable(!storageReady || reviewActive);
        editCardButton.setDisable(!storageReady || !hasCard || reviewActive);
        deleteCardButton.setDisable(!storageReady || !hasCard || reviewActive);
        createDeckButton.setDisable(!storageReady || reviewActive);
        reviewAllButton.setDisable(!storageReady || !hasDeck || reviewActive);
        renameDeckButton.setDisable(!storageReady || !hasDeck || reviewActive);
        deleteDeckButton.setDisable(!storageReady || !hasDeck || reviewActive);
        addToDeckButton.setDisable(!storageReady || !hasDeck
                || service.data().vocabularyCards().isEmpty() || reviewActive);
        removeFromDeckButton.setDisable(!storageReady || !hasDeckCard || reviewActive);
        reviewSelectedCardButton.setDisable(!storageReady || !hasCard || reviewActive);
        reviewDueButton.setDisable(!storageReady || !hasDeck || reviewActive);
    }

    /**
     * Distinguishes invalid document contents from failures reading the source file.
     *
     * <p>The transfer reader wraps format failures and retains parser and encoding
     * causes, so feedback can distinguish these errors without parsing the file again.
     *
     * @param exception contextual failure from the transfer reader.
     * @return true when the retained cause identifies invalid document contents.
     */
    private static boolean isInvalidImportData(DeckTransferException exception) {
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof DeckTransferException
                    || cause instanceof JsonProcessingException
                    || cause instanceof CharacterCodingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private CardInput promptCard(VocabularyCard existing, CardInput initialValues) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "Add vocabulary card" : "Edit vocabulary card");
        dialog.setHeaderText(existing == null ? "Add a globally reusable Japanese word"
                : "Edit the card text; learning progress will be preserved");
        ButtonType save = new ButtonType(existing == null ? "Add card" : "Save changes",
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField hiragana = new TextField(initialValues == null
                ? existing == null ? "" : existing.hiragana() : initialValues.hiragana());
        TextField romaji = new TextField(initialValues == null
                ? existing == null ? "" : existing.romaji() : initialValues.romaji());
        TextField meaning = new TextField(initialValues == null
                ? existing == null ? "" : existing.englishMeaning() : initialValues.englishMeaning());
        hiragana.setPromptText("ねこ");
        romaji.setPromptText("neko");
        meaning.setPromptText("cat");
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.setPadding(new Insets(12));
        form.addRow(0, new Label("Hiragana"), hiragana);
        form.addRow(1, new Label("Romaji"), romaji);
        form.addRow(2, new Label("English meaning"), meaning);
        dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button);

        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == save
                ? new CardInput(hiragana.getText(), romaji.getText(), meaning.getText()) : null;
    }

    private String promptDeckName(String initialName) {
        TextInputDialog dialog = initialName == null
                ? new TextInputDialog() : new TextInputDialog(initialName);
        dialog.setTitle(initialName == null ? "Create deck" : "Rename deck");
        dialog.setHeaderText(initialName == null ? "Name a new study deck"
                : "Rename “" + initialName + "”");
        dialog.setContentText(initialName == null ? "Deck name:" : "New deck name:");
        dialog.getEditor().setPromptText("e.g. Travel basics");
        return dialog.showAndWait().orElse(null);
    }

    private VocabularyCard promptCardChoice(List<VocabularyCard> cards,
            String deckName) {
        List<String> labels = cards.stream()
                .map(card -> card.hiragana() + "  ·  " + card.romaji()
                        + "  —  " + card.englishMeaning()).toList();
        ChoiceDialog<String> dialog = new ChoiceDialog<>(labels.get(0), labels);
        dialog.setTitle("Add card to deck");
        dialog.setHeaderText("Choose an existing global card for “" + deckName + "”");
        dialog.setContentText("Vocabulary card:");
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? dialog.getSelectedItem() : null);
        Optional<String> result = dialog.showAndWait();
        return result.map(label -> cards.get(labels.indexOf(label))).orElse(null);
    }

    private boolean runMutation(String successMessage, Mutation mutation) {
        if (reviewActive()) {
            return false;
        }
        try {
            mutation.run();
            refreshViews();
            setGuidance(successMessage);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            showError("Action not completed", exception.getMessage()
                    + "\n\nCheck the values and selection, then try again.");
            return false;
        } catch (StorageException exception) {
            refreshViews();
            showError("Could not save Koko data", exception.getMessage()
                    + "\n\nYour change was not reported as successful. Check storage permissions or disk space.");
            return false;
        }
    }

    private void setGuidance(String message) {
        guidanceLabel.setText(message);
    }

    /**
     * Reports whether the management screen may start a new action.
     *
     * @return true when storage loaded and no review is in progress.
     */
    private boolean interactionAllowed() {
        return startupError == null && !reviewActive();
    }

    private void startReview(SessionFactory sessionFactory, String guidance) {
        try {
            FlashcardSession session = sessionFactory.create();
            loadReviewView(session, guidance);
        } catch (IllegalArgumentException | NullPointerException exception) {
            showError("Review could not start", exception.getMessage()
                    + "\n\nCheck the selection and try again.");
        } catch (IOException | RuntimeException exception) {
            showError("Review view could not open", exception.getMessage());
        }
    }

    private void loadReviewView(FlashcardSession session, String guidance) throws IOException {
        FXMLLoader loader = new FXMLLoader(requireResource(REVIEW_VIEW_RESOURCE));
        loader.setControllerFactory(controllerFactory);
        Parent reviewRoot = loader.load();
        ReviewController reviewController = loader.getController();
        managementScene = managementRoot.getScene();
        activeReview = session;
        try {
            reviewController.configure(session, this::returnToManagement);
            managementScene.setRoot(reviewRoot);
        } catch (RuntimeException exception) {
            activeReview = null;
            managementScene = null;
            throw exception;
        }
        setGuidance(guidance);
    }

    private void startTypingReview(TypingSessionFactory sessionFactory, String guidance) {
        try {
            TypingSession session = sessionFactory.create();
            loadTypingReviewView(session, guidance);
        } catch (IllegalArgumentException | NullPointerException exception) {
            showError("Typing review could not start", exception.getMessage()
                    + "\n\nCheck the selection and try again.");
        } catch (IOException | RuntimeException exception) {
            showError("Typing review view could not open", exception.getMessage());
        }
    }

    private void loadTypingReviewView(TypingSession session, String guidance) throws IOException {
        FXMLLoader loader = new FXMLLoader(requireResource(TYPING_REVIEW_VIEW_RESOURCE));
        loader.setControllerFactory(controllerFactory);
        Parent reviewRoot = loader.load();
        TypingReviewController reviewController = loader.getController();
        managementScene = managementRoot.getScene();
        activeTypingReview = session;
        try {
            reviewController.configure(session, this::returnToManagement);
            managementScene.setRoot(reviewRoot);
        } catch (RuntimeException exception) {
            activeTypingReview = null;
            managementScene = null;
            throw exception;
        }
        setGuidance(guidance);
    }

    private void returnToManagement() {
        if (!reviewActive() || managementScene == null) {
            return;
        }
        if (activeReview != null) {
            if (activeReview.state() == FlashcardSession.State.PROMPT
                    || activeReview.state() == FlashcardSession.State.ANSWER_REVEALED) {
                activeReview.stop();
                return;
            }
            FlashcardSession.Summary summary = activeReview.summary();
            activeReview = null;
            managementScene.setRoot(managementRoot);
            refreshViews();
            setGuidance("Returned to Home: " + summary.attempted()
                    + " attempted, " + summary.correct() + " correct, "
                    + summary.incorrect() + " incorrect, " + summary.remaining() + " remaining.");
        } else {
            if (activeTypingReview.state() == TypingSession.State.PROMPT
                    || activeTypingReview.state() == TypingSession.State.FEEDBACK) {
                activeTypingReview.stop(activeTypingReview.currentCardId().orElseThrow());
                return;
            }
            TypingSession.Summary summary = activeTypingReview.summary();
            activeTypingReview = null;
            managementScene.setRoot(managementRoot);
            refreshViews();
            setGuidance("Returned to Home: " + summary.attempted()
                    + " attempted, " + summary.correct() + " correct, "
                    + summary.incorrect() + " incorrect, " + summary.skipped()
                    + " skipped, " + summary.remaining() + " remaining.");
        }
        managementScene = null;
    }

    private boolean reviewActive() {
        return activeReview != null || activeTypingReview != null;
    }

    private void showError(String header, String message) {
        setGuidance(message.replace('\n', ' '));
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Koko");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Shows full failure details in an owned dialog while keeping the Sensei area compact.
     *
     * @param header short description of the failed transfer.
     * @param message detailed failure information, which may include long paths or JSON excerpts.
     */
    private void showTransferError(String header, String message) {
        setGuidance(header + ". Check the file or destination and try again.");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(applicationWindow());
        alert.setTitle("Koko transfer");
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static UUID selectedId(ListView<?> list) {
        Object selected = list.getSelectionModel().getSelectedItem();
        if (selected instanceof VocabularyCard card) {
            return card.id();
        }
        if (selected instanceof Deck deck) {
            return deck.id();
        }
        return null;
    }

    private static <T> void restoreSelection(ListView<T> list, UUID id) {
        if (id == null) {
            return;
        }
        for (int index = 0; index < list.getItems().size(); index++) {
            Object item = list.getItems().get(index);
            UUID itemId = item instanceof VocabularyCard card ? card.id()
                    : item instanceof Deck deck ? deck.id() : null;
            if (id.equals(itemId)) {
                list.getSelectionModel().select(index);
                return;
            }
        }
    }

    @FunctionalInterface
    private interface Mutation {
        void run() throws StorageException;
    }

    @FunctionalInterface
    private interface SessionFactory {
        FlashcardSession create();
    }

    @FunctionalInterface
    private interface TypingSessionFactory {
        TypingSession create();
    }

    private record CardInput(String hiragana, String romaji, String englishMeaning) {
    }
}
