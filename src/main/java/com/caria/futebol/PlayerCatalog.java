package com.caria.futebol;

import java.util.List;
import java.util.Map;

/** Fixed attendance order and player ratings. */
final class PlayerCatalog {
    private PlayerCatalog() {}

    /** Base player list (the order used during attendance check). */
    static final List<String> PLAYERS = List.of(
            "Caria",
            "Tiago",
            "Filipe",
            "Francisco",
            "Gui",
            "João",
            "Miguel",
            "Pedro",
            "Rodrigo",
            "Salvador",
            "Pipa",
            "André",
            "Fontes",
            "Vasco"
    );

    /**
     * Player ratings with decimals allowed (original values).
     * Any missing player defaults to 5.0.
     */
    private static final Map<String, Double> RATINGS = Map.ofEntries(
            Map.entry("Caria", 71.9),
            Map.entry("Tiago", 51.9),
            Map.entry("Filipe", 58.1),
            Map.entry("Francisco", 73.8),
            Map.entry("Gui", 66.9),
            Map.entry("João", 50.1),
            Map.entry("Miguel", 67.3),
            Map.entry("Pedro", 69.3),
            Map.entry("Rodrigo", 56.4),
            Map.entry("Salvador", 80.0),
            Map.entry("Pipa", 49.3),
            Map.entry("André", 55.1),
            Map.entry("Fontes", 41.6),
            Map.entry("Vasco", 73.9)
    );

    static double rankOf(String name) {
        return RATINGS.getOrDefault(name, 5.0);
    }
}
