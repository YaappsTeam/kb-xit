package com.kbquants.session;

import java.util.List;

/**
 * Resolver for when there is no instrument master and no live price source
 * -- i.e. simulated market data. Everything must be spelled out:
 * {@code /buy <instrumentKey> <price> <qty>}.
 * <p>
 * The instrument key may contain spaces (indices do), so price and quantity
 * are taken as the last two tokens and the key is everything before them.
 */
public final class LiteralBuyRequestResolver implements BuyRequestResolver {

    private static final String USAGE =
            "usage: /buy <instrumentKey> <price> <qty> (symbol lookup needs MARKET_DATA=live)";

    @Override
    public BuyRequest resolve(List<String> args) {

        if (args.size() < 3) {
            return BuyRequest.rejected(USAGE);
        }

        String instrumentKey = String.join(" ", args.subList(0, args.size() - 2));
        try {
            double price = Double.parseDouble(args.get(args.size() - 2));
            int quantity = Integer.parseInt(args.get(args.size() - 1));

            if (price <= 0 || quantity <= 0) {
                return BuyRequest.rejected("price and quantity must both be positive");
            }
            return BuyRequest.accepted(instrumentKey, instrumentKey, price, quantity, null);

        } catch (NumberFormatException e) {
            return BuyRequest.rejected(USAGE);
        }
    }
}
