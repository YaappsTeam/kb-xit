package com.kbquants.notification;

/**
 * A destination for outbound alert messages. Telegram is the only
 * implementation today; this seam exists because the MVP notification
 * step is expected to later be replaced/augmented by real order placement
 * (limit/GTT orders) for the same profit milestones.
 */
public interface Notifier {
    void send(String message);
}
