# Development and tests

Use a JDK 17 or newer, Python 3 and PHP 8.2+ for all components. No Node build is needed for the frontend. Run commands below from the repository root.

## Java

```powershell
.\gradlew.bat test installDist
```

On Linux use `./gradlew`. SQLite tests use temporary databases. MariaDB tests are opt-in and only accept a localhost URL for a dedicated `football_test` database. Set `FOOTBALL_TEST_DB_URL`, `FOOTBALL_TEST_DB_USER` and `FOOTBALL_TEST_DB_PASSWORD` to enable them. Never supply production credentials to tests.

## PHP and packaging

```sh
php sigma/tests/cookie_test.php
php sigma/tests/teams_test.php
python3 tools/build_sigma.py sigma-deploy
```

On Windows, use `py` instead of `python3` if needed. The cookie test covers CGI paths and personal-page aliases. The team test compares balanced teams against an independent exhaustive oracle.

`http_smoke.py` exercises public browsing, protected game logging/deletion, player management, anonymous editable voting, cleanup, teams, results, statistics and archive/restore. It creates test players and games: run it only against a fresh, isolated test database and deployment. The GitHub Actions PHP job provisions these automatically.

The GitHub workflow also tests Java/MariaDB synchronization, builds the runtime image and verifies SQLite persistence across container recreation. No Discord token or university account is needed for CI.

## Credentials and generated files

Copy `sigma/private/config.example.php` to a private `config.php` on the server, not inside `sigma/public/`. Direct Java runs read process environment variables; Docker Compose reads the local `.env`. Do not commit either secret file, database files, migration exports or `sigma-deploy/`.
