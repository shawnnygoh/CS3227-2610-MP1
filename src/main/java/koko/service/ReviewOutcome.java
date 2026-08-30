package koko.service;

/**
 * The possible outcomes of reviewing one learning-mode prompt.
 */
public enum ReviewOutcome {
    /** The learner answered the prompt correctly. */
    CORRECT,

    /** The learner answered the prompt incorrectly. */
    INCORRECT,

    /** The learner skipped the prompt without answering it. */
    SKIPPED
}
