import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Smart Traffic Light Simulation
 * 
 * Features:
 * - Smart Priority & Queue-based algorithm (Waiting time + Queue length + Ambulance emergency override)
 * - Finely shaped, realistic 2D traffic signal housings with visors, glowing lenses & mounting poles
 * - Glassmorphism UI (frosted translucent panels, specular gradient borders, modern glow accents)
 * - Self-contained, clean, simple Java Swing implementation
 */
public class SmartTrafficLight extends JFrame {

    // --- Data Models ---
    public static class Vehicle {
        final int id;
        final boolean isAmbulance;
        final int arrivalTick;
        final Color color;

        public Vehicle(int id, boolean isAmbulance, int arrivalTick) {
            this.id = id;
            this.isAmbulance = isAmbulance;
            this.arrivalTick = arrivalTick;
            if (isAmbulance) {
                this.color = Color.WHITE;
            } else {
                Color[] palette = {
                    new Color(59, 130, 246),   // Electric Blue
                    new Color(245, 158, 11),   // Amber Orange
                    new Color(168, 85, 247),   // Violet
                    new Color(20, 184, 166),   // Teal
                    new Color(244, 63, 94),    // Coral Rose
                    new Color(16, 185, 129)    // Emerald
                };
                this.color = palette[id % palette.length];
            }
        }
    }

    public enum Phase { GREEN, YELLOW }

    // Road indices: 0: North, 1: East, 2: South, 3: West
    public static final String[] ROAD_NAMES = {"North", "East", "South", "West"};
    public static final double[] ARRIVAL_RATES = {0.25, 0.16, 0.22, 0.12};

    // Simulation State
    @SuppressWarnings("unchecked")
    private final Queue<Vehicle>[] queues = new Queue[4];
    private int currentTick = 0;
    private int activeRoad = 0;
    private int nextRoad = 0;
    private Phase phase = Phase.GREEN;
    private int greenDuration = 0;
    private int totalCarsPassed = 0;
    private int vehicleCounter = 0;
    private Vehicle passingVehicle = null;
    private int passingFromRoad = -1;
    private String statusMessage = "North road starts with GREEN. Click 'Next Step' or 'Auto-Play'.";
    private String arrivalNotification = "";

    // Animation & Auto-Play Timers
    private javax.swing.Timer autoPlayTimer;
    private javax.swing.Timer animationTimer;
    private boolean isAutoPlaying = false;
    private float pulsePhase = 0f;

    // Embedded Web Server for HTML Simulation Connection
    private com.sun.net.httpserver.HttpServer webServer;
    private int serverPort = 8080;

    // Lane Card UI Row Reference
    private static class LaneCardRow {
        final JPanel container;
        final JLabel lblName;
        final JLabel lblDetails;
        Color signalColor = new Color(239, 68, 68);
        boolean isActive = false;

        LaneCardRow(JPanel container, JLabel lblName, JLabel lblDetails) {
            this.container = container;
            this.lblName = lblName;
            this.lblDetails = lblDetails;
        }
    }
    private final LaneCardRow[] laneCards = new LaneCardRow[4];

    // UI Elements
    private SimulationCanvas canvas;
    private JLabel lblStatus;
    private JLabel lblArrivals;
    private JLabel lblStats;
    private JButton btnAutoPlay;

    public SmartTrafficLight() {
        super("Smart Traffic Light - Intelligent Traffic Signal Simulation");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1220, 810);
        setMinimumSize(new Dimension(1080, 720));
        setLocationRelativeTo(null);

        // Initialize queues
        for (int i = 0; i < 4; i++) {
            queues[i] = new ArrayDeque<>();
        }

        // Add initial vehicles
        queues[0].add(new Vehicle(++vehicleCounter, false, 0));
        queues[0].add(new Vehicle(++vehicleCounter, false, 0));
        queues[1].add(new Vehicle(++vehicleCounter, false, 0));
        queues[2].add(new Vehicle(++vehicleCounter, false, 0));
        queues[3].add(new Vehicle(++vehicleCounter, false, 0));

