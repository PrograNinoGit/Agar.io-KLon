package agarclone;

import javax.swing.JFrame;

public class GameFrame extends JFrame {

    public GameFrame() {
        setTitle("Agar.io Klon");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Echter Vollbildmodus: kein Fensterrahmen, maximiert auf die volle
        // Bildschirmgröße. Mit ESC lässt sich das Programm jederzeit beenden,
        // da es ohne Rahmen keinen Schließen-Button gibt.
        setUndecorated(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        showMenu();
    }

    /** Zeigt das Hauptmenü mit der Schwierigkeitsauswahl an. */
    public void showMenu() {
        getContentPane().removeAll();

        MenuPanel menuPanel = new MenuPanel(this);
        add(menuPanel);

        revalidate();
        repaint();
        menuPanel.requestFocusInWindow();
    }

    /** Startet ein neues Spiel mit der gewählten Schwierigkeit. */
    public void startGame(Difficulty difficulty) {
        getContentPane().removeAll();

        GamePanel gamePanel = new GamePanel(difficulty, this);
        add(gamePanel);

        revalidate();
        repaint();
        gamePanel.requestFocusInWindow();
    }
}