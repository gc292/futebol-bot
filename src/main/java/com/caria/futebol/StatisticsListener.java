package com.caria.futebol;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Discord adapter only: validation, persistence and statistics live outside this listener. */
final class StatisticsListener extends ListenerAdapter {
    private static final String PREFIX = "fb:";
    private static final int PAGE_SIZE = 5, LEADERBOARD_SIZE = 10;
    private static final Set<String> COMMANDS = Set.of("profile", "leaderboard", "history", "match", "result", "player", "settings");
    private final FootballStore store;
    private final StatisticsService statistics;

    StatisticsListener(FootballStore store) { this.store = store; statistics = new StatisticsService(store); }
    private static String guild(GenericInteractionCreateEvent event) {
        if (event.getGuild() == null) throw new FootballException("Use this command in a Discord server.");
        return event.getGuild().getId();
    }
    private boolean authorized(GenericInteractionCreateEvent event) {
        Member member = event.getMember();
        if (member == null) return false;
        if (member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER)) return true;
        Set<String> roles = store.settings(guild(event)).authorizedRoles();
        return member.getRoles().stream().anyMatch(role -> roles.contains(role.getId()));
    }
    private void requireAuthorized(GenericInteractionCreateEvent event) {
        if (!authorized(event)) throw new FootballException("You need Manage Server, Administrator, or a configured authorized role.");
    }
    /** Configuration cannot grant itself to a role that does not already manage the server. */
    private static void requireManager(GenericInteractionCreateEvent event) {
        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR) && !member.hasPermission(Permission.MANAGE_SERVER))
            throw new FootballException("Only server managers can change settings.");
    }
    private static void own(GenericInteractionCreateEvent event, String userId) {
        if (!event.getUser().getId().equals(userId)) throw new FootballException("This panel belongs to another user. Run the command yourself.");
    }
    private static void error(IReplyCallback event, RuntimeException error) {
        String message;
        if (error instanceof FootballException) message = error.getMessage();
        else {
            error.printStackTrace(System.err);
            message = "The operation could not be completed. No partial result was saved. Please try again.";
        }
        if (event.isAcknowledged()) event.getHook().sendMessage(message).setEphemeral(true).queue();
        else event.reply(message).setEphemeral(true).queue();
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!COMMANDS.contains(event.getName())) return;
        try {
            String guild = guild(event); store.ensureGuild(guild);
            switch (event.getName()) {
                case "profile" -> profile(event);
                case "history" -> history(event);
                case "leaderboard" -> leaderboard(event);
                case "result" -> result(event);
                case "match" -> match(event);
                case "player" -> link(event);
                case "settings" -> settings(event);
                default -> { }
            }
        } catch (RuntimeException error) { error(event, error); }
    }
    private Long selectedPlayer(SlashCommandInteractionEvent event, boolean defaultToSelf) {
        String guild = guild(event);
        if (event.getOption("user") != null && event.getOption("player") != null)
            throw new FootballException("Choose either a Discord user or a roster player.");
        if (event.getOption("player") != null) {
            String name = event.getOption("player").getAsString();
            return store.players(guild).stream().filter(p -> p.name().equals(name)).map(Player::id).findFirst()
                    .orElseThrow(() -> new FootballException("Unknown roster player."));
        }
        if (event.getOption("user") != null || defaultToSelf) {
            String discordId = event.getOption("user") == null ? event.getUser().getId() : event.getOption("user").getAsUser().getId();
            try { return store.playerByDiscord(guild, discordId).id(); }
            catch (FootballException missing) {
                if (event.getOption("user") != null) throw new FootballException("That user is not linked. Use /player link or select a roster player.");
                return null;
            }
        }
        return null;
    }
    private void profile(SlashCommandInteractionEvent event) {
        Long player = selectedPlayer(event, true);
        if (player == null) {
            StringSelectMenu.Builder menu = StringSelectMenu.create("fb:profile:" + event.getUser().getId());
            for (Player candidate : store.players(guild(event))) menu.addOption(candidate.name(), "" + candidate.id());
            event.reply("Your Discord account is not linked yet. Choose a player, or ask a server manager to use /player link.")
                    .addActionRow(menu.build()).setEphemeral(true).queue();
        } else event.replyEmbeds(profileEmbed(guild(event), player)).queue();
    }
    private MessageEmbed profileEmbed(String guildId, long id) {
        return StatisticsMessages.profile(store.player(guildId, id), statistics.profile(guildId, id));
    }
    private void history(SlashCommandInteractionEvent event) {
        Long player = selectedPlayer(event, false);
        event.replyEmbeds(historyEmbed(guild(event), player, 0))
                .addComponents(historyRows(guild(event), event.getUser().getId(), player, 0)).queue();
    }
    private MessageEmbed historyEmbed(String guild, Long player, int page) {
        String filter = player == null ? "" : " · " + store.player(guild, player).name();
        return StatisticsMessages.history(store.matches(guild, player, page, PAGE_SIZE), filter, page);
    }
    private List<ActionRow> historyRows(String guild, String user, Long player, int page) {
        String base = "fb:history:" + user + ":" + (player == null ? 0 : player) + ":";
        boolean more = !store.matches(guild, player, page + 1, PAGE_SIZE).isEmpty();
        return List.of(ActionRow.of(Button.secondary(base + Math.max(0, page - 1), "Previous").withDisabled(page == 0),
                Button.secondary(base + (page + 1), "Next").withDisabled(!more)));
    }
    private void leaderboard(SlashCommandInteractionEvent event) {
        StatisticsService.Metric metric = event.getOption("metric") == null ? StatisticsService.Metric.WINS
                : StatisticsService.Metric.valueOf(event.getOption("metric").getAsString());
        event.replyEmbeds(leaderboardEmbed(guild(event), metric, 0))
                .addComponents(leaderboardRows(guild(event), event.getUser().getId(), metric, 0)).queue();
    }
    private MessageEmbed leaderboardEmbed(String guild, StatisticsService.Metric metric, int page) {
        return StatisticsMessages.leaderboard(statistics.leaderboard(guild, metric), metric,
                store.settings(guild).minimumWinRateGames(), page, LEADERBOARD_SIZE);
    }
    private List<ActionRow> leaderboardRows(String guild, String user, StatisticsService.Metric metric, int page) {
        StringSelectMenu.Builder menu = StringSelectMenu.create("fb:metric:" + user);
        for (var value : StatisticsService.Metric.values()) menu.addOption(value.name().replace('_', ' '), value.name());
        menu.setDefaultValues(metric.name());
        String base = "fb:leaderboard:" + user + ":" + metric + ":";
        boolean more = statistics.leaderboard(guild, metric).size() > (page + 1) * LEADERBOARD_SIZE;
        return List.of(ActionRow.of(menu.build()), ActionRow.of(
                Button.secondary(base + Math.max(0, page - 1), "Previous").withDisabled(page == 0),
                Button.secondary(base + (page + 1), "Next").withDisabled(!more)));
    }
    private void result(SlashCommandInteractionEvent event) {
        String guild = guild(event), user = event.getUser().getId(), channel = event.getChannel().getId();
        switch (Objects.requireNonNull(event.getSubcommandName())) {
            case "generated" -> event.replyEmbeds(divisionsEmbed(guild, 0))
                    .addComponents(divisionRows(guild, user, 0)).setEphemeral(true).queue();
            case "manual" -> {
                ResultDraft draft = store.beginManual(guild, user, channel);
                event.replyEmbeds(StatisticsMessages.draft(draft, false)).addComponents(manualRows(draft)).setEphemeral(true).queue();
            }
            case "resume" -> {
                ResultDraft draft = store.draft(guild, user, event.getOption("draft").getAsString());
                if (draft.status().equals("CONFIRMED") || draft.status().equals("CANCELLED")) throw new FootballException("This form is already completed or cancelled.");
                if (!draft.action().equals("CREATE")) requireAuthorized(event);
                boolean ready = draft.status().equals("READY");
                event.replyEmbeds(StatisticsMessages.draft(draft, ready)).addComponents(draftRows(draft)).setEphemeral(true).queue();
            }
            default -> throw new FootballException("Choose generated or manual teams.");
        }
    }
    private MessageEmbed divisionsEmbed(String guild, int page) {
        var builder = new net.dv8tion.jda.api.EmbedBuilder().setTitle("Choose the division that was used for the match");
        List<TeamDivision> divisions = store.pendingDivisions(guild, page, PAGE_SIZE);
        if (divisions.isEmpty()) builder.setDescription("No unused generated divisions on this page. Run /teams or /remake, or use /result manual.");
        for (TeamDivision division : divisions) builder.addField("Division #" + division.id() + " · " + StatisticsMessages.date(division.createdAt()) + " · " + division.mode(),
                "🔵 " + StatisticsMessages.names(division.teamA()) + "\n🔴 " + StatisticsMessages.names(division.teamB())
                        + "\nCreated by <@" + division.createdBy() + "> in <#" + division.channelId() + ">", false);
        return builder.setFooter("Page " + (page + 1) + " · Earlier divisions remain available after /remake and restarts").build();
    }
    private List<ActionRow> divisionRows(String guild, String user, int page) {
        List<ActionRow> rows = new ArrayList<>();
        List<TeamDivision> divisions = store.pendingDivisions(guild, page, PAGE_SIZE);
        if (!divisions.isEmpty()) {
            StringSelectMenu.Builder menu = StringSelectMenu.create("fb:division:" + user);
            for (TeamDivision division : divisions) {
                String description = "A: " + StatisticsMessages.names(division.teamA()) + " | B: " + StatisticsMessages.names(division.teamB());
                menu.addOption("#" + division.id() + " · " + StatisticsMessages.date(division.createdAt()), "" + division.id(),
                        description.substring(0, Math.min(100, description.length())));
            }
            rows.add(ActionRow.of(menu.build()));
        }
        String base = "fb:divpage:" + user + ":";
        rows.add(ActionRow.of(Button.secondary(base + Math.max(0, page - 1), "Previous").withDisabled(page == 0),
                Button.secondary(base + (page + 1), "Next").withDisabled(store.pendingDivisions(guild, page + 1, PAGE_SIZE).isEmpty())));
        return rows;
    }
    private List<ActionRow> manualRows(ResultDraft draft) {
        List<ActionRow> rows = new ArrayList<>();
        for (String team : List.of("A", "B")) {
            List<Player> selection = team.equals("A") ? draft.teamA() : draft.teamB();
            StringSelectMenu.Builder menu = StringSelectMenu.create("fb:manual:" + draft.id() + ":" + draft.revision() + ":" + team)
                    .setPlaceholder("Team " + team + " · select " + draft.teamSize() + " players").setRequiredRange(draft.teamSize(), draft.teamSize());
            for (Player player : store.players(draft.guildId())) menu.addOption(player.name(), "" + player.id());
            menu.setDefaultValues(selection.stream().map(p -> "" + p.id()).toList());
            rows.add(ActionRow.of(menu.build()));
        }
        boolean complete = draft.teamA().size() == draft.teamSize() && draft.teamB().size() == draft.teamSize();
        rows.add(ActionRow.of(Button.primary("fb:freeze:" + draft.id() + ":" + draft.revision(), "Confirm teams").withDisabled(!complete),
                Button.secondary("fb:cancel:" + draft.id(), "Cancel")));
        return rows;
    }
    private List<ActionRow> draftRows(ResultDraft draft) {
        if (draft.status().equals("READY")) return List.of(ActionRow.of(
                Button.success("fb:confirm:" + draft.id() + ":" + draft.revision(), "Confirm"),
                Button.secondary("fb:cancel:" + draft.id(), "Cancel")));
        if (draft.divisionId() == null) return manualRows(draft);
        return List.of(ActionRow.of(Button.primary("fb:score:" + draft.id() + ":" + draft.revision(), "Enter score"),
                Button.secondary("fb:cancel:" + draft.id(), "Cancel")));
    }
    private void match(SlashCommandInteractionEvent event) {
        long id = event.getOption("id").getAsLong();
        if ("view".equals(event.getSubcommandName())) event.replyEmbeds(StatisticsMessages.match(store.match(guild(event), id))).queue();
        else {
            requireAuthorized(event);
            boolean delete = "delete".equals(event.getSubcommandName());
            ResultDraft draft = store.beginChange(guild(event), event.getUser().getId(), event.getChannel().getId(), id, delete);
            event.replyEmbeds(StatisticsMessages.draft(draft, delete)).addComponents(draftRows(draft)).setEphemeral(true).queue();
        }
    }
    private void link(SlashCommandInteractionEvent event) {
        requireAuthorized(event);
        // The user option here is an association, rather than a profile filter.
        String name = event.getOption("player").getAsString();
        Player player = store.players(guild(event)).stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
        String discord = "unlink".equals(event.getSubcommandName()) ? null : event.getOption("user").getAsUser().getId();
        store.linkPlayer(guild(event), player.id(), discord);
        event.reply(discord == null ? "Unlinked " + player.name() + "." : "Linked " + player.name() + " to <@" + discord + ">.")
                .setEphemeral(true).queue();
    }
    private void settings(SlashCommandInteractionEvent event) {
        requireManager(event);
        String guild = guild(event);
        GuildSettings previous = store.settings(guild);
        int size = event.getOption("team_size") == null ? previous.manualTeamSize() : integerOption(event, "team_size");
        int minimum = event.getOption("min_games") == null ? previous.minimumWinRateGames() : integerOption(event, "min_games");
        Set<String> roles = new HashSet<>(previous.authorizedRoles());
        if (event.getOption("remove_role") != null && event.getOption("role") == null) throw new FootballException("Specify a role to remove.");
        if (event.getOption("role") != null) {
            var role = event.getOption("role").getAsRole();
            if (role.isPublicRole() || role.isManaged()) throw new FootballException("Choose a normal role, not @everyone or a managed role.");
            if (event.getOption("remove_role") != null && event.getOption("remove_role").getAsBoolean()) roles.remove(role.getId());
            else roles.add(role.getId());
        }
        store.configure(guild, size, minimum, roles);
        event.reply("Manual teams: **" + size + "v" + size + "**. Generated teams: **5v5**. Win-rate minimum: **" + minimum
                + " games**. Authorized roles: " + (roles.isEmpty() ? "none (server managers only)" : String.join(", ", roles.stream().map(r -> "<@&" + r + ">").toList())))
                .setEphemeral(true).queue();
    }
    private static int integerOption(SlashCommandInteractionEvent event, String name) {
        long value = event.getOption(name).getAsLong();
        if (value < 1 || value > Integer.MAX_VALUE) throw new FootballException("Enter a positive integer within the supported range.");
        return (int) value;
    }

    @Override public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().startsWith(PREFIX)) return;
        try {
            String guild = guild(event), user = event.getUser().getId();
            String[] parts = event.getComponentId().split(":");
            switch (parts[1]) {
                case "profile" -> { own(event, parts[2]); event.editMessageEmbeds(profileEmbed(guild, Long.parseLong(event.getValues().get(0)))).setComponents().queue(); }
                case "metric" -> {
                    own(event, parts[2]); var metric = StatisticsService.Metric.valueOf(event.getValues().get(0));
                    event.editMessageEmbeds(leaderboardEmbed(guild, metric, 0)).setComponents(leaderboardRows(guild, user, metric, 0)).queue();
                }
                case "division" -> {
                    own(event, parts[2]);
                    ResultDraft draft = store.beginGenerated(guild, user, event.getChannel().getId(), Long.parseLong(event.getValues().get(0)));
                    event.replyEmbeds(StatisticsMessages.draft(draft, false)).addComponents(draftRows(draft)).setEphemeral(true).queue();
                }
                case "manual" -> {
                    List<Long> selected = event.getValues().stream().map(Long::parseLong).toList();
                    ResultDraft draft = store.selectManualTeam(guild, user, parts[2], parts[4], selected, Integer.parseInt(parts[3]));
                    event.editMessageEmbeds(StatisticsMessages.draft(draft, false)).setComponents(manualRows(draft)).queue();
                }
                default -> throw new FootballException("Unknown selection.");
            }
        } catch (RuntimeException error) { error(event, error); }
    }
    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith(PREFIX)) return;
        try {
            String guild = guild(event), user = event.getUser().getId();
            String[] parts = event.getComponentId().split(":");
            switch (parts[1]) {
                case "history" -> {
                    own(event, parts[2]); Long player = Long.parseLong(parts[3]); if (player == 0) player = null;
                    int page = Integer.parseInt(parts[4]);
                    event.editMessageEmbeds(historyEmbed(guild, player, page)).setComponents(historyRows(guild, user, player, page)).queue();
                }
                case "leaderboard" -> {
                    own(event, parts[2]); var metric = StatisticsService.Metric.valueOf(parts[3]); int page = Integer.parseInt(parts[4]);
                    event.editMessageEmbeds(leaderboardEmbed(guild, metric, page)).setComponents(leaderboardRows(guild, user, metric, page)).queue();
                }
                case "divpage" -> {
                    own(event, parts[2]); int page = Integer.parseInt(parts[3]);
                    event.editMessageEmbeds(divisionsEmbed(guild, page)).setComponents(divisionRows(guild, user, page)).queue();
                }
                case "freeze" -> {
                    ResultDraft draft = store.freezeManualTeams(guild, user, parts[2], Integer.parseInt(parts[3]));
                    event.editMessageEmbeds(StatisticsMessages.draft(draft, false)).setComponents(draftRows(draft)).queue();
                }
                case "score" -> {
                    ResultDraft draft = store.draft(guild, user, parts[2]);
                    if (draft.revision() != Integer.parseInt(parts[3]) || !draft.status().equals("ACTIVE")) throw new FootballException("Use the latest form.");
                    if (!draft.action().equals("CREATE")) requireAuthorized(event);
                    event.replyModal(scoreModal(draft)).queue();
                }
                case "confirm" -> {
                    ResultDraft draft = store.draft(guild, user, parts[2]);
                    boolean permitted = authorized(event);
                    long matchId = store.confirm(guild, user, draft.id(), Integer.parseInt(parts[3]), permitted);
                    if (draft.action().equals("DELETE")) event.editMessage("Deleted MATCH #" + matchId + ". Statistics now reflect the remaining history.").setEmbeds().setComponents().queue();
                    else event.editMessage("Saved MATCH #" + matchId + ".").setEmbeds(StatisticsMessages.match(store.match(guild, matchId))).setComponents().queue();
                }
                case "cancel" -> { store.cancel(guild, user, parts[2]); event.editMessage("Cancelled. No changes were saved.").setEmbeds().setComponents().queue(); }
                default -> throw new FootballException("Unknown button.");
            }
        } catch (RuntimeException error) { error(event, error); }
    }
    private static Modal scoreModal(ResultDraft draft) {
        TextInput.Builder a = TextInput.create("goals_a", "Team A Goals", TextInputStyle.SHORT).setRequired(true).setMaxLength(10);
        TextInput.Builder b = TextInput.create("goals_b", "Team B Goals", TextInputStyle.SHORT).setRequired(true).setMaxLength(10);
        if (draft.goalsA() != null) a.setValue("" + draft.goalsA());
        if (draft.goalsB() != null) b.setValue("" + draft.goalsB());
        TextInput date = TextInput.create("played_at", "Played at (UTC: YYYY-MM-DD HH:MM)", TextInputStyle.SHORT)
                .setValue(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(draft.playedAt()))
                .setRequired(true).setMaxLength(30).build();
        return Modal.create("fb:score:" + draft.id() + ":" + draft.revision(), "Match final score")
                .addActionRows(ActionRow.of(a.build()), ActionRow.of(b.build()), ActionRow.of(date)).build();
    }
    static Instant parsePlayedAt(String text) {
        try {
            String value = text.trim();
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$")) return OffsetDateTime.parse(value.replace(' ', 'T')).toInstant();
            return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException | NullPointerException e) { throw new FootballException("Use YYYY-MM-DD HH:MM in UTC, or an ISO date with timezone."); }
    }
    @Override public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("fb:score:")) return;
        try {
            String[] parts = event.getModalId().split(":");
            ResultDraft draft = store.draft(guild(event), event.getUser().getId(), parts[2]);
            if (!draft.action().equals("CREATE")) requireAuthorized(event);
            draft = store.score(guild(event), event.getUser().getId(), draft.id(),
                    FootballStore.parseGoals(event.getValue("goals_a").getAsString()), FootballStore.parseGoals(event.getValue("goals_b").getAsString()),
                    parsePlayedAt(event.getValue("played_at").getAsString()), Integer.parseInt(parts[3]));
            // Edits the component's panel, removing every earlier score/team button.
            event.editMessageEmbeds(StatisticsMessages.draft(draft, true)).setComponents(draftRows(draft)).queue();
        } catch (RuntimeException error) { error(event, error); }
    }
}
