package simulator;

/**
 * Standalone process entry point: connects to an already-running
 * ComplianceServer (e.g. started via app.Main) as a genuinely separate OS
 * process, over a real TCP socket -- useful for demonstrating the
 * networked nature of this system interactively alongside the GUI.
 *
 * Usage: java simulator.TradeSimulatorMain [host] [port] [numTrades] [ratePerSec] [seed]
 */
public final class TradeSimulatorMain {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7001;
        long numTrades = args.length > 2 ? Long.parseLong(args[2]) : 10_000;
        double rate = args.length > 3 ? Double.parseDouble(args[3]) : 300.0;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : System.nanoTime();

        System.out.printf("Connecting to %s:%d, sending %d trades at ~%.0f/sec...%n", host, port, numTrades, rate);
        TradeSimulator sim = new TradeSimulator(host, port, numTrades, seed, rate);
        sim.run();
        System.out.println("Done.");
    }
}
