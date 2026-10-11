package com.caria.futebol;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.caria.futebol.PlayerCatalog.PLAYERS;
import static com.caria.futebol.BotMessages.attendancePrompt;
import static com.caria.futebol.BotMessages.attendanceSummary;
import static com.caria.futebol.BotMessages.formatTeamsMessage;
import static com.caria.futebol.TeamGenerator.pickBest10ForBalance;
import static com.caria.futebol.TeamGenerator.pickRandom10;
import static com.caria.futebol.TeamGenerator.splitIntoBalanced5v5;
import static com.caria.futebol.TeamGenerator.splitRandom5v5;

public class BotMain extends ListenerAdapter {
    private final FootballStore store;

    BotMain(FootballStore store) { this.store = store; }

    /**
     * Active attendance sessions:
     * key = "userId:channelId" (so a user can run one session per channel).
     */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Last confirmed list per channel.
     * Used by /teams and /remake.
     * key = channelId
     */
    private static final Map<String, List<String>> LAST_CONFIRMED_BY_CHANNEL = new ConcurrentHashMap<>();

    // Fixed button IDs
    private static final String BTN_YES = "att:yes";
    private static final String BTN_NO  = "att:no";

    public static void main(String[] args) throws Exception {
        // IMPORTANT: set your bot token as environment variable DISCORD_TOKEN
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing environment variable: DISCORD_TOKEN");
        }

        String databasePath = System.getenv().getOrDefault("FOOTBALL_DB_PATH", "data/football.db");
        FootballStore store = new FootballStore(Path.of(databasePath));

        // Build JDA and attach the existing and statistics listeners.
        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(new BotMain(store), new StatisticsListener(store))
                .build();

        // Wait until Discord connection is ready
        jda.awaitReady();

