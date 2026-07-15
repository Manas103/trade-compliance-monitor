package compliance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP server that accepts any number of concurrent trade-source connections
 * (each on its own thread) and feeds every trade line it reads into a
 * RuleEngine. ingestTsNanos is stamped the instant readLine() returns --
 * that is the moment this system takes ownership of the trade, and is the
 * timestamp all alert-latency numbers in this project are measured against.
 */
public final class ComplianceServer implements AutoCloseable {
    private final RuleEngine ruleEngine;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread acceptThread;

    public ComplianceServer(int port, RuleEngine ruleEngine) throws IOException {
        this.ruleEngine = ruleEngine;
        this.serverSocket = new ServerSocket(port);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void start() {
        acceptThread = new Thread(this::acceptLoop, "compliance-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                Thread reader = new Thread(() -> handleConnection(socket), "compliance-conn-" + socket.getPort());
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("Accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                long ingestTs = System.nanoTime();
                if (line.isBlank()) continue;
                try {
                    TradeCodec.Fields f = TradeCodec.decode(line);
                    Trade trade = new Trade(f.tradeId(), f.traderId(), f.instrument(), f.side(), f.price(), f.qty(), ingestTs);
                    ruleEngine.submit(trade);
                } catch (RuntimeException parseError) {
                    System.err.println("Bad trade line ignored: " + line);
                }
            }
        } catch (IOException e) {
            // Connection closed by peer -- normal at end of a simulation run.
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
    }
}
