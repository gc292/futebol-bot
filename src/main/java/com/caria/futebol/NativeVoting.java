package com.caria.futebol;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Link voting: a random private browser cookie identifies an editable response. */
final class NativeVoting {
    private final FootballStore store;
    private final String guild;
    private final boolean secureCookies;
    NativeVoting(FootballStore store,String guild,boolean secureCookies) { this.store=store; this.guild=guild; this.secureCookies=secureCookies; }
    boolean configured() { return true; }
    private static String browserToken(HttpExchange x) {
        for (String item:Objects.toString(x.getRequestHeaders().getFirst("Cookie"),"").split(";")) {
            String[] parts=item.trim().split("=",2);
            if (parts.length==2 && parts[0].equals("football_vote_browser") && parts[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return parts[1];
        }
        return null;
    }
    private static Map<String,String> query(HttpExchange x) {
        Map<String,String> values=new HashMap<>();
        for (String item:Objects.toString(x.getRequestURI().getRawQuery(),"").split("&")) {
            String[] parts=item.split("=",2);
            if (parts.length==2) values.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }
        return values;
    }
    boolean handle(HttpExchange x) throws IOException {
        if (!x.getRequestURI().getPath().equals("/api/voting")) return false;
        String method=x.getRequestMethod();
        if (!Set.of("GET","POST").contains(method)) { WebMain.reply(x,405,Map.of("error","Method not allowed.")); return true; }
        String id=query(x).get("id"); RatingPoll poll=store.ratingPoll(guild,id);
        if (!store.nativePoll(id)) throw new FootballException("This voting link is unavailable.");
        String token=browserToken(x);
        if (method.equals("POST")) {
            if (token==null) { WebMain.reply(x,400,Map.of("error","Allow cookies and reload the voting page to save your vote.")); return true; }
            store.castRatingVote(guild,id,token,WebMain.body(x).get("score").getAsDouble());
            poll=store.ratingPoll(guild,id);
        } else if (token==null) {
            token=UUID.randomUUID().toString();
            x.getResponseHeaders().add("Set-Cookie","football_vote_browser="+token+"; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000"+(secureCookies ? "; Secure" : ""));
        }
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("name",poll.playerName()); response.put("status",poll.status()); response.put("count",poll.voteCount()); response.put("average",poll.average());
        response.put("ownScore",store.ownRatingVote(guild,id,token));
        WebMain.reply(x,200,response); return true;
    }
}