        // Register slash commands (GLOBAL). It can take a few minutes to appear.
        // If you already had old commands, they should update after propagation.
        jda.updateCommands().addCommands(FootballCommands.all()).complete();
        System.out.println("✅ Bot is online and commands were submitted!");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!List.of("futebol", "teams", "remake").contains(event.getName())) return;
        if (event.getGuild() == null) {
            event.reply("Use this command in a Discord server.").setEphemeral(true).queue();
            return;
        }
        try {
            store.ensureGuild(event.getGuild().getId());
            switch (event.getName()) {
                case "futebol" -> startAttendance(event);
                case "teams"   -> generateFairTeams(event);
                case "remake"  -> generateRandomTeams(event);
                default -> { /* ignore */ }
            }
        } catch (RuntimeException error) {
            error.printStackTrace(System.err);
            event.reply("Could not save the team division. Please try again.").setEphemeral(true).queue();
        }
    }

    /**
     * /futebol: creates ONE message panel. Buttons stay in the same place.
     * Each click edits that same message to the next player.
     */
    private void startAttendance(SlashCommandInteractionEvent event) {
        String key = sessionKey(event.getGuild().getId(), event.getUser().getId(), event.getChannel().getId());

        if (SESSIONS.containsKey(key)) {
            event.reply("There is already an attendance check running in this channel for you.")
                    .setEphemeral(true).queue();
            return;
        }

        Session session = new Session(event.getUser().getId(), event.getChannel().getId(), PLAYERS);
        SESSIONS.put(key, session);

        // Send ONE fixed panel message and store its messageId.
        event.reply(attendancePrompt(session))
                .addActionRow(
                        Button.success(BTN_YES, "Yes"),
                        Button.danger(BTN_NO, "No")
                )
                .queue(hook -> hook.retrieveOriginal().queue(msg -> session.panelMessageId = msg.getIdLong()));
    }

    /**
     * /teams: fair fixed 5v5 (10 players).
     * - If <10 confirmed: warns
     * - If =10: balance and output
     * - If >10: pick best 10 (many tries), list substitutes
     */
    private void generateFairTeams(SlashCommandInteractionEvent event) {
        List<String> confirmed = confirmedForTeams(event);
        if (confirmed == null) return;

        Pick10Result pick = pickBest10ForBalance(confirmed);
        Teams teams = splitIntoBalanced5v5(pick.chosenPlayers);
        TeamDivision division = store.saveGeneratedDivision(event.getGuild().getId(), event.getChannel().getId(),
                event.getUser().getId(), teams.teamA, teams.teamB, "TEAMS");

        event.reply(formatTeamsMessage(
                "🎲 **Fair Teams (Fixed 5v5)**",
                teams,
                pick.substitutes,
                pick.bestDifference,
                false
        ) + "\n\nSaved division **#" + division.id() + "**. After the game, use **/result generated**.").queue();
    }

    /**
     * /remake: random fixed 5v5 (10 players).
     * - If <10 confirmed: warns
     * - If =10: random split
     * - If >10: random pick 10 + random split, list substitutes
     */
    private void generateRandomTeams(SlashCommandInteractionEvent event) {
        List<String> confirmed = confirmedForTeams(event);
        if (confirmed == null) return;

        Pick10Result pick = pickRandom10(confirmed);
        Teams teams = splitRandom5v5(pick.chosenPlayers);
        TeamDivision division = store.saveGeneratedDivision(event.getGuild().getId(), event.getChannel().getId(),
                event.getUser().getId(), teams.teamA, teams.teamB, "REMAKE");

        event.reply(formatTeamsMessage(
                "🔁 **Remake Teams (Random 5v5)**",
                teams,
                pick.substitutes,
                -1.0,
                true
        ) + "\n\nSaved division **#" + division.id() + "**. After the game, use **/result generated**.").queue();
    }

    /** Replies with the original warning when team generation is unavailable. */
    private List<String> confirmedForTeams(SlashCommandInteractionEvent event) {
        String channelId = event.getGuild().getId() + ":" + event.getChannel().getId();
        List<String> confirmed = LAST_CONFIRMED_BY_CHANNEL.get(channelId);

        if (confirmed == null || confirmed.isEmpty()) {
            event.reply("No confirmed list found for this channel yet. Run **/futebol** first.")
                    .setEphemeral(true).queue();
            return null;
        }

        if (confirmed.size() < 10) {
            event.reply("⚠️ You have **" + confirmed.size() + "** confirmed players. You need **10** for fixed 5v5.")
                    .queue();
            return null;
        }

        return confirmed;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals(BTN_YES) && !id.equals(BTN_NO)) return;
        if (event.getGuild() == null) {
            event.reply("Use this panel in a Discord server.").setEphemeral(true).queue();
            return;
        }

        String key = sessionKey(event.getGuild().getId(), event.getUser().getId(), event.getChannel().getId());
        Session session = SESSIONS.get(key);

        if (session == null) {
            event.reply("No active attendance check here. Run **/futebol** to start one.")
                    .setEphemeral(true).queue();
            return;
        }

        // Only the user who started the session can click
        if (!event.getUser().getId().equals(session.userId)) {
            event.reply("Only the person who started the attendance check can answer here.")
                    .setEphemeral(true).queue();
            return;
        }

        // Ensure clicks happen on the correct panel message
        if (session.panelMessageId == null || event.getMessageIdLong() != session.panelMessageId) {
            event.reply("This panel is not the active one. Run **/futebol** again if needed.")
                    .setEphemeral(true).queue();
            return;
        }

        String current = session.currentPlayer();
        if (current == null) {
            event.reply("This attendance check is already finished.")
                    .setEphemeral(true).queue();
            return;
        }

        boolean yes = id.equals(BTN_YES);
        session.recordAnswer(yes);

        if (session.isFinished()) {
            // Store confirmed list for /teams and /remake
            SESSIONS.remove(key);
            LAST_CONFIRMED_BY_CHANNEL.put(event.getGuild().getId() + ":" + session.channelId, new ArrayList<>(session.confirmed));

            // Edit same message to summary and remove buttons
            event.editMessage(attendanceSummary(session))
                    .setComponents()
                    .queue();
        } else {
            // Edit same message to next player and keep buttons fixed
            event.editMessage(attendancePrompt(session))
                    .setActionRow(
                            Button.success(BTN_YES, "Yes"),
                            Button.danger(BTN_NO, "No")
                    )
                    .queue();
        }
    }

    private static String sessionKey(String guildId, String userId, String channelId) {
        return guildId + ":" + userId + ":" + channelId;
    }
}
