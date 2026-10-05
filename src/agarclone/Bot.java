package agarclone;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Bot kann aus mehreren Zellen bestehen, wenn er sich gesplittet hat -
 * genau wie der Spieler. Die Zellen gelten weiterhin als eine Einheit,
 * bewegen sich gemeinsam auf ein Ziel zu und können wieder verschmelzen.
 */
public class Bot {
    final List<Cell> cells = new ArrayList<>();

    // Gemeinsame Wanderrichtung, wenn der Bot gerade kein Ziel verfolgt
    double wanderDx;
    double wanderDy;

    public Bot(Cell firstCell) {
        cells.add(firstCell);
    }

    double getTotalMass() {
        double total = 0;
        for (Cell c : cells) {
            total += c.getMass();
        }
        return total;
    }

    double getAvgX() {
        double sum = 0;
        for (Cell c : cells) {
            sum += c.x;
        }
        return sum / cells.size();
    }

    double getAvgY() {
        double sum = 0;
        for (Cell c : cells) {
            sum += c.y;
        }
        return sum / cells.size();
    }
}
