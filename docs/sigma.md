# Hosting on Técnico Sigma

This deployment uses PHP 8.2+ with PDO MySQL and the Técnico MariaDB service. It does not run a persistent Java server on Sigma. The Java bot can run elsewhere against the same MariaDB database. No Google Apps Script or Discord login is needed for voting.

## 1. Activate the services

In https://selfservice.dsi.tecnico.ulisboa.pt/ activate AFS, personal web pages and CGI. Use the existing `~/web` directory: do not delete/recreate it or replace its AFS permissions.

Connect from Windows PowerShell:

```powershell
ssh ist123456@sigma.tecnico.ulisboa.pt
```

On Sigma, run:

```sh
php -v
php -m
mysql_reset
```

PHP must be 8.2 or newer with PDO and pdo_mysql. If these are unavailable, contact Técnico IT before proceeding. `mysql_reset` provisions the MariaDB account and prints its password; running it again resets that password. The database host is `db.tecnico.ulisboa.pt`; database and username are your IST ID.

Official instructions: [personal pages](https://si.tecnico.ulisboa.pt/servicos/servidores-e-dados/paginas-web/), [MariaDB](https://si.tecnico.ulisboa.pt/servicos/servidores-e-dados/bases-de-dados/mysql-mariadb/), [Sigma shell](https://si.tecnico.ulisboa.pt/servicos/servidores-e-dados/unix-shell/).

## 2. Build and upload

In Windows PowerShell, from the project folder:

```powershell
py tools\build_sigma.py sigma-deploy
scp -r sigma-deploy\football-private ist123456@sigma.tecnico.ulisboa.pt:~/
scp -r sigma-deploy\web\futebol ist123456@sigma.tecnico.ulisboa.pt:~/web/
```

`~/web/futebol` contains only public files. `~/football-private` contains application code, installer and configuration. Never upload the private folder, config, SQLite database or migration export inside `~/web`.

On Sigma:

```sh
cp ~/football-private/config.example.php ~/football-private/config.php
nano ~/football-private/config.php
fs listacl ~/football-private
```

Fill in the database password, your **existing Discord server ID** as `guild_id`, a management password of at least 8 characters. Leave `secure_cookies` true for HTTPS. The website opens without sign-in. The management password is required to add/remove players, finish voting, log games and delete games.

The private directory must be readable by your CGI identity. If necessary, grant your own daemon principal access:

```sh
fs setacl ~/football-private ist123456.daemon rl
fs setacl ~/football-private system:anyuser none
fs setacl ~/football-private system:authuser none
fs listacl ~/football-private
```

Remove any other broad group read grants shown by `fs listacl`; keep your own account and administrator rights. Do not grant `system:anyuser` access to this private directory. Check AFS permissions with IT if PHP cannot read the configuration; Unix chmod alone does not configure AFS ACLs. Use HTTPS for the public website. For database TLS, ask IT for the supported CA and set `ssl_ca` in config.php; do not guess a CA or disable certificate verification.

## 3. Preserve your current database

Stop the old website and bot before exporting so nothing changes during the move. Find `FOOTBALL_DB_PATH` (default `data/football.db`, relative to the old process working directory). Back up that database first. From PowerShell:

```powershell
py tools\export_sqlite.py data\football.db football-export.json
scp football-export.json ist123456@sigma.tecnico.ulisboa.pt:~/football-private/
```

Then on Sigma:

```sh
php ~/football-private/install.php --import ~/football-private/football-export.json
```

Import requires empty application tables and rolls back on any failure. It preserves IDs, players, archived players, games, team divisions, drafts, rating averages and open votes. Set `guild_id` to the existing server ID before importing. Do **not** run the fresh database command below first if importing.

Only if starting without any old data:

```sh
php ~/football-private/install.php
```

This seeds the initial catalogue once. After a successful import and checking the website, remove the export from the server and keep a private local backup. Native voting links from the local Java server must be reshared using the website's Copy link button; poll IDs and existing votes are preserved, but browser cookies do not transfer between domains. Old Google Form polls retain their saved average; new polls use the built-in voting page.

## 4. Open the website

Open `https://web.tecnico.ulisboa.pt/ist123456/futebol/` using the exact personal-page address shown by Técnico. Test adding a player with a fixed score and with voting, opening the voting link in another browser, finishing a vote, making teams and logging a game.

The website works in a subdirectory without URL rewriting. PHP API requests go to `api.php?route=/api/...`. Sessions are stored in MariaDB, so they persist across CGI requests and deployments. Voting remains one editable vote per browser, not a verified-person system.

## 5. Keep the Discord bot synchronized (optional)

Run the updated Java bot on your PC or another host, not as a continuous background process on Sigma. In the same PowerShell window that starts it:

```powershell
$env:FOOTBALL_DB_URL = 'jdbc:mariadb://db.tecnico.ulisboa.pt:3306/ist123456'
$env:FOOTBALL_DB_USER = 'ist123456'
$env:FOOTBALL_DB_PASSWORD = 'YOUR_DATABASE_PASSWORD'
.\gradlew.bat run
```

Keep the existing Discord token settings. `FOOTBALL_DB_PATH` is ignored when `FOOTBALL_DB_URL` is set. Both processes must use the same database and server ID. SQLite remains supported when the URL is unset. Use verified TLS connection settings supplied by IT if required; the connector supports `sslMode=verify-full` and `serverSslCert`.

The migrated MariaDB database becomes the source of truth. Running the old bot against SQLite would create a separate, unsynchronized copy.

## Updates and troubleshooting

Rebuild and upload the two folders to update the application. `config.php` is never included in the build, so your credentials remain on the server. For schema version 1, running the installer again without `--import` retains existing records. Back up MariaDB before future schema migrations. Keep the public and private code from the same build.

A 500 response contains no database credentials; detailed errors go to the hosting PHP error log. Check configuration, PHP extensions and AFS ACLs first. No SSH account, service activation or actual Sigma deployment is performed by this code package; those require your university account. The deployment was tested with PHP and MariaDB locally; verify the actual Sigma PHP version and privileges before uploading.



### Updating an existing deployment

Rebuild with `py tools/build_sigma.py sigma-deploy`, then upload `sigma-deploy/football-private` to your home directory and `sigma-deploy/web/futebol` to `~/web/` as above. Your private `config.php` is not overwritten. Reload the website with Ctrl+F5. There is no database migration for public browsing or game deletion. An old `web_key` entry may be removed from config.php; it is ignored. Keep `admin_password`, which now protects game logging and deletion as well as player management.

The deployment builder versions JavaScript and CSS URLs using a hash of each file. Rebuild and upload the public folder together on every update so browsers fetch matching assets. The “Updated” time in the website indicates a player-data refresh, not a code deployment.
