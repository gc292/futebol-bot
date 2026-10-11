# Futebol Bot

Discord bot for football attendance, balanced 5v5 teams, persistent player profiles and match statistics. Java 17, JDA and SQLite; no web framework or ORM.

## Attendance and teams

- `/futebol`: checks the roster in its configured order, editing one panel with Yes/No buttons. Each user has one attendance session per channel.
- `/teams`: generates balanced, fixed 5v5 teams from the last confirmed attendance list. With more than ten players, it samples up to 4,000 selections and lists substitutes.
- `/remake`: randomly selects ten confirmed players and splits them into 5v5 teams.

The original random sampling, rating-based greedy balancing and message formats are retained. Every `/teams` and `/remake` saves an immutable division and includes its number in the reply. Earlier divisions remain available after remakes and restarts; the bot never assumes the last division was the one used for a game. Substitutes do not receive match statistics.

Attendance progress and the most recent attendance list stay in memory. Saved divisions, result forms, player associations, settings and match history are stored in SQLite. Restarting resets attendance, but you can still record results against earlier divisions.

## Register results

### Generated teams

1. Use `/result generated`.
2. Browse the saved, unused divisions, identified by number, creation date, creator, channel and players.
3. Select the division that actually played and review both teams.
4. Click **Enter score**. Enter Team A goals, Team B goals and the match date/time in the Modal.
5. Review the teams, score, winner, date and affected players. Click **Confirm** or **Cancel**.

### Manual teams

1. Use `/result manual`; no attendance check or generated division is required.
2. Select Team A and Team B using the two player menus.
3. Click **Confirm teams**, then **Enter score**.
4. Submit the score and date, review the summary, then **Confirm** or **Cancel**.

Each team needs exactly the configured manual team size, defaulting to five. A player cannot be duplicated or appear on both teams, and every player must belong to this server's roster. Generated divisions always remain 5v5 regardless of the manual setting.

Goals must be non-negative integers. Dates use **UTC**: `2026-10-11 15:00`, `2026-10-11`, or ISO timestamps such as `2026-10-11T16:00:00+01:00`. Future match dates are rejected. You can change the prefilled date to the day/time the game actually happened.

Forms are persisted and bound to their creator. Use `/result resume draft:<form-id>` to recover a pending form after restart; the ID appears in its footer. Repeated clicks, outdated buttons and duplicate confirmations cannot record another match. A saved division can have at most one match. Identical manual results with the same teams and exact played-at time are rejected, including reversed team labels. Distinct games on the same day should have their actual, distinct times.

Discord slash commands with subcommands require choosing the subcommand: `/result generated` or `/result manual`, rather than executing bare `/result`.

## Profiles, history and rankings

- `/profile`: shows your linked player profile. If your account is not linked, offers the roster to choose from.
- `/profile user:@member` or `/profile player:Caria`: shows another player, using either a linked Discord account or a roster name.
- `/history`: shows recent games, with Previous/Next buttons.
- `/history user:@member` or `/history player:Caria`: shows only games that player participated in.
- `/match view id:42`: shows both teams, score, winner, game date, source division and registration date.
- `/leaderboard metric:<metric>`: ranks by **wins**, **win rate**, **games played**, **goal difference** or **best win streak**. The panel also lets you change metric and page.

Profiles show Games Played, Wins, Draws, Losses, Win Rate, Goals For, Goals Against, Goal Difference, Points, Current Streak, Best Win Streak and Last Played.

Only **team goals** are entered. Each participating player receives their team's goals for and the opponent's goals against. Points are `3 × wins + draws`, win rate is `wins / games × 100`, and goal difference is `goals for − goals against`. Players without games show `N/A` for win rate. Win-rate rankings require at least **five games** by default. Streaks follow played-at time, then match ID for ties. Ranking ties use player name, then stable player ID.

No individual goals, assists, Elo or dynamic ratings are tracked. All statistics are calculated from match history on demand, so there are no separate counters to get out of sync.

## Player associations, permissions and corrections

The fixed roster and ratings live in `PlayerCatalog.java` and are seeded per server. Each player has a persistent internal ID and an optional Discord ID. Linking an account does not replace the player or lose their history. Names and internal IDs allow players without Discord accounts to participate.

- `/player link player:Caria user:@member`: associates a roster player with a Discord account.
- `/player unlink player:Caria`: removes the association, retaining the profile/history.
- `/match edit id:42`: opens a form to correct the final score or played-at date; review and confirm the change.
- `/match delete id:42`: shows the game and requires confirmation before deleting it.

Linking, editing and deleting require **Administrator**, **Manage Server**, or a configured authorized role. Authorization is checked again when an edit/delete is confirmed. All operations are atomic; stale edits cannot overwrite a more recent change. Deleting a result removes it from every affected player's statistics; the original division becomes available for a replacement result. A previously completed form cannot be used again.

Only server managers can configure:

```text
/settings min_games:10
/settings team_size:6
/settings role:@MatchAdmins
/settings role:@MatchAdmins remove_role:true
```

The team-size setting applies to manual teams and cannot exceed half the roster. The minimum-games setting affects only win-rate qualification. Every record, query, association and configuration is isolated by Discord server (`guildId`).

## Run locally

Install a JDK 17 and set `JAVA_HOME` to it. Invite the bot using the `bot` and `applications.commands` scopes, with View Channels and Send Messages permissions. Set the token as an environment variable:

```bash
export DISCORD_TOKEN='your-bot-token'
export FOOTBALL_DB_PATH='data/football.db' # optional; this is the default
./gradlew run
```

The application reads environment variables; it does not automatically load `.env`. The database directory is created on startup. `.env`, `data/` and SQLite files are ignored by Git and excluded from Docker build contexts. Global commands are registered on startup and may take time to propagate in Discord.

```bash
./gradlew test installDist
```

The standalone distribution is written to `build/install/futebol-bot/`.

## Docker and persistent data

Create a local `.env` containing `DISCORD_TOKEN=...`, then:

```bash
docker compose up -d --build
docker compose logs -f bot
```

Compose mounts the named volume **`futebol-bot-data`** at `/data`, with `FOOTBALL_DB_PATH=/data/football.db`. The database uses foreign-key enforcement, WAL and short transactions. The volume survives bot restarts, image rebuilds, container recreation and normal `docker compose down`/`up`. **`docker compose down -v` deletes the data volume.** Keep database backups separately; a volume is persistence, not a backup.

If you previously started a container manually under the name `futebol-bot`, stop and remove that container before the first Compose deployment. The old bot had no database, so there is no historical match data to import. A future move to another host must include the database volume as well as the code and token.

## Architecture and tests

- `BotMain`, `Session`, `PlayerCatalog`, `TeamGenerator`, `Teams`, `Pick10Result`, `BotMessages`: existing attendance/team flow. The balancing algorithm is unchanged.
- `FootballStore`, `schema.sql`: player identities, configuration, immutable divisions, persistent forms and transactional match operations.
- `Player`, `TeamDivision`, `Match`, `ResultDraft`, `GuildSettings`: domain records.
- `StatisticsService`, `PlayerStatistics`: derived chronological statistics and rankings.
- `FootballCommands`, `StatisticsListener`, `StatisticsMessages`: command definitions, Discord interaction routing and Embeds.

Tests cover all 43,758 possible ten-player selections, the original attendance/team/remake flow, wins/draws/losses, team goal totals, points, qualification and streaks, generated/manual results, invalid players/scores, confirmation races, permissions, editing/deleting/recalculation, persistent forms/settings/history across reopening SQLite, and cross-server isolation. Discord interactions are exercised with mocked JDA events without using a real token or adding test results to a live server.
