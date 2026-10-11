package com.caria.futebol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamGeneratorTest {
    @Test
    void balancesEveryTenPlayerSubsetWithoutLosingPlayers() {
        List<String> players = PlayerCatalog.PLAYERS;
        int checked = 0;
        for (int mask = 0; mask < (1 << players.size()); mask++) {
            if (Integer.bitCount(mask) != 10) continue;
            List<String> ten = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                if ((mask & (1 << i)) != 0) ten.add(players.get(i));
            }
            List<String> originalOrder = List.copyOf(ten);
            Teams teams = TeamGenerator.splitIntoBalanced5v5(ten);
            assertTeams(ten, teams);
            assertEquals(originalOrder, ten);
            checked++;
        }
        assertEquals(43758, checked);
    }

    @Test
    void tenConfirmedPlayersHaveNoSubstitutesAndKeepTheirOrder() {
        List<String> ten = PlayerCatalog.PLAYERS.subList(0, 10);
        Pick10Result pick = TeamGenerator.pickBest10ForBalance(ten);
        assertEquals(ten, pick.chosenPlayers);
        assertTrue(pick.substitutes.isEmpty());
        Teams teams = TeamGenerator.splitIntoBalanced5v5(ten);
        assertEquals(Math.abs(teams.sumA - teams.sumB), pick.bestDifference);
    }

    @Test
    void extraPlayersBecomeSubstitutesInAttendanceOrder() {
        for (int count = 11; count <= PlayerCatalog.PLAYERS.size(); count++) {
            List<String> confirmed = PlayerCatalog.PLAYERS.subList(0, count);
            Pick10Result pick = TeamGenerator.pickBest10ForBalance(confirmed);
            assertEquals(10, pick.chosenPlayers.size());
            assertEquals(confirmed.stream().filter(n -> !pick.chosenPlayers.contains(n)).toList(),
                    pick.substitutes);
            Teams teams = TeamGenerator.splitIntoBalanced5v5(pick.chosenPlayers);
            assertTeams(pick.chosenPlayers, teams);
            assertEquals(Math.abs(teams.sumA - teams.sumB), pick.bestDifference);
        }
    }

    @Test
    void randomRemakesKeepExactlyFivePlayersPerTeam() {
        List<String> ten = PlayerCatalog.PLAYERS.subList(0, 10);
        for (int i = 0; i < 100; i++) {
            assertTeams(ten, TeamGenerator.splitRandom5v5(ten));
        }
        assertEquals(PlayerCatalog.PLAYERS.subList(0, 10), ten);
    }

    @Test
    void randomSelectionPreservesAttendanceOrderForSubstitutes() {
        List<String> confirmed = PlayerCatalog.PLAYERS;
        List<String> originalOrder = List.copyOf(confirmed);
        for (int i = 0; i < 100; i++) {
            Pick10Result pick = TeamGenerator.pickRandom10(confirmed);
            assertEquals(10, new HashSet<>(pick.chosenPlayers).size());
            assertTrue(confirmed.containsAll(pick.chosenPlayers));
            assertEquals(confirmed.stream().filter(n -> !pick.chosenPlayers.contains(n)).toList(),
                    pick.substitutes);
            assertEquals(-1.0, pick.bestDifference);
        }
        assertEquals(originalOrder, confirmed);
    }

    @Test
    void unknownPlayersKeepTheOriginalDefaultRating() {
        assertEquals(5.0, PlayerCatalog.rankOf("Unknown"));
    }

    private static void assertTeams(List<String> chosen, Teams teams) {
        assertEquals(5, teams.teamA.size());
        assertEquals(5, teams.teamB.size());
        List<String> all = new ArrayList<>(teams.teamA);
        all.addAll(teams.teamB);
        assertEquals(new HashSet<>(chosen), new HashSet<>(all));
        assertEquals(10, new HashSet<>(all).size());
        assertEquals(teams.teamA.stream().mapToDouble(PlayerCatalog::rankOf).sum(), teams.sumA, 1e-9);
        assertEquals(teams.teamB.stream().mapToDouble(PlayerCatalog::rankOf).sum(), teams.sumB, 1e-9);
    }
}
