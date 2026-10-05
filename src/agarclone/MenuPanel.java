package agarclone;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MenuPanel extends JPanel {

    private final List<Point> foodDots = new ArrayList<>();
    private final List<Color> foodColors = new ArrayList<>();

    public MenuPanel(GameFrame frame) {
        setBackground(new Color(30, 30, 40));
        setLayout(new GridBagLayout());
        setFocusable(true);

        // Ein paar zufällige "Futterpunkte" für die Agar.io-Optik im Hintergrund
        Random random = new Random();
        for (int i = 0; i < 100; i++) {
            foodDots.add(new Point(random.nextInt(3000), random.nextInt(2000)));
            foodColors.add(new Color(random.nextInt(200) + 30, random.nextInt(200) + 30, random.nextInt(200) + 30));
        }

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = new Insets(14, 0, 14, 0);

        JLabel title = new JLabel("Agar.io Klon");
        title.setFont(new Font("SansSerif", Font.BOLD, 56));
        title.setForeground(Color.WHITE);
        gbc.gridy = 0;
        add(title, gbc);

        JLabel subtitle = new JLabel("Wähle deine Schwierigkeit");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 20));
        subtitle.setForeground(new Color(200, 200, 200));
        gbc.gridy = 1;
        add(subtitle, gbc);

        gbc.gridy = 2;
        add(createDifficultyButton("Leicht",
                () -> frame.startGame(Difficulty.EASY),
                "Bots wandern zufällig umher"), gbc);

        gbc.gridy = 3;
        add(createDifficultyButton("Schwer",
                () -> frame.startGame(Difficulty.HARD),
                "Bots meiden dich, wenn du größer bist -",
                "und jagen dich, wenn du kleiner bist"), gbc);

        gbc.gridy = 4;
        add(createDifficultyButton("Extrem",
                () -> frame.startGame(Difficulty.EXTREME),
                "Wie Schwer, zusätzlich können Bots",
                "sich beim Jagen teilen"), gbc);

        JLabel hint = new JLabel("ESC = Spiel beenden");
        hint.setFont(new Font("SansSerif", Font.PLAIN, 14));
        hint.setForeground(new Color(150, 150, 150));
        gbc.gridy = 5;
        gbc.insets = new Insets(30, 0, 0, 0);
        add(hint, gbc);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    System.exit(0);
                }
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Klassisches Agar.io-Gitter im Hintergrund, passend zum Spielfeld
        g2.setColor(new Color(45, 45, 58));
        int step = 40;
        for (int x = 0; x < getWidth(); x += step) {
            g2.drawLine(x, 0, x, getHeight());
        }
        for (int y = 0; y < getHeight(); y += step) {
            g2.drawLine(0, y, getWidth(), y);
        }

        // Verstreute "Futterpunkte" für etwas Atmosphäre
        for (int i = 0; i < foodDots.size(); i++) {
            Point p = foodDots.get(i);
            int x = p.x % Math.max(1, getWidth());
            int y = p.y % Math.max(1, getHeight());
            g2.setColor(foodColors.get(i));
            g2.fillOval(x, y, 5, 5);
        }
    }

    /** Baut eine klickbare "Karte" statt eines einzelnen JButtons - das umgeht die
     *  Umbruchprobleme von HTML-Text in JButtons bei dieser Fenstergröße. */
    private static final int CARD_WIDTH = 460;

    private JPanel createDifficultyButton(String title, Runnable onClick, String... descriptionLines) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(45, 45, 60));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 90, 110)),
                BorderFactory.createEmptyBorder(14, 10, 14, 10)));

        // Feste Breite für alle Karten, damit sie nicht je nach Textlänge
        // unterschiedlich breit werden
        int height = 50 + descriptionLines.length * 20;
        panel.setPreferredSize(new java.awt.Dimension(CARD_WIDTH, height));
        panel.setMinimumSize(new java.awt.Dimension(CARD_WIDTH, height));
        panel.setMaximumSize(new java.awt.Dimension(CARD_WIDTH, height));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(6));

        for (String line : descriptionLines) {
            JLabel descLabel = new JLabel(line);
            descLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
            descLabel.setForeground(new Color(205, 205, 205));
            descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(descLabel);
        }

        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                panel.setBackground(new Color(65, 65, 85));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                panel.setBackground(new Color(45, 45, 60));
            }
        });

        return panel;
    }
}