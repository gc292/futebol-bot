package com.caria.futebol;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** A small same-origin adapter over the existing database and balancing service. */
public final class WebMain {
    private static final Gson JSON = new Gson();
    private final FootballStore store;
    private final String guild;
    private final String accessKey;
    private final String adminPassword;
    private final Map<String, PasswordAttempts> passwordAttempts = new ConcurrentHashMap<>();
    private record PasswordAttempts(int count, Instant resetAt) {}
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final RatingService ratings;
    private final NativeVoting voting;
    private final boolean legacyForms;
    private final boolean secureCookies;

    WebMain(FootballStore store, String guild, String host, int port, String accessKey) throws IOException {
        this(store,guild,host,port,accessKey,null);
    }
    WebMain(FootballStore store, String guild, String host, int port, String accessKey, FormsGateway forms) throws IOException {
        this(store,guild,host,port,accessKey,forms,null);
    }
    WebMain(FootballStore store, String guild, String host, int port, String accessKey, FormsGateway forms, String adminPassword) throws IOException {
        this(store,guild,host,port,accessKey,forms,adminPassword,false);
    }
    WebMain(FootballStore store, String guild, String host, int port, String accessKey, FormsGateway forms, String adminPassword, boolean secureCookies) throws IOException {
        if (guild == null || guild.isBlank()) throw new IllegalArgumentException("Set FOOTBALL_GUILD_ID to your Discord server ID.");
        if (!Set.of("127.0.0.1", "localhost", "::1").contains(host) && (accessKey == null || accessKey.length() < 16))
            throw new IllegalArgumentException("Set FOOTBALL_WEB_KEY (at least 16 characters) when listening on the network.");
        this.store = store; this.guild = guild; this.accessKey = accessKey == null ? "" : accessKey;
        this.adminPassword = adminPassword == null ? "" : adminPassword;
        if (!this.adminPassword.isEmpty() && this.adminPassword.length() < 8)
            throw new IllegalArgumentException("FOOTBALL_ADMIN_PASSWORD must contain at least 8 characters.");
        store.ensureGuild(guild);
        ratings = new RatingService(store,guild,forms);
        this.secureCookies=secureCookies;
        voting = new NativeVoting(store,guild,secureCookies); legacyForms=forms!=null;
        server = HttpServer.create(new InetSocketAddress(host, port), 32);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        WebMain web = new WebMain(FootballStore.fromEnvironment(env),
                env.get("FOOTBALL_GUILD_ID"), env.getOrDefault("FOOTBALL_WEB_HOST", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("FOOTBALL_WEB_PORT", "8080")), env.get("FOOTBALL_WEB_KEY"),
                null, env.get("FOOTBALL_ADMIN_PASSWORD"), Boolean.parseBoolean(env.getOrDefault("FOOTBALL_WEB_SECURE_COOKIES","false")));
        web.start();
        System.out.println("Football website listening on " + web.server.getAddress());
    }

    void start() { server.start(); ratings.start(); }
    int port() { return server.getAddress().getPort(); }
    void stop() { ratings.close(); server.stop(0); ((ExecutorService) server.getExecutor()).shutdownNow(); }