        setupUI();
        initTimers();
        startEmbeddedWebServer();
        updateLaneCards();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (webServer != null) {
                    try {
                        webServer.stop(0);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // --- Priority Scoring & Smart Decision Engine ---
    private double calculateScore(int roadIndex) {
        Queue<Vehicle> q = queues[roadIndex];
        if (q.isEmpty()) return 0.0;

        boolean hasAmbulance = false;
        for (Vehicle v : q) {
            if (v.isAmbulance) {
                hasAmbulance = true;
                break;
            }
        }
        double ambulanceBoost = hasAmbulance ? 1000.0 : 0.0;
        int waitTime = currentTick - q.peek().arrivalTick;
        return q.size() + (0.5 * waitTime) + ambulanceBoost;
    }

    private int selectBestRoad() {
        int best = activeRoad;
        double maxScore = -1.0;

        for (int d = 0; d < 4; d++) {
            // Prevent lane starvation if current road held green for 6+ steps
            if (d == activeRoad && greenDuration >= 6) continue;
            double s = calculateScore(d);
            if (s > maxScore) {
                maxScore = s;
                best = d;
            }
        }
        return (maxScore <= 0) ? activeRoad : best;
    }

    void stepSimulation() {
        currentTick++;
        passingVehicle = null;
        passingFromRoad = -1;

        // Random new arrivals based on traffic density
        List<String> newArrivals = new ArrayList<>();
        Random rand = new Random();
        for (int d = 0; d < 4; d++) {
            if (rand.nextDouble() < ARRIVAL_RATES[d]) {
                queues[d].add(new Vehicle(++vehicleCounter, false, currentTick));
                newArrivals.add(ROAD_NAMES[d]);
            }
        }
        arrivalNotification = newArrivals.isEmpty() ? "" : "New cars arrived at: " + String.join(", ", newArrivals);

        if (phase == Phase.YELLOW) {
            // Yellow phase completes -> transition to green for the next road
            activeRoad = nextRoad;
            phase = Phase.GREEN;
            greenDuration = 0;
            statusMessage = ROAD_NAMES[activeRoad] + " signal turned GREEN. Vehicles advancing.";
        } else {
            // Currently Green
            greenDuration++;
            int best = selectBestRoad();
            boolean bestHasAmbulance = calculateScore(best) >= 1000;
            boolean currentEmpty = queues[activeRoad].isEmpty();

            // Check if light should turn Yellow
            if (best != activeRoad && (greenDuration >= 3 || currentEmpty || (bestHasAmbulance && greenDuration >= 1))) {
                phase = Phase.YELLOW;
                nextRoad = best;
                double bestScore = calculateScore(best);
                String scoreStr = bestScore >= 1000 ? "AMBULANCE (+1000)" : String.format("%.1f", bestScore);
                statusMessage = ROAD_NAMES[activeRoad] + " turns YELLOW. Next: " + ROAD_NAMES[best] + " (Score " + scoreStr + ")";
            } else {
                // Let vehicle pass through intersection
                if (!queues[activeRoad].isEmpty()) {
                    Vehicle v = queues[activeRoad].poll();
                    passingVehicle = v;
                    passingFromRoad = activeRoad;
                    totalCarsPassed++;
                    int waited = currentTick - v.arrivalTick;
                    String type = v.isAmbulance ? "Ambulance" : "Car";
                    statusMessage = type + " from " + ROAD_NAMES[activeRoad] + " drove through (waited " + waited + " steps).";
                } else {
                    statusMessage = ROAD_NAMES[activeRoad] + " is GREEN, but no cars are waiting.";
                }
            }
        }

        updateUI();
    }

    void addVehicle(int roadIndex, boolean isAmbulance) {
        queues[roadIndex].add(new Vehicle(++vehicleCounter, isAmbulance, currentTick));
        statusMessage = (isAmbulance ? "Ambulance dispatched to " : "Car added to ") + ROAD_NAMES[roadIndex] + " road.";
        updateUI();
    }

    private void resetSimulation() {
        if (isAutoPlaying) toggleAutoPlay();
        for (int i = 0; i < 4; i++) queues[i].clear();
        currentTick = 0;
        activeRoad = 0;
        nextRoad = 0;
        phase = Phase.GREEN;
        greenDuration = 0;
        totalCarsPassed = 0;
        vehicleCounter = 0;
        passingVehicle = null;
        passingFromRoad = -1;
        statusMessage = "Simulation reset. North starts with GREEN.";
        arrivalNotification = "";
        updateUI();
    }

    private void toggleAutoPlay() {
        isAutoPlaying = !isAutoPlaying;
        if (isAutoPlaying) {
            autoPlayTimer.start();
            btnAutoPlay.setText("Pause Simulation");
            btnAutoPlay.setBackground(new Color(239, 68, 68, 175));
        } else {
            autoPlayTimer.stop();
            btnAutoPlay.setText("Auto-Play (1.4s)");
            btnAutoPlay.setBackground(new Color(59, 130, 246, 160));
        }
    }

    private void initTimers() {
        autoPlayTimer = new javax.swing.Timer(1400, e -> stepSimulation());
        animationTimer = new javax.swing.Timer(40, e -> {
            pulsePhase = (pulsePhase + 0.1f) % ((float) Math.PI * 2);
            canvas.repaint();
        });
        animationTimer.start();
    }

    // --- Embedded Web Server & HTML Simulation Bridge ---
    private void startEmbeddedWebServer() {
        try {
            for (int p = 8080; p <= 8095; p++) {
                try {
                    webServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(p), 0);
                    serverPort = p;
                    break;
                } catch (Exception ignored) {}
            }
            if (webServer == null) return;

            // Serve the HTML file directly
            webServer.createContext("/", exchange -> {
                try {
                    java.io.File file = new java.io.File("Smart Traffic Light.html");
                    if (!file.exists()) {
                        // Try fallback path
                        file = new java.io.File("C:/Users/manas/Desktop/DS/mp/Smart Traffic Light.html");
                    }
                    byte[] htmlBytes = java.nio.file.Files.readAllBytes(file.toPath());
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, htmlBytes.length);
                    exchange.getResponseBody().write(htmlBytes);
                } catch (Exception ex) {
                    byte[] err = ("Error loading HTML: " + ex.getMessage()).getBytes();
                    exchange.sendResponseHeaders(500, err.length);
                    exchange.getResponseBody().write(err);
                } finally {
                    exchange.close();
                }
            });

            // Serve live JSON state
            webServer.createContext("/api/state", exchange -> {
                try {
                    String json = buildJsonState();
                    byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                } finally {
                    exchange.close();
                }
            });

            // Handle actions from HTML simulation
            webServer.createContext("/api/action", exchange -> {
                try {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                        return;
                    }

                    String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    handleWebAction(body);

                    byte[] ok = "{\"ok\":true}".getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, ok.length);
                    exchange.getResponseBody().write(ok);
                } finally {
                    exchange.close();
                }
            });

