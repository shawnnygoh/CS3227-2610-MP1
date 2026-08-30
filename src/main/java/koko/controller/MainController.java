package koko.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
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
import javafx.scene.layout.GridPane;
import koko.model.Deck;
import koko.model.VocabularyCard;
import koko.service.KokoService;
import koko.storage.StorageException;

/**
 * Thin JavaFX controller for vocabulary and deck management.
 *
 * <p>All durable changes go through {@link KokoService}; this class only
 * handles selection, dialogs, feedback, and refreshing the views.
 */
public final class MainController {

    private final KokoService service;
    private final String startupError;

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

    /**
     * Creates a controller for the already-created application service.
     *
     * @param service service used by this scene.
     * @param startupError controlled load error, or null when startup loaded normally.
     */
    public MainController(KokoService service, String startupError) {
        this.service = service;
        this.startupError = startupError;
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
        help.setHeaderText("Vocabulary and deck management");
        help.setContentText("Vocabulary is global: create, edit, or delete cards in the left panel.\n\n"
                + "Decks are reusable collections of those cards. Select a deck, open it, and "
                + "add existing cards. Removing a card from a deck does not delete it from the "
                + "global vocabulary.\n\n"
                + "You can rename or delete a deck. Deleting a deck does not delete its cards "
                + "from the global vocabulary or other decks.\n\n"
                + "Deleting a global card removes it from every deck, and always asks for confirmation.\n\n"
                + "Koko saves after each successful change.");
        help.showAndWait();
        setGuidance("Tip: build a global vocabulary first, then reuse cards across multiple decks.");
    }

    private void refreshViews() {
        UUID selectedCardId = selectedId(vocabularyList);
        UUID selectedDeckId = selectedId(deckList);
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
        addCardButton.setDisable(!storageReady);
        editCardButton.setDisable(!storageReady || !hasCard);
        deleteCardButton.setDisable(!storageReady || !hasCard);
        createDeckButton.setDisable(!storageReady);
        openDeckButton.setDisable(!hasDeck);
        renameDeckButton.setDisable(!storageReady || !hasDeck);
        deleteDeckButton.setDisable(!storageReady || !hasDeck);
        addToDeckButton.setDisable(!storageReady || !hasDeck
                || service.data().vocabularyCards().isEmpty());
        removeFromDeckButton.setDisable(!storageReady || !hasDeckCard);
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

    private record CardInput(String hiragana, String romaji, String englishMeaning) {
    }

    private record ChoiceDialogResult(VocabularyCard card) {
    }
}
