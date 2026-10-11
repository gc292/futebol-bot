package com.caria.futebol;

import java.util.Set;

record GuildSettings(int manualTeamSize, int minimumWinRateGames, Set<String> authorizedRoles) {
    GuildSettings { authorizedRoles = Set.copyOf(authorizedRoles); }
}
