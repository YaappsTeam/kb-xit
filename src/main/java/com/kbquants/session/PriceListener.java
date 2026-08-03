package com.kbquants.session;

public interface PriceListener {
    void onPrice(double price, long timestamp);
}
