package koko.controller;

import java.util.Objects;
import java.util.UUID;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import koko.review.TypingSession;
import koko.service.ReviewOutcome;
import koko.storage.StorageException;

/**
 * Thin JavaFX controller for presenting one English-to-Hiragana typing session.
 *
 * <p>The session owns action guards, answer evaluation, persistence, progress, and
 * summaries. This controller only presents snapshots and forwards ID-bound actions.
 */
public final class TypingReviewController {

    @FXML
    private Label progressLabel;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private ScrollPane contentScrollPane;
    @FXML
    private VBox promptPanel;
    @FXML
    private Label meaningLabel;
    @FXML
    private TextField answerField;
    @FXML
    private VBox feedbackPanel;
    @FXML
    private Label enteredAnswerLabel;
    @FXML
    private Label expectedAnswerLabel;
    @FXML
    private Label feedbackLabel;
    @FXML
    private Label errorLabel;
    @FXML
    private Label summaryLabel;
    @FXML
    private Button submitButton;
    @FXML
    private Button skipButton;
    @FXML
    private Button nextButton;
    @FXML
    private Button stopButton;
    @FXML
    private Button returnButton;

    private TypingSession session;
    private Runnable returnToManagement;
    private UUID displayedCardId;
    private boolean actionInProgress;

    /**
     * Creates an FXML-constructed controller.
     */
    public TypingReviewController() {
    }

    /**
     * Configures the loaded view with one session before it is displayed.
     *
     * @param configuredSession session to present.
     * @param managementReturn action that restores management after the summary.
     * @throws NullPointerException if an argument is null.
     */
    void configure(TypingSession configuredSession, Runnable managementReturn) {
        session = Objects.requireNonNull(configuredSession, "Session cannot be null");
        returnToManagement = Objects.requireNonNull(managementReturn,
                "Management return action cannot be null");
        displayedCardId = null;
        actionInProgress = false;
        render();
    }

    @FXML
    private void submit() {
        if (!canAct() || displayedCardId == null
                || session.state() != TypingSession.State.PROMPT) {
            return;
        }
        UUID expectedCardId = displayedCardId;
        String enteredAnswer = answerField.getText();
        actionInProgress = true;
        updateControls();
        try {
            session.submit(expectedCardId, enteredAnswer);
            clearActionError();
            render();
        } catch (StorageException exception) {
            render();
            showActionError("Could not save this answer. No progress was recorded. "
                    + "Retry Submit or choose Skip/Stop. " + exception.getMessage());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            showStaleAction(exception);
        } finally {
            actionInProgress = false;
            updateControls();
        }
    }

    @FXML
    private void skip() {
        if (!canAct() || displayedCardId == null
                || session.state() != TypingSession.State.PROMPT) {
            return;
        }
        UUID expectedCardId = displayedCardId;
        actionInProgress = true;
        updateControls();
        try {
            session.skip(expectedCardId);
            clearActionError();
            render();
        } catch (StorageException exception) {
            render();
            showActionError("Could not save Skip. No progress was recorded. "
                    + "Retry Skip or choose Submit/Stop. " + exception.getMessage());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            showStaleAction(exception);
        } finally {
            actionInProgress = false;
            updateControls();
        }
    }

    @FXML
    private void next() {
        if (!canAct() || displayedCardId == null
                || session.state() != TypingSession.State.FEEDBACK) {
            return;
        }
        UUID expectedCardId = displayedCardId;
        actionInProgress = true;
        updateControls();
        try {
            session.next(expectedCardId);
            clearActionError();
            render();
        } catch (IllegalStateException | IllegalArgumentException exception) {
            showStaleAction(exception);
        } finally {
            actionInProgress = false;
            updateControls();
        }
    }

    @FXML
    private void stop() {
        if (!canAct() || displayedCardId == null
                || (session.state() != TypingSession.State.PROMPT
                && session.state() != TypingSession.State.FEEDBACK)) {
            return;
        }
        UUID expectedCardId = displayedCardId;
        actionInProgress = true;
        updateControls();
        try {
            session.stop(expectedCardId);
            clearActionError();
            render();
        } catch (IllegalStateException | IllegalArgumentException exception) {
            showStaleAction(exception);
        } finally {
            actionInProgress = false;
            updateControls();
        }
    }

    @FXML
    private void returnToManagement() {
        if (session == null || actionInProgress) {
            return;
        }
        if (session.state() == TypingSession.State.PROMPT
                || session.state() == TypingSession.State.FEEDBACK) {
            session.stop(session.currentCardId().orElseThrow());
            render();
            return;
        }
        returnToManagement.run();
    }