    private boolean authorized(HttpExchange x) {
        if (accessKey.isEmpty()) return true;
        String cookies = x.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) return false;
        for (String cookie : cookies.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals("football_session")) {
                Instant expiry = sessions.get(parts[1]);
                if (expiry != null && expiry.isAfter(Instant.now())) return true;
            }
        }
        return false;
    }

    private synchronized boolean requireAdmin(HttpExchange x, JsonObject body) throws IOException {
        if (adminPassword.isEmpty()) {
            reply(x,403,Map.of("error","Set FOOTBALL_ADMIN_PASSWORD on the server before adding or removing players.")); return false;
        }
        Instant now = Instant.now();
        passwordAttempts.entrySet().removeIf(e -> !e.getValue().resetAt().isAfter(now));
        String address = x.getRemoteAddress().getAddress().getHostAddress();
        PasswordAttempts attempts = passwordAttempts.get(address);
        if (attempts != null && attempts.count() >= 5) {
            reply(x,429,Map.of("error","Too many incorrect passwords. Try again in a minute.")); return false;
        }
        String supplied = body.has("password") && body.get("password").isJsonPrimitive() ? body.get("password").getAsString() : "";
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),adminPassword.getBytes(StandardCharsets.UTF_8))) {
            if (passwordAttempts.size() >= 1000 && attempts == null) {
                reply(x,429,Map.of("error","Too many password attempts. Try again in a minute.")); return false;
            }
            passwordAttempts.put(address,new PasswordAttempts(attempts == null ? 1 : attempts.count()+1,attempts == null ? now.plusSeconds(60) : attempts.resetAt()));
            reply(x,403,Map.of("error","Incorrect player management password.")); return false;
        }
        passwordAttempts.remove(address); return true;
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("Referrer-Policy", "same-origin");
            x.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            String path = x.getRequestURI().getPath();
            boolean post = x.getRequestMethod().equals("POST");
            if (post && !Objects.toString(x.getRequestHeaders().getFirst("Content-Type"), "").startsWith("application/json")) {
                reply(x, 415, Map.of("error", "Use application/json.")); return;
            }
            if (voting.handle(x)) return;
            if (path.equals("/api/login") && post) {
                String key = body(x).get("key").getAsString();
                if (!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), accessKey.getBytes(StandardCharsets.UTF_8))) {
                    reply(x, 401, Map.of("error", "That access key is incorrect.")); return;
                }
                sessions.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
                if (sessions.size() >= 1000) { reply(x, 429, Map.of("error", "Too many sessions.")); return; }
                String token = UUID.randomUUID().toString();
                sessions.put(token, Instant.now().plusSeconds(86400));
                x.getResponseHeaders().add("Set-Cookie", "football_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400"+(secureCookies ? "; Secure" : ""));
                reply(x, 200, Map.of("ok", true)); return;
            }
            if (path.startsWith("/api/")) {
                if (!authorized(x)) { reply(x, 401, Map.of("error", "Enter your server access key to continue.")); return; }
                if (path.equals("/api/players") && !post && x.getRequestMethod().equals("GET")) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    Map<Long,RatingPoll> polls = new HashMap<>();
                    for (RatingPoll poll : store.ratingPolls(guild)) polls.put(poll.playerId(),poll);
                    for (StatisticsService.Entry entry : new StatisticsService(store).leaderboard(guild, StatisticsService.Metric.GAMES)) {
                        Map<String, Object> row = new LinkedHashMap<>(player(entry.player()));
                        PlayerStatistics s = entry.statistics();
                        row.put("games", s.gamesPlayed()); row.put("wins", s.wins()); row.put("draws", s.draws()); row.put("losses", s.losses());
                        row.put("winRate", s.gamesPlayed() == 0 ? null : s.winRate()); row.put("goalDifference", s.goalDifference());
                        row.put("points", s.points()); row.put("streak", s.currentStreak()); rows.add(row);
                        RatingPoll poll = polls.get(entry.player().id());
                        row.put("ratingReady",poll == null || poll.voteCount() > 0);
                        if (poll != null) row.put("ratingPoll",poll(poll));
                    }
                    reply(x, 200, Map.of("players", rows, "minimumGames", store.settings(guild).minimumWinRateGames(), "teamSize", store.settings(guild).manualTeamSize(),
                            "formsConfigured",ratings.configured(),"formsSyncFailed",ratings.syncFailed(),"votingConfigured",voting.configured(),"adminConfigured",!adminPassword.isEmpty())); return;
                }
                if (path.equals("/api/players/vote") && post) {
                    JsonObject b = body(x); if (!requireAdmin(x,b)) return;
                    RatingPoll poll = legacyForms ? ratings.add(b.get("name").getAsString()) : store.addNativePoll(guild,b.get("name").getAsString());
                    reply(x,poll.status().equals("CREATING") ? 202 : 201,Map.of("player",player(store.player(guild,poll.playerId())),"poll",poll(poll))); return;
                }
                if (path.equals("/api/rating-polls") && x.getRequestMethod().equals("GET")) {
                    reply(x,200,Map.of("polls",store.ratingPolls(guild).stream().map(this::poll).toList())); return;
                }
                if (Set.of("/api/rating-polls/retry","/api/rating-polls/sync","/api/rating-polls/close","/api/rating-polls/cleanup").contains(path) && post) {
                    JsonObject request=body(x); String id=request.get("id").getAsString();
                    if (store.nativePoll(id)) {
                        store.ratingPoll(guild,id);
                        if (!path.endsWith("/close")) throw new FootballException("Website votes update automatically.");
                        if (!requireAdmin(x,request)) return;
                        store.closeNativePoll(guild,id); reply(x,200,poll(store.ratingPoll(guild,id))); return;
                    }
                    if (!legacyForms) {
                        if (!path.endsWith("/close")) throw new FootballException("Google polls no longer sync. New votes happen on the website.");
                        if (!requireAdmin(x,request)) return;
                        store.finishSavedGooglePoll(guild,id); reply(x,200,poll(store.ratingPoll(guild,id))); return;
                    }
                    if (path.endsWith("/cleanup")) {
                        store.ratingPoll(guild,id); ratings.cleanup(id);
                        reply(x,200,poll(store.ratingPoll(guild,id))); return;
                    }
                    RatingPoll poll=path.endsWith("/retry") ? ratings.create(id) : ratings.update(id,path.endsWith("/close"));
                    reply(x,200,poll(poll)); return;
                }
                if (path.equals("/api/players") && post) {
                    JsonObject b = body(x);
                    if (!requireAdmin(x,b)) return;
                    reply(x, 201, player(store.addPlayer(guild, b.get("name").getAsString(), b.get("rating").getAsDouble()))); return;
                }
                if (path.equals("/api/players/remove") && post) {
                    JsonObject b = body(x); if (!requireAdmin(x,b)) return;
                    store.removePlayer(guild,b.get("id").getAsBigDecimal().longValueExact());
                    reply(x,200,Map.of("ok",true)); return;
                }
                if (path.equals("/api/teams") && post) {
                    JsonObject b = body(x);
                    Set<Long> selected = new LinkedHashSet<>();
                    for (JsonElement id : b.getAsJsonArray("playerIds")) {
                        long value = id.getAsBigDecimal().longValueExact();
                        if (!selected.add(value)) throw new FootballException("Select each player only once.");
                    }
                    List<Player> roster = store.players(guild);
                    Set<Long> ready = new HashSet<>(store.ratedPlayers(guild).stream().map(Player::id).toList());
                    List<Player> attendees = roster.stream().filter(p -> selected.contains(p.id()) && ready.contains(p.id())).toList();
                    if (attendees.size() != selected.size()) throw new FootballException("A selected player is unavailable or still awaiting rating votes. Refresh and try again.");
                    if (attendees.size() < 10) throw new FootballException("Select at least 10 players for 5v5.");
                    Map<String, Double> ratings = new HashMap<>();
                    attendees.forEach(p -> ratings.put(p.name(), p.rating()));
                    List<String> names = attendees.stream().map(Player::name).toList();
                    boolean random = b.has("random") && b.get("random").getAsBoolean();
                    Pick10Result pick = random ? TeamGenerator.pickRandom10(names) : TeamGenerator.pickBest10ForBalance(names, ratings::get);
                    Teams teams = random ? TeamGenerator.splitRandom5v5(pick.chosenPlayers, ratings::get)
                            : TeamGenerator.splitIntoBalanced5v5(pick.chosenPlayers, ratings::get);
                    TeamDivision division = store.saveGeneratedDivision(guild, "website", "website", teams.teamA, teams.teamB, random ? "REMAKE" : "TEAMS");
                    reply(x, 201, Map.of("id", Long.toString(division.id()), "teamA", division.teamA().stream().map(WebMain::player).toList(),
                            "teamB", division.teamB().stream().map(WebMain::player).toList(), "sumA", teams.sumA, "sumB", teams.sumB,
                            "substitutes", pick.substitutes, "random", random)); return;
                }
                if (path.equals("/api/divisions") && x.getRequestMethod().equals("GET")) {
                    List<Map<String, Object>> divisions = new ArrayList<>();
                    for (TeamDivision d : store.pendingDivisions(guild, 0, 100)) divisions.add(Map.of("id", Long.toString(d.id()),
                            "createdAt", d.createdAt().toString(), "teamA", d.teamA().stream().map(WebMain::player).toList(),
                            "teamB", d.teamB().stream().map(WebMain::player).toList()));
                    reply(x, 200, Map.of("divisions", divisions)); return;
                }
                if (path.equals("/api/results/review") && post) {
                    JsonObject b = body(x);
                    int goalsA = FootballStore.parseGoals(b.get("goalsA").getAsString());
                    int goalsB = FootballStore.parseGoals(b.get("goalsB").getAsString());
                    Instant playedAt = Instant.parse(b.get("playedAt").getAsString());
                    if (playedAt.isAfter(Instant.now())) throw new FootballException("The game date cannot be in the future.");
                    ResultDraft draft;
                    if (b.has("divisionId") && !b.get("divisionId").isJsonNull()) {
                        draft = store.beginGenerated(guild, "website", "website", b.get("divisionId").getAsBigDecimal().longValueExact());
                    } else {
                        List<Long> a = readIds(b.getAsJsonArray("teamA")), other = readIds(b.getAsJsonArray("teamB"));
                        int size = store.settings(guild).manualTeamSize();
                        if (a.size() != size || other.size() != size) throw new FootballException("Select exactly " + size + " players per team.");
                        Set<Long> unique = new HashSet<>();
                        for (long id : java.util.stream.Stream.concat(a.stream(), other.stream()).toList()) {
                            if (!unique.add(id)) throw new FootballException("A player cannot be in both teams or selected twice.");
                            store.player(guild, id);
                        }
                        draft = store.beginManual(guild, "website", "website");
                        draft = store.selectManualTeam(guild, "website", draft.id(), "A", a, draft.revision());
                        draft = store.selectManualTeam(guild, "website", draft.id(), "B", other, draft.revision());
                        draft = store.freezeManualTeams(guild, "website", draft.id(), draft.revision());
                    }
                    draft = store.score(guild, "website", draft.id(), goalsA, goalsB, playedAt, draft.revision());
                    reply(x, 200, Map.of("draftId", draft.id(), "revision", draft.revision(), "goalsA", goalsA, "goalsB", goalsB,
                            "playedAt", playedAt.toString(), "teamA", draft.teamA().stream().map(WebMain::player).toList(),
                            "teamB", draft.teamB().stream().map(WebMain::player).toList())); return;
                }
                if (path.equals("/api/results/confirm") && post) {
                    JsonObject b = body(x);
                    ResultDraft draft = store.draft(guild, "website", b.get("draftId").getAsString());
                    int revision = b.get("revision").getAsBigDecimal().intValueExact();
                    if (!draft.action().equals("CREATE")) throw new FootballException("This form cannot create a game.");
                    long matchId = draft.status().equals("CONFIRMED") ? draft.confirmedMatchId()
                            : store.confirm(guild, "website", draft.id(), revision, false);
                    reply(x, 200, Map.of("id", Long.toString(matchId))); return;
                }
                if (path.equals("/api/history") && x.getRequestMethod().equals("GET")) {
                    int page = 0;
                    String query = x.getRequestURI().getQuery();
                    if (query != null && query.startsWith("page=")) page = Integer.parseInt(query.substring(5));
                    if (page < 0 || page > 100000) throw new FootballException("Invalid page.");
                    List<Map<String, Object>> matches = new ArrayList<>();
                    for (Match m : store.matches(guild, null, page, 12)) matches.add(Map.of("id", Long.toString(m.id()),
                            "playedAt", m.playedAt().toString(), "goalsA", m.goalsA(), "goalsB", m.goalsB(),
                            "teamA", m.division().teamA().stream().map(Player::name).toList(),
                            "teamB", m.division().teamB().stream().map(Player::name).toList()));
                    reply(x, 200, Map.of("matches", matches, "hasMore", !store.matches(guild, null, page + 1, 12).isEmpty())); return;
                }
                reply(x, 404, Map.of("error", "Endpoint not found.")); return;
            }
            if (!x.getRequestMethod().equals("GET")) { reply(x, 405, Map.of("error", "Method not allowed.")); return; }
            String resource = switch (path) { case "/", "/index.html" -> "index.html"; case "/vote" -> "vote.html"; case "/vote.js" -> "vote.js"; case "/app.js" -> "app.js"; case "/style.css" -> "style.css"; default -> null; };
            if (resource == null) { reply(x, 404, Map.of("error", "Not found.")); return; }
            try (var input = WebMain.class.getResourceAsStream("/web/" + resource)) {
                if (input == null) { reply(x, 404, Map.of("error", "Missing website asset.")); return; }
                byte[] bytes = input.readAllBytes();
                x.getResponseHeaders().set("Content-Type", resource.endsWith(".js") ? "text/javascript; charset=utf-8" : resource.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
                x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes);
            }
        } catch (JsonParseException e) {
            reply(x, 400, Map.of("error", "Invalid JSON request."));
        } catch (FootballException | IllegalArgumentException | ArithmeticException | NullPointerException | IllegalStateException | java.time.DateTimeException e) {
            if (e instanceof FootballException) reply(x, 400, Map.of("error", e.getMessage()));
            else if (e instanceof IllegalStateException && e.getCause() instanceof java.sql.SQLException) {
                e.printStackTrace(System.err); reply(x, 500, Map.of("error", "Could not access the database. Please try again."));
            } else reply(x, 400, Map.of("error", "Invalid request. Check your input and try again."));
        } catch (Exception e) {
            e.printStackTrace(System.err); reply(x, 500, Map.of("error", "Something went wrong. Please try again."));
        } finally { x.close(); }
    }

    private static Map<String, Object> player(Player p) {
        return Map.of("id", Long.toString(p.id()), "name", p.name(), "rating", p.rating());
    }
    private Map<String,Object> poll(RatingPoll p) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id",p.id()); row.put("playerId",Long.toString(p.playerId())); row.put("name",p.playerName());
        row.put("native",store.nativePoll(p.id()));
        row.put("cleanupPending",store.ratingCleanupPending(guild,p.id()));
        row.put("url",p.formUrl()); row.put("status",p.status()); row.put("count",p.voteCount()); row.put("average",p.average()); row.put("updatedAt",p.updatedAt());
        return row;
    }
    private static List<Long> readIds(JsonArray array) {
        List<Long> ids = new ArrayList<>();
        for (JsonElement id : array) ids.add(id.getAsBigDecimal().longValueExact());
        return ids;
    }
    static JsonObject body(HttpExchange x) throws IOException {
        byte[] bytes = x.getRequestBody().readNBytes(16385);
        if (bytes.length > 16384) throw new FootballException("Request is too large.");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    static void reply(HttpExchange x, int status, Object value) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes);
    }
}
