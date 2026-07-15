package app;

import compliance.ComplianceConfig;
import compliance.ComplianceServer;
import compliance.RuleEngine;
import gui.ComplianceDashboard;

import javax.swing.*;

/**
 * Launches the compliance server (TCP, default port 7001) plus the Swing
 * dashboard. Use the "Start Simulation" button to feed it synthetic trades,
 * or run simulator.TradeSimulatorMain as a separate process pointed at this
 * port for a genuinely cross-process demo.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7001;

        RuleEngine engine = new RuleEngine(ComplianceConfig.buildRules());
        engine.start();

        ComplianceServer server = new ComplianceServer(port, engine);
        server.start();
        System.out.println("ComplianceServer listening on 127.0.0.1:" + server.port());

        SwingUtilities.invokeLater(() -> {
            ComplianceDashboard dashboard = new ComplianceDashboard();
            dashboard.attach(server, engine);
            dashboard.setVisible(true);
        });
    }
}
