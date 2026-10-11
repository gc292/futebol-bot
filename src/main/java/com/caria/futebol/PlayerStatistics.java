package com.caria.futebol;

import java.time.Instant;

record PlayerStatistics(int gamesPlayed, int wins, int draws, int losses, long goalsFor, long goalsAgainst,
                        String streakOutcome, int streakLength, int bestWinStreak, Instant lastPlayed) {
    double winRate() { return gamesPlayed == 0 ? Double.NaN : wins * 100.0 / gamesPlayed; }
    long goalDifference() { return goalsFor - goalsAgainst; }
    long points() { return 3L * wins + draws; }
    String currentStreak() { return streakLength == 0 ? "N/A" : streakLength + " " + streakOutcome; }
}
