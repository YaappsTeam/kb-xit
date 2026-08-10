package com.kbquants.session;

import lombok.extern.slf4j.Slf4j;

/**
 * Places nothing. The exit is recorded and reported; no broker is touched.
 * <p>
 * Says so explicitly rather than quietly succeeding, because the one
 * misunderstanding worth preventing is believing a position was sold when
 * it was not.
 */
@Slf4j
public final class PaperExitOrderPlacer implements ExitOrderPlacer {

    @Override
    public Result placeExit(TradeFillEvent fill, double price, ExitReason reason) {
        log.info("Paper exit ({}): {} x{} at {} -- no broker order placed",
                reason, fill.getInstrumentKey(), fill.getFilledQuantity(), price);
        return Result.simulated("paper — no broker order placed");
    }
}
