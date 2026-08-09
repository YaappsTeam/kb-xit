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
        private final boolean uncertain;
        private final String brokerOrderId;
        private final String detail;

        private Result(boolean successful, boolean simulated, boolean uncertain,
                       String brokerOrderId, String detail) {
            this.successful = successful;
            this.simulated = simulated;
            this.uncertain = uncertain;
            this.brokerOrderId = brokerOrderId;
            this.detail = detail;
        }

        /**
         * The order may or may not have reached the exchange -- a timeout,
         * a dropped connection, a response that never arrived.
         * <p>
         * Distinct from {@link #failed} because the safe responses are
         * opposites. A rejection can be retried; an unknown outcome must
         * not be, since the first order may have filled and a second would
         * sell a position that no longer exists, opening a short.
         */
        public static Result uncertain(String detail) {
            return new Result(false, false, true, null, detail);
        }

        /** A real order the broker accepted. */
        public static Result placed(String brokerOrderId) {
            return new Result(true, false, false, brokerOrderId, "broker order " + brokerOrderId);
        }

        /** No order was placed, and none was meant to be. */
        public static Result simulated(String detail) {
            return new Result(true, true, false, null, detail);
        }

        /**
         * The order was meant to be placed and was not. The caller must
         * keep managing the position: it is still open.
         */
        public static Result failed(String detail) {
            return new Result(false, false, false, null, detail);
        }
    }
}
