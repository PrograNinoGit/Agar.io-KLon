package agarclone;

import java.awt.Color;

public class Cell {
    double x, y;
    double radius;
    Color color;
    String name;

    // Für KI-Gegner: aktuelle Bewegungsrichtung (normalisiert)
    double dx, dy;

    // Für gesplittete Spielerzellen: abklingender "Schwung" nach dem Splitten
    double boostX, boostY;

    // Anzahl verbleibender Ticks, bis diese Zelle wieder mit einer
    // anderen Spielerzelle verschmelzen darf (0 = darf sofort verschmelzen)
    int mergeCooldown;

    // Allgemeiner Cooldown für KI-Aktionen (z.B. wie oft ein Bot splitten darf)
    int aiActionCooldown;

    public Cell(double x, double y, double radius, Color color, String name) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.color = color;
        this.name = name;
        this.dx = 0;
        this.dy = 0;
        this.boostX = 0;
        this.boostY = 0;
        this.mergeCooldown = 0;
        this.aiActionCooldown = 0;
    }

    /** "Masse" proportional zur Fläche - Basis für faires Wachstum/Fressen. */
    public double getMass() {
        return radius * radius;
    }

    /** Größere Zellen sind etwas langsamer, wie im Original Agar.io. */
    public double getSpeed() {
        return Math.max(1.5, 6.0 - radius / 15.0);
    }
}