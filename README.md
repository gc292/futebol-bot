# Futebol Bot

Discord bot for attendance checks and balanced, fixed 5v5 football teams.

## Commands

- `/futebol`: asks about each player in the configured roster order, using one message with Yes/No buttons. Each user can have one active session per channel.
- `/teams`: uses the last completed attendance list in that channel to generate balanced 5v5 teams. With more than ten confirmed players, it samples up to 4,000 selections and lists the remaining players as substitutes.
- `/remake`: randomly selects ten confirmed players and randomly splits them into two teams of five.

Teams require at least ten confirmed players. Attendance state stays in memory and is lost when the bot restarts. Player names and ratings are defined in `PlayerCatalog.java`; missing ratings retain the original default of `5.0`.

## Run locally

Install a JDK 17 and set `JAVA_HOME` to that installation. Create a Discord bot, invite it to your server with the `bot` and `applications.commands` scopes, and set its token in your environment:

```bash
export DISCORD_TOKEN='your-bot-token'
./gradlew run
```

The bot registers global slash commands on startup; Discord may take a few minutes to show them. The command descriptions, message formats, random sampling and greedy balancing algorithm are preserved.

```bash
./gradlew test installDist
```

The standalone distribution is written to `build/install/futebol-bot/`.

## Docker

```bash
docker build -t futebol-bot .
docker run --rm -e DISCORD_TOKEN futebol-bot
```

Export `DISCORD_TOKEN` in the shell before running the container. Tokens must never be committed.

## Code structure

- `BotMain`: Discord startup, command routing, session storage and interaction handling.
- `Session`: attendance progress and answers.
- `PlayerCatalog`: fixed player order and ratings.
- `TeamGenerator`: balanced selections, random selections and team splits.
- `Teams` / `Pick10Result`: generation results.
- `BotMessages`: attendance and team message formatting.

Regression tests cover all 43,758 possible ten-player selections from the eighteen-player roster, team sizes and membership, substitutes, attendance progression and exact message formatting.
