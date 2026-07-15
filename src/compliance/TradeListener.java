package compliance;

/** Notified by RuleEngine's evaluation thread for every trade and alert. */
public interface TradeListener {
    void onTrade(Trade trade);
    void onAlert(Alert alert);
}
