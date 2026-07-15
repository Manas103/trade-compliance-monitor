package gui;

import compliance.Alert;
import compliance.ComplianceServer;
import compliance.RuleEngine;
import compliance.Trade;
import compliance.TradeListener;
import simulator.TradeSimulator;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live Swing view over a running ComplianceServer/RuleEngine. onTrade/
 * onAlert are called from the rule-engine's single evaluation thread, so
 * they only ever enqueue into lock-free-friendly ConcurrentLinkedQueues; a
 * Swing Timer on the EDT drains those queues at a fixed interval and
 * updates the tables. That batching is what keeps the UI responsive at
 * trade rates far higher than Swing repaints comfortably handle -- pushing
 * every single trade straight to the EDT would flood it at anything above
 * a few hundred updates/sec.
 */
public final class ComplianceDashboard extends JFrame implements TradeListener {
    private static final int MAX_ROWS = 300;
    private static final int UI_TICK_MS = 200;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final ConcurrentLinkedQueue<Trade> pendingTrades = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Alert> pendingAlerts = new ConcurrentLinkedQueue<>();
    private final Deque<Long> recentLatenciesNs = new ArrayDeque<>();
    private final AtomicLong totalTrades = new AtomicLong();
    private final AtomicLong totalAlerts = new AtomicLong();
    private long lastTickTrades = 0;

    private final DefaultTableModel tradeModel;
    private final DefaultTableModel alertModel;
    private final JLabel statusLabel = new JLabel("Trades: 0   Alerts: 0   Rate: 0/sec   p99 alert latency (recent): -- ms");

    private ComplianceServer server;
    private RuleEngine engine;

    public ComplianceDashboard() {
        super("Real-Time Trade Compliance Monitor");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 720);
        setLocationRelativeTo(null);

        tradeModel = new DefaultTableModel(new Object[]{"Trade ID", "Trader", "Instrument", "Side", "Price", "Qty"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        alertModel = new DefaultTableModel(new Object[]{"Time", "Rule", "Severity", "Trade ID", "Message"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        JTable tradeTable = new JTable(tradeModel);
        JTable alertTable = new JTable(alertModel);
        alertTable.getColumnModel().getColumn(2).setCellRenderer(new SeverityRenderer());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                wrapWithTitle("Live Trade Feed", tradeTable),
                wrapWithTitle("Compliance Alerts", alertTable));
        split.setResizeWeight(0.45);

        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        add(buildControlPanel(), BorderLayout.NORTH);

        Timer uiTimer = new Timer(UI_TICK_MS, e -> drainToUi());
        uiTimer.start();
    }

    private JPanel wrapWithTitle(String title, JTable table) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField numTradesField = new JTextField("20000", 8);
        JTextField rateField = new JTextField("500", 6);
        JButton startButton = new JButton("Start Simulation");

        panel.add(new JLabel("Trades:"));
        panel.add(numTradesField);
        panel.add(new JLabel("Rate/sec:"));
        panel.add(rateField);
        panel.add(startButton);

        startButton.addActionListener(e -> {
            long numTrades = Long.parseLong(numTradesField.getText().trim());
            double rate = Double.parseDouble(rateField.getText().trim());
            startButton.setEnabled(false);
            new Thread(() -> {
                try {
                    new TradeSimulator("127.0.0.1", server.port(), numTrades, System.nanoTime(), rate).run();
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    SwingUtilities.invokeLater(() -> startButton.setEnabled(true));
                }
            }, "gui-simulator").start();
        });
        return panel;
    }

    public void attach(ComplianceServer server, RuleEngine engine) {
        this.server = server;
        this.engine = engine;
        engine.addListener(this);
    }

    @Override
    public void onTrade(Trade trade) {
        pendingTrades.add(trade);
        totalTrades.incrementAndGet();
    }

    @Override
    public void onAlert(Alert alert) {
        pendingAlerts.add(alert);
        totalAlerts.incrementAndGet();
        synchronized (recentLatenciesNs) {
            recentLatenciesNs.addLast(alert.latencyNanos());
            if (recentLatenciesNs.size() > 2000) recentLatenciesNs.pollFirst();
        }
    }

    private void drainToUi() {
        Trade t;
        int added = 0;
        while ((t = pendingTrades.poll()) != null && added < 500) {
            tradeModel.addRow(new Object[]{t.tradeId(), t.traderId(), t.instrument(), t.side(),
                    String.format("%.2f", t.price()), t.qty()});
            trimRows(tradeModel);
            added++;
        }
        Alert a;
        added = 0;
        while ((a = pendingAlerts.poll()) != null && added < 500) {
            alertModel.addRow(new Object[]{TIME_FMT.format(new Date()), a.ruleName(), a.severity(),
                    a.trade().tradeId(), a.message()});
            trimRows(alertModel);
            added++;
        }

        long trades = totalTrades.get();
        double ratePerSec = (trades - lastTickTrades) * (1000.0 / UI_TICK_MS);
        lastTickTrades = trades;

        double p99Ms;
        synchronized (recentLatenciesNs) {
            if (recentLatenciesNs.isEmpty()) {
                p99Ms = Double.NaN;
            } else {
                long[] arr = recentLatenciesNs.stream().mapToLong(Long::longValue).sorted().toArray();
                p99Ms = arr[(int) (0.99 * (arr.length - 1))] / 1_000_000.0;
            }
        }
        statusLabel.setText(String.format(Locale.US,
                "Trades: %d   Alerts: %d   Rate: %.0f/sec   p99 alert latency (recent window): %s",
                trades, totalAlerts.get(), ratePerSec, Double.isNaN(p99Ms) ? "--" : String.format("%.2f ms", p99Ms)));
    }

    private void trimRows(DefaultTableModel model) {
        while (model.getRowCount() > MAX_ROWS) model.removeRow(0);
    }

    private static final class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected && value != null) {
                switch (value.toString()) {
                    case "HIGH" -> c.setBackground(new Color(255, 205, 205));
                    case "MEDIUM" -> c.setBackground(new Color(255, 240, 200));
                    default -> c.setBackground(Color.WHITE);
                }
            } else if (!isSelected) {
                c.setBackground(Color.WHITE);
            }
            return c;
        }
    }
}
