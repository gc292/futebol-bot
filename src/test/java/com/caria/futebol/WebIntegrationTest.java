package com.caria.futebol;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WebIntegrationTest {
    @TempDir Path temporary;
    WebMain web;
    FootballStore store;
    final HttpClient client = HttpClient.newHttpClient();
    @BeforeEach void setup() throws Exception {
        store = new FootballStore(temporary.resolve("football.db"));
        web = new WebMain(store, "guild", "127.0.0.1", 0, "",null,"test-player-password"); web.start();
    }
    @AfterEach void close() { web.stop(); }
    HttpResponse<String> request(String path, String json) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path));
        if (json != null) {
            var payload=JsonParser.parseString(json).getAsJsonObject();
            if (path.equals("/api/players")) payload.addProperty("password","test-player-password");
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void newPlayersPersistAndUseTheirActualRatingsInSharedDivisions() throws Exception {
        var response = request("/api/players", "{\"name\":\"New teammate\",\"rating\":99.5}");
        assertEquals(201, response.statusCode());
        long id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsLong();
        FootballStore reopened = new FootballStore(temporary.resolve("football.db"));
        reopened.ensureGuild("guild");
        assertEquals(99.5, reopened.player("guild", id).rating());
        List<Long> selected = new ArrayList<>(store.players("guild").subList(0,9).stream().map(Player::id).toList()); selected.add(id);
        response = request("/api/teams", new Gson().toJson(Map.of("playerIds", selected)));
        assertEquals(201, response.statusCode(), response.body());
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        TeamDivision saved = reopened.division("guild", json.get("id").getAsLong());
        assertEquals(5, saved.teamA().size()); assertEquals(5, saved.teamB().size());
        double total = saved.teamA().stream().mapToDouble(Player::rating).sum() + saved.teamB().stream().mapToDouble(Player::rating).sum();
        assertEquals(total, json.get("sumA").getAsDouble()+json.get("sumB").getAsDouble(), 1e-9);
        assertTrue(saved.teamA().stream().anyMatch(p -> p.id() == id) || saved.teamB().stream().anyMatch(p -> p.id() == id));
        assertTrue(reopened.pendingDivisions("guild",0,10).stream().anyMatch(d -> d.id() == saved.id()));
        assertEquals(200, request("/api/players", null).statusCode());
    }
    @Test void rejectsDuplicateNamesInvalidScoresAndForeignPlayers() throws Exception {
        assertEquals(400, request("/api/players", "{\"name\":\"ribeiro\",\"rating\":50}").statusCode());
        assertEquals(400, request("/api/players", "{\"name\":\"Test\",\"rating\":101}").statusCode());
        assertEquals(400, request("/api/teams", "{\"playerIds\":[999999]}").statusCode());
        assertEquals(400, request("/api/teams", "{\"playerIds\":[1,1]}").statusCode());
        assertEquals(400, request("/api/teams", "{\"playerIds\":[1.5]}").statusCode());
    }
    @Test void servesAssetsAndRequiresAuthenticationWhenConfigured() throws Exception {
        assertEquals(200, request("/", null).statusCode());
        assertTrue(request("/app.js", null).body().contains("/api/teams"));
        assertEquals(404, request("/schema.sql", null).statusCode());
        web.stop(); web = new WebMain(store,"guild","127.0.0.1",0,"a-long-test-access-key"); web.start();
        assertEquals(401, request("/api/players", null).statusCode());
        assertEquals(401, request("/api/login", "{\"key\":\"wrong\"}").statusCode());
        var login = request("/api/login", "{\"key\":\"a-long-test-access-key\"}"); assertEquals(200,login.statusCode());
        var cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        var authenticated = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+"/api/players")).header("Cookie",cookie).build();
        assertEquals(200,client.send(authenticated,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void moreThanTenRetainsTheSubstituteList() throws Exception {
        var selected = store.players("guild").stream().map(Player::id).toList();
        var response = request("/api/teams",new Gson().toJson(Map.of("playerIds", selected, "random", true)));
        assertEquals(201,response.statusCode());
        var result = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(selected.size()-10,result.getAsJsonArray("substitutes").size());
    }
    @Test void resultsWrittenByTheBotAppearInWebStatisticsAndHistory() throws Exception {
        var roster = store.players("guild");
        var division = store.saveGeneratedDivision("guild", "discord-channel", "discord-user",
                roster.subList(0,5).stream().map(Player::name).toList(), roster.subList(5,10).stream().map(Player::name).toList(), "TEAMS");
        var draft = store.beginGenerated("guild","discord-user","discord-channel",division.id());
        draft = store.score("guild","discord-user",draft.id(),3,1,java.time.Instant.now().minusSeconds(3600),draft.revision());
        store.confirm("guild","discord-user",draft.id(),draft.revision(),false);
        var data = JsonParser.parseString(request("/api/players",null).body()).getAsJsonObject().getAsJsonArray("players");
        var winner = java.util.stream.StreamSupport.stream(data.spliterator(),false).map(JsonElement::getAsJsonObject)
                .filter(p -> p.get("id").getAsLong() == roster.get(0).id()).findFirst().orElseThrow();
        assertEquals(1,winner.get("wins").getAsInt()); assertEquals(100,winner.get("winRate").getAsDouble());
        var history = JsonParser.parseString(request("/api/history",null).body()).getAsJsonObject().getAsJsonArray("matches");
        assertEquals(1,history.size()); assertEquals(3,history.get(0).getAsJsonObject().get("goalsA").getAsInt());
    }
    @Test void manualGameIsOnlyRecordedAfterReviewAndConfirmationAndRetriesAreSafe() throws Exception {
        var roster = store.players("guild");
        var payload = Map.of("teamA",roster.subList(0,5).stream().map(Player::id).toList(),
                "teamB",roster.subList(5,10).stream().map(Player::id).toList(), "goalsA",4,"goalsB",2,
                "playedAt",java.time.Instant.now().minusSeconds(7200).toString());
        var review = request("/api/results/review",new Gson().toJson(payload));
        assertEquals(200,review.statusCode(),review.body());
        assertTrue(store.matches("guild",null,0,10).isEmpty());
        var draft = JsonParser.parseString(review.body()).getAsJsonObject();
        assertEquals(5,draft.getAsJsonArray("teamA").size());
        String confirmation = new Gson().toJson(Map.of("draftId",draft.get("draftId").getAsString(),"revision",draft.get("revision").getAsInt()));
        var saved = request("/api/results/confirm",confirmation); assertEquals(200,saved.statusCode(),saved.body());
        assertEquals(saved.body(),request("/api/results/confirm",confirmation).body());
        assertEquals(1,store.matches("guild",null,0,10).size());
        assertEquals(1,new StatisticsService(store).profile("guild",roster.get(0).id()).wins());
        var duplicate = JsonParser.parseString(request("/api/results/review",new Gson().toJson(payload)).body()).getAsJsonObject();
        assertEquals(400,request("/api/results/confirm",new Gson().toJson(Map.of("draftId",duplicate.get("draftId").getAsString(),"revision",duplicate.get("revision").getAsInt()))).statusCode());
    }
    @Test void savedLineupsCanBeRecoveredAndLoggedOnce() throws Exception {
        var roster = store.players("guild");
        var lineup = store.saveGeneratedDivision("guild","discord-channel","discord-user",roster.subList(0,5).stream().map(Player::name).toList(),roster.subList(5,10).stream().map(Player::name).toList(),"TEAMS");
        assertEquals(1,JsonParser.parseString(request("/api/divisions",null).body()).getAsJsonObject().getAsJsonArray("divisions").size());
        var payload = Map.of("divisionId",lineup.id(),"goalsA",0,"goalsB",0,"playedAt",java.time.Instant.now().minusSeconds(60).toString());
        var review = request("/api/results/review",new Gson().toJson(payload)); assertEquals(200,review.statusCode(),review.body());
        var draft = JsonParser.parseString(review.body()).getAsJsonObject();
        assertEquals(200,request("/api/results/confirm",new Gson().toJson(Map.of("draftId",draft.get("draftId").getAsString(),"revision",draft.get("revision").getAsInt()))).statusCode());
        assertEquals(400,request("/api/results/review",new Gson().toJson(payload)).statusCode());
        assertEquals(0,JsonParser.parseString(request("/api/divisions",null).body()).getAsJsonObject().getAsJsonArray("divisions").size());
    }
    @Test void gameLoggingRejectsOverlappingTeamsInvalidScoresAndFutureDates() throws Exception {
        var roster = store.players("guild");
        Map<String,Object> payload = new HashMap<>(Map.of("teamA",roster.subList(0,5).stream().map(Player::id).toList(),
                "teamB",roster.subList(0,5).stream().map(Player::id).toList(),"goalsA",1,"goalsB",1,"playedAt",java.time.Instant.now().minusSeconds(60).toString()));
        assertEquals(400,request("/api/results/review",new Gson().toJson(payload)).statusCode());
        payload.put("teamB",roster.subList(5,10).stream().map(Player::id).toList());
        payload.put("goalsA",1.5); assertEquals(400,request("/api/results/review",new Gson().toJson(payload)).statusCode());
        payload.put("goalsA",-1); assertEquals(400,request("/api/results/review",new Gson().toJson(payload)).statusCode());
        payload.put("goalsA",1); payload.put("playedAt",java.time.Instant.now().plusSeconds(3600).toString());
        assertEquals(400,request("/api/results/review",new Gson().toJson(payload)).statusCode());
        assertTrue(store.matches("guild",null,0,10).isEmpty());
    }
}
