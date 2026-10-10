package com.caria.futebol;

import java.util.List;

/** Pick result: chosen 10 + substitutes + best diff found while searching. */
final class Pick10Result {
    final List<String> chosenPlayers;
    final List<String> substitutes;
    final double bestDifference;

    Pick10Result(List<String> chosenPlayers, List<String> substitutes, double bestDifference) {
        this.chosenPlayers = chosenPlayers;
        this.substitutes = substitutes;
        this.bestDifference = bestDifference;
    }
}

