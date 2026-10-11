package com.caria.futebol;

import java.time.Instant;
import java.util.List;

/** Persisted, owner-bound interaction state; Discord component IDs contain only its UUID and revision. */
record ResultDraft(String id, String guildId, String userId, String channelId, String action,
                   String status, Long divisionId, Long targetMatchId, Integer targetVersion,
                   int teamSize, Integer goalsA, Integer goalsB, Instant playedAt,
                   int revision, Long confirmedMatchId, List<Player> teamA, List<Player> teamB) {
    ResultDraft { teamA = List.copyOf(teamA); teamB = List.copyOf(teamB); }
}
