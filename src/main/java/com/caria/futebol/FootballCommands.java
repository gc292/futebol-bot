package com.caria.futebol;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;

import java.util.List;

/** Kept separate so registration can be inspected and tested without connecting to Discord. */
final class FootballCommands {
    private FootballCommands() {}
    static List<CommandData> all() {
        OptionData player = rosterOption("player", "Roster player (also available without a Discord link)", false);
        OptionData user = new OptionData(OptionType.USER, "user", "Linked Discord player", false);
        OptionData metric = new OptionData(OptionType.STRING, "metric", "Ranking metric", false)
                .addChoice("Win rate", "WIN_RATE").addChoice("Wins", "WINS").addChoice("Games played", "GAMES")
                .addChoice("Goal difference", "GOAL_DIFFERENCE").addChoice("Best win streak", "BEST_WIN_STREAK");
        return List.of(
                Commands.slash("futebol", "Start attendance check (fixed panel with buttons)").setGuildOnly(true),
                Commands.slash("teams", "Generate fair 5v5 teams using decimal ranks (0.0–10.0)").setGuildOnly(true),
                Commands.slash("remake", "Remake teams (random, may be less fair)").setGuildOnly(true),
                Commands.slash("profile", "View a player's football statistics").setGuildOnly(true).addOptions(user, player),
                Commands.slash("leaderboard", "Rank football players").setGuildOnly(true).addOptions(metric),
                Commands.slash("history", "Recent matches, optionally filtered by player").setGuildOnly(true).addOptions(user, player),
                Commands.slash("match", "View, correct or delete a recorded match").setGuildOnly(true).addSubcommands(
                        matchCommand("view", "View a match"), matchCommand("edit", "Correct a score or match date"),
                        matchCommand("delete", "Delete a match after confirmation")),
                Commands.slash("result", "Record the result of a played match").setGuildOnly(true).addSubcommands(
                        new SubcommandData("generated", "Choose a saved bot-generated team division"),
                        new SubcommandData("manual", "Select the teams that actually played"),
                        new SubcommandData("resume", "Resume a persisted result form").addOption(OptionType.STRING, "draft", "Form ID shown in the result panel", true)),
                Commands.slash("player", "Associate roster players with Discord accounts").setGuildOnly(true).addSubcommands(
                        new SubcommandData("link", "Link a roster player (authorized users only)")
                                .addOptions(rosterOption("player", "Roster player", true), new OptionData(OptionType.USER, "user", "Discord account", true)),
                        new SubcommandData("unlink", "Remove a Discord association (authorized users only)")
                                .addOptions(rosterOption("player", "Roster player", true))),
                Commands.slash("settings", "Configure manual team size, win-rate qualification and authorized roles").setGuildOnly(true)
                        .addOption(OptionType.INTEGER, "team_size", "Manual team size (generated teams remain 5v5)", false)
                        .addOption(OptionType.INTEGER, "min_games", "Minimum games for the win-rate ranking", false)
                        .addOption(OptionType.ROLE, "role", "Add/remove an authorized role", false)
                        .addOption(OptionType.BOOLEAN, "remove_role", "Remove the specified role instead of adding it", false)
        );
    }
    private static SubcommandData matchCommand(String name, String description) {
        return new SubcommandData(name, description).addOption(OptionType.INTEGER, "id", "Match number", true);
    }
    private static OptionData rosterOption(String name, String description, boolean required) {
        OptionData option = new OptionData(OptionType.STRING, name, description, required);
        for (String player : PlayerCatalog.PLAYERS) option.addChoice(player, player);
        return option;
    }
}
