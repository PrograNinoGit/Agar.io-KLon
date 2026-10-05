package agarclone;

public enum Difficulty {
    EASY,    // Zufällig wandernde Bots (ursprüngliches Verhalten)
    HARD,    // Bots meiden den Spieler, wenn er größer ist, und jagen ihn, wenn er kleiner ist
    EXTREME  // Wie HARD, zusätzlich dürfen Bots sich teilen
}