            webServer.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
            webServer.start();
            System.out.println("Embedded Simulation Server running at http://localhost:" + serverPort);
        } catch (Exception ex) {
            System.err.println("Could not start embedded web server: " + ex.getMessage());
        }
    }

    private synchronized String buildJsonState() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"tick\":").append(currentTick).append(",");
        sb.append("\"activeRoad\":").append(activeRoad).append(",");
        sb.append("\"nextRoad\":").append(nextRoad).append(",");
        sb.append("\"phase\":\"").append(phase.name()).append("\",");
        sb.append("\"greenDuration\":").append(greenDuration).append(",");
        sb.append("\"totalCarsPassed\":").append(totalCarsPassed).append(",");
        sb.append("\"isAutoPlaying\":").append(isAutoPlaying).append(",");
        sb.append("\"statusMessage\":").append(escapeJson(statusMessage)).append(",");
        sb.append("\"arrivalNotification\":").append(escapeJson(arrivalNotification)).append(",");
        sb.append("\"passingFromRoad\":").append(passingFromRoad).append(",");

        if (passingVehicle != null) {
            sb.append("\"passingVehicle\":{\"id\":").append(passingVehicle.id)
              .append(",\"isAmbulance\":").append(passingVehicle.isAmbulance)
              .append(",\"arrivalTick\":").append(passingVehicle.arrivalTick).append("},");
        } else {
            sb.append("\"passingVehicle\":null,");
        }

        sb.append("\"queues\":[");
        for (int d = 0; d < 4; d++) {
            sb.append("[");
            int i = 0;
            for (Vehicle v : queues[d]) {
                if (i++ > 0) sb.append(",");
                sb.append("{\"id\":").append(v.id)
                  .append(",\"isAmbulance\":").append(v.isAmbulance)
                  .append(",\"arrivalTick\":").append(v.arrivalTick).append("}");
            }
            sb.append("]");
            if (d < 3) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void handleWebAction(String json) {
        SwingUtilities.invokeLater(() -> {
            if (json.contains("\"step\"")) {
                stepSimulation();
            } else if (json.contains("\"auto\"")) {
                toggleAutoPlay();
            } else if (json.contains("\"reset\"")) {
                resetSimulation();
            } else if (json.contains("\"add\"")) {
                boolean isAmb = json.contains("\"isAmbulance\":true");
                int road = 0;
                for (int d = 0; d < 4; d++) {
                    if (json.contains("\"road\":" + d)) {
                        road = d;
                        break;
                    }
                }
                addVehicle(road, isAmb);
            }
        });
    }

    private String escapeJson(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private void openWebSimulation() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI("http://localhost:" + serverPort));
            } else {
                java.io.File file = new java.io.File("Smart Traffic Light.html");
                if (file.exists() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file);
                } else {
                    Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", "http://localhost:" + serverPort});
                }
            }
        } catch (Exception ex) {
            try {
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", "http://localhost:" + serverPort});
            } catch (Exception ignored) {}
        }
    }

    // --- Glassmorphism UI Construction ---
    private void setupUI() {
        JPanel rootPanel = new JPanel(new BorderLayout(16, 16)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Deep modern dark background gradient
                GradientPaint bg = new GradientPaint(0, 0, new Color(11, 15, 25), getWidth(), getHeight(), new Color(20, 25, 42));
                g2.setPaint(bg);
                g2.fillRect(0, 0, getWidth(), getHeight());

                // Subtle ambient glass glow accents
                g2.setColor(new Color(59, 130, 246, 22));
                g2.fillOval(-120, -100, 480, 480);
                g2.setColor(new Color(16, 185, 129, 18));
                g2.fillOval(getWidth() - 400, getHeight() - 380, 480, 480);
                g2.dispose();
            }
        };
        rootPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        setContentPane(rootPanel);

        // Left Container: Simulation Canvas inside Glass Panel
        GlassPanel canvasContainer = new GlassPanel(new BorderLayout(0, 8));
        canvasContainer.setBorder(new EmptyBorder(14, 14, 14, 14));

        // Canvas Header
        JPanel simHeader = new JPanel(new BorderLayout(14, 0));
        simHeader.setOpaque(false);
        simHeader.setBorder(new EmptyBorder(0, 4, 10, 4));

        JLabel lblSimTitle = new JLabel("Smart Intersection Simulation");
        lblSimTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblSimTitle.setForeground(new Color(241, 245, 249));

        lblStats = new JLabel("Step 0  |  0 cars passed", SwingConstants.RIGHT);
        lblStats.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        lblStats.setForeground(new Color(148, 163, 184));
        lblStats.setBorder(new EmptyBorder(0, 0, 0, 16));

        simHeader.add(lblSimTitle, BorderLayout.WEST);
        simHeader.add(lblStats, BorderLayout.EAST);
        canvasContainer.add(simHeader, BorderLayout.NORTH);

        canvas = new SimulationCanvas();
        canvasContainer.add(canvas, BorderLayout.CENTER);
        rootPanel.add(canvasContainer, BorderLayout.CENTER);

        // Right Container: Control Dashboard
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setOpaque(false);
        sidebar.setPreferredSize(new Dimension(480, 740));

        // 1. Status Display Glass Card
        GlassPanel statusCard = new GlassPanel(new BorderLayout(8, 8));
        statusCard.setBorder(new EmptyBorder(14, 16, 14, 16));

        lblStatus = new JLabel("<html><body style='width: 430px; font-size: 13px; color: #f8fafc; font-weight: bold;'>" 
                + statusMessage + "</body></html>");

        lblArrivals = new JLabel("System Ready");
        lblArrivals.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblArrivals.setForeground(new Color(148, 163, 184));

        JPanel statusText = new JPanel(new GridLayout(2, 1, 4, 4));
        statusText.setOpaque(false);
        statusText.add(lblStatus);
        statusText.add(lblArrivals);
        statusCard.add(statusText, BorderLayout.CENTER);
        sidebar.add(statusCard);
        sidebar.add(Box.createRigidArea(new Dimension(0, 12)));

        // 2. Control Buttons Glass Card
        GlassPanel actionCard = new GlassPanel(new GridLayout(1, 3, 10, 0));
        actionCard.setBorder(new EmptyBorder(12, 12, 12, 12));

        JButton btnStep = createGlassButton("Next Step >", new Color(16, 185, 129, 190), Color.WHITE, 13, true);
        btnStep.addActionListener(e -> stepSimulation());

        btnAutoPlay = createGlassButton("Auto-Play (1.4s)", new Color(59, 130, 246, 160), Color.WHITE, 12, false);
        btnAutoPlay.addActionListener(e -> toggleAutoPlay());

        JButton btnReset = createGlassButton("Reset", new Color(100, 116, 139, 160), Color.WHITE, 12, false);
        btnReset.addActionListener(e -> resetSimulation());

        actionCard.add(btnStep);
        actionCard.add(btnAutoPlay);
        actionCard.add(btnReset);
        sidebar.add(actionCard);
        sidebar.add(Box.createRigidArea(new Dimension(0, 8)));

        // Web Simulation Connection Button
        JButton btnOpenWeb = createGlassButton("🌐 Open Web Simulation (HTML Browser)", new Color(99, 102, 241, 165), Color.WHITE, 12, true);
        btnOpenWeb.setToolTipText("Open the connected HTML simulation in your web browser");
        btnOpenWeb.setPreferredSize(new Dimension(440, 36));
        btnOpenWeb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        btnOpenWeb.addActionListener(e -> openWebSimulation());
        sidebar.add(btnOpenWeb);
        sidebar.add(Box.createRigidArea(new Dimension(0, 12)));

        // 3. Lane Queues & Scores Glass Card
        GlassPanel lanesCard = new GlassPanel(new BorderLayout(8, 8));
        lanesCard.setBorder(new EmptyBorder(14, 16, 14, 16));

        JLabel lblLanesTitle = new JLabel("Traffic Status & Smart Priority");
        lblLanesTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblLanesTitle.setForeground(new Color(226, 232, 240));
        lanesCard.add(lblLanesTitle, BorderLayout.NORTH);

        JPanel cardsPanel = new JPanel(new GridLayout(4, 1, 6, 6));
        cardsPanel.setOpaque(false);

        for (int d = 0; d < 4; d++) {
            final int index = d;
            JLabel nameLabel = new JLabel(ROAD_NAMES[d] + " Road", SwingConstants.LEFT);
            nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            nameLabel.setForeground(Color.WHITE);

            JLabel detailsLabel = new JLabel("0 cars  |  Score: 0.0", SwingConstants.RIGHT);
            detailsLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            detailsLabel.setForeground(new Color(148, 163, 184));

            JPanel card = new JPanel(new GridLayout(1, 2, 8, 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    LaneCardRow row = laneCards[index];
                    Color sigCol = (row != null) ? row.signalColor : new Color(239, 68, 68);
                    boolean isAct = (row != null) && row.isActive;

                    // Frosted pill
                    if (isAct) {
                        g2.setColor(new Color(sigCol.getRed(), sigCol.getGreen(), sigCol.getBlue(), 32));
                    } else {
                        g2.setColor(new Color(255, 255, 255, 12));
                    }
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);

                    // Border
                    if (isAct) {
                        g2.setColor(new Color(sigCol.getRed(), sigCol.getGreen(), sigCol.getBlue(), 130));
                    } else {
                        g2.setColor(new Color(255, 255, 255, 25));
                    }
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);

                    // Indicator Dot
                    g2.setColor(sigCol);
                    g2.fillOval(10, (getHeight() - 10) / 2, 10, 10);

                    if (isAct) {
                        g2.setColor(new Color(sigCol.getRed(), sigCol.getGreen(), sigCol.getBlue(), 60));
                        g2.fillOval(8, (getHeight() - 14) / 2, 14, 14);
                    }
                    g2.dispose();
                }
            };
            card.setOpaque(false);
            card.setBorder(new EmptyBorder(6, 28, 6, 14));
            card.add(nameLabel);
            card.add(detailsLabel);

            laneCards[d] = new LaneCardRow(card, nameLabel, detailsLabel);
            cardsPanel.add(card);
        }

        lanesCard.add(cardsPanel, BorderLayout.CENTER);
        sidebar.add(lanesCard);
        sidebar.add(Box.createRigidArea(new Dimension(0, 12)));

        // 4. Vehicle Injection Glass Card
        GlassPanel injectCard = new GlassPanel(new BorderLayout(8, 8));
        injectCard.setBorder(new EmptyBorder(14, 16, 14, 16));

        JLabel lblInjectTitle = new JLabel("Interactive Traffic Injection");
        lblInjectTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblInjectTitle.setForeground(new Color(226, 232, 240));
        injectCard.add(lblInjectTitle, BorderLayout.NORTH);

        JPanel injectBody = new JPanel(new GridLayout(2, 1, 10, 10));
        injectBody.setOpaque(false);

        // Row 1: Add Car
        JPanel carRow = new JPanel(new BorderLayout(8, 0));
        carRow.setOpaque(false);
        JLabel lblCar = new JLabel("+ Add Car:");
        lblCar.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblCar.setForeground(new Color(203, 213, 225));
        lblCar.setPreferredSize(new Dimension(95, 28));

        JPanel carBtns = new JPanel(new GridLayout(1, 4, 6, 0));
        carBtns.setOpaque(false);
        for (int d = 0; d < 4; d++) {
            final int road = d;
            JButton b = createGlassButton(ROAD_NAMES[d].substring(0, 1), new Color(255, 255, 255, 24), Color.WHITE, 12, true);
            b.setToolTipText("Add car to " + ROAD_NAMES[d]);
            b.addActionListener(e -> addVehicle(road, false));
            carBtns.add(b);
        }
        carRow.add(lblCar, BorderLayout.WEST);
        carRow.add(carBtns, BorderLayout.CENTER);
        injectBody.add(carRow);

        // Row 2: Add Ambulance
        JPanel ambRow = new JPanel(new BorderLayout(8, 0));
        ambRow.setOpaque(false);
        JLabel lblAmb = new JLabel("+ Ambulance:");
        lblAmb.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblAmb.setForeground(new Color(252, 165, 165));
        lblAmb.setPreferredSize(new Dimension(95, 28));

        JPanel ambBtns = new JPanel(new GridLayout(1, 4, 6, 0));
        ambBtns.setOpaque(false);
        for (int d = 0; d < 4; d++) {
            final int road = d;
            JButton b = createGlassButton(ROAD_NAMES[d].substring(0, 1), new Color(239, 68, 68, 65), new Color(254, 202, 202), 12, true);
            b.setToolTipText("Add emergency ambulance to " + ROAD_NAMES[d]);
            b.addActionListener(e -> addVehicle(road, true));
            ambBtns.add(b);
        }
        ambRow.add(lblAmb, BorderLayout.WEST);
        ambRow.add(ambBtns, BorderLayout.CENTER);
        injectBody.add(ambRow);

        injectCard.add(injectBody, BorderLayout.CENTER);
        sidebar.add(injectCard);
        sidebar.add(Box.createRigidArea(new Dimension(0, 12)));

        // 5. Algorithm Explanation Glass Card
        GlassPanel infoCard = new GlassPanel(new BorderLayout());
        infoCard.setBorder(new EmptyBorder(12, 14, 12, 14));
        JLabel lblInfo = new JLabel("<html><body style='width: 340px; color: #94a3b8; font-size: 11px; line-height: 1.4;'>"
                + "<b style='color: #cbd5e1;'>How Decision Making Operates:</b><br>"
                + "• Every road receives a dynamic priority <b>Score</b>:<br>"
                + "&nbsp;&nbsp;<code>Score = waiting cars + (0.5 × first car wait time) + Ambulance Boost (1000)</code><br>"
                + "• The road with the highest score receives <b>GREEN</b> next.<br>"
                + "• Signal transitions smoothly: <b>GREEN → YELLOW → RED</b>."
                + "</body></html>");
        infoCard.add(lblInfo, BorderLayout.CENTER);
        sidebar.add(infoCard);

        rootPanel.add(sidebar, BorderLayout.EAST);
    }

    private void updateUI() {
        lblStatus.setText("<html><body style='width: 430px; font-size: 13px; color: #f8fafc; font-weight: bold;'>" 
                + statusMessage + "</body></html>");
        lblArrivals.setText(arrivalNotification.isEmpty() ? "Normal Traffic Flow" : arrivalNotification);
        lblStats.setText("Step " + currentTick + "  |  " + totalCarsPassed + " passed");
        updateLaneCards();
        canvas.repaint();
    }

    private void updateLaneCards() {
        for (int d = 0; d < 4; d++) {
            LaneCardRow row = laneCards[d];
            if (row == null) continue;

            boolean isActive = (d == activeRoad);
            Color sigCol = isActive 
                    ? (phase == Phase.GREEN ? new Color(34, 197, 94) : new Color(245, 158, 11))
                    : new Color(239, 68, 68);
            String sigState = isActive ? (phase == Phase.GREEN ? "GREEN" : "YELLOW") : "RED";

            row.isActive = isActive;
            row.signalColor = sigCol;
            row.lblName.setText(ROAD_NAMES[d] + " Road");
            row.lblName.setForeground(isActive ? Color.WHITE : new Color(203, 213, 225));

            int count = queues[d].size();
            String countStr = count + (count == 1 ? " car" : " cars");
            double score = calculateScore(d);
            String scoreStr = score >= 1000 ? "1000+ (EMG)" : String.format("%.1f", score);
            row.lblDetails.setText(countStr + "  |  Score " + scoreStr);
            row.lblDetails.setForeground(score >= 1000 ? new Color(252, 165, 165) : new Color(148, 163, 184));
            row.container.repaint();
        }
    }

    // Glassmorphism Button Factory
    private JButton createGlassButton(String text, Color bg, Color fg, int fontSize, boolean isBold) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color c = getBackground();
                if (getModel().isPressed()) {
                    c = c.darker();
                } else if (getModel().isRollover()) {
                    c = new Color(Math.min(255, c.getRed() + 25),
                                  Math.min(255, c.getGreen() + 25),
                                  Math.min(255, c.getBlue() + 25),
                                  Math.min(255, c.getAlpha() + 45));
                }

                // Frosted pill
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);

                // Top glossy reflection
                GradientPaint shine = new GradientPaint(0, 0, new Color(255, 255, 255, 50),
                                                        0, getHeight() / 2f, new Color(255, 255, 255, 5));
                g2.setPaint(shine);
                g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() / 2, 8, 8);

                // Specular border
                g2.setColor(new Color(255, 255, 255, 60));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);

                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Segoe UI", isBold ? Font.BOLD : Font.PLAIN, fontSize));
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // Glassmorphism Container Panel
    public static class GlassPanel extends JPanel {
        public GlassPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Glass background fill
            g2.setColor(new Color(22, 29, 44, 175));
            g2.fillRoundRect(0, 0, w, h, 16, 16);

            // Specular top gradient shine
            GradientPaint shine = new GradientPaint(0, 0, new Color(255, 255, 255, 24),
                                                    0, h, new Color(255, 255, 255, 3));
            g2.setPaint(shine);
            g2.fillRoundRect(0, 0, w, h, 16, 16);

            // Glass border stroke
            g2.setColor(new Color(255, 255, 255, 35));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // --- 2D Simulation Canvas ---
    private class SimulationCanvas extends JPanel {
        public SimulationCanvas() {
            setOpaque(false);
            setPreferredSize(new Dimension(640, 640));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();

            // Keep drawing centered & square (600x600 space)
            int size = Math.min(width, height) - 16;
            int originX = (width - size) / 2;
            int originY = (height - size) / 2;

            g2.translate(originX, originY);
            double scale = size / 600.0;
            g2.scale(scale, scale);

            // 1. Draw Ground & Road Geometry
            drawRoads(g2);

            // 2. Draw Road Markings & Crosswalks
            drawMarkings(g2);

            // 3. Draw Waiting Vehicles
            drawVehicles(g2);

            // 4. Draw Passing Vehicle (if moving through)
            if (passingVehicle != null && passingFromRoad != -1) {
                drawPassingVehicle(g2);
            }

            // 5. Draw Finely Shaped 2D Traffic Signals with Visors and Glows
            drawTrafficSignals(g2);

            g2.dispose();
        }

        private void drawRoads(Graphics2D g2) {
            // Surrounding Grass / Ground
            g2.setColor(new Color(20, 35, 27));
            g2.fillRoundRect(0, 0, 600, 600, 16, 16);

            // Sidewalk Curbs (4 corner blocks)
            g2.setColor(new Color(45, 55, 72));
            g2.fillRoundRect(10, 10, 215, 215, 14, 14);
            g2.fillRoundRect(375, 10, 215, 215, 14, 14);
            g2.fillRoundRect(10, 375, 215, 215, 14, 14);
            g2.fillRoundRect(375, 375, 215, 215, 14, 14);

            // Lawn insets
            g2.setColor(new Color(26, 48, 36));
            g2.fillRoundRect(18, 18, 199, 199, 10, 10);
            g2.fillRoundRect(383, 18, 199, 199, 10, 10);
            g2.fillRoundRect(18, 383, 199, 199, 10, 10);
            g2.fillRoundRect(383, 383, 199, 199, 10, 10);

            // Asphalt Roads (Vertical & Horizontal intersection)
            g2.setColor(new Color(28, 33, 43));
            g2.fillRect(225, 0, 150, 600);
            g2.fillRect(0, 225, 600, 150);

            // Central Junction Area
            g2.setColor(new Color(32, 38, 50));
            g2.fillRect(225, 225, 150, 150);

            // Dashed Center Divider Lines
            float[] dash = {14f, 12f};
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, dash, 0));
            g2.setColor(new Color(234, 179, 8, 210)); // Warm Amber Divider
            g2.drawLine(300, 0, 300, 215);     // North
            g2.drawLine(300, 385, 300, 600);   // South
            g2.drawLine(0, 300, 215, 300);     // West
            g2.drawLine(385, 300, 600, 300);   // East
            g2.setStroke(new BasicStroke(1f));
        }

        private void drawMarkings(Graphics2D g2) {
            // White Zebra Crosswalks
            g2.setColor(new Color(226, 232, 240, 140));
            // North Crosswalk
            for (int x = 232; x <= 360; x += 16) {
                g2.fillRect(x, 216, 9, 9);
            }
            // South Crosswalk
            for (int x = 232; x <= 360; x += 16) {
                g2.fillRect(x, 375, 9, 9);
            }
            // West Crosswalk
            for (int y = 232; y <= 360; y += 16) {
                g2.fillRect(216, y, 9, 9);
            }
            // East Crosswalk
            for (int y = 232; y <= 360; y += 16) {
                g2.fillRect(375, y, 9, 9);
            }

            // Solid Stop Lines for incoming traffic lanes
            g2.setColor(new Color(255, 255, 255, 240));
            // North incoming (right lane, x in [300, 375], y = 210)
            g2.fillRect(300, 210, 75, 5);
            // South incoming (right lane from driver perspective, x in [225, 300], y = 385)
            g2.fillRect(225, 385, 75, 5);
            // West incoming (y in [300, 375], x = 210)
            g2.fillRect(210, 300, 5, 75);
            // East incoming (y in [225, 300], x = 385)
            g2.fillRect(385, 225, 5, 75);
        }

        private void drawVehicles(Graphics2D g2) {
            for (int d = 0; d < 4; d++) {
                Queue<Vehicle> q = queues[d];
                if (q.isEmpty()) continue;

                Vehicle[] arr = q.toArray(new Vehicle[0]);
                int displayCount = Math.min(arr.length, 4);

                for (int i = 0; i < displayCount; i++) {
                    Vehicle v = arr[i];
                    Point p = getVehicleSlot(d, i);
                    drawShaped2DCar(g2, p.x, p.y, d, v);
                }

                // If queue exceeds 4 vehicles, show overflow glass badge
                if (arr.length > 4) {
                    Point p = getVehicleSlot(d, 4);
                    g2.setColor(new Color(15, 23, 42, 230));
                    g2.fillRoundRect(p.x - 14, p.y - 12, 28, 24, 8, 8);
                    g2.setColor(new Color(255, 255, 255, 100));
                    g2.drawRoundRect(p.x - 14, p.y - 12, 28, 24, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                    String overflow = "+" + (arr.length - 4);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(overflow, p.x - fm.stringWidth(overflow) / 2, p.y + 4);
                }
            }
        }

        private Point getVehicleSlot(int direction, int index) {
            int step = 42;
            switch (direction) {
                case 0: // North incoming (driving Southwards)
                    return new Point(338, 175 - index * step);
                case 1: // East incoming (driving Westwards)
                    return new Point(425 + index * step, 262);
                case 2: // South incoming (driving Northwards)
                    return new Point(262, 425 + index * step);
                case 3: // West incoming (driving Eastwards)
                default:
                    return new Point(175 - index * step, 338);
            }
        }

        private void drawPassingVehicle(Graphics2D g2) {
            Point center;
            switch (passingFromRoad) {
                case 0: center = new Point(338, 290); break;
                case 1: center = new Point(310, 262); break;
                case 2: center = new Point(262, 310); break;
                default: center = new Point(290, 338); break;
            }
            drawShaped2DCar(g2, center.x, center.y, passingFromRoad, passingVehicle);
        }

        /**
         * Renders a finely shaped 2D vehicle.
         * Features sleek body lines, windshield, rear window, headlights, taillights,
         * side mirrors, wheels, and an animated flashing beacon for ambulances.
         */
        private void drawShaped2DCar(Graphics2D g2, int cx, int cy, int direction, Vehicle v) {
            Graphics2D g = (Graphics2D) g2.create();
            g.translate(cx, cy);

            // Car model is designed facing UP (North), rotated into movement direction:
            // 0: Southbound (PI), 1: Westbound (-PI/2), 2: Northbound (0), 3: Eastbound (PI/2)
            double angle = 0;
            switch (direction) {
                case 0: angle = Math.PI; break;
                case 1: angle = -Math.PI / 2.0; break;
                case 2: angle = 0; break;
                case 3: angle = Math.PI / 2.0; break;
            }
            g.rotate(angle);

            int carW = 24;
            int carH = 36;
            int x = -carW / 2;
            int y = -carH / 2;

            // Soft shadow under car
            g.setColor(new Color(0, 0, 0, 75));
            g.fillRoundRect(x - 2, y, carW + 4, carH + 3, 10, 10);

            // 4 Wheels
            g.setColor(new Color(17, 24, 39));
            g.fillRoundRect(x - 3, y + 4, 3, 8, 2, 2);
            g.fillRoundRect(x + carW, y + 4, 3, 8, 2, 2);
            g.fillRoundRect(x - 3, y + carH - 12, 3, 8, 2, 2);
            g.fillRoundRect(x + carW, y + carH - 12, 3, 8, 2, 2);

            // Side Mirrors
            g.setColor(v.isAmbulance ? Color.WHITE : v.color.darker());
            g.fillOval(x - 3, y + 9, 3, 4);
            g.fillOval(x + carW, y + 9, 3, 4);

            if (v.isAmbulance) {
                // Ambulance Body: Crisp hospital white
                g.setColor(new Color(248, 250, 252));
                g.fillRoundRect(x, y, carW, carH, 6, 6);

                // Red Emergency side stripes
                g.setColor(new Color(220, 38, 38));
                g.fillRect(x + 1, y + 7, 3, carH - 14);
                g.fillRect(x + carW - 4, y + 7, 3, carH - 14);

                // Windshield (dark glass)
                g.setColor(new Color(30, 41, 59));
                g.fillRoundRect(x + 3, y + 4, carW - 6, 7, 3, 3);
                // Rear window
                g.fillRoundRect(x + 4, y + carH - 6, carW - 8, 4, 2, 2);

                // Red Cross Emblem on roof
                g.setColor(new Color(220, 38, 38));
                g.fillRect(-2, -2, 5, 12);
                g.fillRect(-5, 1, 11, 5);

                // Flashing Dual Emergency Beacons (Red & Blue alternating)
                boolean flash = ((int) (pulsePhase * 3.5)) % 2 == 0;
                Color beaconLeft = flash ? new Color(239, 68, 68, 220) : new Color(59, 130, 246, 70);
                Color beaconRight = flash ? new Color(59, 130, 246, 70) : new Color(239, 68, 68, 220);

                g.setColor(beaconLeft);
                g.fillOval(-8, -8, 7, 7);
                g.setColor(beaconRight);
                g.fillOval(1, -8, 7, 7);

                // Core white sparkle
                g.setColor(Color.WHITE);
                g.fillOval(flash ? -6 : 3, -6, 3, 3);

            } else {
                // Normal Vehicle Body: Metallic gradient finish
                GradientPaint bodyPaint = new GradientPaint(x, y, v.color, x + carW, y + carH, v.color.darker());
                g.setPaint(bodyPaint);
                g.fillRoundRect(x, y, carW, carH, 8, 8);

                // Sunroof / Roof depression
                g.setColor(new Color(0, 0, 0, 35));
                g.fillRoundRect(x + 3, y + 8, carW - 6, carH - 15, 4, 4);

                // Front Windshield
                g.setColor(new Color(15, 23, 42, 230));
                g.fillRoundRect(x + 3, y + 5, carW - 6, 6, 3, 3);

                // Rear Windshield
                g.fillRoundRect(x + 3, y + carH - 10, carW - 6, 4, 2, 2);

                // Glowing Headlights
                g.setColor(new Color(254, 240, 138, 230));
                g.fillRect(x + 2, y, 4, 2);
                g.fillRect(x + carW - 6, y, 4, 2);

                // Red Taillights
                g.setColor(new Color(239, 68, 68, 220));
                g.fillRect(x + 2, y + carH - 2, 4, 2);
                g.fillRect(x + carW - 6, y + carH - 2, 4, 2);
            }

            // Outline stroke
            g.setColor(new Color(0, 0, 0, 90));
            g.drawRoundRect(x, y, carW, carH, 7, 7);

            g.dispose();
        }

        /**
         * Renders all 4 Traffic Signal Fixtures.
         * Positioned comfortably on the corner curbs facing oncoming traffic without overlap.
         */
        private void drawTrafficSignals(Graphics2D g2) {
            // Signal coordinates:
            // 0: North Road -> (395, 145), controls Southbound traffic
            // 1: East Road  -> (475, 195), controls Westbound traffic
            // 2: South Road -> (205, 455), controls Northbound traffic
            // 3: West Road  -> (125, 405), controls Eastbound traffic

            int[][] signalPos = {
                {395, 145}, // North
                {475, 195}, // East
                {205, 455}, // South
                {125, 405}  // West
            };

            for (int d = 0; d < 4; d++) {
                int x = signalPos[d][0];
                int y = signalPos[d][1];
                boolean isCurrentRoad = (d == activeRoad);

                int activeLamp; // 0: Red, 1: Yellow, 2: Green
                if (isCurrentRoad) {
                    activeLamp = (phase == Phase.YELLOW) ? 1 : 2;
                } else {
                    activeLamp = 0; // Red
                }

                renderFineTrafficSignal(g2, x, y, ROAD_NAMES[d], activeLamp);
            }

            // Floating Master Signal HUD Pill at top of intersection
            drawSignalHudPill(g2);
        }

        private void drawSignalHudPill(Graphics2D g2) {
            Graphics2D g = (Graphics2D) g2.create();
            int pillW = 260;
            int pillH = 34;
            int px = (600 - pillW) / 2;
            int py = 12;

            // Translucent glass pill
            g.setColor(new Color(15, 23, 42, 220));
            g.fillRoundRect(px, py, pillW, pillH, 17, 17);

            // Specular border
            g.setColor(new Color(255, 255, 255, 45));
            g.setStroke(new BasicStroke(1.2f));
            g.drawRoundRect(px, py, pillW, pillH, 17, 17);

            // Active signal color
            Color sigCol = (phase == Phase.GREEN) ? new Color(34, 197, 94) : new Color(245, 158, 11);
            String phaseTxt = (phase == Phase.GREEN) ? "GREEN (GO)" : "YELLOW (PREPARE)";

            // Glowing indicator
            g.setColor(new Color(sigCol.getRed(), sigCol.getGreen(), sigCol.getBlue(), 80));
            g.fillOval(px + 12, py + 9, 16, 16);
            g.setColor(sigCol);
            g.fillOval(px + 14, py + 11, 12, 12);

            // Text
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g.drawString(ROAD_NAMES[activeRoad].toUpperCase() + "  -  " + phaseTxt, px + 36, py + 22);

            g.dispose();
        }

        /**
         * Renders an individual, highly shaped 2D Traffic Light:
         * - Steel mounting pole & bracket
         * - Chamfered target backplate with high-visibility contrast border
         * - Metallic housing with specular edge
         * - Arched visors/hoods over each lamp
         * - Multi-tier radiant luminous blooming when illuminated
         * - Road direction badge
         */
        private void renderFineTrafficSignal(Graphics2D g2, int x, int y, String roadName, int activeLamp) {
            Graphics2D g = (Graphics2D) g2.create();

            int boxW = 26;
            int boxH = 66;

            // 1. Steel Mounting Pole
            g.setColor(new Color(71, 85, 105));
            g.fillRect(x - 2, y - boxH / 2 + 10, 4, boxH + 15);
            g.setColor(new Color(30, 41, 59));
            g.drawRect(x - 2, y - boxH / 2 + 10, 4, boxH + 15);

            // 2. Drop Shadow of signal housing
            g.setColor(new Color(0, 0, 0, 95));
            g.fillRoundRect(x - boxW / 2 + 3, y - boxH / 2 + 4, boxW, boxH, 12, 12);

            // 3. High-Visibility Signal Backplate (Yellow/Dark Border)
            int bpPad = 5;
            g.setColor(new Color(245, 158, 11, 230)); // Safety Yellow backplate rim
            g.fillRoundRect(x - boxW / 2 - bpPad, y - boxH / 2 - bpPad, boxW + bpPad * 2, boxH + bpPad * 2, 14, 14);

            g.setColor(new Color(15, 23, 42)); // Inner black backplate
            g.fillRoundRect(x - boxW / 2 - bpPad + 2, y - boxH / 2 - bpPad + 2, boxW + (bpPad - 2) * 2, boxH + (bpPad - 2) * 2, 12, 12);

            // 4. Main Metallic Signal Housing
            GradientPaint housingPaint = new GradientPaint(x - boxW / 2f, y - boxH / 2f, new Color(40, 47, 60),
                                                           x + boxW / 2f, y + boxH / 2f, new Color(18, 22, 30));
            g.setPaint(housingPaint);
            g.fillRoundRect(x - boxW / 2, y - boxH / 2, boxW, boxH, 10, 10);

            // Specular border on housing
            g.setColor(new Color(90, 105, 128));
            g.setStroke(new BasicStroke(1.2f));
            g.drawRoundRect(x - boxW / 2, y - boxH / 2, boxW, boxH, 10, 10);

            // 5. Lamps: 0: Red, 1: Yellow, 2: Green
            int lampRadius = 6;
            int[] lampY = { y - 19, y, y + 19 };

            Color[] activeGlowColors = {
                new Color(239, 68, 68),   // Brilliant Red
                new Color(245, 158, 11),  // Brilliant Amber
                new Color(34, 197, 94)    // Brilliant Emerald Green
            };
            Color[] unlitColors = {
                new Color(60, 15, 15),    // Deep unlit Red
                new Color(55, 38, 10),    // Deep unlit Amber
                new Color(12, 45, 25)     // Deep unlit Green
            };

            for (int k = 0; k < 3; k++) {
                int ly = lampY[k];
                boolean isLit = (k == activeLamp);

                // Arched Visor / Hood over each lamp
                g.setColor(new Color(15, 19, 26));
                g.fillArc(x - lampRadius - 4, ly - lampRadius - 6, (lampRadius + 4) * 2, (lampRadius + 4) * 2, 15, 150);
                g.setColor(new Color(71, 85, 105));
                g.setStroke(new BasicStroke(1f));
                g.drawArc(x - lampRadius - 4, ly - lampRadius - 6, (lampRadius + 4) * 2, (lampRadius + 4) * 2, 15, 150);

                if (isLit) {
                    Color glow = activeGlowColors[k];
                    float pulse = (float) (Math.sin(pulsePhase) * 2.5);

                    // Outer Radiant Bloom
                    g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 38));
                    g.fillOval(x - lampRadius - 10, ly - lampRadius - 10, (lampRadius + 10) * 2, (lampRadius + 10) * 2);

                    // Mid Glow
                    g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 95));
                    g.fillOval(x - lampRadius - 4, ly - lampRadius - 4, (lampRadius + 4) * 2, (lampRadius + 4) * 2);

                    // Saturated Core Lens
                    GradientPaint lensGrad = new GradientPaint(x - lampRadius, ly - lampRadius, Color.WHITE,
                                                               x + lampRadius, ly + lampRadius, glow);
                    g.setPaint(lensGrad);
                    g.fillOval(x - lampRadius, ly - lampRadius, lampRadius * 2, lampRadius * 2);

                    // Crisp Specular Dot
                    g.setColor(new Color(255, 255, 255, 230));
                    g.fillOval(x - 3, ly - 4, 3, 3);

                } else {
                    // Unlit glass lens with subtle dark rim
                    g.setColor(unlitColors[k]);
                    g.fillOval(x - lampRadius, ly - lampRadius, lampRadius * 2, lampRadius * 2);

                    g.setColor(new Color(255, 255, 255, 20));
                    g.drawOval(x - lampRadius, ly - lampRadius, lampRadius * 2, lampRadius * 2);
                }
            }

            // Direction Name Tag Pill below fixture
            g.setColor(new Color(15, 23, 42, 220));
            g.fillRoundRect(x - 11, y + boxH / 2 + 8, 22, 15, 5, 5);
            g.setColor(new Color(255, 255, 255, 60));
            g.drawRoundRect(x - 11, y + boxH / 2 + 8, 22, 15, 5, 5);

            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 10));
            FontMetrics fm = g.getFontMetrics();
            String label = roadName.substring(0, 1);
            g.drawString(label, x - fm.stringWidth(label) / 2, y + boxH / 2 + 19);

            g.dispose();
        }
    }

    // --- Main Entry Point ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            SmartTrafficLight app = new SmartTrafficLight();
            app.setVisible(true);
        });
    }
}
