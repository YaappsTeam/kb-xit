package com.kbquants.notification;

/**
 * Callback for commands received via the Telegram bot's two-way control
 * plane. entries are user invocations (not decisions the app makes) --
 * see PRODUCT_REQUIREMENTS.md section 2.
 */
public interface TelegramCommandListener {
    void onBuy(String instrumentKey, double price, int quantity);
    void onExit(String orderId);
    void onExitAll();
    void onStatusRequested();
}
