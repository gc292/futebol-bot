package com.caria.futebol;

import java.time.Instant;
import java.util.List;

record TeamDivision(long id, String guildId, String channelId, String createdBy,
                    Instant createdAt, String source, String mode, int teamSize,
                    List<Player> teamA, List<Player> teamB) {
    TeamDivision { teamA = List.copyOf(teamA); teamB = List.copyOf(teamB); }
}
