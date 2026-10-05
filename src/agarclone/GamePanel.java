package agarclone;

import javax.swing.JPanel;
import javax.swing.Timer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GamePanel extends JPanel implements ActionListener, MouseMotionListener, KeyListener {

    // Fallback-Werte, bevor das Fenster zum ersten Mal sichtbar/gemessen wurde
    private static final int DEFAULT_VIEWPORT_WIDTH = 1280;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 800;

    // Die komplette Spielwelt ist deutlich größer als der sichtbare Ausschnitt.
    // Die Kamera folgt dem Spieler darin (siehe updateCamera()).
    private static final int WORLD_WIDTH = 5000;
    private static final int WORLD_HEIGHT = 3600;

    private static final int FOOD_COUNT = 600;
    private static final int ENEMY_COUNT = 16;

    // ---- Bot-KI (Schwer/Extrem) ----
    private static final double AI_DETECTION_RADIUS =650; // ab wann ein Bot überhaupt auf den Spieler reagiert
    private static final int ENEMY_SPLIT_CAP = 40;          // Obergrenze für Bot-Zellen im Extrem-Modus
    private static final double ENEMY_SPLIT_RANGE = 250;    // Beute muss so nah sein, damit der Bot splittet
    private static final int ENEMY_SPLIT_COOLDOWN_TICKS = 300; // ~5 Sekunden, bevor ein Bot erneut splittet
    private static final int MAX_BOT_CELLS = 8;              // wie viele Teile ein einzelner Bot maximal haben darf

    private static final double PLAYER_START_RADIUS = 20;
    private static final double FOOD_RADIUS = 4;

    // ---- Split-Mechanik ----
    private static final double MIN_SPLIT_RADIUS = 30;
    private static final int MAX_PLAYER_CELLS = 16;
    private static final double SPLIT_BOOST_SPEED = 16;
    private static final double BOOST_FRICTION = 0.90;
    private static final int MERGE_COOLDOWN_TICKS = 300;

    // ---- Feed-/Ausstoß-Mechanik (Taste W) ----
    private static final double EJECT_MIN_RADIUS = 35;
    private static final double EJECT_PELLET_RADIUS = 9;
    private static final double EJECT_MASS_LOSS = EJECT_PELLET_RADIUS * EJECT_PELLET_RADIUS * 4;
    private static final double EJECT_SPEED = 14;
    private static final double EJECT_FRICTION = 0.92;
    private static final int EJECT_OWNER_IMMUNE_TICKS = 10;

    // ---- Viren / Stachelkugeln ----
    private static final int VIRUS_COUNT = 14;
    private static final double VIRUS_RADIUS = 55;
    private static final double VIRUS_POP_THRESHOLD = 1.3; // muss min. 30% größer als der Virus sein
    private static final int VIRUS_POP_PIECES = 6;          // in so viele Teile zerspringt eine Zelle
    private static final double VIRUS_POP_BOOST_SPEED = 10;

    // Füttern mit ausgestoßener Masse (W): wie im Original wächst ein Virus mit
    // jeder Fütterung und teilt sich nach einer bestimmten Anzahl in zwei Viren.
    private static final int VIRUS_FEED_THRESHOLD = 7;       // so oft füttern bis zur Teilung
    private static final double VIRUS_GROWTH_PER_FEED = 6;   // Wachstum pro Fütterung
    private static final double VIRUS_SPLIT_BOOST_SPEED = 18;
    private static final double VIRUS_BOOST_FRICTION = 0.95;
    private static final int VIRUS_MAX_COUNT = 40;           // Obergrenze gegen unendliches Vermehren

    // ---- Kamera-Zoom ----
    // Linear statt proportional zur Größe: bei kleiner/mittlerer Größe bleibt der
    // Zoom fast bei 1.0, erst ab deutlich größeren Zellen zoomt die Kamera merklich raus.
    private static final double MIN_ZOOM = 0.5;             // Untergrenze allein durch Masse
    private static final double ABSOLUTE_MIN_ZOOM = 0.3;    // Untergrenze, auch bei starker Aufteilung
    private static final double MAX_ZOOM = 1.0;
    private static final double ZOOM_FLOOR_RADIUS = 700;    // ab dieser Gesamtgröße ist MIN_ZOOM erreicht
    private static final double ZOOM_SMOOTHING = 0.06;      // wie schnell der Zoom sich anpasst

    private final Random random = new Random();

    private final List<Cell> playerCells = new ArrayList<>();
    private final List<Bot> bots = new ArrayList<>();
    private final List<Food> foods = new ArrayList<>();
    private final List<EjectedMass> ejectedMasses = new ArrayList<>();
    private final List<Virus> viruses = new ArrayList<>();

    // Mausposition relativ zum sichtbaren Fenster (Bildschirmkoordinaten)
    private double mouseX = DEFAULT_VIEWPORT_WIDTH / 2.0;
    private double mouseY = DEFAULT_VIEWPORT_HEIGHT / 2.0;

    // Position der Kamera in der Spielwelt (oben-links des sichtbaren Ausschnitts)
    private double cameraX = 0;
    private double cameraY = 0;

    // Aktueller Zoomfaktor: 1.0 = normal, < 1.0 = rausgezoomt
    private double zoom = 1.0;

    private int score = 0;
    private boolean gameOver = false;

    private final Timer timer;
    private final Difficulty difficulty;
    private final GameFrame frame;

    public GamePanel(Difficulty difficulty, GameFrame frame) {
        this.difficulty = difficulty;
        this.frame = frame;

        setBackground(new Color(235, 235, 235));
        setFocusable(true);

        addMouseMotionListener(this);
        addKeyListener(this);

        initGame();

        // Spielschleife: ca. 60 Bilder pro Sekunde. Swing-JPanels sind bereits
        // double-buffered, daher kein Flackern trotz häufigem repaint().
        timer = new Timer(16, this);
        timer.start();
    }

    private void initGame() {
        playerCells.clear();
        bots.clear();
        foods.clear();
        ejectedMasses.clear();
        viruses.clear();
        score = 0;
        gameOver = false;
        zoom = 1.0;

        Cell start = new Cell(WORLD_WIDTH / 2.0, WORLD_HEIGHT / 2.0, PLAYER_START_RADIUS,
                new Color(30, 144, 255), "Du");
        playerCells.add(start);

        cameraX = start.x - viewportWidth() / 2.0;
        cameraY = start.y - viewportHeight() / 2.0;

        for (int i = 0; i < ENEMY_COUNT; i++) {
            spawnEnemy();
        }
        for (int i = 0; i < FOOD_COUNT; i++) {
            spawnFood();
        }
        for (int i = 0; i < VIRUS_COUNT; i++) {
            spawnVirus();
        }
    }

    private void spawnFood() {
        double x = FOOD_RADIUS + random.nextDouble() * (WORLD_WIDTH - 2 * FOOD_RADIUS);
        double y = FOOD_RADIUS + random.nextDouble() * (WORLD_HEIGHT - 2 * FOOD_RADIUS);
        Color color = new Color(random.nextInt(200) + 30, random.nextInt(200) + 30, random.nextInt(200) + 30);
        foods.add(new Food(x, y, FOOD_RADIUS, color));
    }

    private void spawnEnemy() {
        double radius = 10 + random.nextDouble() * 20; // 10 - 30
        double x = radius + random.nextDouble() * (WORLD_WIDTH - 2 * radius);
        double y = radius + random.nextDouble() * (WORLD_HEIGHT - 2 * radius);
        Color color = new Color(random.nextInt(150) + 80, random.nextInt(100), random.nextInt(100));
        Cell cell = new Cell(x, y, radius, color, "Bot");

        Bot bot = new Bot(cell);
        pickNewWanderDirection(bot);
        bots.add(bot);
    }

    /** Alle Zellen aller Bots als eine flache Liste - praktisch für Kollisionen/Rendering. */
    private List<Cell> allEnemyCells() {
        List<Cell> all = new ArrayList<>();
        for (Bot bot : bots) {
            all.addAll(bot.cells);
        }
        return all;
    }

    /** Findet den Bot, zu dem eine bestimmte Gegnerzelle gehört. */
    private Bot findOwnerBot(Cell cell) {
        for (Bot bot : bots) {
            if (bot.cells.contains(cell)) {
                return bot;
            }
        }
        return null;
    }

    /** Entfernt gefressene/verschwundene Gegnerzellen aus ihren Bots und ersetzt
     *  komplett ausgelöschte Bots durch einen frischen. */
    private void removeEnemyCellsAndRespawn(Set<Cell> cellsToRemove) {
        if (cellsToRemove.isEmpty()) {
            return;
        }

        int removedBots = 0;
        Iterator<Bot> botIt = bots.iterator();
        while (botIt.hasNext()) {
            Bot bot = botIt.next();
            bot.cells.removeAll(cellsToRemove);
            if (bot.cells.isEmpty()) {
                botIt.remove();
                removedBots++;
            }
        }

        for (int i = 0; i < removedBots; i++) {
            spawnEnemy();
        }
    }

    private void spawnVirus() {
        double x = VIRUS_RADIUS + random.nextDouble() * (WORLD_WIDTH - 2 * VIRUS_RADIUS);
        double y = VIRUS_RADIUS + random.nextDouble() * (WORLD_HEIGHT - 2 * VIRUS_RADIUS);
        viruses.add(new Virus(x, y, VIRUS_RADIUS));
    }

    private void pickNewWanderDirection(Bot bot) {
        double angle = random.nextDouble() * Math.PI * 2;
        bot.wanderDx = Math.cos(angle);
        bot.wanderDy = Math.sin(angle);
    }

    /** Setzt die Bewegungsrichtung aller Zellen eines Bots auf denselben Wert. */
    private void applyDirectionToBot(Bot bot, double dx, double dy) {
        for (Cell c : bot.cells) {
            c.dx = dx;
            c.dy = dy;
        }
    }

    /** Aktuelle sichtbare Breite - im Vollbild die Bildschirmbreite. */
    private int viewportWidth() {
        return getWidth() > 0 ? getWidth() : DEFAULT_VIEWPORT_WIDTH;
    }

    /** Aktuelle sichtbare Höhe - im Vollbild die Bildschirmhöhe. */
    private int viewportHeight() {
        return getHeight() > 0 ? getHeight() : DEFAULT_VIEWPORT_HEIGHT;
    }

    // ---------- Spielschleife ----------

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!gameOver) {
            updateGame();
        }
        repaint();
    }

    private void updateGame() {
        movePlayerCells();
        moveEnemies();
        updateEjectedMasses();
        updateViruses();
        handleFoodCollisions();
        handleEjectedMassCollisions();
        handlePlayerEnemyCollisions();
        handleEnemyEnemyCollisions();
        handleVirusCollisions();
        updateCamera();
    }

    private void movePlayerCells() {
        double worldMouseX = cameraX + mouseX / zoom;
        double worldMouseY = cameraY + mouseY / zoom;

        for (Cell pc : playerCells) {
            double dx = worldMouseX - pc.x;
            double dy = worldMouseY - pc.y;
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist > 1) {
                double speed = pc.getSpeed();
                pc.x += (dx / dist) * speed;
                pc.y += (dy / dist) * speed;
            }

            // Schwung vom Splitten/Platzen anwenden, klingt mit der Zeit ab
            pc.x += pc.boostX;
            pc.y += pc.boostY;
            pc.boostX *= BOOST_FRICTION;
            pc.boostY *= BOOST_FRICTION;
            if (Math.abs(pc.boostX) < 0.05) pc.boostX = 0;
            if (Math.abs(pc.boostY) < 0.05) pc.boostY = 0;

            if (pc.mergeCooldown > 0) {
                pc.mergeCooldown--;
            }

            keepInWorldBounds(pc);
        }

        separatePlayerCells();
        mergePlayerCells();
    }

    private void separatePlayerCells() {
        for (int i = 0; i < playerCells.size(); i++) {
            for (int j = i + 1; j < playerCells.size(); j++) {
                Cell a = playerCells.get(i);
                Cell b = playerCells.get(j);

                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double minDist = (a.radius + b.radius) * 0.85;

                if (dist > 0 && dist < minDist && (a.mergeCooldown > 0 || b.mergeCooldown > 0)) {
                    double overlap = (minDist - dist) / 2.0;
                    double nx = dx / dist;
                    double ny = dy / dist;
                    a.x -= nx * overlap;
                    a.y -= ny * overlap;
                    b.x += nx * overlap;
                    b.y += ny * overlap;
                }
            }
        }
    }

    private void mergePlayerCells() {
        boolean mergedAny = true;
        while (mergedAny) {
            mergedAny = false;
            outer:
            for (int i = 0; i < playerCells.size(); i++) {
                for (int j = i + 1; j < playerCells.size(); j++) {
                    Cell a = playerCells.get(i);
                    Cell b = playerCells.get(j);

                    if (a.mergeCooldown > 0 || b.mergeCooldown > 0) {
                        continue;
                    }

                    double dx = b.x - a.x;
                    double dy = b.y - a.y;
                    double dist = Math.sqrt(dx * dx + dy * dy);

                    if (dist < Math.max(a.radius, b.radius) * 0.5) {
                        double massA = a.getMass();
                        double massB = b.getMass();
                        double newMass = massA + massB;

                        a.x = (a.x * massA + b.x * massB) / newMass;
                        a.y = (a.y * massA + b.y * massB) / newMass;
                        a.radius = Math.sqrt(newMass);

                        playerCells.remove(j);
                        mergedAny = true;
                        break outer;
                    }
                }
            }
        }
    }

    private void moveEnemies() {
        for (Bot bot : bots) {
            updateBotDirection(bot);

            for (Cell c : bot.cells) {
                double speed = c.getSpeed();
                c.x += c.dx * speed;
                c.y += c.dy * speed;

                // Schwung vom Splitten (nur im Extrem-Modus relevant), klingt ab
                c.x += c.boostX;
                c.y += c.boostY;
                c.boostX *= BOOST_FRICTION;
                c.boostY *= BOOST_FRICTION;
                if (Math.abs(c.boostX) < 0.05) c.boostX = 0;
                if (Math.abs(c.boostY) < 0.05) c.boostY = 0;

                if (c.mergeCooldown > 0) {
                    c.mergeCooldown--;
                }
                if (c.aiActionCooldown > 0) {
                    c.aiActionCooldown--;
                }

                if (c.x - c.radius < 0 || c.x + c.radius > WORLD_WIDTH) {
                    c.dx *= -1;
                }
                if (c.y - c.radius < 0 || c.y + c.radius > WORLD_HEIGHT) {
                    c.dy *= -1;
                }
                keepInWorldBounds(c);
            }

            separateBotCells(bot);
            mergeBotCells(bot);
        }

        if (difficulty == Difficulty.EXTREME) {
            tryEnemySplits();
        }
    }

    /** Hält die eigenen Zellen eines Bots auseinander, solange sie nicht verschmelzen dürfen. */
    private void separateBotCells(Bot bot) {
        List<Cell> cells = bot.cells;
        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                Cell a = cells.get(i);
                Cell b = cells.get(j);

                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double minDist = (a.radius + b.radius) * 0.85;

                if (dist > 0 && dist < minDist && (a.mergeCooldown > 0 || b.mergeCooldown > 0)) {
                    double overlap = (minDist - dist) / 2.0;
                    double nx = dx / dist;
                    double ny = dy / dist;
                    a.x -= nx * overlap;
                    a.y -= ny * overlap;
                    b.x += nx * overlap;
                    b.y += ny * overlap;
                }
            }
        }
    }

    /** Lässt gesplittete Zellen desselben Bots wieder verschmelzen, sobald die Abklingzeit um ist. */
    private void mergeBotCells(Bot bot) {
        List<Cell> cells = bot.cells;
        boolean mergedAny = true;
        while (mergedAny) {
            mergedAny = false;
            outer:
            for (int i = 0; i < cells.size(); i++) {
                for (int j = i + 1; j < cells.size(); j++) {
                    Cell a = cells.get(i);
                    Cell b = cells.get(j);

                    if (a.mergeCooldown > 0 || b.mergeCooldown > 0) {
                        continue;
                    }

                    double dx = b.x - a.x;
                    double dy = b.y - a.y;
                    double dist = Math.sqrt(dx * dx + dy * dy);

                    if (dist < Math.max(a.radius, b.radius) * 0.5) {
                        double massA = a.getMass();
                        double massB = b.getMass();
                        double newMass = massA + massB;

                        a.x = (a.x * massA + b.x * massB) / newMass;
                        a.y = (a.y * massA + b.y * massB) / newMass;
                        a.radius = Math.sqrt(newMass);

                        cells.remove(j);
                        mergedAny = true;
                        break outer;
                    }
                }
            }
        }
    }

    /** Entscheidet die gemeinsame Bewegungsrichtung eines ganzen Bots (alle seine Zellen ziehen am selben Strang). */
    private void updateBotDirection(Bot bot) {
        if (difficulty == Difficulty.EASY) {
            if (random.nextInt(90) == 0) {
                pickNewWanderDirection(bot);
            }
            applyDirectionToBot(bot, bot.wanderDx, bot.wanderDy);
            return;
        }

        double avgX = bot.getAvgX();
        double avgY = bot.getAvgY();

        // Schwer/Extrem: nächstgelegene Spielerzelle in Reichweite suchen
        Cell nearestPlayerCell = null;
        double nearestDist = AI_DETECTION_RADIUS;

        for (Cell pc : playerCells) {
            double dx = pc.x - avgX;
            double dy = pc.y - avgY;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestPlayerCell = pc;
            }
        }

        if (nearestPlayerCell == null) {
            // Kein Spieler in der Nähe -> normales Zufallsverhalten
            if (random.nextInt(90) == 0) {
                pickNewWanderDirection(bot);
            }
            applyDirectionToBot(bot, bot.wanderDx, bot.wanderDy);
            return;
        }

        double dx = nearestPlayerCell.x - avgX;
        double dy = nearestPlayerCell.y - avgY;
        double dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        double botRadius = Math.sqrt(bot.getTotalMass()); // Gesamtgröße des Bots als Vergleichswert

        if (nearestPlayerCell.radius > botRadius * 1.15) {
            // Spieler ist deutlich größer -> fliehen
            applyDirectionToBot(bot, -dx / dist, -dy / dist);
        } else if (botRadius > nearestPlayerCell.radius * 1.15) {
            // Spieler ist deutlich kleiner -> jagen
            applyDirectionToBot(bot, dx / dist, dy / dist);
        } else if (random.nextInt(90) == 0) {
            // Zu ähnliche Größe, riskant -> lieber normal weiterwandern
            pickNewWanderDirection(bot);
            applyDirectionToBot(bot, bot.wanderDx, bot.wanderDy);
        }
    }

    /** Lässt Bots im Extrem-Modus beim Jagen gelegentlich eine Zelle splitten - sie bleibt
     *  dabei Teil desselben Bots, statt ein eigenständiger neuer Bot zu werden. */
    private void tryEnemySplits() {
        for (Bot bot : bots) {
            List<Cell> snapshot = new ArrayList<>(bot.cells);

            for (Cell c : snapshot) {
                if (totalEnemyCellCount() >= ENEMY_SPLIT_CAP || bot.cells.size() >= MAX_BOT_CELLS) {
                    break;
                }
                if (c.aiActionCooldown > 0 || c.radius < MIN_SPLIT_RADIUS) {
                    continue;
                }

                Cell nearestPlayerCell = null;
                double nearestDist = AI_DETECTION_RADIUS;
                for (Cell pc : playerCells) {
                    double dx = pc.x - c.x;
                    double dy = pc.y - c.y;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearestPlayerCell = pc;
                    }
                }

                // Nur splitten, wenn gerade aktiv eine kleinere, nahe Spielerzelle gejagt wird
                if (nearestPlayerCell == null || !(c.radius > nearestPlayerCell.radius * 1.15)) {
                    continue;
                }
                if (nearestDist > ENEMY_SPLIT_RANGE) {
                    continue;
                }
                // nicht bei jeder Gelegenheit sofort splitten, wirkt sonst zu aggressiv
                if (random.nextInt(40) != 0) {
                    continue;
                }

                double dx = nearestPlayerCell.x - c.x;
                double dy = nearestPlayerCell.y - c.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double dirX = dist > 1 ? dx / dist : 1;
                double dirY = dist > 1 ? dy / dist : 0;

                double newMass = c.getMass() / 2.0;
                double newRadius = Math.sqrt(newMass);

                c.radius = newRadius;
                c.mergeCooldown = MERGE_COOLDOWN_TICKS;
                c.aiActionCooldown = ENEMY_SPLIT_COOLDOWN_TICKS;

                Cell splitPart = new Cell(c.x + dirX * c.radius, c.y + dirY * c.radius,
                        newRadius, c.color, c.name);
                splitPart.boostX = dirX * SPLIT_BOOST_SPEED;
                splitPart.boostY = dirY * SPLIT_BOOST_SPEED;
                splitPart.mergeCooldown = MERGE_COOLDOWN_TICKS;
                splitPart.aiActionCooldown = ENEMY_SPLIT_COOLDOWN_TICKS;

                bot.cells.add(splitPart);
            }
        }
    }

    private int totalEnemyCellCount() {
        int total = 0;
        for (Bot bot : bots) {
            total += bot.cells.size();
        }
        return total;
    }

    /** Bewegt Viren, die gerade durch eine Teilung Schwung bekommen haben. Normale,
     *  ungefütterte Viren stehen wie im Original einfach still (vx/vy bleiben 0). */
    private void updateViruses() {
        for (Virus v : viruses) {
            if (v.vx == 0 && v.vy == 0) {
                continue;
            }

            v.x += v.vx;
            v.y += v.vy;
            v.vx *= VIRUS_BOOST_FRICTION;
            v.vy *= VIRUS_BOOST_FRICTION;
            if (Math.abs(v.vx) < 0.05) v.vx = 0;
            if (Math.abs(v.vy) < 0.05) v.vy = 0;

            v.x = Math.max(v.radius, Math.min(WORLD_WIDTH - v.radius, v.x));
            v.y = Math.max(v.radius, Math.min(WORLD_HEIGHT - v.radius, v.y));
        }
    }

    private void keepInWorldBounds(Cell cell) {
        cell.x = Math.max(cell.radius, Math.min(WORLD_WIDTH - cell.radius, cell.x));
        cell.y = Math.max(cell.radius, Math.min(WORLD_HEIGHT - cell.radius, cell.y));
    }

    private void handleFoodCollisions() {
        List<Cell> allCells = allEnemyCells();
        allCells.addAll(playerCells);

        Set<Cell> playerCellSet = new HashSet<>(playerCells);

        int foodToRespawn = 0;

        for (Cell cell : allCells) {
            Iterator<Food> it = foods.iterator();
            while (it.hasNext()) {
                Food food = it.next();
                double dx = cell.x - food.x;
                double dy = cell.y - food.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < cell.radius) {
                    double newMass = cell.getMass() + food.radius * food.radius * 4;
                    cell.radius = Math.sqrt(newMass);

                    it.remove();
                    foodToRespawn++;

                    if (playerCellSet.contains(cell)) {
                        score += 10;
                    }
                }
            }
        }

        for (int i = 0; i < foodToRespawn; i++) {
            spawnFood();
        }
    }

    private void handlePlayerEnemyCollisions() {
        List<Cell> playerSnapshot = new ArrayList<>(playerCells);
        List<Cell> enemySnapshot = allEnemyCells();

        Set<Cell> eatenEnemyCells = new HashSet<>();
        Set<Cell> eatenPlayerCells = new HashSet<>();

        for (Cell pc : playerSnapshot) {
            for (Cell enemyCell : enemySnapshot) {
                if (eatenEnemyCells.contains(enemyCell) || eatenPlayerCells.contains(pc)) {
                    continue;
                }

                double dx = pc.x - enemyCell.x;
                double dy = pc.y - enemyCell.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < Math.max(pc.radius, enemyCell.radius)) {
                    if (pc.radius > enemyCell.radius * 1.15) {
                        double newMass = pc.getMass() + enemyCell.getMass() * 0.7;
                        pc.radius = Math.sqrt(newMass);
                        score += 50;
                        eatenEnemyCells.add(enemyCell);
                    } else if (enemyCell.radius > pc.radius * 1.15) {
                        double newMass = enemyCell.getMass() + pc.getMass() * 0.5;
                        enemyCell.radius = Math.sqrt(newMass);
                        eatenPlayerCells.add(pc);
                    }
                }
            }
        }

        // Gefressene Gegnerzellen aus ihren jeweiligen Bots entfernen; nur ein
        // komplett ausgelöschter Bot wird durch einen neuen ersetzt
        removeEnemyCellsAndRespawn(eatenEnemyCells);

        playerCells.removeAll(eatenPlayerCells);

        if (playerCells.isEmpty()) {
            gameOver = true;
        }
    }

    /** Bots fressen auch Zellen anderer Bots, wenn einer deutlich größer als der andere ist.
     *  Zellen desselben Bots fressen sich nie gegenseitig. */
    private void handleEnemyEnemyCollisions() {
        List<Cell> snapshot = allEnemyCells();
        Set<Cell> eaten = new HashSet<>();

        for (int i = 0; i < snapshot.size(); i++) {
            Cell a = snapshot.get(i);
            if (eaten.contains(a)) {
                continue;
            }
            Bot botA = findOwnerBot(a);

            for (int j = i + 1; j < snapshot.size(); j++) {
                Cell b = snapshot.get(j);
                if (eaten.contains(b)) {
                    continue;
                }

                Bot botB = findOwnerBot(b);
                if (botA == botB) {
                    continue; // eigene Zellen desselben Bots fressen sich nicht
                }

                double dx = a.x - b.x;
                double dy = a.y - b.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < Math.max(a.radius, b.radius)) {
                    if (a.radius > b.radius * 1.15) {
                        double newMass = a.getMass() + b.getMass() * 0.7;
                        a.radius = Math.sqrt(newMass);
                        eaten.add(b);
                    } else if (b.radius > a.radius * 1.15) {
                        double newMass = b.getMass() + a.getMass() * 0.7;
                        b.radius = Math.sqrt(newMass);
                        eaten.add(a);
                        break; // a ist jetzt weg, weitere Vergleiche für a sind unnötig
                    }
                }
            }
        }

        removeEnemyCellsAndRespawn(eaten);
    }

    private void handleVirusCollisions() {
        // Spielerzellen, die einen Virus berühren und deutlich größer sind, platzen
        List<Cell> playerSnapshot = new ArrayList<>(playerCells);
        Set<Virus> poppedByPlayer = new HashSet<>();
        List<Cell> cellsToPop = new ArrayList<>();

        for (Cell pc : playerSnapshot) {
            for (Virus v : viruses) {
                if (poppedByPlayer.contains(v)) {
                    continue;
                }
                double dx = pc.x - v.x;
                double dy = pc.y - v.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < pc.radius && pc.radius > v.radius * VIRUS_POP_THRESHOLD) {
                    cellsToPop.add(pc);
                    poppedByPlayer.add(v);
                    break;
                }
            }
        }

        viruses.removeAll(poppedByPlayer);
        for (int i = 0; i < poppedByPlayer.size(); i++) {
            spawnVirus();
        }
        for (Cell pc : cellsToPop) {
            popCell(pc);
        }

        // KI-Gegner, die deutlich größer als ein Virus sind, verlieren einen Großteil ihrer Masse
        Set<Virus> poppedByEnemy = new HashSet<>();
        for (Cell enemy : allEnemyCells()) {
            for (Virus v : viruses) {
                if (poppedByEnemy.contains(v)) {
                    continue;
                }
                double dx = enemy.x - v.x;
                double dy = enemy.y - v.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < enemy.radius && enemy.radius > v.radius * VIRUS_POP_THRESHOLD) {
                    enemy.radius = Math.sqrt(enemy.getMass() * 0.4);
                    poppedByEnemy.add(v);
                    break;
                }
            }
        }
        viruses.removeAll(poppedByEnemy);
        for (int i = 0; i < poppedByEnemy.size(); i++) {
            spawnVirus();
        }
    }

    /** Lässt eine Spielerzelle wie im Original in mehrere kleine Teile zerplatzen. */
    private void popCell(Cell pc) {
        int availableSlots = MAX_PLAYER_CELLS - playerCells.size() + 1; // +1: pc zählt schon mit und wird wiederverwendet
        int pieces = Math.min(VIRUS_POP_PIECES, Math.max(1, availableSlots));

        if (pieces <= 1) {
            return; // kein Platz mehr für weitere Teile
        }

        double massPerPiece = pc.getMass() / pieces;
        double radiusPerPiece = Math.sqrt(massPerPiece);

        double originalX = pc.x;
        double originalY = pc.y;
        Color color = pc.color;
        String name = pc.name;

        double firstAngle = random.nextDouble() * Math.PI * 2;

        pc.radius = radiusPerPiece;
        pc.mergeCooldown = MERGE_COOLDOWN_TICKS;
        pc.boostX = Math.cos(firstAngle) * VIRUS_POP_BOOST_SPEED;
        pc.boostY = Math.sin(firstAngle) * VIRUS_POP_BOOST_SPEED;

        for (int i = 1; i < pieces; i++) {
            double angle = firstAngle + (Math.PI * 2 * i / pieces);
            Cell piece = new Cell(originalX, originalY, radiusPerPiece, color, name);
            piece.boostX = Math.cos(angle) * VIRUS_POP_BOOST_SPEED;
            piece.boostY = Math.sin(angle) * VIRUS_POP_BOOST_SPEED;
            piece.mergeCooldown = MERGE_COOLDOWN_TICKS;
            playerCells.add(piece);
        }
    }

    /** Berechnet, wie weit die Kamera aktuell rauszoomen sollte. */
    private double computeZoom() {
        double totalMass = 0;
        for (Cell pc : playerCells) {
            totalMass += pc.getMass();
        }
        double totalRadius = Math.sqrt(totalMass);

        // Linear statt proportional: bei kleiner Größe bleibt der Zoom nahe 1.0,
        // erst Richtung ZOOM_FLOOR_RADIUS wird spürbar rausgezoomt.
        double zoomRange = MAX_ZOOM - MIN_ZOOM;
        double radiusRange = ZOOM_FLOOR_RADIUS - PLAYER_START_RADIUS;
        double massZoom = MAX_ZOOM - (totalRadius - PLAYER_START_RADIUS) * (zoomRange / radiusRange);
        massZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, massZoom));

        // Wenn die Zellen weit auseinander gesplittet sind, zusätzlich rauszoomen,
        // damit alle eigenen Zellen im Blick bleiben.
        double spreadZoom = MAX_ZOOM;
        if (playerCells.size() > 1) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Cell pc : playerCells) {
                minX = Math.min(minX, pc.x - pc.radius);
                maxX = Math.max(maxX, pc.x + pc.radius);
                minY = Math.min(minY, pc.y - pc.radius);
                maxY = Math.max(maxY, pc.y + pc.radius);
            }
            double spreadWidth = Math.max(1, maxX - minX);
            double spreadHeight = Math.max(1, maxY - minY);

            double zoomX = (viewportWidth() * 0.85) / spreadWidth;
            double zoomY = (viewportHeight() * 0.85) / spreadHeight;
            spreadZoom = Math.min(zoomX, zoomY);
        }

        double finalZoom = Math.min(massZoom, spreadZoom);
        return Math.max(ABSOLUTE_MIN_ZOOM, Math.min(MAX_ZOOM, finalZoom));
    }

    private void updateCamera() {
        if (playerCells.isEmpty()) {
            return;
        }

        double avgX = 0;
        double avgY = 0;
        for (Cell pc : playerCells) {
            avgX += pc.x;
            avgY += pc.y;
        }
        avgX /= playerCells.size();
        avgY /= playerCells.size();

        double targetZoom = computeZoom();
        zoom += (targetZoom - zoom) * ZOOM_SMOOTHING; // sanft anpassen statt zu springen

        double halfViewWorldWidth = viewportWidth() / (2.0 * zoom);
        double halfViewWorldHeight = viewportHeight() / (2.0 * zoom);

        cameraX = avgX - halfViewWorldWidth;
        cameraY = avgY - halfViewWorldHeight;

        // Kamera darf nicht über den Rand der Welt hinausschauen
        cameraX = Math.max(0, Math.min(WORLD_WIDTH - viewportWidth() / zoom, cameraX));
        cameraY = Math.max(0, Math.min(WORLD_HEIGHT - viewportHeight() / zoom, cameraY));
    }

    // ---------- Aktionen ----------

    private void trySplit() {
        if (gameOver) {
            return;
        }
        if (playerCells.size() >= MAX_PLAYER_CELLS) {
            return;
        }

        double worldMouseX = cameraX + mouseX / zoom;
        double worldMouseY = cameraY + mouseY / zoom;

        List<Cell> snapshot = new ArrayList<>(playerCells);
        List<Cell> newCells = new ArrayList<>();

        for (Cell pc : snapshot) {
            if (playerCells.size() + newCells.size() >= MAX_PLAYER_CELLS) {
                break;
            }
            if (pc.radius < MIN_SPLIT_RADIUS) {
                continue;
            }

            double newMass = pc.getMass() / 2.0;
            double newRadius = Math.sqrt(newMass);

            double dx = worldMouseX - pc.x;
            double dy = worldMouseY - pc.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            double dirX = dist > 1 ? dx / dist : 1;
            double dirY = dist > 1 ? dy / dist : 0;

            pc.radius = newRadius;
            pc.mergeCooldown = MERGE_COOLDOWN_TICKS;

            Cell splitPart = new Cell(pc.x + dirX * pc.radius, pc.y + dirY * pc.radius,
                    newRadius, pc.color, pc.name);
            splitPart.boostX = dirX * SPLIT_BOOST_SPEED;
            splitPart.boostY = dirY * SPLIT_BOOST_SPEED;
            splitPart.mergeCooldown = MERGE_COOLDOWN_TICKS;

            newCells.add(splitPart);
        }

        playerCells.addAll(newCells);
    }

    private void tryEject() {
        if (gameOver) {
            return;
        }

        double worldMouseX = cameraX + mouseX / zoom;
        double worldMouseY = cameraY + mouseY / zoom;

        List<Cell> snapshot = new ArrayList<>(playerCells);

        for (Cell pc : snapshot) {
            if (pc.radius < EJECT_MIN_RADIUS) {
                continue;
            }

            double dx = worldMouseX - pc.x;
            double dy = worldMouseY - pc.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            double dirX = dist > 1 ? dx / dist : 1;
            double dirY = dist > 1 ? dy / dist : 0;

            double newMass = Math.max(pc.getMass() - EJECT_MASS_LOSS, EJECT_MIN_RADIUS * EJECT_MIN_RADIUS * 0.3);
            pc.radius = Math.sqrt(newMass);

            double spawnX = pc.x + dirX * (pc.radius + EJECT_PELLET_RADIUS);
            double spawnY = pc.y + dirY * (pc.radius + EJECT_PELLET_RADIUS);

            EjectedMass pellet = new EjectedMass(spawnX, spawnY, EJECT_PELLET_RADIUS, pc.color,
                    dirX * EJECT_SPEED, dirY * EJECT_SPEED, EJECT_OWNER_IMMUNE_TICKS);
            ejectedMasses.add(pellet);
        }
    }

    private void updateEjectedMasses() {
        for (EjectedMass em : ejectedMasses) {
            em.x += em.vx;
            em.y += em.vy;
            em.vx *= EJECT_FRICTION;
            em.vy *= EJECT_FRICTION;
            if (Math.abs(em.vx) < 0.05) em.vx = 0;
            if (Math.abs(em.vy) < 0.05) em.vy = 0;

            if (em.x - em.radius < 0) {
                em.x = em.radius;
                em.vx *= -1;
            }
            if (em.x + em.radius > WORLD_WIDTH) {
                em.x = WORLD_WIDTH - em.radius;
                em.vx *= -1;
            }
            if (em.y - em.radius < 0) {
                em.y = em.radius;
                em.vy *= -1;
            }
            if (em.y + em.radius > WORLD_HEIGHT) {
                em.y = WORLD_HEIGHT - em.radius;
                em.vy *= -1;
            }

            if (em.ownerImmuneTicks > 0) {
                em.ownerImmuneTicks--;
            }
        }
    }

    private void handleEjectedMassCollisions() {
        List<Cell> allCells = allEnemyCells();
        allCells.addAll(playerCells);
        Set<Cell> playerCellSet = new HashSet<>(playerCells);

        Iterator<EjectedMass> it = ejectedMasses.iterator();
        while (it.hasNext()) {
            EjectedMass em = it.next();
            if (em.ownerImmuneTicks > 0) {
                continue;
            }

            boolean consumed = false;

            for (Cell cell : allCells) {
                double dx = cell.x - em.x;
                double dy = cell.y - em.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < cell.radius) {
                    double newMass = cell.getMass() + em.radius * em.radius * 4;
                    cell.radius = Math.sqrt(newMass);

                    if (playerCellSet.contains(cell)) {
                        score += 5;
                    }

                    it.remove();
                    consumed = true;
                    break;
                }
            }

            if (consumed) {
                continue;
            }

            // Keine Zelle getroffen - vielleicht trifft das Pellet stattdessen einen Virus (Füttern)
            for (Virus v : viruses) {
                double dx = em.x - v.x;
                double dy = em.y - v.y;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist < v.radius) {
                    feedVirus(v, em);
                    it.remove();
                    break;
                }
            }
        }
    }

    /** Füttert einen Virus mit einem Masse-Pellet; nach genug Fütterungen teilt er sich wie im Original. */
    private void feedVirus(Virus v, EjectedMass em) {
        if (viruses.size() >= VIRUS_MAX_COUNT) {
            return; // Obergrenze erreicht - Virus nimmt keine weitere Fütterung mehr an
        }

        v.feedCount++;
        v.radius += VIRUS_GROWTH_PER_FEED;

        if (v.feedCount >= VIRUS_FEED_THRESHOLD) {
            // Richtung aus dem Schwung des Pellets übernehmen - der neue Virus
            // schießt in die Richtung weg, aus der gefüttert wurde (wie im Original)
            double dirX, dirY;
            double speed = Math.sqrt(em.vx * em.vx + em.vy * em.vy);
            if (speed > 0.5) {
                dirX = em.vx / speed;
                dirY = em.vy / speed;
            } else {
                double angle = random.nextDouble() * Math.PI * 2;
                dirX = Math.cos(angle);
                dirY = Math.sin(angle);
            }

            v.feedCount = 0;
            v.radius = VIRUS_RADIUS;

            Virus newVirus = new Virus(v.x + dirX * (v.radius + 10), v.y + dirY * (v.radius + 10), VIRUS_RADIUS);
            newVirus.vx = dirX * VIRUS_SPLIT_BOOST_SPEED;
            newVirus.vy = dirY * VIRUS_SPLIT_BOOST_SPEED;
            viruses.add(newVirus);
        }
    }

    // ---------- Rendering ----------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        AffineTransform originalTransform = g2.getTransform();

        // Zoom + Kamera-Verschiebung auf alles anwenden, was zur Spielwelt gehört
        g2.scale(zoom, zoom);
        g2.translate(-cameraX, -cameraY);

        drawGrid(g2);
        drawWorldBorder(g2);

        // Sichtbaren Weltausschnitt (mit etwas Puffer) bestimmen, damit Objekte
        // außerhalb davon gar nicht erst gezeichnet werden müssen.
        double viewLeft = cameraX - 50;
        double viewRight = cameraX + viewportWidth() / zoom + 50;
        double viewTop = cameraY - 50;
        double viewBottom = cameraY + viewportHeight() / zoom + 50;

        for (Food food : foods) {
            if (food.x < viewLeft || food.x > viewRight || food.y < viewTop || food.y > viewBottom) {
                continue;
            }
            g2.setColor(food.color);
            g2.fill(new Ellipse2D.Double(food.x - food.radius, food.y - food.radius,
                    food.radius * 2, food.radius * 2));
        }

        for (EjectedMass em : ejectedMasses) {
            if (em.x < viewLeft || em.x > viewRight || em.y < viewTop || em.y > viewBottom) {
                continue;
            }
            g2.setColor(em.color);
            Ellipse2D.Double shape = new Ellipse2D.Double(em.x - em.radius, em.y - em.radius,
                    em.radius * 2, em.radius * 2);
            g2.fill(shape);
            g2.setColor(em.color.darker());
            g2.draw(shape);
        }

        for (Virus v : viruses) {
            if (v.x < viewLeft || v.x > viewRight || v.y < viewTop || v.y > viewBottom) {
                continue;
            }
            drawVirus(g2, v);
        }

        for (Cell enemy : allEnemyCells()) {
            if (enemy.x < viewLeft || enemy.x > viewRight || enemy.y < viewTop || enemy.y > viewBottom) {
                continue;
            }
            drawCell(g2, enemy);
        }
        for (Cell pc : playerCells) {
            drawCell(g2, pc); // eigene Zellen immer zeichnen, auch knapp außerhalb
        }

        // Transformation zurücksetzen, damit die HUD-Anzeige fest am Bildschirm klebt
        g2.setTransform(originalTransform);

        drawHud(g2);

        if (gameOver) {
            drawGameOver(g2);
        }
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(new Color(210, 210, 210));
        g2.setStroke(new BasicStroke(1f));
        int step = 40;

        // Nur den aktuell sichtbaren Weltausschnitt zeichnen statt der gesamten
        // 5000x3600 großen Welt - das spart pro Frame sehr viele drawLine-Aufrufe.
        int viewLeft = Math.max(0, (int) Math.floor(cameraX / step) * step);
        int viewRight = Math.min(WORLD_WIDTH, (int) Math.ceil((cameraX + viewportWidth() / zoom) / step) * step);
        int viewTop = Math.max(0, (int) Math.floor(cameraY / step) * step);
        int viewBottom = Math.min(WORLD_HEIGHT, (int) Math.ceil((cameraY + viewportHeight() / zoom) / step) * step);

        for (int x = viewLeft; x <= viewRight; x += step) {
            g2.drawLine(x, viewTop, x, viewBottom);
        }
        for (int y = viewTop; y <= viewBottom; y += step) {
            g2.drawLine(viewLeft, y, viewRight, y);
        }
    }

    /** Zeichnet den Weltrand mit dicker Umrandung und diagonalen Warnstreifen wie im Original. */
    private void drawWorldBorder(Graphics2D g2) {
        int thickness = 16;
        Color base = new Color(45, 45, 60);
        Color stripe = new Color(255, 205, 60);

        g2.setColor(base);
        g2.setStroke(new BasicStroke(thickness));
        g2.drawRect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);

        // Aktuell sichtbarer Weltausschnitt - die Streifen werden NUR dort
        // gezeichnet, wo eine Kante gerade im Bild ist. Vorher wurden pro Frame
        // ca. 1800 Linien über die komplette Welt gezeichnet, egal ob sichtbar
        // oder nicht - das war der Hauptgrund für die Ruckler.
        double viewLeft = cameraX;
        double viewRight = cameraX + viewportWidth() / zoom;
        double viewTop = cameraY;
        double viewBottom = cameraY + viewportHeight() / zoom;

        Shape oldClip = g2.getClip();
        g2.setStroke(new BasicStroke(thickness / 3f));
        g2.setColor(stripe);

        int half = thickness / 2;
        int spacing = 26;
        int margin = thickness * 4;

        // oben - nur zeichnen, wenn die Kamera gerade in der Nähe der Oberkante ist
        if (viewTop < margin) {
            g2.setClip(new Rectangle(-half, -half, WORLD_WIDTH + thickness, thickness));
            int startX = Math.max(-margin, (int) (Math.floor((viewLeft - margin) / spacing) * spacing));
            int endX = Math.min(WORLD_WIDTH + margin, (int) (viewRight + margin));
            for (int x = startX; x < endX; x += spacing) {
                g2.drawLine(x, -half, x + thickness, thickness - half);
            }
        }

        // unten
        if (viewBottom > WORLD_HEIGHT - margin) {
            g2.setClip(new Rectangle(-half, WORLD_HEIGHT - half, WORLD_WIDTH + thickness, thickness));
            int startX = Math.max(-margin, (int) (Math.floor((viewLeft - margin) / spacing) * spacing));
            int endX = Math.min(WORLD_WIDTH + margin, (int) (viewRight + margin));
            for (int x = startX; x < endX; x += spacing) {
                g2.drawLine(x, WORLD_HEIGHT - half, x + thickness, WORLD_HEIGHT + thickness - half);
            }
        }

        // links
        if (viewLeft < margin) {
            g2.setClip(new Rectangle(-half, -half, thickness, WORLD_HEIGHT + thickness));
            int startY = Math.max(-margin, (int) (Math.floor((viewTop - margin) / spacing) * spacing));
            int endY = Math.min(WORLD_HEIGHT + margin, (int) (viewBottom + margin));
            for (int y = startY; y < endY; y += spacing) {
                g2.drawLine(-half, y, thickness - half, y + thickness);
            }
        }

        // rechts
        if (viewRight > WORLD_WIDTH - margin) {
            g2.setClip(new Rectangle(WORLD_WIDTH - half, -half, thickness, WORLD_HEIGHT + thickness));
            int startY = Math.max(-margin, (int) (Math.floor((viewTop - margin) / spacing) * spacing));
            int endY = Math.min(WORLD_HEIGHT + margin, (int) (viewBottom + margin));
            for (int y = startY; y < endY; y += spacing) {
                g2.drawLine(WORLD_WIDTH - half, y, WORLD_WIDTH + thickness - half, y + thickness);
            }
        }

        g2.setClip(oldClip);
        g2.setStroke(new BasicStroke(1f));
    }

    /** Zeichnet eine grüne Stachelkugel als gezacktes Vieleck, statt eines einfachen Kreises. */
    private void drawVirus(Graphics2D g2, Virus v) {
        int spikes = 18;
        double outerR = v.radius;
        double innerR = v.radius * 0.82;

        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < spikes * 2; i++) {
            double angle = Math.PI * i / spikes;
            double r = (i % 2 == 0) ? outerR : innerR;
            double px = v.x + Math.cos(angle) * r;
            double py = v.y + Math.sin(angle) * r;
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();

        g2.setColor(new Color(51, 204, 51));
        g2.fill(path);
        g2.setColor(new Color(30, 140, 30));
        g2.setStroke(new BasicStroke(2f));
        g2.draw(path);
    }

    private void drawCell(Graphics2D g2, Cell cell) {
        g2.setColor(cell.color);
        Ellipse2D.Double shape = new Ellipse2D.Double(cell.x - cell.radius, cell.y - cell.radius,
                cell.radius * 2, cell.radius * 2);
        g2.fill(shape);

        g2.setColor(cell.color.darker());
        g2.draw(shape);

        // Bei Bots immer den Namen zeigen; bei den eigenen (ggf. vielen kleinen
        // gesplitteten) Zellen erst ab einer Mindestgröße, sonst wirkt es überladen.
        if (cell.radius > 12 || !cell.name.equals("Du")) {
            g2.setColor(Color.WHITE);
            Font font = new Font("SansSerif", Font.BOLD, Math.max(10, (int) (cell.radius / 2)));
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int textWidth = fm.stringWidth(cell.name);
            g2.drawString(cell.name, (float) (cell.x - textWidth / 2.0), (float) (cell.y + fm.getAscent() / 2.0 - 2));
        }
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SansSerif", Font.BOLD, 20));
        g2.drawString("Score: " + score, 15, 30);

        double totalMass = 0;
        for (Cell pc : playerCells) {
            totalMass += pc.getMass();
        }
        int totalSize = (int) Math.sqrt(totalMass);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g2.drawString("Größe: " + totalSize + "   Zellen: " + playerCells.size()
                + "   Modus: " + difficultyLabel(), 15, 50);
        g2.drawString("Leertaste = Teilen   |   W = Masse ausstoßen", 15, 70);
        g2.drawString("Vorsicht: grüne Stachelkugeln lassen dich platzen, wenn du zu groß bist!", 15, 90);
    }

    private String difficultyLabel() {
        switch (difficulty) {
            case EASY:
                return "Leicht";
            case HARD:
                return "Schwer";
            case EXTREME:
                return "Extrem";
            default:
                return "";
        }
    }

    private void drawGameOver(Graphics2D g2) {
        int w = viewportWidth();
        int h = viewportHeight();

        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRect(0, 0, w, h);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 48));
        String msg = "GAME OVER";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2 - 20);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 22));
        String scoreMsg = "Score: " + score;
        fm = g2.getFontMetrics();
        g2.drawString(scoreMsg, (w - fm.stringWidth(scoreMsg)) / 2, h / 2 + 20);

        g2.setFont(new Font("SansSerif", Font.PLAIN, 18));
        String restartMsg = "Drücke 'R' zum Neustart   |   'M' für das Menü";
        fm = g2.getFontMetrics();
        g2.drawString(restartMsg, (w - fm.stringWidth(restartMsg)) / 2, h / 2 + 55);
    }

    // ---------- Eingaben ----------

    @Override
    public void mouseMoved(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            trySplit();
        } else if (e.getKeyCode() == KeyEvent.VK_W) {
            tryEject();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            System.exit(0);
        } else if (gameOver && e.getKeyCode() == KeyEvent.VK_R) {
            initGame();
        } else if (gameOver && e.getKeyCode() == KeyEvent.VK_M) {
            timer.stop();
            frame.showMenu();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // nicht benötigt
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // nicht benötigt
    }
}