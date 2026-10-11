package com.caria.futebol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with a dedicated MariaDB test database; never use production credentials. */
@EnabledIfEnvironmentVariable(named="FOOTBALL_TEST_DB_URL", matches="jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/football_test.*")
class MariaDBStoreTest {
    private FootballStore connect() {
        return new FootballStore(System.getenv("FOOTBALL_TEST_DB_URL"),
                System.getenv("FOOTBALL_TEST_DB_USER"),System.getenv("FOOTBALL_TEST_DB_PASSWORD"));
    }
    @Test void sharedPlayersVotesResultsAndEditsRoundTrip() {
        var first=connect();var second=connect();String guild="test-"+UUID.randomUUID();first.ensureGuild(guild);
        var p=first.addPlayer(guild,"Maria player",73.5);
        assertEquals(73.5,second.player(guild,p.id()).rating());
        first.configure(guild,5,3,Set.of("role-1"));assertEquals(3,second.settings(guild).minimumWinRateGames());
        second.linkPlayer(guild,p.id(),"linked-user");assertEquals(p.id(),first.playerByDiscord(guild,"linked-user").id());
        var poll=first.addNativePoll(guild,"Maria vote");
        first.castRatingVote(guild,poll.id(),"one",60);second.castRatingVote(guild,poll.id(),"two",90);first.castRatingVote(guild,poll.id(),"one",80);
        assertEquals(85,second.ratingPoll(guild,poll.id()).average());assertEquals(2,second.ratingPoll(guild,poll.id()).voteCount());
        second.closeNativePoll(guild,poll.id());assertNull(first.ownRatingVote(guild,poll.id(),"one"));assertEquals(85,first.player(guild,poll.playerId()).rating());
        var players=first.players(guild);
        var division=first.saveGeneratedDivision(guild,"channel","user",players.subList(0,5).stream().map(Player::name).toList(),players.subList(5,10).stream().map(Player::name).toList(),"TEAMS");
        var draft=second.beginGenerated(guild,"user","channel",division.id());
        draft=first.score(guild,"user",draft.id(),3,1,Instant.parse("2026-01-01T12:00:00Z"),draft.revision());
        long match=second.confirm(guild,"user",draft.id(),draft.revision(),false);
        assertEquals(1,new StatisticsService(first).profile(guild,players.get(0).id()).wins());
        var edit=first.beginChange(guild,"user","channel",match,false);
        edit=second.score(guild,"user",edit.id(),1,1,edit.playedAt(),edit.revision());first.confirm(guild,"user",edit.id(),edit.revision(),true);
        assertEquals(1,new StatisticsService(second).profile(guild,players.get(0).id()).draws());
        first.removePlayer(guild,p.id());second.ensureGuild(guild);assertFalse(second.players(guild).stream().anyMatch(x->x.id()==p.id()));
        assertEquals(p.id(),second.addPlayer(guild,"Maria player",70).id());
        var deletion=second.beginChange(guild,"user","channel",match,true);first.confirm(guild,"user",deletion.id(),deletion.revision(),true);
        assertTrue(second.matches(guild,null,0,10).isEmpty());
    }
    @Test void separateProcessesSerializeConcurrentVoting() throws Exception {
        var first=connect();var second=connect();String guild="test-"+UUID.randomUUID();first.ensureGuild(guild);
        var poll=first.addNativePoll(guild,"Concurrent Maria vote");
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<16;i++) {
                final int score=i;
                futures.add(pool.submit(()->(score%2==0?first:second).castRatingVote(guild,poll.id(),"voter-"+score,score)));
            }
            for(var future:futures)future.get(30,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(16,first.ratingPoll(guild,poll.id()).voteCount());
        assertEquals(7.5,second.ratingPoll(guild,poll.id()).average());
        first.closeNativePoll(guild,poll.id());
        assertThrows(FootballException.class,()->second.castRatingVote(guild,poll.id(),"late",99));
        assertEquals(7.5,second.player(guild,poll.playerId()).rating());
    }}
