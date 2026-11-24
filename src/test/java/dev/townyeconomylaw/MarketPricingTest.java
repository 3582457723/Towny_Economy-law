package dev.townyeconomylaw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketPricingTest {
    @Test
    void bulkPurchaseAndSuccessiveSalesUseTheSameReserve() {
        long base = 2_500;
        int startingSupply = 12;
        long bulk = Market.value(base, startingSupply, 3);
        assertEquals(7_578, bulk);
        assertEquals(bulk, Market.value(base, startingSupply + 2, 1)
            + Market.value(base, startingSupply, 2));
    }

    @Test
    void taxRoundsDownAtTheCentAndHandlesMaximumTransactionSize() {
        assertEquals(15, Market.fee(1_234, 125));
        assertEquals(0, Market.fee(1, 9_999));
        assertEquals(Input.MAX_CASH, Market.fee(Input.MAX_CASH, 10_000));
    }
}
