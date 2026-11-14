package com.caria.futebol;

import java.util.*;

final class MatchRatings {
    private MatchRatings() {}

    static double multiplier(int margin) {
        if (margin <= 1) return 1;
        return Math.min(Math.nextDown(1.8), 1 - 0.8 * Math.expm1(-(margin - 1) / 2.0));
    }

    static double expected(double own, double opponent) {
        return 1 / (1 + Math.pow(10, (opponent - own) / 20));
    }

    static void apply(Map<Long, Double> ratings, List<Long> a, List<Long> b, int goalsA, int goalsB) {
        var ids = new HashSet<Long>();
        if (a.isEmpty() || b.isEmpty() || goalsA < 0 || goalsB < 0)
            throw new FootballException("Invalid match for rating calculation.");
        for (var team : List.of(a, b)) for (long id : team) {
            Double rating = ratings.get(id);
            if (!ids.add(id) || rating == null || !Double.isFinite(rating) || rating < 0 || rating > 100)
                throw new FootballException("Invalid participant or rating in match history.");
        }
        double averageA = average(ratings,a);
        double averageB = average(ratings,b);
        double actual = goalsA == goalsB ? 0.5 : goalsA > goalsB ? 1 : 0;
        double delta = clamp(3 * (actual - expected(averageA, averageB)) * multiplier(Math.abs(goalsA-goalsB)), -3, 3);
        for (long id : a) ratings.put(id, clamp(ratings.get(id)+delta,0,100));
        for (long id : b) ratings.put(id, clamp(ratings.get(id)-delta,0,100));
    }

    private static double average(Map<Long,Double> ratings,List<Long> team) {
        double sum=0;
        for (long id:team.stream().sorted().toList()) sum+=ratings.get(id);
        return sum/team.size();
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min,value));
    }
}
