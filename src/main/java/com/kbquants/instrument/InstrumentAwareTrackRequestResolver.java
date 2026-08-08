package com.kbquants.instrument;

import com.kbquants.session.TrackRequest;
import com.kbquants.session.TrackRequestResolver;
import com.kbquants.session.QuoteService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Resolves /buy arguments against the instrument master, defaulting the
 * entry price from the last traded price and the quantity from capital per
 * trade. Supports:
 * <pre>
 *   /buy NIFTY50                 -- price and quantity both defaulted
 *   /buy ACC 25                  -- explicit quantity, price defaulted
 *   /buy ACC 1850.5 25           -- both explicit
 *   /buy NSE_EQ|INE012A01025 ... -- raw instrument key, still accepted
 * </pre>
 * <p>
 * Symbols are matched longest-first, which is what stops a multi-token
 * symbol being misread: {@code /buy NIFTY 25000 CE 18 AUG 26} resolves the
 * whole option rather than treating the trailing "26" as a quantity.
 * Matching also ignores case and spaces, so the same contract can be typed
 * as {@code nifty25000ce18aug26}.
 */
public final class InstrumentAwareTrackRequestResolver implements TrackRequestResolver {

    private final InstrumentCatalog catalog;
    private final QuoteService quoteService;
    private final PositionSizer sizer;

    public InstrumentAwareTrackRequestResolver(InstrumentRegistry registry, QuoteService quoteService, PositionSizer sizer) {
        this(new InstrumentCatalog(registry), quoteService, sizer);
    }

    public InstrumentAwareTrackRequestResolver(InstrumentCatalog catalog, QuoteService quoteService, PositionSizer sizer) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.quoteService = Objects.requireNonNull(quoteService, "quoteService must not be null");
        this.sizer = Objects.requireNonNull(sizer, "sizer must not be null");
    }

    /**
     * Read per call rather than cached in a field, so an on-demand refresh
     * takes effect on the very next command.
     */
    private InstrumentRegistry registry() {
        return catalog.registry();
    }

    @Override
    public String refreshInstruments() {
        return catalog.refresh();
    }

    @Override
    public TrackRequest resolve(List<String> args) {

        if (args.isEmpty()) {
            return TrackRequest.rejected("usage: /track <symbol> [price] [qty]");
        }

        for (int symbolTokens = args.size(); symbolTokens >= 1; symbolTokens--) {

            String candidate = String.join(" ", args.subList(0, symbolTokens));
            List<String> trailing = args.subList(symbolTokens, args.size());

            if (trailing.size() > 2 || !allNumeric(trailing)) {
                continue;
            }

            Optional<Instrument> found = registry().resolve(candidate);
            if (found.isEmpty()) {
                // An ambiguous symbol is a different failure from an unknown
                // one, and only worth reporting for the full-length candidate;
                // shorter prefixes are just wrong guesses.
                if (symbolTokens == args.size() && registry().candidatesFor(candidate).size() > 1) {
                    return TrackRequest.rejected(ambiguityMessage(candidate));
                }
                continue;
            }

            return build(found.get(), trailing);
        }

        return TrackRequest.rejected("unknown instrument: " + String.join(" ", args));
    }

    private TrackRequest build(Instrument instrument, List<String> trailing) {

        // An index has no tradable position behind it -- you trade its
        // options or futures. Accepting the buy would open a paper trade
        // that could never correspond to a real one.
        if ("NSE_INDEX".equals(instrument.getSegment())) {
            return TrackRequest.rejected(instrument.getTradingSymbol()
                    + " is an index and cannot be bought — trade its option or future instead");
        }

        double price;
        String priceNote = null;

        if (trailing.size() == 2) {
            price = Double.parseDouble(trailing.get(0));
        } else {
            OptionalDouble ltp = quoteService.lastTradedPrice(instrument.getInstrumentKey());
            if (ltp.isEmpty()) {
                return TrackRequest.rejected("could not fetch last traded price for " + instrument.getTradingSymbol()
                        + "; pass the price explicitly: /track <symbol> <price> <qty>");
            }
            price = ltp.getAsDouble();
            priceNote = "price from LTP";
        }

        if (price <= 0) {
            return TrackRequest.rejected("price must be positive, got " + price);
        }

        if (!trailing.isEmpty()) {
            int quantity = Integer.parseInt(trailing.get(trailing.size() - 1));
            if (quantity <= 0) {
                return TrackRequest.rejected("quantity must be positive, got " + quantity);
            }
            return TrackRequest.accepted(instrument.getInstrumentKey(), instrument.getTradingSymbol(),
                    price, quantity, tickSizeOf(instrument), priceNote);
        }

        PositionSizer.Result sized = sizer.size(instrument, price);
        if (!sized.isAccepted()) {
            return TrackRequest.rejected(sized.getRejectionReason());
        }

        return TrackRequest.accepted(instrument.getInstrumentKey(), instrument.getTradingSymbol(),
                price, sized.getQuantity(), tickSizeOf(instrument), sizingNote(instrument, sized));
    }

    /**
     * The instrument master carries tick size in paise; everything else in
     * the system works in rupees.
     */
    private static double tickSizeOf(Instrument instrument) {
        return instrument.getTickSize() > 0 ? instrument.getTickSize() / 100.0 : 0;
    }

    private static String sizingNote(Instrument instrument, PositionSizer.Result sized) {

        int lotSize = instrument.effectiveLotSize();

        // "36 lots x 1" reads absurdly for an equity; lots only mean
        // something where the contract actually has one.
        String note = lotSize == 1
                ? String.format("qty %d, %.0f deployed", sized.getQuantity(), sized.getDeployedCapital())
                : String.format("%d lot%s x %d = %d, %.0f deployed",
                        sized.getLots(), sized.getLots() == 1 ? "" : "s",
                        lotSize, sized.getQuantity(), sized.getDeployedCapital());

        if (sized.getRiskAtHardStop() > 0) {
            note += String.format(", risking %.0f at the stop", sized.getRiskAtHardStop());
        }

        if (sized.isLimitedByRisk()) {
            note += " (size set by max risk, not capital)";
        }

        if (sized.isCappedByFreezeLimit()) {
            note += String.format(
                    " (capped at the %d-lot exchange freeze limit; only %.0f of your capital can be deployed in one order, and orders are not sliced)",
                    instrument.maxLotsPerOrder(), sized.getDeployedCapital());
        }
        return note;
    }

    private String ambiguityMessage(String candidate) {
        StringBuilder sb = new StringBuilder("'" + candidate + "' is ambiguous — pass the full instrument key:");
        for (Instrument instrument : registry().candidatesFor(candidate)) {
            sb.append("\n  ").append(instrument.getInstrumentKey())
              .append("  ").append(instrument.getName() == null ? "" : instrument.getName());
        }
        return sb.toString();
    }

    private static boolean allNumeric(List<String> tokens) {
        for (String token : tokens) {
            try {
                Double.parseDouble(token);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
