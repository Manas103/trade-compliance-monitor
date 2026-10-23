package staging;

/**
 * A proposed order, not yet executed. StagingGate decides whether it may be
 * released to the market; unlike compliance.Trade (an already-executed fill
 * the monitor is reacting to after the fact), an Order can still be stopped.
 */
public record Order(
        long orderId,
        String pmId,
        String instrument,
        char side, // 'B' or 'S'
        long qty,
        double price
) {
    public double notional() {
        return price * qty;
    }

    public long signedQty() {
        return side == 'B' ? qty : -qty;
    }
}
