# Futebol website

The responsive frontend lives in `src/main/resources/web/` (plain HTML, CSS and JavaScript, with no Node build step or third-party frontend assets). `WebMain` serves it and provides a small API over the existing `FootballStore`, `StatisticsService` and `TeamGenerator`. The bot remains usable alongside the website.

## Home server with the existing Docker database

1. Retain your existing `.env` and `DISCORD_TOKEN`. Add the variables from `.env.web.example`. Set `FOOTBALL_GUILD_ID` to your actual Discord **server ID**, not a channel/user ID. In Discord, enable Developer Mode, then copy the server ID.
2. Start both services:

   ```sh
   docker compose -f compose.yaml -f compose.web.yaml up -d --build
   ```

3. Visit `http://localhost:8080` on the server. To use phones on the home network, set `FOOTBALL_WEB_BIND` to the server's LAN IP and visit `http://<server-lan-ip>:8080`.

Both services use the existing `futebol-bot-data` volume and `/data/football.db`. Keep that volume; do not use `docker compose down -v`. SQLite WAL handles concurrent access on this host. Do not put the database on a network filesystem. Use HTTPS when hosting publicly. Browsing, team generation and voting are public; player management and game logging/deletion require the management password. The frontend never receives the bot token or database path.

To run the website alone, add `--no-deps web` to the Compose command (if the bot is already running separately, ensure it mounts this same volume).

## Local Java development

Use a JDK 17 or newer (tested with Java 21) and your existing database path. PowerShell:

```powershell
$env:FOOTBALL_DB_PATH='D:/path/to/your/existing/football.db'
$env:FOOTBALL_GUILD_ID='your-server-id'
./gradlew.bat runWeb
```

The default address is `http://127.0.0.1:8080`. No Discord bot token is needed for `runWeb`. Voting links work without additional credentials; see `voting.md`. The application reads environment variables, not `.env` files, when run directly. Set `FOOTBALL_WEB_HOST=0.0.0.0` to allow LAN access. `FOOTBALL_WEB_PORT` is optional. The normal `./gradlew run` still starts Discord.

## Management password

Set `FOOTBALL_ADMIN_PASSWORD` to a private password of at least 8 characters before managing players or games. The website opens without sign-in; old `FOOTBALL_WEB_KEY` settings are ignored. Adding/removing players, finishing voting, reviewing/saving game results and deleting games require the password on the server. Without it, browsing and making teams still work, while management actions are locked. Passwords are sent only for the action and are not saved in browser storage. Five incorrect attempts from one address block management requests for one minute.

Windows PowerShell, before starting the website:

```powershell
$env:FOOTBALL_ADMIN_PASSWORD = 'choose-your-private-password'
[Environment]::SetEnvironmentVariable('FOOTBALL_ADMIN_PASSWORD', $env:FOOTBALL_ADMIN_PASSWORD, 'User')
.\gradlew.bat runWeb
```

For Docker, add the same variable to `.env` and recreate the web container. Restart the website after changing the password.

In Players, use Remove beside a player's stats, then enter the password and confirm. Removal hides the player from the website and bot, preserves old games, and disables the old voting link. Re-adding a removed name restores the player's identity and results with a new score or voting poll.

## What works

- Tap player cards to mark attendees; search, sort, select all or clear.
- Attendance is retained in this browser's local storage. It is a local planning list, separate from the bot's in-memory attendance session.
- Add a player with a score from 0 to 100 (decimals allowed), or start built-in voting. Both require the management password. Friends open a shareable voting page with no sign-in; each browser has one editable vote. Averages update immediately in the shared database. Finish voting with the management password to save the final average/count and delete individual votes. See [voting.md](voting.md). Players with no votes are excluded from team generation.
- Generate 5v5 teams with the smallest difference in total player ratings. For 10 players, all 126 distinct splits are considered. For 11–24 attendees, an exact search also chooses the best ten; extra players remain substitutes. Above 24, up to 4,000 lineups are sampled and each is split optimally. Random shuffle remains separate. Balance depends on the voted ratings; this does not optimize positions, chemistry, or fair bench rotation.
- Each generation saves a division in the shared database. Use **Log this game** to enter a score for the generated teams. Saved unused lineups can also be selected later in **Games → Log a game**. Copy teams for sharing.
- Browse catalogue statistics and paginated match history. Discord match edits/deletions and links to player records are read from the shared database; visible pages refresh every 15 seconds and on returning to the tab. Link metadata is retained in the database but isn't displayed by this frontend.
- Database roster and ratings now drive Discord attendance and team generation too. Startup seeds missing defaults and preserves existing ratings; `PlayerCatalog.java` is now the initial seed, not a mechanism for updating persisted ratings. Discord player-name options accept free text so newly added names can be used immediately.

Log games directly in **Games → Log a game**: choose a saved lineup or assign players to Team A and Team B, enter the goals and local date/time, review, and confirm. Manual teams use the existing configured team size (five by default). Only confirmation updates statistics. Duplicate confirmation retries cannot create a second game. Use Delete beside a game in the history to remove it with the management password; statistics update automatically. Discord can still correct results and manage account links. There is no fabricated demo data or separate browser database. A fresh database is seeded with your existing 18 default players; existing match history stays intact.

## Verification

`./gradlew test installDist` runs bot and HTTP integration tests, including built-in voting, per-browser edits, cookie persistence across restarts, access controls, concurrent closing, database persistence and vote deletion. Voting tests run locally with browser-style cookies. Tests use temporary databases and do not write to the live database. Legacy Google integration tests remain as migration regression coverage; production startup does not initialize Google services.




## Técnico Sigma

For the university hosting deployment, use [sigma.md](sigma.md). It provides a PHP API for the shared frontend and MariaDB storage, with SQLite migration and Java bot synchronization. The local Java/SQLite instructions above remain available for testing on Windows.

In Games, use Delete on a result, review the game and enter the management password. Deletion immediately recalculates statistics and keeps the saved teams available. If a game changed since the page loaded, refresh and review it again before deletion.
