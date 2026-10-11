package com.caria.futebol;

import java.util.*;
import java.util.function.ToDoubleFunction;

/** Minimize total-rating difference with exactly five players on each side. */
final class TeamGenerator {
    private static final int EXACT_POOL_LIMIT = 24;
    private static final int PICK_10_TRIES = 4000;
    private record Five(int mask, double sum) {}
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
        List<String> pool = new ArrayList<>(confirmed);
        Collections.shuffle(pool); // Vary equally good choices instead of favouring catalogue order.
        if (pool.size() <= EXACT_POOL_LIMIT) {
            double[] scores = pool.stream().mapToDouble(ratingOf).toArray();
            List<Five> choices = new ArrayList<>();
            for (int a=0;a<scores.length-4;a++) for (int b=a+1;b<scores.length-3;b++)
                for (int c=b+1;c<scores.length-2;c++) for (int d=c+1;d<scores.length-1;d++) for (int e=d+1;e<scores.length;e++)
                    choices.add(new Five((1<<a)|(1<<b)|(1<<c)|(1<<d)|(1<<e),scores[a]+scores[b]+scores[c]+scores[d]+scores[e]));
            choices.sort(Comparator.comparingDouble(Five::sum));
            double best = Double.POSITIVE_INFINITY; int chosenMask = 0;
            // The closest disjoint pair of five-player subsets gives the global optimum.
            search: for (int i=0;i<choices.size();i++) {
                Five a=choices.get(i);
                for (int j=i+1;j<choices.size();j++) {
                    Five b=choices.get(j); double difference=b.sum()-a.sum();
                    if (difference >= best) break;
                    if ((a.mask() & b.mask()) != 0) continue;
                    best=difference; chosenMask=a.mask()|b.mask();
                    if (best == 0) break search;
                    break; // Later candidates are at least as far from this sum.
                }
            }
            List<String> ten=new ArrayList<>();
            for (int i=0;i<pool.size();i++) if ((chosenMask & (1<<i)) != 0) ten.add(pool.get(i));
            return new Pick10Result(ten,substitutesFor(confirmed,ten),best);
        }
        // Bound work for unusually large groups. Each sampled ten is still split exactly.
        double best=Double.POSITIVE_INFINITY; List<String> bestTen=null;
        for (int i=0;i<PICK_10_TRIES;i++) {
            Collections.shuffle(pool); List<String> ten=new ArrayList<>(pool.subList(0,10));
            Teams teams=splitIntoBalanced5v5(ten,ratingOf); double difference=Math.abs(teams.sumA-teams.sumB);
            if (difference < best) { best=difference; bestTen=ten; if (best == 0) break; }
        }
        return new Pick10Result(bestTen,substitutesFor(confirmed,bestTen),best);
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
