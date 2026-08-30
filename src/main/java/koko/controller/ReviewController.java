package koko.controller;

import java.util.Objects;
import java.util.UUID;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import koko.review.FlashcardSession;
import koko.service.ReviewOutcome;
import koko.storage.StorageException;

/**
 * Thin JavaFX controller for presenting one configured flashcard session.
 *
 * <p>The session remains responsible for action guards, persistence, progress,
 * and summaries. This controller only presents its snapshots and forwards
 * actions with the ID of the card currently displayed.
 */
public final class ReviewController {

    @FXML
    private Label progressLabel;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private VBox promptPanel;
    @FXML
    private Label promptLabel;
    @FXML
    private HBox answerPanel;
    @FXML
    private Label romajiLabel;
    @FXML
    private Label meaningLabel;
    @FXML
    private Label feedbackLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private Button revealButton;
    @FXML
    private Button correctButton;
    @FXML
    private Button incorrectButton;
    @FXML
    private Button stopButton;
    @FXML
    private Button returnButton;

    private FlashcardSession session;
    private Runnable returnToManagement;
    private UUID displayedCardId;
    private boolean actionInProgress;

    /**
     * Creates an FXML-constructed controller.
     */
    public ReviewController() {
    }

    /**
     * Configures the loaded view with one session before it is displayed.
     *
     * @param configuredSession session to present.
     * @param managementReturn action that restores management after the summary.
     * @throws NullPointerException if an argument is null.
     */
    void configure(FlashcardSession configuredSession, Runnable managementReturn) {
        session = Objects.requireNonNull(configuredSession, "Session cannot be null");
        returnToManagement = Objects.requireNonNull(managementReturn,
                "Management return action cannot be null");
        displayedCardId = null;
        actionInProgress = false;
        render();
    }

    @FXML
    private void reveal() {
        if (!canAct() || displayedCardId == null) {
            return;
        }
        UUID expectedCardId = displayedCardId;
        actionInProgress = true;
        updateControls();
        try {
            session.reveal(expectedCardId);
            clearFeedback();
        } catch (IllegalStateException exception) {
            showStaleAction(exception);
        } finally {
            actionInProgress = false;
            render();
        }
    }

    @FXML
    private void markCorrect() {
        submit(ReviewOutcome.CORRECT);
    }

    @FXML
    private void markIncorrect() {
        submit(ReviewOutcome.INCORRECT);
    }

    private void submit(ReviewOutcome outcome) {
        if (!canAct() || displayedCardId == null
                || session.state() != FlashcardSession.State.ANSWER_REVEALED) {
            return;
        }
        UUID expectedCardId = displayedCardId;
        actionInProgress = true;
        updateControls();
        try {
            session.submit(expectedCardId, outcome);
            clearFeedback();
        } catch (StorageException exception) {
            feedbackLabel.setText("Could not save this outcome. No progress was recorded. "
                    + "Retry Correct or Incorrect, or choose Stop. " + exception.getMessage());
            feedbackLabel.setVisible(true);
            feedbackLabel.setManaged(true);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            showStaleAction(exception);
        } finally {
            actionInProgress = false;
            render();
        }
    }

    @FXML
    private void stop() {
        if (!canAct() || (session.state() != FlashcardSession.State.PROMPT
                && session.state() != FlashcardSession.State.ANSWER_REVEALED)) {
            return;
        }
        actionInProgress = true;
        updateControls();
        session.stop();
        actionInProgress = false;
        clearFeedback();
        render();
    }

    @FXML
    private void returnToManagement() {
        if (session == null || actionInProgress) {
            return;
        }
        if (session.state() == FlashcardSession.State.PROMPT
                || session.state() == FlashcardSession.State.ANSWER_REVEALED) {
            session.stop();
            clearFeedback();
            render();
            return;
        }
        returnToManagement.run();
    }

    private void render() {
        if (session == null) {
            return;
        }
        FlashcardSession.Summary summary = session.summary();
        int total = summary.initialQueueSize();
        int completed = summary.attempted();
        progressLabel.setText(total == 0
                ? "No cards in this review queue."
                : "Card " + Math.min(completed + 1, total) + " of " + total
                        + " · " + summary.remaining() + " remaining");
        progressBar.setProgress(total == 0 ? 1.0 : completed / (double) total);

        if (session.state() == FlashcardSession.State.PROMPT) {
            renderPrompt();
        } else if (session.state() == FlashcardSession.State.ANSWER_REVEALED) {
            renderAnswer();
        } else {
            renderSummary(summary);
        }
        updateControls();
    }

    private void renderPrompt() {
        FlashcardSession.Prompt prompt = session.currentPrompt().orElse(null);
        if (prompt == null) {
            return;
        }
        displayedCardId = prompt.cardId();
        promptPanel.setVisible(true);
        promptPanel.setManaged(true);
        promptLabel.setText(prompt.hiragana());
        answerPanel.setVisible(false);
        answerPanel.setManaged(false);
        romajiLabel.setText("");
        meaningLabel.setText("");
        summaryLabel.setVisible(false);
        summaryLabel.setManaged(false);
    }

    private void renderAnswer() {
        FlashcardSession.Answer answer = session.currentAnswer().orElse(null);
        if (answer == null) {
            return;
        }
        displayedCardId = answer.cardId();
        romajiLabel.setText(answer.romaji());
        meaningLabel.setText(answer.englishMeaning());
        answerPanel.setVisible(true);
        answerPanel.setManaged(true);
        summaryLabel.setVisible(false);
        summaryLabel.setManaged(false);
    }

    private void renderSummary(FlashcardSession.Summary summary) {
        displayedCardId = null;
        promptPanel.setVisible(false);
        promptPanel.setManaged(false);
        answerPanel.setVisible(false);
        answerPanel.setManaged(false);
        summaryLabel.setText(summary.initialQueueSize() == 0
                ? "No due flashcards are waiting in this deck. Cards scheduled for later are not"
                        + " included."
                : "Attempted: " + summary.attempted() + "\nCorrect: " + summary.correct()
                        + "\nIncorrect: " + summary.incorrect() + "\nRemaining: "
                        + summary.remaining() + "\nStatus: "
                        + (summary.stopped() ? "stopped" : "completed"));
        summaryLabel.setVisible(true);
        summaryLabel.setManaged(true);
        progressLabel.setText("Review summary · " + summary.attempted() + " of "
                + summary.initialQueueSize() + " attempted");
    }

    private boolean canAct() {
        return session != null && !actionInProgress;
    }

    private void updateControls() {
        if (session == null) {
            return;
        }
        boolean active = session.state() == FlashcardSession.State.PROMPT
                || session.state() == FlashcardSession.State.ANSWER_REVEALED;
        boolean prompt = session.state() == FlashcardSession.State.PROMPT;
        boolean revealed = session.state() == FlashcardSession.State.ANSWER_REVEALED;
        revealButton.setDisable(!prompt || actionInProgress);
        correctButton.setDisable(!revealed || actionInProgress);
        incorrectButton.setDisable(!revealed || actionInProgress);
        stopButton.setDisable(!active || actionInProgress);
        returnButton.setDisable(active || actionInProgress);
    }

    private void clearFeedback() {
        feedbackLabel.setText("");
        feedbackLabel.setVisible(false);
        feedbackLabel.setManaged(false);
    }

    private void showStaleAction(RuntimeException exception) {
        feedbackLabel.setText("That action is no longer current. The session state was left unchanged."
                + " " + exception.getMessage());
        feedbackLabel.setVisible(true);
        feedbackLabel.setManaged(true);
    }
}
