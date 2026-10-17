package com.caria.futebol;

import java.util.*;
import java.util.function.ToDoubleFunction;

/** Minimize average-rating difference with exactly five players on each side. */
final class TeamGenerator {
    private static final int EXACT_POOL_LIMIT = 32;
    private record Five(long mask, double sum) {}
    private TeamGenerator() {}

    private static void validate(List<String> players, ToDoubleFunction<String> ratingOf) {
        if (players.size() < 10 || new HashSet<>(players).size() != players.size())
            throw new FootballException("Choose at least 10 different players.");
        for (String name : players) if (!Double.isFinite(ratingOf.applyAsDouble(name)))
            throw new FootballException("Every player needs a finite rating.");
    }
    static Pick10Result pickRandom10(List<String> confirmed) {
        validate(confirmed,name -> 0);
        List<String> pool = new ArrayList<>(confirmed); Collections.shuffle(pool);
        List<String> ten = new ArrayList<>(pool.subList(0,10));
        return new Pick10Result(ten,substitutesFor(confirmed,ten),-1.0);
    }
    private static List<String> substitutesFor(List<String> confirmed, List<String> chosen) {
        Set<String> selected = new HashSet<>(chosen);
        return confirmed.stream().filter(name -> !selected.contains(name)).toList();
    }
    static Pick10Result pickBest10ForBalance(List<String> confirmed) {
        return pickBest10ForBalance(confirmed,PlayerCatalog::rankOf);
    }
    static Pick10Result pickBest10ForBalance(List<String> confirmed, ToDoubleFunction<String> ratingOf) {
        validate(confirmed,ratingOf);
        if (confirmed.size() == 10) {
            Teams teams = splitIntoBalanced5v5(confirmed,ratingOf);
            return new Pick10Result(new ArrayList<>(confirmed),List.of(),Math.abs(teams.sumA-teams.sumB));
        }
        Teams best=rankedOptions(confirmed,ratingOf).get(0);
        Set<String> chosen=new HashSet<>(best.teamA); chosen.addAll(best.teamB);
        List<String> ten=confirmed.stream().filter(chosen::contains).toList();
        return new Pick10Result(ten,substitutesFor(confirmed,ten),Math.abs(best.sumA-best.sumB));
    }

    /** Three distinct splits in increasing rating-gap order; A/B swaps count once. */
    static List<Teams> rankedOptions(List<String> confirmed, ToDoubleFunction<String> ratingOf) {
        validate(confirmed,ratingOf);
        List<String> pool = new ArrayList<>(confirmed);
        Collections.sort(pool);
        if (pool.size()>EXACT_POOL_LIMIT)
            throw new FootballException("Choose at most 32 attendees for guaranteed optimal teams. Narrow the selection or use random shuffle.");
        double[] scores=pool.stream().mapToDouble(ratingOf).toArray();
        List<Five> choices=new ArrayList<>();
        for (int a=0;a<scores.length-4;a++) for (int b=a+1;b<scores.length-3;b++)
            for (int c=b+1;c<scores.length-2;c++) for (int d=c+1;d<scores.length-1;d++) for (int e=d+1;e<scores.length;e++)
                choices.add(new Five((1L<<a)|(1L<<b)|(1L<<c)|(1L<<d)|(1L<<e),scores[a]+scores[b]+scores[c]+scores[d]+scores[e]));
        choices.sort(Comparator.comparingDouble(Five::sum).thenComparingLong(Five::mask));
        record Pair(long a,long b,double gap) {}
        List<Pair> best=new ArrayList<>();
        // Sorted sums give a lower bound: later j cannot improve a full top-three
        // once their gap reaches the current worst. Never prune on roster overlap.
        // Every unordered disjoint pair is considered or excluded by this bound.
        for (int i=0;i<choices.size();i++) for (int j=i+1;j<choices.size();j++) {
            Five a=choices.get(i), b=choices.get(j); double gap=b.sum()-a.sum();
            if (best.size()==3 && gap>=best.get(2).gap()) break;
            if ((a.mask()&b.mask())!=0) continue;
            best.add(new Pair(a.mask(),b.mask(),gap)); best.sort(Comparator.comparingDouble(Pair::gap));
            if (best.size()>3) best.remove(3);
        }
        List<Teams> result=new ArrayList<>();
        for (Pair pair:best) {
            Teams teams=new Teams();
            for(int i=0;i<pool.size();i++) {
                if ((pair.a()&(1L<<i))!=0) { teams.teamA.add(pool.get(i)); teams.sumA+=scores[i]; }
                if ((pair.b()&(1L<<i))!=0) { teams.teamB.add(pool.get(i)); teams.sumB+=scores[i]; }
            }
            result.add(teams);
        }
        return result;
    }
    static Teams splitIntoBalanced5v5(List<String> tenPlayers) {
        return splitIntoBalanced5v5(tenPlayers,PlayerCatalog::rankOf);
    }
    static Teams splitIntoBalanced5v5(List<String> tenPlayers, ToDoubleFunction<String> ratingOf) {
        validate(tenPlayers,ratingOf);
        if (tenPlayers.size() != 10) throw new FootballException("A 5v5 split needs exactly 10 players.");
        List<String> pool=new ArrayList<>(tenPlayers); Collections.shuffle(pool);
        double[] scores=pool.stream().mapToDouble(ratingOf).toArray();
        double best=Double.POSITIVE_INFINITY; int bestMask=0;
        // Fix the first player on A: 126 combinations cover every distinct 5v5 split.
        for (int b=1;b<7;b++) for (int c=b+1;c<8;c++) for (int d=c+1;d<9;d++) for (int e=d+1;e<10;e++) {
            int mask=1|(1<<b)|(1<<c)|(1<<d)|(1<<e); double a=0,other=0;
            for (int i=0;i<10;i++) { if ((mask & (1<<i)) != 0) a+=scores[i]; else other+=scores[i]; }
            double difference=Math.abs(a-other);
            if (difference < best) { best=difference; bestMask=mask; }
        }
        return teamsFor(pool,scores,bestMask);
    }
    private static Teams teamsFor(List<String> pool, double[] scores, int mask) {
        Teams teams=new Teams();
        for (int i=0;i<pool.size();i++) {
            if ((mask & (1<<i)) != 0) { teams.teamA.add(pool.get(i)); teams.sumA+=scores[i]; }
            else { teams.teamB.add(pool.get(i)); teams.sumB+=scores[i]; }
        }
        return teams;
    }
    static Teams splitRandom5v5(List<String> tenPlayers) {
        return splitRandom5v5(tenPlayers,PlayerCatalog::rankOf);
    }
    static Teams splitRandom5v5(List<String> tenPlayers, ToDoubleFunction<String> ratingOf) {
        validate(tenPlayers,ratingOf);
        if (tenPlayers.size() != 10) throw new FootballException("A 5v5 split needs exactly 10 players.");
        List<String> pool=new ArrayList<>(tenPlayers); Collections.shuffle(pool);
        return teamsFor(pool,pool.stream().mapToDouble(ratingOf).toArray(),31);
    }
}
