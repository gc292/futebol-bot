package com.caria.futebol;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RatingIntegrationTest {
    @TempDir Path temporary;
    FootballStore store;
    WebMain web;
    FakeForms google;
    final HttpClient client=HttpClient.newHttpClient();
    static class FakeForms implements FormsGateway {
        volatile List<Double> scores=List.of();
        volatile boolean closed,failed,deleteFailed;
        int deleteCalls, readCalls;
        Runnable beforeDelete;
        final Map<String,String> forms=new java.util.concurrent.ConcurrentHashMap<>();
        public String create(String id,String name) {
            if (failed) throw new FootballException("Google unavailable");
            return forms.computeIfAbsent(id,k -> "https://docs.google.com/forms/d/e/test-"+k+"/viewform");
        }
        public void delete(String id) {
            if (beforeDelete != null) beforeDelete.run();
            if (deleteFailed) throw new FootballException("Google cleanup unavailable");
            forms.remove(id); deleteCalls++;
        }
        public VoteSnapshot read(String id,boolean close) {
            readCalls++;
            if (failed) throw new FootballException("Google unavailable");
            if (close && scores.isEmpty()) throw new FootballException("At least one vote is required");
            if (close) closed=true;
            return new VoteSnapshot(scores,closed);
        }
    }
    @BeforeEach void setup() throws Exception {
        store=new FootballStore(temporary.resolve("football.db")); google=new FakeForms();
        web=new WebMain(store,"guild","127.0.0.1",0,"",google,"test-player-password"); web.start();
    }
    @AfterEach void close() { web.stop(); }
    HttpResponse<String> request(String path,Object value) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+path));
        if (value!=null) {
            var payload=new Gson().toJsonTree(value).getAsJsonObject();
            if (path.equals("/api/players/vote") || path.startsWith("/api/rating-polls/")) payload.addProperty("password","test-player-password");
            builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        }
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonObject add() throws Exception {
        var response=request("/api/players/vote",Map.of("name","New player"));
        assertTrue(response.statusCode()==201 || response.statusCode()==202,response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    @Test void createsFormAndAveragesAllCurrentAnswersWithoutDoubleCounting() throws Exception {
        var created=add(); var poll=created.getAsJsonObject("poll"); String id=poll.get("id").getAsString();
        long playerId=created.getAsJsonObject("player").get("id").getAsLong();
        assertTrue(poll.get("url").getAsString().startsWith("https://docs.google.com/forms/"));
        assertFalse(store.ratedPlayers("guild").stream().anyMatch(p -> p.id()==playerId));
        google.scores=List.of(70.0,80.0,90.0);
        assertEquals(200,request("/api/rating-polls/sync",Map.of("id",id)).statusCode());
        assertEquals(80,store.player("guild",playerId).rating());
        assertEquals(3,store.ratingPoll("guild",id).voteCount());
        request("/api/rating-polls/sync",Map.of("id",id));
        assertEquals(3,store.ratingPoll("guild",id).voteCount());
        // An edited/deleted response replaces the snapshot, instead of accumulating twice.
        google.scores=List.of(100.0,77.0);
        request("/api/rating-polls/sync",Map.of("id",id));
        assertEquals(88.5,store.player("guild",playerId).rating());
        assertTrue(store.ratedPlayers("guild").stream().anyMatch(p -> p.id()==playerId));
        FootballStore reopened=new FootballStore(temporary.resolve("football.db")); reopened.ensureGuild("guild");
        assertEquals(88.5,reopened.player("guild",playerId).rating());
        assertEquals(poll.get("url").getAsString(),reopened.ratingPoll("guild",id).formUrl());
    }
    @Test void pendingPlayersCannotBeBalancedUntilAResponseArrives() throws Exception {
        var created=add();long playerId=created.getAsJsonObject("player").get("id").getAsLong();
        List<Long> selected=new ArrayList<>(store.players("guild").subList(0,9).stream().map(Player::id).toList()); selected.add(playerId);
        assertEquals(400,request("/api/teams",Map.of("playerIds",selected)).statusCode());
        google.scores=List.of(99.0);
        request("/api/rating-polls/sync",Map.of("id",created.getAsJsonObject("poll").get("id").getAsString()));
        var teams=request("/api/teams",Map.of("playerIds",selected));assertEquals(201,teams.statusCode(),teams.body());
        var result=JsonParser.parseString(teams.body()).getAsJsonObject();
        var saved=store.division("guild",result.get("id").getAsLong());
        assertEquals(saved.teamA().stream().mapToDouble(Player::rating).sum(),result.get("sumA").getAsDouble(),1e-9);
    }
    @Test void closingLocksTheFinalMeanAndRejectsAnEmptyPoll() throws Exception {
        var created=add();String id=created.getAsJsonObject("poll").get("id").getAsString();
        assertEquals(400,request("/api/rating-polls/close",Map.of("id",id)).statusCode());
        google.scores=List.of(70.0,80.0,90.0);
        assertEquals(200,request("/api/rating-polls/close",Map.of("id",id)).statusCode());
        assertTrue(google.closed); assertEquals("CLOSED",store.ratingPoll("guild",id).status());
        google.scores=List.of(1.0);
        request("/api/rating-polls/sync",Map.of("id",id));
        assertEquals(80,store.ratingPoll("guild",id).average());
    }
    @Test void cleanupWaitsForTheSavedFinalRatingAndRetriesAfterRestart() throws Exception {
        var created=add(); String id=created.getAsJsonObject("poll").get("id").getAsString();
        long player=created.getAsJsonObject("player").get("id").getAsLong();
        google.scores=List.of(60.0,90.0);
        google.beforeDelete=()-> {
            assertEquals("CLOSED",store.ratingPoll("guild",id).status());
            assertEquals(75,store.player("guild",player).rating());
        };
        google.deleteFailed=true;
        var closed=request("/api/rating-polls/close",Map.of("id",id));
        assertEquals(200,closed.statusCode());
        assertTrue(JsonParser.parseString(closed.body()).getAsJsonObject().get("cleanupPending").getAsBoolean());
        assertTrue(store.ratingCleanupPending("guild",id)); assertEquals(0,google.deleteCalls);
        FootballStore reopened=new FootballStore(temporary.resolve("football.db"));
        google.deleteFailed=false;
        try (RatingService service=new RatingService(reopened,"guild",google)) { service.sync(); }
        assertEquals(1,google.deleteCalls); assertFalse(reopened.ratingCleanupPending("guild",id));
        assertNull(reopened.ratingPoll("guild",id).formUrl());
        assertEquals(75,reopened.ratingPoll("guild",id).average());
        assertEquals(2,reopened.ratingPoll("guild",id).voteCount());
        int reads=google.readCalls;
        request("/api/rating-polls/sync",Map.of("id",id));
        assertEquals(reads,google.readCalls); assertEquals(1,google.deleteCalls);
    }
    @Test void invalidFinalSnapshotCannotTriggerDeletion() throws Exception {
        var created=add(); String id=created.getAsJsonObject("poll").get("id").getAsString();
        google.scores=List.of(101.0);
        assertEquals(400,request("/api/rating-polls/close",Map.of("id",id)).statusCode());
        assertEquals(0,google.deleteCalls); assertFalse(store.ratingCleanupPending("guild",id));
        assertEquals("OPEN",store.ratingPoll("guild",id).status());
    }
    @Test void cleanupEndpointCannotDeleteAnOpenPoll() throws Exception {
        var created=add(); String id=created.getAsJsonObject("poll").get("id").getAsString();
        request("/api/rating-polls/cleanup",Map.of("id",id));
        assertEquals(0,google.deleteCalls); assertNotNull(store.ratingPoll("guild",id).formUrl());
    }
    @Test void failedCreationCanBeRecoveredWithoutAnotherPlayerOrForm() throws Exception {
        google.failed=true;var created=add();String id=created.getAsJsonObject("poll").get("id").getAsString();
        assertEquals("CREATING",store.ratingPoll("guild",id).status());
        google.failed=false;
        assertEquals(200,request("/api/rating-polls/retry",Map.of("id",id)).statusCode());
        request("/api/rating-polls/retry",Map.of("id",id));
        assertEquals(1,google.forms.size()); assertEquals(19,store.players("guild").size());
    }
    @Test void failedOrInvalidResponsesCannotReplaceTheLastValidMean() throws Exception {
        var created=add();String id=created.getAsJsonObject("poll").get("id").getAsString();
        google.scores=List.of(70.0,80.0);request("/api/rating-polls/sync",Map.of("id",id));
        google.failed=true;assertEquals(400,request("/api/rating-polls/sync",Map.of("id",id)).statusCode());
        assertEquals(75,store.ratingPoll("guild",id).average());
        google.failed=false;google.scores=List.of(101.0);
        assertEquals(400,request("/api/rating-polls/sync",Map.of("id",id)).statusCode());
        assertEquals(75,store.ratingPoll("guild",id).average());
        google.scores=List.of();request("/api/rating-polls/sync",Map.of("id",id));
        assertNull(store.ratingPoll("guild",id).average());
    }
    @Test void websiteVotingNeedsNoGoogleConfiguration() throws Exception {
        web.stop();web=new WebMain(store,"guild","127.0.0.1",0,"",null,"test-player-password");web.start();
        assertEquals(201,request("/api/players/vote",Map.of("name","New player")).statusCode());
        assertEquals(19,store.players("guild").size());
        assertTrue(store.nativePoll(store.ratingPolls("guild").get(0).id()));
    }
    @Test void automaticSyncRecoversOpenFormsAndIsolatesGuilds() throws Exception {
        var created=add();String id=created.getAsJsonObject("poll").get("id").getAsString();
        google.scores=List.of(80.0,81.0,82.0);
        try (RatingService service=new RatingService(store,"guild",google)) { service.sync(); }
        assertEquals(81,store.ratingPoll("guild",id).average());
        store.ensureGuild("other");var other=store.addPlayerForVoting("other","Other player");
        assertEquals(400,request("/api/rating-polls/sync",Map.of("id",other.id())).statusCode());
        assertEquals(0,store.ratingPoll("other",other.id()).voteCount());
    }
}
