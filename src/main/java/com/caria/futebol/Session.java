package com.caria.futebol;

import java.util.ArrayList;
import java.util.List;

/** Represents a running attendance check session. */
final class Session {
    final String userId;
    final String channelId;
    final List<String> players;

    int index = 0;

    // The fixed panel message ID (the one we keep editing)
    Long panelMessageId = null;

    final List<String> confirmed = new ArrayList<>();
    final List<String> notGoing = new ArrayList<>();

    Session(String userId, String channelId, List<String> players) {
        this.userId = userId;
        this.channelId = channelId;
        this.players = players;
    }

    String currentPlayer() {
        if (index < 0 || index >= players.size()) return null;
        return players.get(index);
    }

    void recordAnswer(boolean attending) {
        String player = currentPlayer();
        if (attending) confirmed.add(player);
        else notGoing.add(player);
        advance();
    }

    void advance() {
        index++;
    }

    boolean isFinished() {
        return index >= players.size();
    }
}
