package com.kbquants.notification;

/**
 * A destination for outbound alert messages. Telegram is the only
 * implementation today; this seam exists because the MVP notification
 * step is expected to later be replaced/augmented by real order placement
 * (limit/GTT orders) for the same profit milestones.
 */
public interface Notifier {

    void send(String message);

    /**
     * Sends a prompt together with a set of choices the user can pick from
     * directly, each identified by an opaque callback token.
     * <p>
     * The default degrades to plain text listing the choices, so a Notifier
     * with no concept of interactive controls still conveys the options --
     * only the tapping is lost.
     *
     * @param choices label to callback-token, in presentation order
     */
    default void sendChoices(String prompt, java.util.LinkedHashMap<String, String> choices) {
        StringBuilder sb = new StringBuilder(prompt);
        choices.keySet().forEach(label -> sb.append("\n  ").append(label));
        send(sb.toString());
    }
}
