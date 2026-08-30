package koko.controller;

import java.io.IOException;
import java.net.URL;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.util.Callback;
import koko.model.Deck;
import koko.model.VocabularyCard;
import koko.review.FlashcardSession;
import koko.service.KokoService;
import koko.storage.StorageException;

/**
 * Thin JavaFX controller for vocabulary and deck management.
 *
 * <p>All durable changes go through {@link KokoService}; this class only
 * handles selection, dialogs, feedback, and refreshing the views.
 */
public final class MainController {

    private static final String REVIEW_VIEW_RESOURCE = "/koko/view/ReviewView.fxml";

    private final KokoService service;
    private final String startupError;
    private final Clock clock;
    private final Callback<Class<?>, Object> controllerFactory;
    private FlashcardSession activeReview;
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
    private Button editCardButton;
    @FXML
    private Button deleteCardButton;
    @FXML
    private Button addCardButton;
    @FXML
    private Button openDeckButton;
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
     * Creates a controller for the already-created application service.
     *
     * @param service service used by this scene.
     * @param startupError controlled load error, or null when startup loaded normally.
     */
    public MainController(KokoService service, String startupError) {
        this(service, startupError, Clock.systemDefaultZone(), type -> {
            if (type == ReviewController.class) {
                return new ReviewController();
            }
            throw new IllegalStateException("Unexpected FXML controller: " + type.getName());
        });
    }

    /**
     * Creates a controller with the shared application clock and FXML factory.
     *
     * @param service service used by this scene.
     * @param startupError controlled load error, or null when startup loaded normally.
     * @param clock clock used to establish review session dates.
     * @param controllerFactory factory used when loading the review view.
     * @throws NullPointerException if an argument is null.
     */
    public MainController(KokoService service, String startupError, Clock clock,
            Callback<Class<?>, Object> controllerFactory) {
        this.service = java.util.Objects.requireNonNull(service, "Service cannot be null");
        this.startupError = startupError;
        this.clock = java.util.Objects.requireNonNull(clock, "Clock cannot be null");
        this.controllerFactory = java.util.Objects.requireNonNull(controllerFactory,
                "Controller factory cannot be null");
    }

