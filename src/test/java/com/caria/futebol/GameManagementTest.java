package com.caria.futebol;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GameManagementTest {
 @TempDir Path directory;
 FootballStore store;WebMain web;final HttpClient client=HttpClient.newHttpClient();
 static final String PASSWORD="game-management-password";
 @BeforeEach void start() throws Exception {store=new FootballStore(directory.resolve("games.db"));web=new WebMain(store,"guild","127.0.0.1",0,"ignored-old-key",null,PASSWORD);web.start();}
 @AfterEach void stop(){web.stop();}
 HttpResponse<String> request(String path,Object body) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+web.port()+path));
  if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)));
  return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
 }
 JsonObject json(HttpResponse<String> r){return JsonParser.parseString(r.body()).getAsJsonObject();}
 Map<String,Object> game(){var p=store.players("guild");return new HashMap<>(Map.of("teamA",p.subList(0,5).stream().map(Player::id).toList(),"teamB",p.subList(5,10).stream().map(Player::id).toList(),"goalsA",4,"goalsB",2,"playedAt",Instant.now().minusSeconds(3600).toString()));}
 @Test void publicBrowsingAndProtectedLoggingDeletionRecalculateStats() throws Exception {
  assertEquals(200,request("/api/players",null).statusCode());assertEquals(200,request("/api/history",null).statusCode());
  var body=game();assertEquals(403,request("/api/results/review",body).statusCode());body.put("password","wrong");assertEquals(403,request("/api/results/review",body).statusCode());
  assertTrue(store.matches("guild",null,0,10).isEmpty());body.put("password",PASSWORD);var response=request("/api/results/review",body);assertEquals(200,response.statusCode(),response.body());var d=json(response);
  var confirm=new HashMap<String,Object>(Map.of("draftId",d.get("draftId").getAsString(),"revision",d.get("revision").getAsInt()));
  assertEquals(403,request("/api/results/confirm",confirm).statusCode());assertTrue(store.matches("guild",null,0,10).isEmpty());
  confirm.put("password",PASSWORD);response=request("/api/results/confirm",confirm);assertEquals(200,response.statusCode(),response.body());long id=json(response).get("id").getAsLong();
  long winner=store.players("guild").get(0).id();assertEquals(1,new StatisticsService(store).profile("guild",winner).wins());
  assertEquals(403,request("/api/results/delete",Map.of("id",id,"version",1)).statusCode());
  assertEquals(400,request("/api/results/delete",Map.of("id",id,"version",2,"password",PASSWORD)).statusCode());assertEquals(1,store.matches("guild",null,0,10).size());
  assertEquals(200,request("/api/results/delete",Map.of("id",id,"version",1,"password",PASSWORD)).statusCode());
  assertEquals(0,new StatisticsService(store).profile("guild",winner).gamesPlayed());assertTrue(store.matches("guild",null,0,10).isEmpty());
  assertEquals(400,request("/api/results/confirm",confirm).statusCode());
  assertEquals(5,store.division("guild",store.draft("guild","website",d.get("draftId").getAsString()).divisionId()).teamA().size());
 }
 @Test void staleVersionsAndOtherGuildsCannotDeleteChangedGames() throws Exception {
  var body=game();body.put("password",PASSWORD);var d=json(request("/api/results/review",body));
  long id=json(request("/api/results/confirm",Map.of("draftId",d.get("draftId").getAsString(),"revision",d.get("revision").getAsInt(),"password",PASSWORD))).get("id").getAsLong();
  var edit=store.beginChange("guild","discord","channel",id,false);edit=store.score("guild","discord",edit.id(),2,2,edit.playedAt(),edit.revision());store.confirm("guild","discord",edit.id(),edit.revision(),true);
  assertEquals(400,request("/api/results/delete",Map.of("id",id,"version",1,"password",PASSWORD)).statusCode());assertEquals(2,store.match("guild",id).version());
  assertThrows(FootballException.class,()->store.deleteMatch("other-guild",id,2));
  assertEquals(2,json(request("/api/history",null)).getAsJsonArray("matches").get(0).getAsJsonObject().get("version").getAsInt());
  assertEquals(200,request("/api/results/delete",Map.of("id",id,"version",2,"password",PASSWORD)).statusCode());
 }
}
