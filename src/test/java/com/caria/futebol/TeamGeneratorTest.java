package com.caria.futebol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamGeneratorTest {
    @Test void exactTopThreeMatchUnprunedIntegerOracleAcrossSixtyPools() {
        var rng=new java.util.Random(20261017);
        for(int trial=0;trial<60;trial++) {
            int n=10+trial%5; long[] units=new long[n];
            for(int i=0;i<n;i++) units[i]=trial%10==0 ? 50000 : rng.nextInt(100001);
            var names=java.util.stream.IntStream.range(0,n).mapToObj(i -> String.format("P%02d",i)).toList();
            List<Integer> masks=new ArrayList<>();List<Long> sums=new ArrayList<>();
            for(int mask=0;mask<(1<<n);mask++) if(Integer.bitCount(mask)==5) {
                long sum=0;for(int i=0;i<n;i++)if((mask&(1<<i))!=0)sum+=units[i];masks.add(mask);sums.add(sum);
            }
            long[] expected={Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE};
            // Independent oracle: visit every disjoint pair, no sum sorting or pruning.
            for(int a=0;a<masks.size();a++)for(int b=a+1;b<masks.size();b++)if((masks.get(a)&masks.get(b))==0){
                long gap=Math.abs(sums.get(a)-sums.get(b));
                if(gap<expected[2]){expected[2]=gap;java.util.Arrays.sort(expected);}
            }
            var options=TeamGenerator.rankedOptions(names,name -> units[Integer.parseInt(name.substring(1))]/1000.0);
            var keys=new HashSet<String>();
            for(int i=0;i<3;i++){
                Teams t=options.get(i);assertEquals(expected[i]/5000.0,Math.abs(t.sumA/5-t.sumB/5),1e-9,"Trial "+trial+" option "+i);
                var used=new HashSet<>(t.teamA);used.addAll(t.teamB);assertEquals(10,used.size());
                var sides=new ArrayList<>(List.of(t.teamA.toString(),t.teamB.toString()));sides.sort(String::compareTo);assertTrue(keys.add(sides.toString()));
            }
            var reversed=new ArrayList<>(names);java.util.Collections.reverse(reversed);
            assertEquals(options.get(0).teamA,TeamGenerator.rankedOptions(reversed,name -> units[Integer.parseInt(name.substring(1))]/1000.0).get(0).teamA);
        }
    }
    @Test void exactSearchBeyondOldCutoffFindsAnalyticallyKnownTopThreeAndHandlesBit31() {
        // For powers of two the smallest gaps are 31,47,47 units across any pool >=11.
        // This fixture has no perfect ties, so a zero-gap shortcut cannot mask sampling.
        for(int n:List.of(25,32)) {
            var names=java.util.stream.IntStream.range(0,n).mapToObj(i -> String.format("P%02d",i)).toList();
            var options=TeamGenerator.rankedOptions(names,name -> Math.scalb(1.0,Integer.parseInt(name.substring(1))-26));
            double[] expected={31,47,47};
            for(int i=0;i<3;i++)assertEquals(Math.scalb(expected[i],-26)/5,Math.abs(options.get(i).sumA/5-options.get(i).sumB/5),1e-14);
        }
        var tooMany=java.util.stream.IntStream.range(0,33).mapToObj(i -> "P"+i).toList();
        assertTrue(assertThrows(FootballException.class,()->TeamGenerator.rankedOptions(tooMany,n -> 50)).getMessage().contains("32"));
        assertEquals(10,TeamGenerator.pickRandom10(tooMany).chosenPlayers.size());
        var highBit=java.util.stream.IntStream.range(0,32).mapToObj(i -> String.format("P%02d",i)).toList();
        var forced=TeamGenerator.rankedOptions(highBit,name -> {
            int i=Integer.parseInt(name.substring(1));return i<9 ? 0 : i==31 ? Math.scalb(1.0,-16) : Math.scalb(1.0,i-24);
        });
        for(Teams t:forced){assertTrue(t.teamA.contains("P31")||t.teamB.contains("P31"));assertEquals(Math.scalb(1.0,-16),Math.abs(t.sumA-t.sumB));}

    }
    @Test void rankedOptionsMatchTheThreeBestDistinctSplitsIncludingTiesAndSubstitutes() {
        for (double[] ratings:List.of(new double[]{89,87,77,67,62,32,24,21,16,14},new double[]{98,83,72,62,51,44,39,31,22,13,7},new double[]{50,50,50,50,50,50,50,50,50,50,50,50},new double[]{8.1,8.2,8.3,4.4,5.5,6.6,7.7,9.8,9.9,10.1})) {
            var names=java.util.stream.IntStream.range(0,ratings.length).mapToObj(i -> "P"+i).toList();
            java.util.function.ToDoubleFunction<String> score=n -> ratings[Integer.parseInt(n.substring(1))];
            List<Double> gaps=new ArrayList<>();
            for(int a=0;a<(1<<ratings.length);a++) if(Integer.bitCount(a)==5)
                for(int b=a+1;b<(1<<ratings.length);b++) if(Integer.bitCount(b)==5 && (a&b)==0) {
                    double aa=0,bb=0;for(int i=0;i<ratings.length;i++){if((a&(1<<i))!=0)aa+=ratings[i];if((b&(1<<i))!=0)bb+=ratings[i];}
                    gaps.add(Math.abs(aa-bb));
                }
            gaps.sort(Double::compare);var options=TeamGenerator.rankedOptions(names,score);assertEquals(3,options.size());
            var keys=new HashSet<String>();
            for(int i=0;i<3;i++) {
                Teams t=options.get(i);assertEquals(gaps.get(i),Math.abs(t.sumA-t.sumB),1e-9);
                assertEquals(5,t.teamA.size());assertEquals(5,t.teamB.size());
                var all=new HashSet<>(t.teamA);all.addAll(t.teamB);assertEquals(10,all.size());assertTrue(names.containsAll(all));
                var sides=new ArrayList<>(List.of(t.teamA.stream().sorted().toList().toString(),t.teamB.stream().sorted().toList().toString()));sides.sort(String::compareTo);assertTrue(keys.add(sides.toString()));
            }
            assertEquals(options.get(0).teamA,TeamGenerator.rankedOptions(names,score).get(0).teamA);
        }
    }
    @Test void rankedOptionsHandleLargeGroupsAndKeepAllAttendeesAvailable() {
        for(int n:List.of(24,25)) {
            var names=java.util.stream.IntStream.range(0,n).mapToObj(i -> "P"+i).toList();
            var options=TeamGenerator.rankedOptions(names,x -> 50);assertEquals(3,options.size());
            for(Teams t:options){assertEquals(0,Math.abs(t.sumA-t.sumB));var ids=new HashSet<>(t.teamA);ids.addAll(t.teamB);assertEquals(10,ids.size());}
        }
    }
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