    /**
     * Connects selection listeners and renders the initial state.
     */
    @FXML
    private void initialize() {
        vocabularyList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(VocabularyCard card, boolean empty) {
                super.updateItem(card, empty);
                setText(empty || card == null ? null
                        : card.hiragana() + "  ·  " + card.romaji()
                                + "  —  " + card.englishMeaning());
            }
        });
        deckList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Deck deck, boolean empty) {
                super.updateItem(deck, empty);
                setText(empty || deck == null ? null : deck.name());
            }
        });
        deckCardList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(VocabularyCard card, boolean empty) {
                super.updateItem(card, empty);
                setText(empty || card == null ? null
                        : card.hiragana() + "  ·  " + card.romaji()
                                + "  —  " + card.englishMeaning());
            }
        });

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
        if (!reviewCanStart()) {
            return;
        }
        VocabularyCard selected = vocabularyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setGuidance("Select a global vocabulary card to review it.");
            return;
        }
        startReview(() -> FlashcardSession.forCard(service, selected.id(), clock),
                "Reviewing the selected global card.");
    }

    @FXML
    private void reviewDueCards() {
        if (!reviewCanStart()) {
            return;
        }
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            setGuidance("Select a deck to review its due flashcards.");
            return;
        }
        startReview(() -> FlashcardSession.forDeck(service, selected.id(), clock),
                "Reviewing due flashcards from “" + selected.name() + "”.");
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
    private void openDeck() {
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            setGuidance("Opened “" + selected.name() + "”. Add global cards using Add card.");
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
        ChoiceDialogResult choice = promptCardChoice(available, selectedDeck.name());
        if (choice == null) {
            return;
        }
        runMutation("Added " + choice.card().hiragana() + " to “" + selectedDeck.name() + "”.", () ->
                service.addCardToDeck(selectedDeck.id(), choice.card().id()));
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

    @FXML
    private void showHelp() {
        Alert help = new Alert(Alert.AlertType.INFORMATION);
        help.setTitle("Koko help");
        help.setHeaderText("How Koko works");
        help.setContentText("On the Home screen, create, edit, or delete global vocabulary cards "
                + "in the left panel.\n\n"
                + "Decks are reusable collections of those cards. Select a deck, open it, and "
                + "add existing cards. Removing a card from a deck does not delete it from the "
                + "global vocabulary.\n\n"
                + "You can rename or delete a deck. Deleting a deck does not delete its cards "
                + "from the global vocabulary or other decks.\n\n"
                + "Deleting a global card removes it from every deck, and always asks for confirmation.\n\n"
                + "Review due flashcards from a selected deck, or review one selected global card "
                + "even when it is not due or assigned to a deck. Reveal the romaji and English "
                + "meaning before choosing Correct or Incorrect. Stop keeps unanswered cards "
                + "unanswered, and Koko saves each recorded outcome.");
        help.showAndWait();
        setGuidance("Tip: build a global vocabulary first, then reuse cards across multiple decks.");
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
            deckCardList.setPlaceholder(new Label("Open a deck to see its cards."));
            return;
        }
        var cards = selected.cardIds().stream()
                .map(id -> service.data().findVocabularyCard(id).orElse(null))
                .filter(card -> card != null).toList();
        deckCardList.setItems(FXCollections.observableArrayList(cards));
        deckCardList.setPlaceholder(new Label("This deck is empty. Add an existing card above."));
    }

    private void updateButtonStates() {
        boolean hasCard = vocabularyList.getSelectionModel().getSelectedItem() != null;
        boolean hasDeck = deckList.getSelectionModel().getSelectedItem() != null;
        boolean hasDeckCard = deckCardList.getSelectionModel().getSelectedItem() != null;
        boolean storageReady = startupError == null;
        boolean reviewActive = activeReview != null;
        addCardButton.setDisable(!storageReady || reviewActive);
        editCardButton.setDisable(!storageReady || !hasCard || reviewActive);
        deleteCardButton.setDisable(!storageReady || !hasCard || reviewActive);
        createDeckButton.setDisable(!storageReady || reviewActive);
        openDeckButton.setDisable(!hasDeck);
        renameDeckButton.setDisable(!storageReady || !hasDeck || reviewActive);
        deleteDeckButton.setDisable(!storageReady || !hasDeck || reviewActive);
        addToDeckButton.setDisable(!storageReady || !hasDeck
                || service.data().vocabularyCards().isEmpty() || reviewActive);
        removeFromDeckButton.setDisable(!storageReady || !hasDeckCard || reviewActive);
        reviewSelectedCardButton.setDisable(!storageReady || !hasCard || reviewActive);
        reviewDueButton.setDisable(!storageReady || !hasDeck || reviewActive);
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

    private ChoiceDialogResult promptCardChoice(List<VocabularyCard> cards,
            String deckName) {
        List<String> labels = cards.stream()
                .map(card -> card.hiragana() + "  ·  " + card.romaji()
                        + "  —  " + card.englishMeaning()).toList();
        javafx.scene.control.ChoiceDialog<String> dialog =
                new javafx.scene.control.ChoiceDialog<>(labels.get(0), labels);
        dialog.setTitle("Add card to deck");
        dialog.setHeaderText("Choose an existing global card for “" + deckName + "”");
        dialog.setContentText("Vocabulary card:");
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? dialog.getSelectedItem() : null);
        Optional<String> result = dialog.showAndWait();
        return result.map(label -> new ChoiceDialogResult(cards.get(labels.indexOf(label))))
                .orElse(null);
    }

    private boolean runMutation(String successMessage, Mutation mutation) {
        if (activeReview != null) {
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

    private boolean reviewCanStart() {
        return startupError == null && activeReview == null;
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
        URL resource = MainController.class.getResource(REVIEW_VIEW_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Required resource not found on classpath: "
                    + REVIEW_VIEW_RESOURCE);
        }
        FXMLLoader loader = new FXMLLoader(resource);
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

    private void returnToManagement() {
        if (activeReview == null || managementScene == null) {
            return;
        }
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
        managementScene = null;
    }

    private void showError(String header, String message) {
        setGuidance(message.replace('\n', ' '));
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Koko");
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

    private record CardInput(String hiragana, String romaji, String englishMeaning) {
    }

    private record ChoiceDialogResult(VocabularyCard card) {
    }
}
