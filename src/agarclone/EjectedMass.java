package agarclone;

import java.awt.Color;

public class EjectedMass {
    double x, y;
    double radius;
    Color color;

    // Schwung, mit dem das Pellet nach dem Ausstoßen wegfliegt (klingt ab)
    double vx, vy;

    // Kurze Schonfrist direkt nach dem Ausstoßen, damit das Pellet nicht
    // sofort wieder von derselben Zelle verschluckt wird, aus der es kam.
    int ownerImmuneTicks;

    public EjectedMass(double x, double y, double radius, Color color,
                        double vx, double vy, int ownerImmuneTicks) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.color = color;
        this.vx = vx;
        this.vy = vy;
        this.ownerImmuneTicks = ownerImmuneTicks;
    }
}
