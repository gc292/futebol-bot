package com.caria.futebol;

import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerManagementTest {
    @TempDir Path temporary;
    FootballStore store;
    WebMain web;
    int googleCreates;
    static final String PASSWORD="test-management-password";
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void setup() throws Exception {
        store=new FootballStore(temporary.resolve("players.db"));
        FormsGateway gateway=new FormsGateway() {
            public String create(String id,String name) { googleCreates++; return "https://docs.google.com/forms/d/e/test/viewform"; }
            public VoteSnapshot read(String id,boolean close) { return new VoteSnapshot(List.of(),false); }
        };
        web=new WebMain(store,"guild","127.0.0.1",0,"",gateway,PASSWORD); web.start();
    }
    @AfterEach void close() { web.stop(); }
    HttpResponse<String> post(String path,Map<String,Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void manualRatingEditsRequirePasswordValidateAndPreserveIdentity() throws Exception {
        long id=store.players("guild").get(0).id();Player before=store.player("guild",id);
        assertEquals(403,post("/api/players/rating",Map.of("id",id,"rating",80)).statusCode());
        assertEquals(403,post("/api/players/rating",Map.of("id",id,"rating",80,"password","wrong")).statusCode());
        assertEquals(before.rating(),store.player("guild",id).rating());
        for(double score:List.of(-1.0,100.1))assertEquals(400,post("/api/players/rating",Map.of("id",id,"rating",score,"password",PASSWORD)).statusCode());
        assertEquals(400,post("/api/players/rating",Map.of("id",id,"password",PASSWORD)).statusCode());
        assertEquals(400,post("/api/players/rating",Map.of("id",id,"rating","invalid","password",PASSWORD)).statusCode());
        for(double score:List.of(0.0,100.0,83.75)){
            var response=post("/api/players/rating",Map.of("id",id,"rating",score,"password",PASSWORD));assertEquals(200,response.statusCode(),response.body());
            Player saved=new FootballStore(temporary.resolve("players.db")).player("guild",id);assertEquals(score,saved.rating());assertEquals(before.name(),saved.name());
        }
        assertThrows(FootballException.class,()->store.setPlayerRating("guild",id,Double.NaN));
        store.ensureGuild("other");long foreign=store.players("other").get(0).id();assertEquals(400,post("/api/players/rating",Map.of("id",foreign,"rating",20,"password",PASSWORD)).statusCode());
        store.removePlayer("guild",id);assertEquals(400,post("/api/players/rating",Map.of("id",id,"rating",20,"password",PASSWORD)).statusCode());
    }
    @Test void manualRatingReplacesVotesAndDoesNotChangePastGames() throws Exception {
        var poll=store.addNativePoll("guild","Manual override");store.castRatingVote("guild",poll.id(),"voter",65);
        assertEquals(200,post("/api/players/rating",Map.of("id",poll.playerId(),"rating",88.25,"password",PASSWORD)).statusCode());
        assertFalse(store.nativePoll(poll.id()));assertFalse(store.ratingPolls("guild").stream().anyMatch(p -> p.id().equals(poll.id())));
        assertThrows(FootballException.class,()->store.castRatingVote("guild",poll.id(),"voter",10));
        assertEquals(88.25,store.player("guild",poll.playerId()).rating());
        var pending=store.addNativePoll("guild","No votes yet");store.setPlayerRating("guild",pending.playerId(),0);assertTrue(store.ratedPlayers("guild").stream().anyMatch(p->p.id()==pending.playerId()));
        var players=store.players("guild");var division=store.saveGeneratedDivision("guild","website","website",players.subList(0,5).stream().map(Player::name).toList(),players.subList(5,10).stream().map(Player::name).toList(),"TEAMS");
        var draft=store.beginGenerated("guild","website","website",division.id());draft=store.score("guild","website",draft.id(),2,1,java.time.Instant.now().minusSeconds(60),draft.revision());long match=store.confirm("guild","website",draft.id(),draft.revision(),false);
        long id=players.get(0).id();var before=new StatisticsService(store).profile("guild",id);store.setPlayerRating("guild",id,99);
        var after=new StatisticsService(store).profile("guild",id);assertEquals(before.gamesPlayed(),after.gamesPlayed());assertEquals(before.wins(),after.wins());assertEquals(2,store.match("guild",match).goalsA());
    }
    @Test void bothAddRoutesAndRemovalRequireThePasswordBeforeAnyChanges() throws Exception {
        int before=store.players("guild").size(); long id=store.players("guild").get(0).id();
        assertEquals(403,post("/api/players/vote",Map.of("name","New friend")).statusCode());
        assertEquals(403,post("/api/players",Map.of("name","Other friend","rating",80)).statusCode());
        assertEquals(403,post("/api/players/remove",Map.of("id",id,"password","wrong")).statusCode());
        assertEquals(before,store.players("guild").size()); assertEquals(0,googleCreates);
        assertEquals(201,post("/api/players/vote",Map.of("name","New friend","password",PASSWORD)).statusCode());
        assertEquals(1,googleCreates);
        assertEquals(200,post("/api/players/remove",Map.of("id",id,"password",PASSWORD)).statusCode());
        assertFalse(store.players("guild").stream().anyMatch(p -> p.id()==id));
    }
    @Test void managementIsLockedWhenNoPasswordIsConfigured() throws Exception {
        web.stop(); web=new WebMain(store,"guild","127.0.0.1",0,""); web.start();
        assertEquals(403,post("/api/players",Map.of("name","Blocked","rating",80,"password",PASSWORD)).statusCode());
        assertEquals(403,post("/api/players/remove",Map.of("id",store.players("guild").get(0).id(),"password",PASSWORD)).statusCode());
    }
    @Test void directScoresNeedNoGoogleConnectionAndAreImmediatelyAvailable() throws Exception {
        web.stop(); web=new WebMain(store,"guild","127.0.0.1",0,"",null,PASSWORD); web.start();
        assertEquals(201,post("/api/players",Map.of("name","Direct score","rating",83.75,"password",PASSWORD)).statusCode());
        Player player=store.players("guild").stream().filter(p -> p.name().equals("Direct score")).findFirst().orElseThrow();
        FootballStore reopened=new FootballStore(temporary.resolve("players.db"));
        assertEquals(83.75,reopened.player("guild",player.id()).rating());
        assertTrue(reopened.ratedPlayers("guild").stream().anyMatch(p -> p.id()==player.id()));
        assertTrue(reopened.ratingPolls("guild").isEmpty()); assertEquals(0,googleCreates);
        assertEquals(400,post("/api/players",Map.of("name","Invalid score","rating",101,"password",PASSWORD)).statusCode());
    }
    @Test void removalCannotCrossGuildsOrCreateNewManualTeamsWithRemovedPlayers() throws Exception {
        store.ensureGuild("other"); long foreign=store.players("other").get(0).id();
        assertEquals(400,post("/api/players/remove",Map.of("id",foreign,"password",PASSWORD)).statusCode());
        var roster=store.players("guild"); long id=roster.get(0).id();
        assertEquals(200,post("/api/players/remove",Map.of("id",id,"password",PASSWORD)).statusCode());
        assertEquals(400,post("/api/teams",Map.of("playerIds",roster.subList(0,10).stream().map(Player::id).toList())).statusCode());
        assertEquals(400,post("/api/results/review",Map.of("password",PASSWORD,"teamA",roster.subList(0,5).stream().map(Player::id).toList(),
                "teamB",roster.subList(5,10).stream().map(Player::id).toList(),"goalsA",1,"goalsB",0,
                "playedAt",java.time.Instant.now().minusSeconds(60).toString())).statusCode());
        assertTrue(store.matches("guild",null,0,10).isEmpty());
    }
    @Test void repeatedWrongPasswordsAreRateLimited() throws Exception {
        for (int i=0;i<5;i++) assertEquals(403,post("/api/players",Map.of("password","wrong")).statusCode());
        assertEquals(429,post("/api/players",Map.of("password","wrong")).statusCode());
    }
}
