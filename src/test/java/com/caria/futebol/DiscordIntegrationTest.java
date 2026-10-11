package com.caria.futebol;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordIntegrationTest {
    @TempDir Path temporary;
    private FootballStore store;
    private StatisticsListener listener;

    @BeforeEach void setup() {
        store = new FootballStore(temporary.resolve("football.db")); store.ensureGuild("guild");
        listener = new StatisticsListener(store);
    }
    private SlashCommandInteractionEvent slash(String name, String subcommand) {
        var event = mock(SlashCommandInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getName()).thenReturn(name); when(event.getSubcommandName()).thenReturn(subcommand);
        when(event.getGuild().getId()).thenReturn("guild"); when(event.getUser().getId()).thenReturn("user");
        when(event.getChannel().getId()).thenReturn("channel");
        return event;
    }
    private ButtonInteractionEvent button(String id) {
        var event = mock(ButtonInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(event.getComponentId()).thenReturn(id); when(event.getGuild().getId()).thenReturn("guild");
        when(event.getUser().getId()).thenReturn("user"); when(event.getChannel().getId()).thenReturn("channel");
        return event;
    }
    private ResultDraft ready() {
        TeamDivision division = store.saveGeneratedDivision("guild", "channel", "user", PlayerCatalog.PLAYERS.subList(0, 5), PlayerCatalog.PLAYERS.subList(5, 10), "TEAMS");
        ResultDraft draft = store.beginGenerated("guild", "user", "channel", division.id());
        return store.score("guild", "user", draft.id(), 8, 6, Instant.parse("2026-01-01T12:00:00Z"), draft.revision());
    }
    private List<Long> ids(int start, int end) { return store.players("guild").subList(start, end).stream().map(Player::id).toList(); }

    @Test void commandDefinitionsSerializeAndPreserveTheExistingCommands() {
        var commands = FootballCommands.all();
        assertEquals(Set.of("futebol", "teams", "remake", "profile", "history", "leaderboard", "result", "match", "player", "settings"),
                new HashSet<>(commands.stream().map(command -> command.getName()).toList()));
        for (var command : commands) assertDoesNotThrow(command::toData);
    }
    @Test void originalAttendanceTeamsAndRemakeStillWorkAndSaveIndependentSnapshots() {
        BotMain original = new BotMain(store);
        SlashCommandInteractionEvent start = slash("futebol", null);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(start.reply(anyString())).thenReturn(reply);
        InteractionHook hook = mock(InteractionHook.class);
        RestAction<Message> retrieve = mock(RestAction.class);
        Message panel = mock(Message.class);
        when(panel.getIdLong()).thenReturn(42L); when(hook.retrieveOriginal()).thenReturn(retrieve);
        doAnswer(call -> { ((Consumer<Message>) call.getArgument(0)).accept(panel); return null; }).when(retrieve).queue(any(Consumer.class));
        doAnswer(call -> { ((Consumer<InteractionHook>) call.getArgument(0)).accept(hook); return null; }).when(reply).queue(any(Consumer.class));
        original.onSlashCommandInteraction(start);
        verify(start).reply(contains("**Ribeiro** — are you in?"));
        for (int i = 0; i < PlayerCatalog.PLAYERS.size(); i++) {
            var answer = button(i < 10 ? "att:yes" : "att:no"); when(answer.getMessageIdLong()).thenReturn(42L);
            original.onButtonInteraction(answer);
        }
        var teams = slash("teams", null); original.onSlashCommandInteraction(teams);
        verify(teams).reply(contains("Fair Teams (Fixed 5v5)"));
        var remake = slash("remake", null); original.onSlashCommandInteraction(remake);
        verify(remake).reply(contains("Remake Teams (Random 5v5)"));
        var saved = store.pendingDivisions("guild", 0, 10);
        assertEquals(2, saved.size()); assertEquals("REMAKE", saved.get(0).mode()); assertEquals("TEAMS", saved.get(1).mode());
        assertEquals(5, saved.get(0).teamA().size()); assertEquals(5, saved.get(0).teamB().size());
        // Another guild with the same mocked channel ID cannot inherit attendance.
        var other = slash("teams", null); when(other.getGuild().getId()).thenReturn("other"); original.onSlashCommandInteraction(other);
        verify(other).reply(contains("No confirmed list found"));
    }
    @Test void manualCommandAndPersistentSelectionsWorkWithoutAttendance() {
        var event = slash("result", "manual"); listener.onSlashCommandInteraction(event);
        verify(event).replyEmbeds(any(MessageEmbed.class));
        // Resume can build real Discord component payloads without a live session map.
        var draft = store.beginManual("guild", "user", "channel");
        draft = store.selectManualTeam("guild", "user", draft.id(), "A", ids(0, 5), draft.revision());
        draft = store.selectManualTeam("guild", "user", draft.id(), "B", ids(5, 10), draft.revision());
        var freeze = button("fb:freeze:" + draft.id() + ":" + draft.revision()); listener.onButtonInteraction(freeze);
        assertNotNull(store.draft("guild", "user", draft.id()).divisionId());
        verify(freeze).editMessageEmbeds(any(MessageEmbed.class));
    }
    @Test void componentConfirmationIsOwnerBoundAndPersistsExactlyOnce() {
        ResultDraft draft = ready();
        var thief = button("fb:confirm:" + draft.id() + ":" + draft.revision()); when(thief.getUser().getId()).thenReturn("another");
        listener.onButtonInteraction(thief); assertTrue(store.matches("guild", null, 0, 10).isEmpty());
        verify(thief).reply(anyString());
        var valid = button("fb:confirm:" + draft.id() + ":" + draft.revision()); listener.onButtonInteraction(valid);
        assertEquals(1, store.matches("guild", null, 0, 10).size());
        var repeat = button("fb:confirm:" + draft.id() + ":" + draft.revision()); listener.onButtonInteraction(repeat);
        assertEquals(1, store.matches("guild", null, 0, 10).size()); verify(repeat).reply(contains("already"));
    }
    @Test void unauthorizedEditingAndDeletingCannotMutateMatches() {
        ResultDraft draft = ready(); long match = store.confirm("guild", "user", draft.id(), draft.revision(), false);
        for (String action : List.of("edit", "delete")) {
            var event = slash("match", action); when(event.getOption("id").getAsLong()).thenReturn(match);
            listener.onSlashCommandInteraction(event); verify(event).reply(contains("Manage Server"));
        }
        ResultDraft delete = store.beginChange("guild", "user", "channel", match, true);
        var event = button("fb:confirm:" + delete.id() + ":" + delete.revision());
        listener.onButtonInteraction(event); verify(event).reply(contains("not authorized"));
        assertEquals(8, store.match("guild", match).goalsA());
        var allowed = button("fb:confirm:" + delete.id() + ":" + delete.revision());
        when(allowed.getMember().hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        listener.onButtonInteraction(allowed); assertTrue(store.matches("guild", null, 0, 10).isEmpty());
    }
    @Test void linkingUsesTheRosterPlayerAndDiscordUserRatherThanProfileFilterRules() {
        var event = slash("player", "link");
        when(event.getMember().hasPermission(Permission.MANAGE_SERVER)).thenReturn(true);
        when(event.getOption("player").getAsString()).thenReturn("Caria"); when(event.getOption("user").getAsUser().getId()).thenReturn("linked-user");
        listener.onSlashCommandInteraction(event); assertEquals("Caria", store.playerByDiscord("guild", "linked-user").name());
    }
    @Test void scoreModalShowsSummaryButDoesNotSaveUntilConfirm() {
        TeamDivision division = store.saveGeneratedDivision("guild", "channel", "user", PlayerCatalog.PLAYERS.subList(0, 5), PlayerCatalog.PLAYERS.subList(5, 10), "TEAMS");
        ResultDraft draft = store.beginGenerated("guild", "user", "channel", division.id());
        var modal = mock(ModalInteractionEvent.class, RETURNS_DEEP_STUBS);
        when(modal.getModalId()).thenReturn("fb:score:" + draft.id() + ":0"); when(modal.getGuild().getId()).thenReturn("guild");
        when(modal.getUser().getId()).thenReturn("user");
        when(modal.getValue("goals_a").getAsString()).thenReturn("8"); when(modal.getValue("goals_b").getAsString()).thenReturn("6");
        when(modal.getValue("played_at").getAsString()).thenReturn("2026-01-01 12:00");
        listener.onModalInteraction(modal);
        assertEquals("READY", store.draft("guild", "user", draft.id()).status()); assertTrue(store.matches("guild", null, 0, 10).isEmpty());
        verify(modal).editMessageEmbeds(any(MessageEmbed.class));
    }
    @Test void dateInputsUseUtcAndRejectInvalidDates() {
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), StatisticsListener.parsePlayedAt("2026-01-01 12:00"));
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), StatisticsListener.parsePlayedAt("2026-01-01T13:00:00+01:00"));
        assertThrows(FootballException.class, () -> StatisticsListener.parsePlayedAt("2026-02-31"));
    }

    @Test void configuredRolesCanCorrectMatchesButCannotChangeAuthorizationSettings() {
        store.configure("guild", 5, 5, Set.of("match-role"));
        ResultDraft ready = ready(); long match = store.confirm("guild", "user", ready.id(), ready.revision(), false);
        ResultDraft delete = store.beginChange("guild", "user", "channel", match, true);
        var role = mock(net.dv8tion.jda.api.entities.Role.class); when(role.getId()).thenReturn("match-role");
        var confirm = button("fb:confirm:" + delete.id() + ":" + delete.revision());
        when(confirm.getMember().getRoles()).thenReturn(List.of(role));
        listener.onButtonInteraction(confirm); assertTrue(store.matches("guild", null, 0, 10).isEmpty());
        var settings = slash("settings", null); when(settings.getMember().getRoles()).thenReturn(List.of(role));
        listener.onSlashCommandInteraction(settings); verify(settings).reply(contains("Only server managers"));
    }
}
