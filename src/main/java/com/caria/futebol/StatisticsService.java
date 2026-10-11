package com.caria.futebol;

import java.time.Instant;
import java.util.*;

/** Statistics are derived on demand, so edits and deletions cannot leave stale counters. */
final class StatisticsService {
    record Appearance(long playerId, long matchId, Instant playedAt, int goalsFor, int goalsAgainst) {}
    record Entry(Player player, PlayerStatistics statistics) {}
    enum Metric { WIN_RATE, WINS, GAMES, GOAL_DIFFERENCE, BEST_WIN_STREAK }
    private final FootballStore store;
    StatisticsService(FootballStore store) { this.store = store; }

    PlayerStatistics profile(String guildId, long playerId) {
        store.player(guildId, playerId);
        return calculate(store.appearances(guildId).stream().filter(a -> a.playerId() == playerId).toList());
    }
    List<Entry> leaderboard(String guildId, Metric metric) {
        Map<Long, List<Appearance>> byPlayer = new HashMap<>();
        for (Appearance appearance : store.appearances(guildId))
            byPlayer.computeIfAbsent(appearance.playerId(), ignored -> new ArrayList<>()).add(appearance);
        int minimum = store.settings(guildId).minimumWinRateGames();
        List<Entry> entries = new ArrayList<>();
        for (Player player : store.players(guildId)) {
            PlayerStatistics stats = calculate(byPlayer.getOrDefault(player.id(), List.of()));
            if (metric != Metric.WIN_RATE || stats.gamesPlayed() >= minimum) entries.add(new Entry(player, stats));
        }
        entries.sort(Comparator.comparingDouble((Entry e) -> value(e.statistics(), metric)).reversed()
                .thenComparing(e -> e.player().name()).thenComparingLong(e -> e.player().id()));
        return List.copyOf(entries);
    }
    static double value(PlayerStatistics stats, Metric metric) {
        return switch (metric) {
            case WIN_RATE -> stats.winRate(); case WINS -> stats.wins(); case GAMES -> stats.gamesPlayed();
            case GOAL_DIFFERENCE -> stats.goalDifference(); case BEST_WIN_STREAK -> stats.bestWinStreak();
        };
    }
    static PlayerStatistics calculate(List<Appearance> input) {
        List<Appearance> appearances = new ArrayList<>(input);
        appearances.sort(Comparator.comparing(Appearance::playedAt).thenComparingLong(Appearance::matchId));
        int wins = 0, draws = 0, losses = 0, length = 0, bestWins = 0;
        long goalsFor = 0, goalsAgainst = 0;
        String streak = "N/A";
        Instant last = null;
        for (Appearance appearance : appearances) {
            String outcome;
            if (appearance.goalsFor() > appearance.goalsAgainst()) { wins++; outcome = "Wins"; }
            else if (appearance.goalsFor() < appearance.goalsAgainst()) { losses++; outcome = "Losses"; }
            else { draws++; outcome = "Draws"; }
            length = outcome.equals(streak) ? length + 1 : 1;
            streak = outcome;
            if (outcome.equals("Wins")) bestWins = Math.max(bestWins, length);
            goalsFor += appearance.goalsFor(); goalsAgainst += appearance.goalsAgainst(); last = appearance.playedAt();
        }
        return new PlayerStatistics(appearances.size(), wins, draws, losses, goalsFor, goalsAgainst, streak, length, bestWins, last);
    }
}
