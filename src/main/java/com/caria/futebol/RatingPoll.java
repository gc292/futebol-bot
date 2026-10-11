package com.caria.futebol;

record RatingPoll(String id, String guildId, long playerId, String playerName, String formUrl,
                  String status, int voteCount, Double average, String updatedAt) {}
