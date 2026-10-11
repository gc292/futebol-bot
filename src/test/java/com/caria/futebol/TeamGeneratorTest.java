package com.caria.futebol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamGeneratorTest {
    @Test void fixesAGreedySplitThatMissedAMuchCloserMatch() {
        double[] scores={89,87,77,67,62,32,24,21,16,14};
        var names=java.util.stream.IntStream.range(0,10).mapToObj(i -> "P"+i).toList();
        Teams teams=TeamGenerator.splitIntoBalanced5v5(names,name -> scores[Integer.parseInt(name.substring(1))]);
        assertEquals(1,Math.abs(teams.sumA-teams.sumB),1e-9); // Old greedy split: 17 points.
    }
    @Test void agreesWithAnIndependentExhaustiveOracleIncludingSubstitutesAndFractionalVotes() {
        var random=new java.util.Random(12345);
        for (int trial=0;trial<24;trial++) {
            int size=10+trial%4;
            double[] scores=java.util.stream.IntStream.range(0,size).mapToDouble(i -> random.nextInt(10001)/100.0).toArray();
            var names=java.util.stream.IntStream.range(0,size).mapToObj(i -> "P"+i).toList();
            java.util.function.ToDoubleFunction<String> rating=name -> scores[Integer.parseInt(name.substring(1))];
            Pick10Result pick=TeamGenerator.pickBest10ForBalance(names,rating);
            Teams split=TeamGenerator.splitIntoBalanced5v5(pick.chosenPlayers,rating);
            double expected=oracle(scores);
            assertEquals(expected,pick.bestDifference,1e-9);
            assertEquals(expected,Math.abs(split.sumA-split.sumB),1e-9);
            assertEquals(size-10,pick.substitutes.size());
            assertEquals(10,new HashSet<>(pick.chosenPlayers).size());
        }
    }
    private static double oracle(double[] ratings) {
        List<Integer> masks=new ArrayList<>();
        for (int mask=0;mask<(1<<ratings.length);mask++) if (Integer.bitCount(mask)==5) masks.add(mask);
        double best=Double.POSITIVE_INFINITY;
        for (int a:masks) for (int b:masks) {
            if ((a & b)!=0) continue;
            double sumA=0,sumB=0;
            for (int i=0;i<ratings.length;i++) {
                if ((a & (1<<i))!=0) sumA+=ratings[i];
                if ((b & (1<<i))!=0) sumB+=ratings[i];
            }
            best=Math.min(best,Math.abs(sumA-sumB));
        }
        return best;
    }
    @Test void handlesLargePoolsWithoutLosingAttendees() {
        for (int size:List.of(24,25)) {
            var names=java.util.stream.IntStream.range(0,size).mapToObj(i -> "P"+i).toList();
            var pick=TeamGenerator.pickBest10ForBalance(names,name -> 50.25);
            assertEquals(0,pick.bestDifference); assertEquals(10,pick.chosenPlayers.size());
            assertEquals(size-10,pick.substitutes.size());
        }
    }
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
        assertEquals(Math.abs(teams.sumA - teams.sumB), pick.bestDifference,1e-9);
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
            assertEquals(Math.abs(teams.sumA - teams.sumB), pick.bestDifference,1e-9);
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
