package com.caria.futebol;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Shared SQLite or MariaDB persistence. Each operation uses an isolated transaction. */
final class FootballStore {
    private final String url;
    private final SQLiteConfig config = new SQLiteConfig();
    private final Properties connectionProperties;
    private final boolean maria;

    FootballStore(Path database) {
        try {
            Path absolute = database.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            url = "jdbc:sqlite:" + absolute;
            maria = false;
            config.enforceForeignKeys(true);
            config.setBusyTimeout(10_000);
            config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connectionProperties=config.toProperties();
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                    if (version.next() && version.getInt(1) > 1)
                        throw new IllegalStateException("Database was created by a newer version of the bot");
                }
                statement.execute("PRAGMA journal_mode=WAL");
                try (var resource = FootballStore.class.getResourceAsStream("/schema.sql")) {
                    if (resource == null) throw new IllegalStateException("Missing schema.sql");
                    for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Cannot initialize football database", e);
        }
    }

    static FootballStore fromEnvironment(Map<String,String> env) {
        String jdbc=env.getOrDefault("FOOTBALL_DB_URL","");
        if (jdbc.isBlank()) return new FootballStore(Path.of(env.getOrDefault("FOOTBALL_DB_PATH","data/football.db")));
        String user=env.getOrDefault("FOOTBALL_DB_USER","");
        if (user.isBlank() || !env.containsKey("FOOTBALL_DB_PASSWORD"))
            throw new IllegalArgumentException("Set FOOTBALL_DB_USER and FOOTBALL_DB_PASSWORD with FOOTBALL_DB_URL.");
        return new FootballStore(jdbc,user,env.get("FOOTBALL_DB_PASSWORD"));
    }
    FootballStore(String jdbc,String user,String password) {
        if (jdbc==null || !jdbc.startsWith("jdbc:mariadb://")) throw new IllegalArgumentException("FOOTBALL_DB_URL must start with jdbc:mariadb://.");
        url=jdbc; maria=true; connectionProperties=new Properties();
        connectionProperties.setProperty("user",user); connectionProperties.setProperty("password",password);
        connectionProperties.setProperty("connectTimeout","10000"); connectionProperties.setProperty("socketTimeout","30000");
        try (Connection c=open(); Statement statement=c.createStatement()) {
            try (var resource=FootballStore.class.getResourceAsStream("/schema-mariadb.sql")) {
                if (resource==null) throw new IllegalStateException("Missing MariaDB schema.");
                for (String sql:new String(resource.readAllBytes(),StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
            try (ResultSet version=statement.executeQuery("SELECT version FROM schema_metadata WHERE id=1")) {
                if (!version.next() || version.getInt(1)!=1) throw new IllegalStateException("Unsupported MariaDB schema version.");
            }
        } catch (SQLException | IOException error) { throw new IllegalStateException("Cannot initialize MariaDB football database",error); }
    }

    private static String timestamp(Instant instant) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(instant);
    }

    private Connection open() throws SQLException { return DriverManager.getConnection(url, connectionProperties); }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    @FunctionalInterface private interface Mapper<T> { T map(ResultSet result) throws SQLException; }

    private synchronized <T> T transaction(Work<T> work) {
        try (Connection connection = open()) {
            if (maria && one(connection,"SELECT GET_LOCK(CONCAT('football:',LEFT(SHA2(DATABASE(),256),48)),10)",r -> r.getInt(1))!=1)
                throw new FootballException("The database is busy. Try again shortly.");
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                if (maria) try (Statement release=connection.createStatement()) {
                    release.execute("SELECT RELEASE_LOCK(CONCAT('football:',LEFT(SHA2(DATABASE(),256),48)))");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Football database operation failed", e);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        if (!c.getMetaData().getURL().startsWith("jdbc:sqlite:")) sql=sql
                .replace("INSERT OR IGNORE","INSERT IGNORE")
                .replace("ON CONFLICT(guild_id,name) DO NOTHING","ON DUPLICATE KEY UPDATE name=VALUES(name)")
                .replace("ON CONFLICT(poll_id,voter_id) DO UPDATE SET score=excluded.score","ON DUPLICATE KEY UPDATE score=VALUES(score)")
                .replace("last_insert_rowid()","LAST_INSERT_ID()");
        PreparedStatement statement = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }
    private static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, args)) { return statement.executeUpdate(); }
    }
    private static <T> List<T> query(Connection c, String sql, Mapper<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, args); ResultSet result = statement.executeQuery()) {
            List<T> values = new ArrayList<>();
            while (result.next()) values.add(mapper.map(result));
            return values;
        }
    }
    private static <T> T one(Connection c, String sql, Mapper<T> mapper, Object... args) throws SQLException {
        List<T> values = query(c, sql, mapper, args);
        if (values.isEmpty()) throw new FootballException("Not found in this server.");
        return values.get(0);
    }
    private static long lastId(Connection c) throws SQLException {
        return one(c, "SELECT last_insert_rowid()", r -> r.getLong(1));
    }
    private static Long nullableLong(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column); return r.wasNull() ? null : value;
    }
    private static Integer nullableInt(ResultSet r, String column) throws SQLException {
        int value = r.getInt(column); return r.wasNull() ? null : value;
    }
    private static Player readPlayer(ResultSet r) throws SQLException {
        return new Player(r.getLong("id"), r.getString("guild_id"), r.getString("name"),
                r.getString("discord_id"), r.getDouble("rating"));
    }

    void ensureGuild(String guildId) {
        if (guildId == null || guildId.isBlank()) throw new FootballException("Use this command in a server.");
        transaction(c -> {
            execute(c, "INSERT OR IGNORE INTO guild_settings(guild_id) VALUES(?)", guildId);
            for (int i = 0; i < PlayerCatalog.PLAYERS.size(); i++) {
                String name = PlayerCatalog.PLAYERS.get(i);
                execute(c, """
                        INSERT INTO players(guild_id,name,rating,roster_order) VALUES(?,?,?,?)
                        ON CONFLICT(guild_id,name) DO NOTHING
                        """, guildId, name, PlayerCatalog.rankOf(name), i);
            }
            return null;
        });
    }
    List<Player> players(String guildId) {
        return transaction(c -> query(c, "SELECT * FROM players WHERE guild_id=? AND id NOT IN (SELECT player_id FROM removed_players WHERE guild_id=?) ORDER BY roster_order,id",
                FootballStore::readPlayer, guildId, guildId));
    }
    /** Keep player identities and past match memberships intact. */
    void removePlayer(String guildId, long id) {
        transaction(c -> {
            player(c,guildId,id);
            execute(c,"INSERT OR IGNORE INTO removed_players(guild_id,player_id,removed_at) VALUES(?,?,?)",guildId,id,timestamp(Instant.now()));
            return null;
        });
    }
    private static void requireRosterPlayer(Connection c, String guildId, long id) throws SQLException {
        player(c,guildId,id);
        if (!query(c,"SELECT player_id FROM removed_players WHERE guild_id=? AND player_id=?",r -> r.getLong(1),guildId,id).isEmpty())
            throw new FootballException("This player has been removed. Refresh the player list.");
    }
    Player addPlayer(String guildId, String name, double rating) {
        return addPlayer(guildId, name, rating, null);
    }
    RatingPoll addPlayerForVoting(String guildId, String name) {
        String pollId = UUID.randomUUID().toString();
        addPlayer(guildId, name, 0, pollId);
        return ratingPoll(guildId, pollId);
    }
    private Player addPlayer(String guildId, String name, double rating, String pollId) {
        if (name == null || name.strip().isEmpty() || name.strip().length() > 80)
            throw new FootballException("Enter a player name (1–80 characters).");
        if (!Double.isFinite(rating) || rating < 0 || rating > 100)
            throw new FootballException("Rating must be between 0 and 100.");
        String clean = name.strip();
        return transaction(c -> addPlayer(c,guildId,clean,rating,pollId));
    }
    private Player addPlayer(Connection c,String guildId,String clean,double rating,String pollId) throws SQLException {
            List<Long> existing=query(c,"SELECT id FROM players WHERE guild_id=? AND lower(name)=lower(?)",r -> r.getLong(1),guildId,clean);
            long playerId;
            if (!existing.isEmpty()) {
                playerId=existing.get(0);
                if (execute(c,"DELETE FROM removed_players WHERE guild_id=? AND player_id=?",guildId,playerId)==0)
                    throw new FootballException("A player with that name already exists.");
                // Reuse the historical identity, but start a new rating vote when re-added.
                execute(c,"DELETE FROM rating_polls WHERE guild_id=? AND player_id=?",guildId,playerId);
                execute(c,"UPDATE players SET rating=? WHERE guild_id=? AND id=?",rating,guildId,playerId);
            } else {
                execute(c, "INSERT INTO players(guild_id,name,rating,roster_order) SELECT ?,?,?,COALESCE(MAX(roster_order),-1)+1 FROM players WHERE guild_id=?",
                        guildId, clean, rating, guildId);
                playerId = lastId(c);
            }
            if (pollId != null) execute(c, "INSERT INTO rating_polls(id,guild_id,player_id,updated_at) VALUES(?,?,?,?)",
                    pollId, guildId, playerId, timestamp(Instant.now()));
            return player(c, guildId, playerId);
    }
    private static RatingPoll readPoll(ResultSet r) throws SQLException {
        double average = r.getDouble("average");
        Double nullableAverage = r.wasNull() ? null : average;
        return new RatingPoll(r.getString("id"),r.getString("guild_id"),r.getLong("player_id"),r.getString("name"),
                r.getString("form_url"),r.getString("status"),r.getInt("vote_count"),nullableAverage,r.getString("updated_at"));
    }
    boolean nativePoll(String id) {
        return transaction(c -> !query(c,"SELECT poll_id FROM native_polls WHERE poll_id=?",r -> r.getString(1),id).isEmpty());
    }
    RatingPoll addNativePoll(String guildId, String name) {
        // Player, poll and voting mode must become visible together.
        return transaction(c -> {
            if (name == null || name.strip().isEmpty() || name.strip().length() > 80) throw new FootballException("Enter a player name (1-80 characters).");
            String id=UUID.randomUUID().toString();
            addPlayer(c,guildId,name.strip(),0,id);
            RatingPoll poll=one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id WHERE rp.id=?",FootballStore::readPoll,id);
            execute(c,"INSERT INTO native_polls(poll_id) VALUES(?)",poll.id());
            execute(c,"UPDATE rating_polls SET status='OPEN',form_url=? WHERE id=?","/vote?id="+poll.id(),poll.id());
            return one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id WHERE rp.id=?",FootballStore::readPoll,poll.id());
        });
    }
    Double ownRatingVote(String guildId, String id, String voter) {
        ratingPoll(guildId,id);
        return transaction(c -> {
            List<Double> votes=query(c,"SELECT score FROM rating_votes WHERE poll_id=? AND voter_id=?",r -> r.getDouble(1),id,voter);
            return votes.isEmpty() ? null : votes.get(0);
        });
    }
    void castRatingVote(String guildId, String id, String voter, double score) {
        if (voter == null || voter.isBlank()) throw new FootballException("Open the voting link first.");
        if (!Double.isFinite(score) || score < 0 || score > 100) throw new FootballException("Enter a score between 0 and 100.");
        transaction(c -> {
            RatingPoll poll=one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id WHERE rp.guild_id=? AND rp.id=?",FootballStore::readPoll,guildId,id);
            requireRosterPlayer(c,guildId,poll.playerId());
            if (!poll.status().equals("OPEN") || query(c,"SELECT poll_id FROM native_polls WHERE poll_id=?",r -> r.getString(1),id).isEmpty())
                throw new FootballException("Voting is closed or unavailable.");
            execute(c,"INSERT INTO rating_votes(poll_id,voter_id,score) VALUES(?,?,?) ON CONFLICT(poll_id,voter_id) DO UPDATE SET score=excluded.score",id,voter,score);
            refreshNativeAverage(c,guildId,poll,false);
            return null;
        });
    }
    private void refreshNativeAverage(Connection c,String guildId,RatingPoll poll,boolean close) throws SQLException {
        List<Double> scores=query(c,"SELECT score FROM rating_votes WHERE poll_id=?",r -> r.getDouble(1),poll.id());
        if (scores.isEmpty()) throw new FootballException("At least one vote is required to finish voting.");
        double average=scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        execute(c,"UPDATE rating_polls SET vote_count=?,average=?,status=?,updated_at=? WHERE guild_id=? AND id=?",scores.size(),average,close ? "CLOSED" : "OPEN",timestamp(Instant.now()),guildId,poll.id());
        execute(c,"UPDATE players SET rating=? WHERE guild_id=? AND id=?",average,guildId,poll.playerId());
        if (close) execute(c,"DELETE FROM rating_votes WHERE poll_id=?",poll.id());
    }
    void closeNativePoll(String guildId,String id) {
        transaction(c -> {
            RatingPoll poll=one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id WHERE rp.guild_id=? AND rp.id=?",FootballStore::readPoll,guildId,id);
            requireRosterPlayer(c,guildId,poll.playerId());
            if (poll.status().equals("CLOSED")) return null;
            if (query(c,"SELECT poll_id FROM native_polls WHERE poll_id=?",r -> r.getString(1),id).isEmpty()) throw new FootballException("This is an older Google poll.");
            refreshNativeAverage(c,guildId,poll,true);
            return null;
        });
    }
    void finishSavedGooglePoll(String guildId,String id) {
        transaction(c -> {
            RatingPoll poll=one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id WHERE rp.guild_id=? AND rp.id=?",FootballStore::readPoll,guildId,id);
            requireRosterPlayer(c,guildId,poll.playerId());
            if (poll.status().equals("CLOSED")) return null;
            if (poll.voteCount()==0 || poll.average()==null) throw new FootballException("This older poll has no saved votes. Remove and re-add the player to start website voting or enter a score.");
            execute(c,"UPDATE rating_polls SET status='CLOSED',updated_at=? WHERE guild_id=? AND id=?",timestamp(Instant.now()),guildId,id);
            return null;
        });
    }
    List<RatingPoll> ratingPolls(String guildId) {
        return transaction(c -> query(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id AND p.guild_id=rp.guild_id WHERE rp.guild_id=? AND p.id NOT IN (SELECT player_id FROM removed_players WHERE guild_id=rp.guild_id) ORDER BY p.id DESC",FootballStore::readPoll,guildId));
    }
    RatingPoll ratingPoll(String guildId, String id) {
        return transaction(c -> one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id AND p.guild_id=rp.guild_id WHERE rp.guild_id=? AND rp.id=? AND p.id NOT IN (SELECT player_id FROM removed_players WHERE guild_id=rp.guild_id)",FootballStore::readPoll,guildId,id));
    }
    void attachRatingForm(String guildId, String id, String url) {
        transaction(c -> { execute(c,"UPDATE rating_polls SET form_url=?,status='OPEN',updated_at=? WHERE guild_id=? AND id=? AND status='CREATING'",url,timestamp(Instant.now()),guildId,id); return null; });
    }
    void updateRatingVotes(String guildId, String id, List<Double> scores, boolean closed) {
        for (double value : scores) if (!Double.isFinite(value) || value < 0 || value > 100)
            throw new FootballException("Google returned an invalid score.");
        if (closed && scores.isEmpty()) throw new FootballException("At least one vote is required to finish voting.");
        Double average = scores.isEmpty() ? null : scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        transaction(c -> {
            RatingPoll poll = one(c,"SELECT rp.*,p.name FROM rating_polls rp JOIN players p ON p.id=rp.player_id WHERE rp.guild_id=? AND rp.id=?",FootballStore::readPoll,guildId,id);
            requireRosterPlayer(c,guildId,poll.playerId());
            if (poll.status().equals("CLOSED")) return null;
            if (!poll.status().equals("OPEN")) throw new FootballException("Create the Google Form first.");
            execute(c,"UPDATE rating_polls SET vote_count=?,average=?,status=?,updated_at=? WHERE guild_id=? AND id=?",
                    scores.size(),average,closed ? "CLOSED" : "OPEN",timestamp(Instant.now()),guildId,id);
            execute(c,"UPDATE players SET rating=? WHERE guild_id=? AND id=?",average == null ? 0.0 : average,guildId,poll.playerId());
            // Queue cleanup in the same transaction as the final saved rating.
            if (closed) execute(c,"INSERT OR IGNORE INTO rating_cleanup(poll_id,guild_id) VALUES(?,?)",id,guildId);
            return null;
        });
    }
    List<String> pendingRatingCleanup(String guildId) {
        return transaction(c -> query(c,"SELECT poll_id FROM rating_cleanup WHERE guild_id=? AND complete=0",r -> r.getString(1),guildId));
    }
    boolean ratingCleanupPending(String guildId, String id) {
        return transaction(c -> !query(c,"SELECT poll_id FROM rating_cleanup WHERE guild_id=? AND poll_id=? AND complete=0",r -> r.getString(1),guildId,id).isEmpty());
    }
    void finishRatingCleanup(String guildId, String id) {
        transaction(c -> {
            execute(c,"UPDATE rating_cleanup SET complete=1 WHERE guild_id=? AND poll_id=?",guildId,id);
            execute(c,"UPDATE rating_polls SET form_url=NULL WHERE guild_id=? AND id=? AND status='CLOSED'",guildId,id);
            return null;
        });
    }
    List<Player> ratedPlayers(String guildId) {
        Set<Long> awaiting = new HashSet<>();
        for (RatingPoll poll : ratingPolls(guildId)) if (poll.voteCount() == 0) awaiting.add(poll.playerId());
        return players(guildId).stream().filter(p -> !awaiting.contains(p.id())).toList();
    }
    Player player(String guildId, long id) { return transaction(c -> player(c, guildId, id)); }
    private static Player player(Connection c, String guildId, long id) throws SQLException {
        return one(c, "SELECT * FROM players WHERE guild_id=? AND id=?", FootballStore::readPlayer, guildId, id);
    }
    Player playerByDiscord(String guildId, String discordId) {
        return transaction(c -> one(c, "SELECT * FROM players WHERE guild_id=? AND discord_id=?",
                FootballStore::readPlayer, guildId, discordId));
    }
    void linkPlayer(String guildId, long playerId, String discordId) {
        transaction(c -> {
            player(c, guildId, playerId);
            if (!query(c, "SELECT id FROM players WHERE guild_id=? AND discord_id=? AND id<>?",
                    r -> r.getLong(1), guildId, discordId, playerId).isEmpty())
                throw new FootballException("That Discord account is already linked to another player.");
            execute(c, "UPDATE players SET discord_id=? WHERE guild_id=? AND id=?", discordId, guildId, playerId);
            return null;
        });
    }
    GuildSettings settings(String guildId) { return transaction(c -> settings(c, guildId)); }
    private static GuildSettings settings(Connection c, String guildId) throws SQLException {
        Set<String> roles = new HashSet<>(query(c, "SELECT role_id FROM authorized_roles WHERE guild_id=?",
                r -> r.getString(1), guildId));
        return one(c, "SELECT * FROM guild_settings WHERE guild_id=?",
                r -> new GuildSettings(r.getInt("manual_team_size"), r.getInt("minimum_win_rate_games"), roles), guildId);
    }
    void configure(String guildId, int teamSize, int minimumGames, Set<String> roles) {
        transaction(c -> {
            int available = one(c, "SELECT count(*) FROM players WHERE guild_id=?", r -> r.getInt(1), guildId);
            if (teamSize < 1 || teamSize > 12 || 2 * teamSize > available)
                throw new FootballException("Team size must be between 1 and " + Math.min(12, available / 2) + ".");
            if (minimumGames < 1) throw new FootballException("Minimum games must be at least 1.");
            execute(c, "UPDATE guild_settings SET manual_team_size=?,minimum_win_rate_games=? WHERE guild_id=?",
                    teamSize, minimumGames, guildId);
            execute(c, "DELETE FROM authorized_roles WHERE guild_id=?", guildId);
            for (String role : roles) execute(c, "INSERT INTO authorized_roles VALUES(?,?)", guildId, role);
            return null;
        });
    }

    TeamDivision saveGeneratedDivision(String guildId, String channelId, String userId,
                                       List<String> teamA, List<String> teamB, String mode) {
        return transaction(c -> {
            List<Long> a = idsForNames(c, guildId, teamA), b = idsForNames(c, guildId, teamB);
            return insertDivision(c, guildId, channelId, userId, "BOT", mode, 5, a, b);
        });
    }
    private static List<Long> idsForNames(Connection c, String guildId, List<String> names) throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (String name : names) ids.add(one(c, "SELECT id FROM players WHERE guild_id=? AND name=?", r -> r.getLong(1), guildId, name));
        return ids;
    }
    private static void validateTeams(Connection c, String guildId, int size, List<Long> a, List<Long> b) throws SQLException {
        if (a.size() != size || b.size() != size) throw new FootballException("Both teams must contain exactly " + size + " players.");
        validateMembers(c, guildId, a, b);
    }
    private static void validateMembers(Connection c, String guildId, List<Long> a, List<Long> b) throws SQLException {
        Set<Long> seen = new HashSet<>();
        for (List<Long> team : List.of(a, b)) for (Long id : team) {
            if (id == null || !seen.add(id)) throw new FootballException("A player cannot appear twice or in both teams.");
            requireRosterPlayer(c, guildId, id);
        }
    }
    private static TeamDivision insertDivision(Connection c, String guildId, String channelId, String userId,
                                                String source, String mode, int size, List<Long> a, List<Long> b) throws SQLException {
        validateTeams(c, guildId, size, a, b);
        execute(c, "INSERT INTO divisions(guild_id,channel_id,created_by,created_at,source,mode,team_size) VALUES(?,?,?,?,?,?,?)",
                guildId, channelId, userId, timestamp(Instant.now()), source, mode, size);
        long id = lastId(c);
        insertMembers(c, "division_players", "division_id", guildId, id, a, b);
        return division(c, guildId, id);
    }
    private static void insertMembers(Connection c, String table, String key, String guildId, Object id,
                                      List<Long> a, List<Long> b) throws SQLException {
        for (int team = 0; team < 2; team++) {
            List<Long> values = team == 0 ? a : b;
            for (int i = 0; i < values.size(); i++) execute(c,
                    "INSERT INTO " + table + "(guild_id," + key + ",player_id,team,position) VALUES(?,?,?,?,?)",
                    guildId, id, values.get(i), team == 0 ? "A" : "B", i);
        }
    }
    private static List<Player> members(Connection c, String table, String key, String guildId, Object id, String team) throws SQLException {
        return query(c, "SELECT p.* FROM " + table + " m JOIN players p ON p.id=m.player_id AND p.guild_id=m.guild_id"
                + " WHERE m.guild_id=? AND m." + key + "=? AND m.team=? ORDER BY m.position", FootballStore::readPlayer, guildId, id, team);
    }
    TeamDivision division(String guildId, long id) { return transaction(c -> division(c, guildId, id)); }
    private static TeamDivision division(Connection c, String guildId, long id) throws SQLException {
        List<Player> a = members(c, "division_players", "division_id", guildId, id, "A");
        List<Player> b = members(c, "division_players", "division_id", guildId, id, "B");
        return one(c, "SELECT * FROM divisions WHERE guild_id=? AND id=?", r -> new TeamDivision(
                id, guildId, r.getString("channel_id"), r.getString("created_by"), Instant.parse(r.getString("created_at")),
                r.getString("source"), r.getString("mode"), r.getInt("team_size"), a, b), guildId, id);
    }
    List<TeamDivision> pendingDivisions(String guildId, int page, int limit) {
        checkPage(page, limit);
        return transaction(c -> {
            List<Long> ids = query(c, """
                    SELECT d.id FROM divisions d LEFT JOIN matches m ON m.division_id=d.id
                    WHERE d.guild_id=? AND d.source='BOT' AND m.id IS NULL
                    ORDER BY d.id DESC LIMIT ? OFFSET ?
                    """, r -> r.getLong(1), guildId, limit, page * limit);
            List<TeamDivision> divisions = new ArrayList<>();
            for (long id : ids) divisions.add(division(c, guildId, id));
            return divisions;
        });
    }

    ResultDraft beginGenerated(String guildId, String userId, String channelId, long divisionId) {
        return transaction(c -> {
            TeamDivision division = division(c, guildId, divisionId);
            if (!division.source().equals("BOT")) throw new FootballException("Select a bot-generated division.");
            if (divisionHasMatch(c, divisionId)) throw new FootballException("This division already has a recorded match.");
            return insertDraft(c, guildId, userId, channelId, "CREATE", divisionId, null, null, division.teamSize(), null, null, Instant.now());
        });
    }
    ResultDraft beginManual(String guildId, String userId, String channelId) {
        return transaction(c -> insertDraft(c, guildId, userId, channelId, "CREATE", null, null, null,
                settings(c, guildId).manualTeamSize(), null, null, Instant.now()));
    }
    ResultDraft beginChange(String guildId, String userId, String channelId, long matchId, boolean delete) {
        return transaction(c -> {
            Match match = match(c, guildId, matchId);
            return insertDraft(c, guildId, userId, channelId, delete ? "DELETE" : "EDIT", match.division().id(),
                    matchId, match.version(), match.division().teamSize(), match.goalsA(), match.goalsB(), match.playedAt());
        });
    }
    private static ResultDraft insertDraft(Connection c, String guildId, String userId, String channelId, String action,
                                           Long divisionId, Long targetId, Integer targetVersion, int size,
                                           Integer goalsA, Integer goalsB, Instant playedAt) throws SQLException {
        String id = UUID.randomUUID().toString();
        execute(c, """
                INSERT INTO drafts(id,guild_id,user_id,channel_id,action,status,division_id,target_match_id,target_version,
                  team_size,goals_a,goals_b,played_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, guildId, userId, channelId, action, action.equals("DELETE") ? "READY" : "ACTIVE",
                divisionId, targetId, targetVersion, size, goalsA, goalsB, timestamp(playedAt));
        return draft(c, guildId, userId, id);
    }
    ResultDraft draft(String guildId, String userId, String id) { return transaction(c -> draft(c, guildId, userId, id)); }
    private static ResultDraft draft(Connection c, String guildId, String userId, String id) throws SQLException {
        ResultDraft value = one(c, "SELECT * FROM drafts WHERE guild_id=? AND id=? AND user_id=?", r -> new ResultDraft(
                id, guildId, userId, r.getString("channel_id"), r.getString("action"), r.getString("status"),
                nullableLong(r, "division_id"), nullableLong(r, "target_match_id"), nullableInt(r, "target_version"),
                r.getInt("team_size"), nullableInt(r, "goals_a"), nullableInt(r, "goals_b"),
                Instant.parse(r.getString("played_at")), r.getInt("revision"), nullableLong(r, "confirmed_match_id"), List.of(), List.of()), guildId, id, userId);
        List<Player> a, b;
        if (value.divisionId() != null) {
            TeamDivision division = division(c, guildId, value.divisionId()); a = division.teamA(); b = division.teamB();
        } else {
            a = members(c, "draft_players", "draft_id", guildId, id, "A");
            b = members(c, "draft_players", "draft_id", guildId, id, "B");
        }
        return new ResultDraft(id, guildId, userId, value.channelId(), value.action(), value.status(), value.divisionId(),
                value.targetMatchId(), value.targetVersion(), value.teamSize(), value.goalsA(), value.goalsB(), value.playedAt(),
                value.revision(), value.confirmedMatchId(), a, b);
    }
    private static List<Long> ids(List<Player> players) { return players.stream().map(Player::id).toList(); }
    private static void active(ResultDraft draft) {
        if (!draft.status().equals("ACTIVE")) throw new FootballException("This form has already been submitted, confirmed or cancelled.");
    }
    private static void revision(ResultDraft draft, int expected) {
        if (draft.revision() != expected) throw new FootballException("This form has changed. Use the latest buttons or start /result again.");
    }
    ResultDraft selectManualTeam(String guildId, String userId, String id, String team, List<Long> selected, int expectedRevision) {
        return transaction(c -> {
            ResultDraft draft = draft(c, guildId, userId, id); active(draft); revision(draft, expectedRevision);
            if (draft.divisionId() != null || !Set.of("A", "B").contains(team)) throw new FootballException("These teams are already fixed.");
            if (selected.size() != draft.teamSize()) throw new FootballException("Select exactly " + draft.teamSize() + " players.");
            List<Long> a = team.equals("A") ? selected : ids(draft.teamA());
            List<Long> b = team.equals("B") ? selected : ids(draft.teamB());
            validateMembers(c, guildId, a, b);
            execute(c, "DELETE FROM draft_players WHERE guild_id=? AND draft_id=?", guildId, id);
            insertMembers(c, "draft_players", "draft_id", guildId, id, a, b);
            execute(c, "UPDATE drafts SET revision=revision+1 WHERE id=?", id);
            return draft(c, guildId, userId, id);
        });
    }
    ResultDraft freezeManualTeams(String guildId, String userId, String id, int expectedRevision) {
        return transaction(c -> {
            ResultDraft draft = draft(c, guildId, userId, id); active(draft); revision(draft, expectedRevision);
            if (draft.divisionId() != null) throw new FootballException("Teams have already been confirmed.");
            TeamDivision division = insertDivision(c, guildId, draft.channelId(), userId, "MANUAL", "MANUAL",
                    draft.teamSize(), ids(draft.teamA()), ids(draft.teamB()));
            execute(c, "UPDATE drafts SET division_id=?,revision=revision+1 WHERE id=?", division.id(), id);
            return draft(c, guildId, userId, id);
        });
    }
    static int parseGoals(String text) {
        if (text == null || !text.trim().matches("[0-9]+")) throw new FootballException("Goals must be non-negative integers.");
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException e) { throw new FootballException("The score is too large."); }
    }
    ResultDraft score(String guildId, String userId, String id, int goalsA, int goalsB, Instant playedAt, int expectedRevision) {
        if (goalsA < 0 || goalsB < 0 || playedAt == null) throw new FootballException("Enter a valid date and non-negative integer scores.");
        return transaction(c -> {
            ResultDraft draft = draft(c, guildId, userId, id); active(draft); revision(draft, expectedRevision);
            if (draft.divisionId() == null || draft.action().equals("DELETE")) throw new FootballException("Confirm the teams first.");
            if (playedAt.isAfter(Instant.now().plusSeconds(300))) throw new FootballException("The match date cannot be in the future.");
            execute(c, "UPDATE drafts SET goals_a=?,goals_b=?,played_at=?,status='READY',revision=revision+1 WHERE id=?",
                    goalsA, goalsB, timestamp(playedAt), id);
            return draft(c, guildId, userId, id);
        });
    }
    void cancel(String guildId, String userId, String id) {
        transaction(c -> {
            ResultDraft draft = draft(c, guildId, userId, id);
            if (draft.status().equals("CONFIRMED")) throw new FootballException("Already confirmed. Use /match to edit or delete it.");
            execute(c, "UPDATE drafts SET status='CANCELLED',revision=revision+1 WHERE id=?", id);
            return null;
        });
    }
    private static boolean divisionHasMatch(Connection c, long divisionId) throws SQLException {
        return !query(c, "SELECT id FROM matches WHERE division_id=?", r -> r.getLong(1), divisionId).isEmpty();
    }
    /** The authorization decision is checked by the Discord adapter at both creation and confirmation. */
    long confirm(String guildId, String userId, String id, int expectedRevision, boolean authorizedToModify) {
        return transaction(c -> {
            ResultDraft draft = draft(c, guildId, userId, id); revision(draft, expectedRevision);
            if (!draft.action().equals("CREATE") && !authorizedToModify) throw new FootballException("You are not authorized to edit or delete matches.");
            if (draft.status().equals("CONFIRMED")) throw new FootballException("This operation has already been confirmed.");
            if (!draft.status().equals("READY")) throw new FootballException("Submit a score and review it before confirming.");
            long matchId;
            if (draft.action().equals("CREATE")) {
                if (divisionHasMatch(c, draft.divisionId())) throw new FootballException("This division already has a recorded match.");
                // Independent manual forms of the same teams, timestamp and score are also rejected.
                if (isDuplicateManual(c, draft)) throw new FootballException("An identical manual match is already recorded at this date and time.");
                execute(c, "INSERT INTO matches(guild_id,division_id,goals_a,goals_b,played_at,recorded_at,recorded_by) VALUES(?,?,?,?,?,?,?)",
                        guildId, draft.divisionId(), draft.goalsA(), draft.goalsB(), timestamp(draft.playedAt()), timestamp(Instant.now()), userId);
                matchId = lastId(c);
            } else {
                Match match = match(c, guildId, draft.targetMatchId());
                if (match.version() != draft.targetVersion()) throw new FootballException("The match changed since you opened this form. Open it again.");
                matchId = match.id();
                if (draft.action().equals("EDIT")) execute(c,
                        "UPDATE matches SET goals_a=?,goals_b=?,played_at=?,version=version+1 WHERE guild_id=? AND id=? AND version=?",
                        draft.goalsA(), draft.goalsB(), timestamp(draft.playedAt()), guildId, matchId, draft.targetVersion());
                else execute(c, "DELETE FROM matches WHERE guild_id=? AND id=? AND version=?", guildId, matchId, draft.targetVersion());
            }
            execute(c, "UPDATE drafts SET status='CONFIRMED',confirmed_match_id=? WHERE id=?", matchId, id);
            return matchId;
        });
    }
    private static boolean isDuplicateManual(Connection c, ResultDraft draft) throws SQLException {
        if (!division(c, draft.guildId(), draft.divisionId()).source().equals("MANUAL")) return false;
        List<Long> candidates = query(c, "SELECT id FROM matches WHERE guild_id=? AND played_at=?", r -> r.getLong(1), draft.guildId(), timestamp(draft.playedAt()));
        Set<Long> a = new HashSet<>(ids(draft.teamA())), b = new HashSet<>(ids(draft.teamB()));
        for (long id : candidates) {
            Match match = match(c, draft.guildId(), id);
            Set<Long> existingA = new HashSet<>(ids(match.division().teamA())), existingB = new HashSet<>(ids(match.division().teamB()));
            if (a.equals(existingA) && b.equals(existingB) && draft.goalsA() == match.goalsA() && draft.goalsB() == match.goalsB()
                    || a.equals(existingB) && b.equals(existingA) && draft.goalsA() == match.goalsB() && draft.goalsB() == match.goalsA()) return true;
        }
        return false;
    }

    /** Delete only the reviewed version; statistics are derived from remaining matches. */
    void deleteMatch(String guildId,long id,int expectedVersion) {
        transaction(c -> {
            Match match=match(c,guildId,id);
            if (match.version()!=expectedVersion) throw new FootballException("This game changed. Refresh and review it before deleting.");
            execute(c,"DELETE FROM matches WHERE guild_id=? AND id=? AND version=?",guildId,id,expectedVersion);
            execute(c,"UPDATE drafts SET status='CANCELLED',revision=revision+1 WHERE guild_id=? AND confirmed_match_id=? AND action='CREATE' AND status='CONFIRMED'",guildId,id);
            return null;
        });
    }

    Match match(String guildId, long id) { return transaction(c -> match(c, guildId, id)); }
    private static Match match(Connection c, String guildId, long id) throws SQLException {
        record Row(long divisionId, int a, int b, Instant played, Instant recorded, String user, int version) {}
        Row row = one(c, "SELECT * FROM matches WHERE guild_id=? AND id=?", r -> new Row(r.getLong("division_id"),
                r.getInt("goals_a"), r.getInt("goals_b"), Instant.parse(r.getString("played_at")),
                Instant.parse(r.getString("recorded_at")), r.getString("recorded_by"), r.getInt("version")), guildId, id);
        return new Match(id, guildId, division(c, guildId, row.divisionId()), row.a(), row.b(), row.played(), row.recorded(), row.user(), row.version());
    }
    List<Match> matches(String guildId, Long playerId, int page, int limit) {
        checkPage(page, limit);
        return transaction(c -> {
            if (playerId != null) player(c, guildId, playerId);
            List<Long> ids = query(c, """
                    SELECT m.id FROM matches m WHERE m.guild_id=?
                    AND (? IS NULL OR EXISTS(SELECT 1 FROM division_players p WHERE p.division_id=m.division_id AND p.player_id=? AND p.guild_id=m.guild_id))
                    ORDER BY m.played_at DESC,m.id DESC LIMIT ? OFFSET ?
                    """, r -> r.getLong(1), guildId, playerId, playerId, limit, page * limit);
            List<Match> matches = new ArrayList<>();
            for (long id : ids) matches.add(match(c, guildId, id));
            return matches;
        });
    }
    private static void checkPage(int page, int limit) {
        if (page < 0 || limit < 1 || limit > 1000 || page > Integer.MAX_VALUE / limit)
            throw new FootballException("Invalid page.");
    }
    /** A consistent chronological snapshot used for derived statistics, without loading each division. */
    List<StatisticsService.Appearance> appearances(String guildId) {
        return transaction(c -> query(c, """
                SELECT p.player_id,p.team,m.id,m.goals_a,m.goals_b,m.played_at FROM matches m
                JOIN division_players p ON p.division_id=m.division_id AND p.guild_id=m.guild_id
                WHERE m.guild_id=? ORDER BY m.played_at,m.id,p.player_id
                """, r -> new StatisticsService.Appearance(r.getLong("player_id"), r.getLong("id"),
                Instant.parse(r.getString("played_at")), r.getString("team").equals("A") ? r.getInt("goals_a") : r.getInt("goals_b"),
                r.getString("team").equals("A") ? r.getInt("goals_b") : r.getInt("goals_a")), guildId));
    }
}
