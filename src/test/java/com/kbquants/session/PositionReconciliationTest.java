package com.kbquants.session;

import com.kbquants.session.PositionReconciliation.Kind;
import com.kbquants.session.PositionReconciliation.Report;
import com.kbquants.session.PositionReconciliation.TrackedPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this system believes against what the broker holds.
 * <p>
 * The comparison is kept pure so these cases can be stated directly. Each
 * one is a way a stale belief turns into a wrong order: selling quantity
 * that is no longer there, and selling past flat, which is a short.
 */
class PositionReconciliationTest {

    private static TrackedPosition tracked(String orderId, String instrument, int quantity) {
        return new TrackedPosition(orderId, instrument, instrument, quantity, true);
    }

    private static BrokerPosition broker(String instrument, int quantity) {
        return new BrokerPosition(instrument, quantity, 100.0, instrument, "I");
    }

    @Test
    void matchingQuantitiesShouldReconcileCleanly() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65)), List.of(broker("NSE_FO|1", 65)));

        assertTrue(report.isClean());
        assertEquals(1, report.getConfirmed().size());
    }

    /** Closed by hand, or while this was down. The engine is watching a ghost. */
    @Test
    void aPositionTheBrokerDoesNotHoldShouldBeFlaggedGone() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65)), List.of());

        assertEquals(1, report.getDiscrepancies().size());
        assertEquals(Kind.GONE, report.getDiscrepancies().get(0).getKind());
        assertTrue(report.getConfirmed().isEmpty());
    }

    /**
     * A zero-quantity row means closed today, not held. Reading it as a
     * position would confirm a trade that no longer exists.
     */
    @Test
    void aClosedOutRowShouldCountAsGone() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65)), List.of(broker("NSE_FO|1", 0)));

        assertEquals(Kind.GONE, report.getDiscrepancies().get(0).getKind());
    }

    /** Half sold by hand: an exit for the full quantity would go short. */
    @Test
    void aPartiallySoldPositionShouldBeFlaggedReduced() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 130)), List.of(broker("NSE_FO|1", 65)));

        PositionReconciliation.Discrepancy discrepancy = report.getDiscrepancies().get(0);
        assertEquals(Kind.REDUCED, discrepancy.getKind());
        assertEquals(65, discrepancy.getBrokerQuantity());
        assertEquals(130, discrepancy.getTrackedQuantity());
    }

    /**
     * The broker reports positions, not orders, so two trades on one strike
     * are a single position to it. The tracked total is what must be
     * compared, or each half would look like a shortfall against itself.
     */
    @Test
    void twoTradesOnOneInstrumentShouldBeComparedInAggregate() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65), tracked("o2", "NSE_FO|1", 65)),
                List.of(broker("NSE_FO|1", 130)));

        assertTrue(report.isClean());
        assertEquals(2, report.getConfirmed().size());
    }

    /** A shortfall cannot be attributed to one of them, so both stand down. */
    @Test
    void aShortfallShouldImplicateEveryTradeOnThatInstrument() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65), tracked("o2", "NSE_FO|1", 65)),
                List.of(broker("NSE_FO|1", 65)));

        assertEquals(2, report.getDiscrepancies().size());
        assertTrue(report.getDiscrepancies().stream().allMatch(d -> d.getKind() == Kind.REDUCED));
    }

    @Test
    void aPositionNobodyIsWatchingShouldBeReportedAsUntracked() {

        Report report = PositionReconciliation.compare(List.of(), List.of(broker("NSE_EQ|ACC", 10)));

        assertEquals(1, report.getUntracked().size());
        assertEquals(10, report.getUntracked().get(0).getQuantity());
        assertFalse(report.isClean());
    }

    /**
     * Only the surplus. Offering the whole position would ask the user to
     * adopt quantity this system is already managing.
     */
    @Test
    void onlyTheUnmanagedSurplusShouldBeOffered() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65)), List.of(broker("NSE_FO|1", 195)));

        assertEquals(1, report.getConfirmed().size());
        assertEquals(130, report.getUntracked().get(0).getQuantity());
    }

    /**
     * This system only ever sells what was bought elsewhere, so a short is
     * not a position it could be managing -- and must never be offered as
     * one, since adopting it would arrange to sell more of it.
     */
    @Test
    void shortsShouldBeIgnoredEntirely() {

        Report report = PositionReconciliation.compare(List.of(), List.of(broker("NSE_FO|1", -65)));

        assertTrue(report.getUntracked().isEmpty());
    }

    @Test
    void aShortShouldNotConfirmALongOnTheSameInstrument() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NSE_FO|1", 65)), List.of(broker("NSE_FO|1", -65)));

        assertEquals(Kind.GONE, report.getDiscrepancies().get(0).getKind());
    }

    @Test
    void nothingTrackedAndNothingHeldShouldBeClean() {

        assertTrue(PositionReconciliation.compare(List.of(), List.of()).isClean());
    }

    @Test
    void shouldDescribeAGonePositionInTermsAUserCanActOn() {

        Report report = PositionReconciliation.compare(
                List.of(tracked("o1", "NIFTY 25000 CE", 65)), List.of());

        assertTrue(report.getDiscrepancies().get(0).describe().contains("not open at your broker"));
    }
}
