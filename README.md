# Futebol

A website for choosing who is playing, making balanced 5v5 teams, rating players and logging games between friends. The Discord bot remains available and can share the same database.

## Start here

- **Técnico Sigma:** [Hosting and database migration](docs/sigma.md). Uses PHP and MariaDB; no persistent Java server is required on Sigma.
- **Windows or a home server:** [Run the Java website locally or with Docker](docs/website.md).
- **Player ratings:** [Shareable voting links](docs/voting.md). Friends vote without accounts; player management, finishing votes, logging games and deleting games require the management password.
- **Discord bot:** [Commands and setup](docs/discord.md).
- **Development:** [Builds and tests](docs/testing.md).

Players can receive a fixed score or the average of friends' votes. Finished polls retain the final average and delete individual votes. Games are logged manually, and statistics are calculated from their results. Team balancing finds the minimum rating difference for up to 24 attendees; larger groups use a bounded search.

## Repository layout

| Location | Contents |
| --- | --- |
| `src/main/java/` | Discord bot, Java web API, shared persistence and team algorithm |
| `src/main/resources/web/` | Shared HTML, CSS and JavaScript frontend |
| `src/main/resources/` | SQLite and MariaDB schemas |
| `src/test/java/` | Java unit and integration tests |
| `sigma/private/` | PHP backend, CLI installer and configuration example |
| `sigma/public/` | Public PHP API entry point |
| `sigma/tests/` | PHP and HTTP regression checks |
| `tools/` | Sigma package builder and SQLite export tool |
| `docs/` | Hosting, voting, bot and development guides |
| `.github/workflows/` | Continuous integration |

Build the Sigma upload directories from the shared frontend:

```powershell
py tools/build_sigma.py sigma-deploy
```

This produces `sigma-deploy/web/futebol/` and `sigma-deploy/football-private/`. Follow the [Sigma guide](docs/sigma.md) before uploading. Generated packages, database exports, credentials and IDE files are excluded from Git. The private configuration stays outside the public website.

The Java application supports SQLite by default and MariaDB through `FOOTBALL_DB_URL`, `FOOTBALL_DB_USER` and `FOOTBALL_DB_PASSWORD`. Both the website and bot must use the same database and guild ID to stay synchronized. New voting does not use Google Forms or Apps Script; Java compatibility adapters remain covered by tests for older poll data.

The website opens without a sign-in key. Use the management password when logging or deleting games; deleting a result updates all affected player statistics.
