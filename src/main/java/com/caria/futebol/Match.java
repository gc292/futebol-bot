package com.caria.futebol;

import java.time.Instant;

record Match(long id, String guildId, TeamDivision division, int goalsA, int goalsB,
             Instant playedAt, Instant recordedAt, String recordedBy, int version) {
    String winner() { return goalsA == goalsB ? "Draw" : goalsA > goalsB ? "Team A" : "Team B"; }
}
