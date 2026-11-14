package com.caria.futebol;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MatchRatingsTest {
    @TempDir Path dir;

    @Test void formulasAndBounds() {
        assertEquals(0.5,MatchRatings.expected(50,50));
        assertTrue(MatchRatings.expected(60,40)>0.5);
        assertTrue(MatchRatings.expected(40,60)<0.5);
        assertEquals(1,MatchRatings.multiplier(0));
        assertEquals(1,MatchRatings.multiplier(1));
        assertTrue(MatchRatings.multiplier(1000)<1.8);
        for (int d=2;d<10;d++) {
            assertTrue(MatchRatings.multiplier(d)>MatchRatings.multiplier(d-1));
            assertTrue(MatchRatings.multiplier(d)-MatchRatings.multiplier(d-1)
                    > MatchRatings.multiplier(d+1)-MatchRatings.multiplier(d));
        }
        var ratings=new HashMap<>(Map.of(1L,50.0,2L,50.0));
        MatchRatings.apply(ratings,List.of(1L),List.of(2L),1,0);
        assertEquals(51.5,ratings.get(1L));assertEquals(48.5,ratings.get(2L));
        ratings.put(1L,50.0);ratings.put(2L,50.0);
        MatchRatings.apply(ratings,List.of(1L),List.of(2L),3,0);
        assertEquals(52.2585446706,ratings.get(1L),1e-9);
        ratings.put(1L,99.0);ratings.put(2L,1.0);
        MatchRatings.apply(ratings,List.of(1L),List.of(2L),0,0);
        assertTrue(ratings.get(1L)<99 && ratings.get(2L)>1);
        ratings.put(1L,99.0);ratings.put(2L,1.0);
        MatchRatings.apply(ratings,List.of(1L),List.of(2L),1,0);
        assertTrue(ratings.get(1L)<=100 && ratings.get(2L)>=0);
        var capped=new HashMap<>(Map.of(1L,0.0,2L,100.0));
        MatchRatings.apply(capped,List.of(1L),List.of(2L),10,0);
        assertEquals(Map.of(1L,3.0,2L,97.0),capped);
        assertThrows(FootballException.class,()->MatchRatings.apply(capped,List.of(1L),List.of(1L),1,0));
        assertThrows(FootballException.class,()->MatchRatings.apply(capped,List.of(),List.of(2L),1,0));
        assertThrows(FootballException.class,()->MatchRatings.apply(capped,List.of(1L),List.of(2L),-1,0));
    }

    @Test void sameSnapshotRegardlessOfPlayerOrder() {
        var one=new HashMap<>(Map.of(1L,100.0,2L,55.5,3L,33.0,4L,20.0,5L,0.0));
        var two=new HashMap<>(one);
        MatchRatings.apply(one,List.of(1L,2L,3L),List.of(4L,5L),3,0);
        MatchRatings.apply(two,List.of(3L,2L,1L),List.of(5L,4L),3,0);
        assertEquals(one,two);
    }

    @Test void existingGamesReplayOnceAndDeletionKeepsManualOverride() throws Exception {
        Path db=dir.resolve("legacy.db");
        var store=new FootballStore(db);
        store.ensureGuild("guild");
        var p=store.players("guild");
        var div=store.saveGeneratedDivision("guild","website","website",p.subList(0,5).stream().map(Player::name).toList(),
                p.subList(5,10).stream().map(Player::name).toList(),"TEAMS");
        // Simulate a database from before the rating migration.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=c.createStatement()){
            statement.executeUpdate("DELETE FROM player_rating_baselines");
            statement.executeUpdate("UPDATE players SET rating=50");
            statement.executeUpdate("INSERT INTO matches(guild_id,division_id,goals_a,goals_b,played_at,recorded_at,recorded_by)"
                    +" VALUES('guild',"+div.id()+",1,0,'2025-01-01T20:00:00.000000000Z','2025-01-01T20:00:00.000000000Z','website')");
        }
        store=new FootballStore(db);
        store.ensureGuild("guild");
        assertEquals(51.5,store.player("guild",p.get(0).id()).rating());
        assertEquals(48.5,store.player("guild",p.get(5).id()).rating());
        store.ensureGuild("guild");
        assertEquals(51.5,store.player("guild",p.get(0).id()).rating());
        store.setPlayerRating("guild",p.get(0).id(),76.25);
        var match=store.matches("guild",null,0,10).get(0);
        store.deleteMatch("guild",match.id(),match.version());
        assertEquals(76.25,store.player("guild",p.get(0).id()).rating());
        assertEquals(50,store.player("guild",p.get(5).id()).rating());
    }
}
