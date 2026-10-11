package com.caria.futebol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class FootballStoreTest {
    @TempDir Path directory;
    private Path database;
    private FootballStore store;
    private static final String GUILD = "guild", USER = "user", CHANNEL = "channel";
    private static final Instant DATE = Instant.parse("2026-01-01T12:00:00Z");

    @BeforeEach void setup() {
        database = directory.resolve("football.db");
        store = new FootballStore(database); store.ensureGuild(GUILD);
    }
    private TeamDivision generated() {
        return store.saveGeneratedDivision(GUILD, CHANNEL, USER, PlayerCatalog.PLAYERS.subList(0, 5),
                PlayerCatalog.PLAYERS.subList(5, 10), "TEAMS");
    }
    private long record(TeamDivision division, int a, int b, Instant date) {
        ResultDraft draft = store.beginGenerated(GUILD, USER, CHANNEL, division.id());
        draft = store.score(GUILD, USER, draft.id(), a, b, date, draft.revision());
        return store.confirm(GUILD, USER, draft.id(), draft.revision(), false);
    }
    private List<Long> playerIds(int from, int to) { return store.players(GUILD).subList(from, to).stream().map(Player::id).toList(); }
    private ResultDraft manual() {
        ResultDraft draft = store.beginManual(GUILD, USER, CHANNEL);
        draft = store.selectManualTeam(GUILD, USER, draft.id(), "A", playerIds(0, 5), draft.revision());
        draft = store.selectManualTeam(GUILD, USER, draft.id(), "B", playerIds(5, 10), draft.revision());
        return store.freezeManualTeams(GUILD, USER, draft.id(), draft.revision());
    }

    @Test void winsDrawsLossesAndTeamGoalsAreDerivedFromHistory() {
        record(generated(), 8, 6, DATE);
        record(generated(), 2, 2, DATE.plusSeconds(60));
        record(generated(), 0, 4, DATE.plusSeconds(120));
        var stats = new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0));
        assertEquals(3, stats.gamesPlayed()); assertEquals(1, stats.wins()); assertEquals(1, stats.draws()); assertEquals(1, stats.losses());
        assertEquals(100.0 / 3, stats.winRate(), 1e-9); assertEquals(4, stats.points());
        assertEquals(10, stats.goalsFor()); assertEquals(12, stats.goalsAgainst()); assertEquals(-2, stats.goalDifference());
        assertEquals("1 Losses", stats.currentStreak()); assertEquals(1, stats.bestWinStreak()); assertEquals(DATE.plusSeconds(120), stats.lastPlayed());
        var opponent = new StatisticsService(store).profile(GUILD, playerIds(5, 6).get(0));
        assertEquals(12, opponent.goalsFor()); assertEquals(10, opponent.goalsAgainst()); assertEquals(1, opponent.wins());
    }
    @Test void noMatchesHaveNoWinRateAndLeaderboardRequiresConfiguredMinimum() {
        long id = playerIds(0, 1).get(0);
        assertTrue(Double.isNaN(new StatisticsService(store).profile(GUILD, id).winRate()));
        record(generated(), 8, 6, DATE);
        assertTrue(new StatisticsService(store).leaderboard(GUILD, StatisticsService.Metric.WIN_RATE).isEmpty());
        store.configure(GUILD, 5, 1, Set.of("role"));
        assertEquals(10, new StatisticsService(store).leaderboard(GUILD, StatisticsService.Metric.WIN_RATE).size());
        assertEquals(18, new StatisticsService(store).leaderboard(GUILD, StatisticsService.Metric.GAMES).size());
        assertEquals(Set.of("role"), store.settings(GUILD).authorizedRoles());
    }
    @Test void streaksUseChronologicalDatesAndMatchIdForEqualDates() {
        record(generated(), 0, 1, DATE.plusSeconds(100)); // inserted first but played last
        record(generated(), 1, 0, DATE);
        record(generated(), 2, 0, DATE);
        record(generated(), 3, 3, DATE.plusSeconds(50));
        var stats = new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0));
        assertEquals(2, stats.bestWinStreak()); assertEquals("1 Losses", stats.currentStreak());
        assertEquals(DATE.plusSeconds(100), stats.lastPlayed());
        assertEquals(4, store.matches(GUILD, null, 0, 10).size());
    }
    @Test void generatedAndManualTeamsArePersistedAndCountEqually() {
        TeamDivision generated = generated(); record(generated, 8, 6, DATE);
        ResultDraft manual = manual(); manual = store.score(GUILD, USER, manual.id(), 0, 0, DATE.plusSeconds(1), manual.revision());
        long id = store.confirm(GUILD, USER, manual.id(), manual.revision(), false);
        assertEquals("MANUAL", store.match(GUILD, id).division().source());
        assertEquals(2, new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0)).gamesPlayed());
        assertEquals(1, new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0)).draws());
        assertEquals(2, store.matches(GUILD, playerIds(0, 1).get(0), 0, 10).size());
        assertTrue(store.matches(GUILD, playerIds(17, 18).get(0), 0, 10).isEmpty());
    }
    @Test void manualTeamSizeIsConfiguredWithoutChangingFixedGeneratedFiveVsFive() {
        store.configure(GUILD, 3, 5, Set.of());
        ResultDraft draft = store.beginManual(GUILD, USER, CHANNEL);
        draft = store.selectManualTeam(GUILD, USER, draft.id(), "A", playerIds(0, 3), draft.revision());
        draft = store.selectManualTeam(GUILD, USER, draft.id(), "B", playerIds(3, 6), draft.revision());
        draft = store.freezeManualTeams(GUILD, USER, draft.id(), draft.revision());
        assertEquals(3, draft.teamA().size()); assertEquals(5, generated().teamSize());
    }
    @Test void duplicateCrossTeamUnknownAndWrongSizePlayersAreRejectedAtomically() {
        ResultDraft initial = store.beginManual(GUILD, USER, CHANNEL);
        List<Long> duplicate = new ArrayList<>(playerIds(0, 5)); duplicate.set(4, duplicate.get(0));
        assertThrows(FootballException.class, () -> store.selectManualTeam(GUILD, USER, initial.id(), "A", duplicate, 0));
        assertThrows(FootballException.class, () -> store.selectManualTeam(GUILD, USER, initial.id(), "A", List.of(99999L, 99998L, 99997L, 99996L, 99995L), 0));
        assertThrows(FootballException.class, () -> store.selectManualTeam(GUILD, USER, initial.id(), "A", playerIds(0, 4), 0));
        ResultDraft a = store.selectManualTeam(GUILD, USER, initial.id(), "A", playerIds(0, 5), 0);
        assertThrows(FootballException.class, () -> store.selectManualTeam(GUILD, USER, a.id(), "B", playerIds(4, 9), a.revision()));
        assertTrue(store.draft(GUILD, USER, a.id()).teamB().isEmpty());
        assertThrows(FootballException.class, () -> store.freezeManualTeams(GUILD, USER, a.id(), a.revision()));
    }
    @Test void invalidScoresAndFutureDatesAreRejected() {
        for (String bad : List.of("-1", "1.5", "NaN", "", "2147483648")) assertThrows(FootballException.class, () -> FootballStore.parseGoals(bad));
        assertEquals(8, FootballStore.parseGoals(" 8 "));
        ResultDraft draft = store.beginGenerated(GUILD, USER, CHANNEL, generated().id());
        assertThrows(FootballException.class, () -> store.score(GUILD, USER, draft.id(), -1, 0, DATE, draft.revision()));
        assertThrows(FootballException.class, () -> store.score(GUILD, USER, draft.id(), 1, 0, Instant.now().plusSeconds(1000), draft.revision()));
        assertTrue(store.matches(GUILD, null, 0, 10).isEmpty());
    }
    @Test void doubleClicksAndTwoFormsForTheSameDivisionCannotRecordTwice() {
        TeamDivision division = generated();
        ResultDraft one = store.beginGenerated(GUILD, USER, CHANNEL, division.id());
        ResultDraft two = store.beginGenerated(GUILD, "another", CHANNEL, division.id());
        one = store.score(GUILD, USER, one.id(), 8, 6, DATE, one.revision());
        two = store.score(GUILD, "another", two.id(), 8, 6, DATE, two.revision());
        final ResultDraft first = one, second = two;
        store.confirm(GUILD, USER, first.id(), first.revision(), false);
        assertThrows(FootballException.class, () -> store.confirm(GUILD, USER, first.id(), first.revision(), false));
        assertThrows(FootballException.class, () -> store.confirm(GUILD, "another", second.id(), second.revision(), false));
        assertEquals(1, store.matches(GUILD, null, 0, 10).size()); assertTrue(store.pendingDivisions(GUILD, 0, 10).isEmpty());
    }
    @Test void duplicateManualFormsAndCancelledFormsCannotRecord() {
        ResultDraft first = manual(); first = store.score(GUILD, USER, first.id(), 8, 6, DATE, first.revision());
        store.confirm(GUILD, USER, first.id(), first.revision(), false);
        ResultDraft duplicate = manual(); duplicate = store.score(GUILD, USER, duplicate.id(), 8, 6, DATE, duplicate.revision());
        final ResultDraft repeated = duplicate;
        assertThrows(FootballException.class, () -> store.confirm(GUILD, USER, repeated.id(), repeated.revision(), false));
        store.cancel(GUILD, USER, repeated.id());
        assertThrows(FootballException.class, () -> store.confirm(GUILD, USER, repeated.id(), repeated.revision(), false));
    }
    @Test void editAndDeleteRecalculateAllStatisticsAndRejectUnauthorizedAndStaleChanges() {
        long match = record(generated(), 8, 6, DATE);
        ResultDraft edit = store.beginChange(GUILD, USER, CHANNEL, match, false);
        edit = store.score(GUILD, USER, edit.id(), 6, 8, DATE, edit.revision());
        final ResultDraft ready = edit;
        assertThrows(FootballException.class, () -> store.confirm(GUILD, USER, ready.id(), ready.revision(), false));
        ResultDraft stale = store.beginChange(GUILD, USER, CHANNEL, match, true);
        store.confirm(GUILD, USER, ready.id(), ready.revision(), true);
        var stats = new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0));
        assertEquals(0, stats.wins()); assertEquals(1, stats.losses()); assertEquals(0, stats.winRate());
        assertEquals(-2, stats.goalDifference()); assertEquals(0, stats.bestWinStreak());
        assertThrows(FootballException.class, () -> store.confirm(GUILD, USER, stale.id(), stale.revision(), true));
        ResultDraft delete = store.beginChange(GUILD, USER, CHANNEL, match, true);
        store.confirm(GUILD, USER, delete.id(), delete.revision(), true);
        assertEquals(0, new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0)).gamesPlayed());
        assertThrows(FootballException.class, () -> store.match(GUILD, match));
    }
    @Test void restartRecoversDivisionsFormsBindingsHistoryAndSettings() {
        TeamDivision first = generated(), second = generated();
        store.linkPlayer(GUILD, playerIds(0, 1).get(0), "discord-user");
        store.configure(GUILD, 4, 2, Set.of("role"));
        ResultDraft draft = store.beginGenerated(GUILD, USER, CHANNEL, first.id());
        draft = store.score(GUILD, USER, draft.id(), 8, 6, DATE, draft.revision());
        FootballStore restarted = new FootballStore(database); restarted.ensureGuild(GUILD);
        assertEquals(2, restarted.pendingDivisions(GUILD, 0, 10).size());
        assertEquals("discord-user", restarted.playerByDiscord(GUILD, "discord-user").discordId());
        long match = restarted.confirm(GUILD, USER, draft.id(), draft.revision(), false);
        assertEquals(8, new FootballStore(database).match(GUILD, match).goalsA());
        assertEquals(second.id(), restarted.pendingDivisions(GUILD, 0, 10).get(0).id());
        assertEquals(4, restarted.settings(GUILD).manualTeamSize()); assertEquals(2, restarted.settings(GUILD).minimumWinRateGames());
    }
    @Test void serverAndUserIsolationAppliesToEveryIdentifier() {
        store.ensureGuild("other");
        TeamDivision division = generated();
        ResultDraft draft = store.beginGenerated(GUILD, USER, CHANNEL, division.id());
        assertThrows(FootballException.class, () -> store.division("other", division.id()));
        assertThrows(FootballException.class, () -> store.beginGenerated("other", USER, CHANNEL, division.id()));
        assertThrows(FootballException.class, () -> store.draft(GUILD, "another", draft.id()));
        assertThrows(FootballException.class, () -> store.draft("other", USER, draft.id()));
        long match = record(division, 1, 0, DATE);
        assertThrows(FootballException.class, () -> store.match("other", match));
        assertTrue(store.matches("other", null, 0, 10).isEmpty());
        assertThrows(FootballException.class, () -> store.player("other", playerIds(0, 1).get(0)));
        assertThrows(FootballException.class, () -> store.linkPlayer("other", playerIds(0, 1).get(0), "discord"));
    }
    @Test void concurrentConnectionsStillAllowOnlyOneConfirmation() throws Exception {
        TeamDivision division = generated();
        ResultDraft a = store.beginGenerated(GUILD, USER, CHANNEL, division.id());
        ResultDraft b = store.beginGenerated(GUILD, "another", CHANNEL, division.id());
        final ResultDraft first = store.score(GUILD, USER, a.id(), 8, 6, DATE, a.revision());
        final ResultDraft second = store.score(GUILD, "another", b.id(), 8, 6, DATE, b.revision());
        FootballStore independent = new FootballStore(database);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> outcomes = executor.invokeAll(List.of(
                    () -> confirmOrConflict(store, USER, first), () -> confirmOrConflict(independent, "another", second)));
            int successes = 0; for (Future<Boolean> outcome : outcomes) if (outcome.get()) successes++;
            assertEquals(1, successes); assertEquals(1, store.matches(GUILD, null, 0, 10).size());
        } finally { executor.shutdownNow(); }
    }
    private static boolean confirmOrConflict(FootballStore store, String user, ResultDraft draft) {
        try { store.confirm(GUILD, user, draft.id(), draft.revision(), false); return true; }
        catch (FootballException e) { return false; }
    }
    @Test void staleManualComponentsCannotOverwriteNewSelections() {
        ResultDraft draft = store.beginManual(GUILD, USER, CHANNEL);
        store.selectManualTeam(GUILD, USER, draft.id(), "A", playerIds(0, 5), 0);
        assertThrows(FootballException.class, () -> store.selectManualTeam(GUILD, USER, draft.id(), "B", playerIds(5, 10), 0));
    }

    @Test void allStreakTypesUseChronologicalOrderAndGoalsDoNotOverflowIntegers() {
        List<StatisticsService.Appearance> games = new ArrayList<>();
        for (int i = 0; i < 3; i++) games.add(new StatisticsService.Appearance(1, i + 1, DATE, Integer.MAX_VALUE, 0));
        for (int i = 3; i < 5; i++) games.add(new StatisticsService.Appearance(1, i + 1, DATE, 0, 1));
        assertEquals("2 Losses", StatisticsService.calculate(games).currentStreak());
        games.add(new StatisticsService.Appearance(1, 6, DATE, 1, 1));
        games.add(new StatisticsService.Appearance(1, 7, DATE, 0, 0));
        Collections.reverse(games);
        PlayerStatistics stats = StatisticsService.calculate(games);
        assertEquals("2 Draws", stats.currentStreak()); assertEquals(3, stats.bestWinStreak());
        assertEquals(3L * Integer.MAX_VALUE + 1, stats.goalsFor()); assertEquals(3, stats.goalsAgainst());
        assertEquals(11, stats.points());
    }

    @Test void fractionalTimesSortByTimeBeforeIdsAndHistoryPaginates() {
        long later = record(generated(), 0, 1, DATE.plusNanos(1));
        long earlier = record(generated(), 1, 0, DATE);
        assertEquals(later, store.matches(GUILD, null, 0, 1).get(0).id());
        assertEquals(earlier, store.matches(GUILD, null, 1, 1).get(0).id());
        assertTrue(store.matches(GUILD, null, 2, 1).isEmpty());
        assertEquals("1 Losses", new StatisticsService(store).profile(GUILD, playerIds(0, 1).get(0)).currentStreak());
    }

    @Test void playerBindingsAreUniquePerServerAndDoNotReplaceHistoricalIdentities() {
        record(generated(), 8, 6, DATE);
        long first = playerIds(0, 1).get(0), second = playerIds(1, 2).get(0);
        store.linkPlayer(GUILD, first, "discord");
        assertThrows(FootballException.class, () -> store.linkPlayer(GUILD, second, "discord"));
        assertEquals(1, new StatisticsService(store).profile(GUILD, store.playerByDiscord(GUILD, "discord").id()).wins());
        store.linkPlayer(GUILD, first, null);
        assertEquals(1, new StatisticsService(store).profile(GUILD, first).wins());
        assertThrows(FootballException.class, () -> store.playerByDiscord(GUILD, "discord"));
    }
}
