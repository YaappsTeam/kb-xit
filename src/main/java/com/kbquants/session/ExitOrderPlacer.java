package com.kbquants.session;

import lombok.Getter;

/**
 * Places the sell order that actually closes a position.
 * <p>
 * Until now the engine marked a trade closed and told the user, without
 * anything being sold -- correct for paper trading, but it left no place
 * for a real order to go. This is that place.
 * <p>
 * The distinction that matters is between <em>deciding</em> to exit and
 * <em>having</em> exited. They are the same thing on paper and very
 * different with real money, which is why {@link Result} can say the order
 * failed: a trade whose sell order was rejected is still open, and
 * treating it as closed would be a lie that stops the engine protecting a
 * live position.
 */
public interface ExitOrderPlacer {

    Result placeExit(TradeFillEvent fill, double price, ExitReason reason);

    @Getter
    final class Result {

        private final boolean successful;
        private final boolean simulated;
        private final String brokerOrderId;
        private final String detail;

        private Result(boolean successful, boolean simulated, String brokerOrderId, String detail) {
            this.successful = successful;
            this.simulated = simulated;
            this.brokerOrderId = brokerOrderId;
            this.detail = detail;
        }

        /** A real order the broker accepted. */
        public static Result placed(String brokerOrderId) {
            return new Result(true, false, brokerOrderId, "broker order " + brokerOrderId);
        }

        /** No order was placed, and none was meant to be. */
        public static Result simulated(String detail) {
            return new Result(true, true, null, detail);
        }

        /**
         * The order was meant to be placed and was not. The caller must
         * keep managing the position: it is still open.
         */
        public static Result failed(String detail) {
            return new Result(false, false, null, detail);
        }
    }
}
