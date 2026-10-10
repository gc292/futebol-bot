package com.caria.futebol;

import java.util.List;
import java.util.Locale;

import static com.caria.futebol.PlayerCatalog.rankOf;

/** Discord message formatting, independent of event handling. */
final class BotMessages {
    private BotMessages() {}

    static String attendancePrompt(Session session) {
        return "⚽ **Futebol Attendance**\n\n" +
                "**" + session.currentPlayer() + "** — are you in?\n" +
                "_(Click a button. This panel will move to the next player.)_";
    }

    static String attendanceSummary(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ **Attendance Finished**\n\n");

        sb.append("**Confirmed (").append(session.confirmed.size()).append("):**\n");
        for (String n : session.confirmed) sb.append("- ").append(n).append("\n");

        sb.append("\n**Not going (").append(session.notGoing.size()).append("):**\n");
        for (String n : session.notGoing) sb.append("- ").append(n).append("\n");

        sb.append("\nRun **/teams** for fair teams or **/remake** to reshuffle randomly.");
        return sb.toString();
    }

    static String formatTeamsMessage(String title, Teams teams, List<String> substitutes, double bestDifference, boolean isRemake) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");

        sb.append("**Team A (").append(String.format(Locale.US, "%.1f", teams.sumA)).append("):**\n");
        for (String n : teams.teamA) {
            sb.append("- ").append(n)
                    .append(" (").append(String.format(Locale.US, "%.1f", rankOf(n))).append(")\n");
        }

        sb.append("\n**Team B (").append(String.format(Locale.US, "%.1f", teams.sumB)).append("):**\n");
        for (String n : teams.teamB) {
            sb.append("- ").append(n)
                    .append(" (").append(String.format(Locale.US, "%.1f", rankOf(n))).append(")\n");
        }

        sb.append("\n**Difference (A vs B):** ")
                .append(String.format(Locale.US, "%.2f", Math.abs(teams.sumA - teams.sumB)));

        if (!substitutes.isEmpty()) {
            sb.append("\n\n**Substitutes (").append(substitutes.size()).append("):**\n");
            for (String n : substitutes) {
                sb.append("- ").append(n)
                        .append(" (").append(String.format(Locale.US, "%.1f", rankOf(n))).append(")\n");
            }

            if (!isRemake && bestDifference >= 0) {
                sb.append("\n_(From more than 10 confirmed, I picked the 10 that produced the best balance. Best diff found: ")
                        .append(String.format(Locale.US, "%.2f", bestDifference)).append(")_");
            } else {
                sb.append("\n_(Remake mode: random pick & random split — may be less fair.)_");
            }
        } else if (isRemake) {
            sb.append("\n_(Remake mode: random split — may be less fair.)_");
        }

        return sb.toString();
    }

}