    private void render() {
        if (session == null) {
            return;
        }
        TypingSession.Summary summary = session.summary();
        int total = summary.initialQueueSize();
        int cardNumber = summary.attempted()
                + (session.state() == TypingSession.State.PROMPT ? 1 : 0);
        progressLabel.setText(total == 0
                ? "No cards in this review queue."
                : "Card " + cardNumber + " of " + total
                        + " · " + summary.remaining() + " remaining");
        progressBar.setProgress(total == 0 ? 1.0 : summary.attempted() / (double) total);

        if (session.state() == TypingSession.State.PROMPT) {
            renderPrompt();
        } else if (session.state() == TypingSession.State.FEEDBACK) {
            renderFeedback();
        } else {
            renderSummary(summary);
        }
        updateControls();
    }

    private void renderPrompt() {
        TypingSession.Prompt prompt = session.currentPrompt().orElse(null);
        if (prompt == null) {
            return;
        }
        // Keep the answer when rendering the same prompt after a failed save.
        if (!prompt.cardId().equals(displayedCardId)) {
            answerField.clear();
        }
        displayedCardId = prompt.cardId();
        contentScrollPane.setHvalue(0);
        contentScrollPane.setVvalue(0);
        meaningLabel.setText(prompt.englishMeaning());
        promptPanel.setVisible(true);
        promptPanel.setManaged(true);
        feedbackPanel.setVisible(false);
        feedbackPanel.setManaged(false);
        summaryLabel.setVisible(false);
        summaryLabel.setManaged(false);
        requestAnswerFocus(prompt.cardId());
    }

    /**
     * Focuses a prompt after its root is attached and the current action enables controls.
     *
     * <p>The deferred request ignores a prompt that has since been answered or stopped.
     *
     * @param cardId card whose prompt requested focus.
     */
    private void requestAnswerFocus(UUID cardId) {
        Platform.runLater(() -> {
            if (canAct() && session.state() == TypingSession.State.PROMPT
                    && cardId.equals(displayedCardId) && answerField.getScene() != null
                    && !answerField.isDisabled()) {
                answerField.requestFocus();
            }
        });
    }

    private void renderFeedback() {
        TypingSession.Feedback feedback = session.currentFeedback().orElse(null);
        if (feedback == null) {
            return;
        }
        displayedCardId = feedback.cardId();
        enteredAnswerLabel.setText(feedback.enteredAnswer().isEmpty()
                ? "(blank)" : feedback.enteredAnswer());
        expectedAnswerLabel.setText(feedback.expectedHiragana());
        feedbackLabel.setText(feedback.outcome() == ReviewOutcome.CORRECT
                ? "Correct" : feedback.outcome() == ReviewOutcome.SKIPPED
                ? "Skipped" : "Incorrect");
        promptPanel.setVisible(false);
        promptPanel.setManaged(false);
        feedbackPanel.setVisible(true);
        feedbackPanel.setManaged(true);
        answerField.setText(feedback.enteredAnswer());
        summaryLabel.setVisible(false);
        summaryLabel.setManaged(false);
    }

    private void renderSummary(TypingSession.Summary summary) {
        displayedCardId = null;
        promptPanel.setVisible(false);
        promptPanel.setManaged(false);
        feedbackPanel.setVisible(false);
        feedbackPanel.setManaged(false);
        summaryLabel.setText(summary.initialQueueSize() == 0
                ? "No cards in this review queue."
                : "Attempted: " + summary.attempted() + "\nCorrect: " + summary.correct()
                        + "\nIncorrect: " + summary.incorrect() + "\nSkipped: "
                        + summary.skipped() + "\nRemaining: " + summary.remaining()
                        + "\nStatus: " + (summary.stopped() ? "stopped" : "completed"));
        summaryLabel.setVisible(true);
        summaryLabel.setManaged(true);
        progressLabel.setText("Typing review summary · " + summary.attempted() + " of "
                + summary.initialQueueSize() + " attempted");
    }

    private boolean canAct() {
        return session != null && !actionInProgress;
    }

    private void updateControls() {
        if (session == null) {
            return;
        }
        boolean prompt = session.state() == TypingSession.State.PROMPT;
        boolean feedback = session.state() == TypingSession.State.FEEDBACK;
        boolean active = prompt || feedback;
        answerField.setDisable(!prompt || actionInProgress);
        submitButton.setDisable(!prompt || actionInProgress);
        skipButton.setDisable(!prompt || actionInProgress);
        nextButton.setDisable(!feedback || actionInProgress);
        stopButton.setDisable(!active || actionInProgress);
        returnButton.setDisable(active || actionInProgress);
    }

    private void clearActionError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void showActionError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void showStaleAction(RuntimeException exception) {
        showActionError("That action is no longer current. The session state was left unchanged. "
                + exception.getMessage());
    }
}
