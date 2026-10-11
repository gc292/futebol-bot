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
        assertEquals(400,post("/api/results/review",Map.of("teamA",roster.subList(0,5).stream().map(Player::id).toList(),
                "teamB",roster.subList(5,10).stream().map(Player::id).toList(),"goalsA",1,"goalsB",0,
                "playedAt",java.time.Instant.now().minusSeconds(60).toString())).statusCode());
        assertTrue(store.matches("guild",null,0,10).isEmpty());
    }
    @Test void repeatedWrongPasswordsAreRateLimited() throws Exception {
        for (int i=0;i<5;i++) assertEquals(403,post("/api/players",Map.of("password","wrong")).statusCode());
        assertEquals(429,post("/api/players",Map.of("password","wrong")).statusCode());
    }
}
