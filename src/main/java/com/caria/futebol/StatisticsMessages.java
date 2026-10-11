package com.caria.futebol;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class StatisticsMessages {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private StatisticsMessages() {}
    static String date(Instant instant) { return instant == null ? "N/A" : DATE.format(instant); }
    static String names(List<Player> players) { return players.isEmpty() ? "Select players" : String.join(", ", players.stream().map(Player::name).toList()); }
    static String number(double value) { return String.format(Locale.US, "%.1f", value); }
    static MessageEmbed profile(Player player, PlayerStatistics stats) {
        return new EmbedBuilder().setTitle("⚽ " + player.name()).setColor(Color.BLUE)
                .setDescription(player.discordId() == null ? "Not linked to a Discord account" : "<@" + player.discordId() + ">")
                .addField("Games Played", "" + stats.gamesPlayed(), true)
                .addField("Wins / Draws / Losses", stats.wins() + " / " + stats.draws() + " / " + stats.losses(), true)
                .addField("Win Rate", stats.gamesPlayed() == 0 ? "N/A" : number(stats.winRate()) + "%", true)
                .addField("Goals For", "" + stats.goalsFor(), true).addField("Goals Against", "" + stats.goalsAgainst(), true)
                .addField("Goal Difference", "" + stats.goalDifference(), true).addField("Points", "" + stats.points(), true)
                .addField("Current Streak", stats.currentStreak(), true).addField("Best Win Streak", "" + stats.bestWinStreak(), true)
                .addField("Last Played", date(stats.lastPlayed()), false).build();
    }
    static MessageEmbed match(Match match) {
        return teams("MATCH #" + match.id(), match.division().teamA(), match.division().teamB())
                .addField("Date", date(match.playedAt()), false)
                .addField("Final Score", "🔵 " + match.goalsA() + " - " + match.goalsB() + " 🔴", true)
                .addField("Winner", match.winner(), true)
                .addField("Teams", match.division().source() + " · " + match.division().mode() + " · Division #" + match.division().id(), false)
                .addField("Result Registered", date(match.recordedAt()), false)
                .setFooter("Version " + match.version() + " · Registered by " + match.recordedBy()).build();
    }
    static EmbedBuilder teams(String title, List<Player> a, List<Player> b) {
        return new EmbedBuilder().setTitle(title).setColor(Color.BLUE)
                .addField("🔵 Team A", names(a), false).addField("🔴 Team B", names(b), false);
    }
    static MessageEmbed draft(ResultDraft draft, boolean ready) {
        EmbedBuilder builder = teams(draft.action().equals("DELETE") ? "Delete MATCH #" + draft.targetMatchId() + "?"
                : draft.action().equals("EDIT") ? "Edit MATCH #" + draft.targetMatchId() : ready ? "Confirm match result" : "Select / confirm teams", draft.teamA(), draft.teamB());
        if (draft.divisionId() != null) builder.addField("Division", "#" + draft.divisionId(), true);
        if (ready) {
            builder.addField("Final Score", "🔵 " + draft.goalsA() + " - " + draft.goalsB() + " 🔴", true)
                    .addField("Winner", draft.goalsA().equals(draft.goalsB()) ? "Draw" : draft.goalsA() > draft.goalsB() ? "Team A" : "Team B", true)
                    .addField("Date", date(draft.playedAt()), false)
                    .addField("Players Affected", "" + (draft.teamA().size() + draft.teamB().size()), true);
        }
        return builder.setFooter("Form ID: " + draft.id() + " · /result resume to recover after restart").build();
    }
    static MessageEmbed history(List<Match> matches, String filter, int page) {
        EmbedBuilder builder = new EmbedBuilder().setTitle("Match history" + filter).setColor(Color.BLUE);
        if (matches.isEmpty()) builder.setDescription("No matches on this page.");
        for (Match match : matches) builder.addField("#" + match.id() + " · " + date(match.playedAt()) + " · " + match.goalsA() + "–" + match.goalsB(),
                "🔵 " + names(match.division().teamA()) + "\n🔴 " + names(match.division().teamB()) + "\n" + match.winner() + " · " + match.division().source(), false);
        return builder.setFooter("Page " + (page + 1) + " · /match view id:<number> for details").build();
    }
    static MessageEmbed leaderboard(List<StatisticsService.Entry> all, StatisticsService.Metric metric, int minimum, int page, int size) {
        EmbedBuilder builder = new EmbedBuilder().setTitle("Leaderboard · " + metric.name().replace('_', ' ')).setColor(Color.ORANGE);
        StringBuilder lines = new StringBuilder();
        int end = Math.min(all.size(), (page + 1) * size);
        for (int i = page * size; i < end; i++) {
            var entry = all.get(i);
            double value = StatisticsService.value(entry.statistics(), metric);
            String display = metric == StatisticsService.Metric.WIN_RATE ? number(value) + "%" : String.format(Locale.US, "%.0f", value);
            lines.append(i + 1).append(". **").append(entry.player().name()).append("** — ").append(display)
                    .append(" (GP: ").append(entry.statistics().gamesPlayed()).append(")\n");
        }
        builder.setDescription(lines.isEmpty() ? "No qualifying players." : lines.toString());
        return builder.setFooter("Page " + (page + 1) + (metric == StatisticsService.Metric.WIN_RATE ? " · Minimum " + minimum + " games" : "")).build();
    }
}
