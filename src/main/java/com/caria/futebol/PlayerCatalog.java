package com.caria.futebol;

import java.util.List;
import java.util.Map;

/** Fixed attendance order and player ratings. */
final class PlayerCatalog {
    private PlayerCatalog() {}

    /** Base player list (the order used during attendance check). */
    static final List<String> PLAYERS = List.of(
            "Ribeiro",
            "Charola",
            "Salvador",
            "Francisco",
            "Caria",
            "Xavi",
            "Pedro",
            "Ronald",
            "Gui",
            "Miguel",
            "Sal",
            "André",
            "Filipe",
            "Rodrigo",
            "João",
            "Tiago",
            "Fontes",
            "Pipa"
    );

    /** Missing players retain the default rating of 5.0. */
    private static final Map<String, Double> RATINGS = Map.ofEntries(
            Map.entry("Ribeiro", 86.0),
            Map.entry("Charola", 86.0),
            Map.entry("Salvador", 80.0),
            Map.entry("Francisco", 73.8),
            Map.entry("Caria", 71.9),
            Map.entry("Xavi", 71.9),
            Map.entry("Pedro", 69.3),
            Map.entry("Ronald", 68.3),
            Map.entry("Gui", 67.9),
            Map.entry("Miguel", 67.3),
            Map.entry("Sal", 65.0),
            Map.entry("André", 59.1),
            Map.entry("Filipe", 58.1),
            Map.entry("Rodrigo", 56.4),
            Map.entry("João", 53.1),
            Map.entry("Tiago", 51.9),
            Map.entry("Fontes", 51.6),
            Map.entry("Pipa", 5.0)
    );

    static double rankOf(String name) {
        return RATINGS.getOrDefault(name, 5.0);
    }
}
