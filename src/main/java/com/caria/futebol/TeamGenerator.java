package com.caria.futebol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.caria.futebol.PlayerCatalog.rankOf;

/** Preserves the original randomized selection and greedy balancing. */
final class TeamGenerator {
    private static final int PICK_10_TRIES = 4000;

    private TeamGenerator() {}

    static Pick10Result pickRandom10(List<String> confirmed) {
        List<String> pool = new ArrayList<>(confirmed);
        Collections.shuffle(pool);
        List<String> ten = new ArrayList<>(pool.subList(0, 10));
        return new Pick10Result(ten, substitutesFor(confirmed, ten), -1.0);
    }

    private static List<String> substitutesFor(List<String> confirmed, List<String> chosen) {
        Set<String> selected = new HashSet<>(chosen);
        List<String> substitutes = new ArrayList<>();
        for (String name : confirmed) {
            if (!selected.contains(name)) substitutes.add(name);
        }
        return substitutes;
    }

    static Pick10Result pickBest10ForBalance(List<String> confirmed) {
        List<String> list = new ArrayList<>(confirmed);

        if (list.size() == 10) {
            Teams teams = splitIntoBalanced5v5(list);
            double diff = Math.abs(teams.sumA - teams.sumB);
            return new Pick10Result(list, List.of(), diff);
        }

        Random rnd = new Random();
        double bestDiff = Double.MAX_VALUE;
        List<String> bestTen = null;

        for (int i = 0; i < PICK_10_TRIES; i++) {
            Collections.shuffle(list, rnd);
            List<String> ten = new ArrayList<>(list.subList(0, 10));

            Teams teams = splitIntoBalanced5v5(ten);
            double diff = Math.abs(teams.sumA - teams.sumB);

            if (diff < bestDiff) {
                bestDiff = diff;
                bestTen = ten;
                if (bestDiff < 0.05) break;
            }
        }

        if (bestTen == null) {
            bestTen = new ArrayList<>(list.subList(0, 10));
            Teams teams = splitIntoBalanced5v5(bestTen);
            bestDiff = Math.abs(teams.sumA - teams.sumB);
        }

        return new Pick10Result(bestTen, substitutesFor(confirmed, bestTen), bestDiff);
    }

    static Teams splitIntoBalanced5v5(List<String> tenPlayers) {
        List<String> list = new ArrayList<>(tenPlayers);

        // Shuffle first so ties produce variety
        Collections.shuffle(list);

        // Sort by rank descending
        list.sort((x, y) -> Double.compare(rankOf(y), rankOf(x)));

        Teams teams = new Teams();

        for (String name : list) {
            double rating = rankOf(name);

            // Enforce exactly 5 players per team
            if (teams.teamA.size() >= 5) {
                teams.teamB.add(name);
                teams.sumB += rating;
                continue;
            }
            if (teams.teamB.size() >= 5) {
                teams.teamA.add(name);
                teams.sumA += rating;
                continue;
            }

            // Greedy balance
            if (teams.sumA <= teams.sumB) {
                teams.teamA.add(name);
                teams.sumA += rating;
            } else {
                teams.teamB.add(name);
                teams.sumB += rating;
            }
        }

        return teams;
    }

    /** Random split (fixed 5v5). Not optimized for fairness. */
    static Teams splitRandom5v5(List<String> tenPlayers) {
        List<String> list = new ArrayList<>(tenPlayers);
        Collections.shuffle(list);

        Teams teams = new Teams();
        for (int i = 0; i < list.size(); i++) {
            String name = list.get(i);
            double rating = rankOf(name);

            if (i < 5) {
                teams.teamA.add(name);
                teams.sumA += rating;
            } else {
                teams.teamB.add(name);
                teams.sumB += rating;
            }
        }
        return teams;
    }

}
