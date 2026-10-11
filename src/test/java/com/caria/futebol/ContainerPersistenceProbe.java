package com.caria.futebol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/** Run by two distinct disposable containers against a dedicated test volume, never the live database. */
public final class ContainerPersistenceProbe {
    public static void main(String[] args) throws Exception {
        FootballStore store = new FootballStore(Path.of("/data/probe.db"));
        String guild = "probe-guild", user = "probe-user";
        store.ensureGuild(guild);
        if (args[0].equals("write")) {
            TeamDivision division = store.saveGeneratedDivision(guild, "channel", user, PlayerCatalog.PLAYERS.subList(0, 5), PlayerCatalog.PLAYERS.subList(5, 10), "TEAMS");
            ResultDraft result = store.beginGenerated(guild, user, "channel", division.id());
            result = store.score(guild, user, result.id(), 8, 6, Instant.parse("2026-01-01T12:00:00Z"), result.revision());
            store.confirm(guild, user, result.id(), result.revision(), false);
            store.linkPlayer(guild, store.players(guild).get(0).id(), "linked-probe-user");
            store.configure(guild, 4, 3, Set.of("probe-role"));
            TeamDivision pending = store.saveGeneratedDivision(guild, "channel", user, PlayerCatalog.PLAYERS.subList(0, 5), PlayerCatalog.PLAYERS.subList(5, 10), "REMAKE");
            ResultDraft form = store.beginGenerated(guild, user, "channel", pending.id());
            Files.writeString(Path.of("/data/draft-id"), form.id());
        } else if (args[0].equals("read")) {
            if (store.matches(guild, null, 0, 10).size() != 1) throw new AssertionError("Match was lost");
            if (store.pendingDivisions(guild, 0, 10).size() != 1) throw new AssertionError("Division was lost");
            if (store.settings(guild).manualTeamSize() != 4 || store.settings(guild).minimumWinRateGames() != 3) throw new AssertionError("Settings were lost");
            if (!store.settings(guild).authorizedRoles().equals(Set.of("probe-role"))) throw new AssertionError("Roles were lost");
            Player linked = store.playerByDiscord(guild, "linked-probe-user");
            if (new StatisticsService(store).profile(guild, linked.id()).wins() != 1) throw new AssertionError("Profile was lost");
            if (!store.draft(guild, user, Files.readString(Path.of("/data/draft-id"))).status().equals("ACTIVE")) throw new AssertionError("Form was lost");
        } else throw new IllegalArgumentException("Use write or read");
        System.out.println("Container persistence " + args[0] + " passed");
    }
}
