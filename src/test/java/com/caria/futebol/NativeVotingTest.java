package com.caria.futebol;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeVotingTest {
    @TempDir Path temporary;
    FootballStore store;
    WebMain web;
    static final String PASSWORD="test-management-password", KEY="test-site-access-key";
    HttpClient client() {
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL) {
            @Override public void put(URI uri,Map<String,List<String>> headers) throws java.io.IOException {
                super.put(uri,headers);
                // Match browser cookie syntax rather than Java's obsolete quoted RFC2965 format.
                getCookieStore().getCookies().forEach(c -> c.setVersion(0));
            }
        };
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }
    @BeforeEach void setup() throws Exception {
        store=new FootballStore(temporary.resolve("votes.db"));
        web=new WebMain(store,"guild","127.0.0.1",0,KEY,null,PASSWORD); web.start();
    }
    @AfterEach void stop() { web.stop(); }
    HttpResponse<String> request(HttpClient client,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+path));
        if (body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonObject json(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    HttpClient admin() throws Exception {
        var client=client(); assertEquals(200,request(client,"/api/login",Map.of("key",KEY)).statusCode()); return client;
    }
    String add(HttpClient admin) throws Exception {
        var response=request(admin,"/api/players/vote",Map.of("name","New friend","password",PASSWORD));
        assertEquals(201,response.statusCode(),response.body()); return json(response).getAsJsonObject("poll").get("id").getAsString();
    }
    @Test void friendsVoteWithoutSiteKeyUpdateOneVoteAndCloseDeletesIndividualScores() throws Exception {
        var admin=admin(); String id=add(admin); var a=client(); var b=client();
        assertEquals(200,request(a,"/vote?id="+id,null).statusCode());
        assertEquals(200,request(a,"/api/voting?id="+id,null).statusCode());
        assertEquals(401,request(a,"/api/players",null).statusCode());
        assertEquals(400,request(client(),"/api/voting?id="+id,Map.of("score",99,"voter_id","spoofed")).statusCode());
        request(b,"/api/voting?id="+id,null);
        assertEquals(200,request(a,"/api/voting?id="+id,Map.of("score",60)).statusCode());
        assertEquals(200,request(b,"/api/voting?id="+id,Map.of("score",90)).statusCode());
        assertEquals(75,store.ratingPoll("guild",id).average());
        request(a,"/api/voting?id="+id,Map.of("score",80.5));
        assertEquals(2,store.ratingPoll("guild",id).voteCount()); assertEquals(85.25,store.ratingPoll("guild",id).average());
        assertEquals(80.5,json(request(a,"/api/voting?id="+id,null)).get("ownScore").getAsDouble());
        assertEquals(90,json(request(b,"/api/voting?id="+id,null)).get("ownScore").getAsDouble());
        assertEquals(403,request(admin,"/api/rating-polls/close",Map.of("id",id)).statusCode());
        assertEquals(200,request(admin,"/api/rating-polls/close",Map.of("id",id,"password",PASSWORD)).statusCode());
        assertEquals(400,request(a,"/api/voting?id="+id,Map.of("score",1)).statusCode());
        FootballStore reopened=new FootballStore(temporary.resolve("votes.db"));
        assertEquals("CLOSED",reopened.ratingPoll("guild",id).status()); assertEquals(2,reopened.ratingPoll("guild",id).voteCount());
        assertEquals(85.25,reopened.player("guild",reopened.ratingPoll("guild",id).playerId()).rating());
        assertFalse(json(request(a,"/api/voting?id="+id,null)).has("ownScore"));
        assertFalse(json(request(b,"/api/voting?id="+id,null)).has("ownScore"));
        assertFalse(reopened.ratingCleanupPending("guild",id));
        assertEquals(200,request(admin,"/api/rating-polls/close",Map.of("id",id,"password",PASSWORD)).statusCode());
    }
    @Test void browserCookieKeepsTheSameEditableVoteAfterServerRestart() throws Exception {
        String id=add(admin()); var browser=client();
        var opened=request(browser,"/api/voting?id="+id,null);
        String cookie=opened.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(cookie.contains("Secure"));
        request(browser,"/api/voting?id="+id,Map.of("score",72));
        web.stop(); web=new WebMain(store,"guild","127.0.0.1",0,KEY,null,PASSWORD); web.start();
        assertEquals(72,json(request(browser,"/api/voting?id="+id,null)).get("ownScore").getAsDouble());
        request(browser,"/api/voting?id="+id,Map.of("score",83));
        assertEquals(1,store.ratingPoll("guild",id).voteCount()); assertEquals(83,store.ratingPoll("guild",id).average());
        assertFalse(json(request(client(),"/api/voting?id="+id,null)).has("ownScore"));
        assertEquals(404,request(browser,"/auth/discord/start?poll="+id,null).statusCode());
    }
    @Test void invalidEmptyRemovedAndOtherGuildPollsCannotChangeRatings() throws Exception {
        String id=add(admin());
        assertThrows(FootballException.class,()->store.closeNativePoll("guild",id));
        assertThrows(FootballException.class,()->store.castRatingVote("guild",id,"one",Double.NaN));
        assertThrows(FootballException.class,()->store.castRatingVote("guild",id,"one",101));
        assertEquals(0,store.ratingPoll("guild",id).voteCount());
        store.ensureGuild("other"); String foreign=store.addNativePoll("other","Other friend").id();
        assertEquals(400,request(client(),"/api/voting?id="+foreign,null).statusCode());
        store.removePlayer("guild",store.ratingPoll("guild",id).playerId());
        assertEquals(400,request(client(),"/api/voting?id="+id,null).statusCode());
        assertThrows(FootballException.class,()->store.castRatingVote("guild",id,"one",80));
        RatingPoll restored=store.addNativePoll("guild","New friend"); assertNotEquals(id,restored.id());
        assertFalse(store.nativePoll(id)); assertEquals(0,restored.voteCount());
    }
    @Test void concurrentVotesAndClosingAcrossConnectionsKeepFinalSnapshotConsistent() throws Exception {
        String id=add(admin()); store.castRatingVote("guild",id,"first",80);
        FootballStore other=new FootballStore(temporary.resolve("votes.db"));
        var executor=Executors.newFixedThreadPool(2);
        try {
            var vote=executor.submit(()-> { try { other.castRatingVote("guild",id,"second",100); } catch(FootballException closed) { assertTrue(closed.getMessage().contains("closed")); } });
            var close=executor.submit(()->store.closeNativePoll("guild",id)); vote.get(); close.get();
        } finally { executor.shutdownNow(); }
        var finalPoll=store.ratingPoll("guild",id);
        assertEquals("CLOSED",finalPoll.status());
        assertEquals(finalPoll.voteCount()==1 ? 80 : 90,finalPoll.average());
        assertNull(other.ownRatingVote("guild",id,"second"));
    }
    @Test void secureCookiesCanBeEnabledWhenHostedWithHttps() throws Exception {
        String id=add(admin()); web.stop();
        web=new WebMain(store,"guild","127.0.0.1",0,KEY,null,PASSWORD,true); web.start();
        assertTrue(request(client(),"/api/voting?id="+id,null).headers().firstValue("set-cookie").orElseThrow().contains("; Secure"));
    }
    @Test void olderGooglePollsKeepTheirSavedAverageWithoutCallingGoogle() throws Exception {
        RatingPoll old=store.addPlayerForVoting("guild","Older friend");
        store.attachRatingForm("guild",old.id(),"https://docs.google.com/forms/d/e/older/viewform");
        store.updateRatingVotes("guild",old.id(),List.of(70.0,90.0),false);
        var admin=admin();
        assertEquals(403,request(admin,"/api/rating-polls/close",Map.of("id",old.id())).statusCode());
        assertEquals(200,request(admin,"/api/rating-polls/close",Map.of("id",old.id(),"password",PASSWORD)).statusCode());
        assertEquals("CLOSED",store.ratingPoll("guild",old.id()).status());
        assertEquals(80,store.player("guild",old.playerId()).rating());
        assertFalse(store.ratingCleanupPending("guild",old.id()));
        assertEquals(400,request(client(),"/api/voting?id="+old.id(),null).statusCode());
        RatingPoll empty=store.addPlayerForVoting("guild","Empty old friend");
        assertEquals(400,request(admin,"/api/rating-polls/close",Map.of("id",empty.id(),"password",PASSWORD)).statusCode());
    }
}
