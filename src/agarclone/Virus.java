package agarclone;

public class Virus {
    double x, y;
    double radius;

    // Wie oft dieser Virus bereits mit ausgestoßener Masse (W) gefüttert wurde
    int feedCount;

    // Schwung, mit dem ein frisch entstandener Virus nach einer Teilung wegfliegt
    double vx, vy;

    public Virus(double x, double y, double radius) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.feedCount = 0;
        this.vx = 0;
        this.vy = 0;
    }
}