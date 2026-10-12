# Shareable voting links

Voting runs inside the website using the shared football database: PHP/MariaDB on Sigma or Java/SQLite for local hosting. Friends open a link and enter a score, with no account or sign-in. No Google Forms, Apps Script, Drive storage, Discord OAuth credentials or external voting service is needed.

## Using it

1. Add player → Start voting → enter the player management password.
2. Copy the voting link and send it to your friends.
3. They open the page, enter a score from 0 to 100, and press Save vote. Decimals are allowed. Saving again from the same browser updates their existing vote.
4. The average and player score update immediately in the shared database. Open pages refresh every 15 seconds. Players become selectable after their first vote.
5. Players → Rating votes → Finish voting → enter the management password. The final average and count are saved and individual votes are deleted in the same transaction. Closing cannot be undone, and retries are safe.

The voting link does not require the management password. Anyone with the link can vote, so share it with the intended group. Player creation, removal and closing votes retain password protection.

## How repeat votes work

A random private browser cookie identifies each response. The cookie lasts one year, is inaccessible to JavaScript, and survives website restarts. There are no names or Discord identities attached to votes. Voters can only retrieve their own score, not other individual responses.

This limits each browser to one editable vote per player. It does **not** guarantee one vote per person: another browser/device, private browsing or clearing cookies can create another vote. Friends must use the same browser to edit their original response. Cookies must be allowed to submit votes.

## Running locally or online

No new credentials are required. Keep your existing database, guild ID and management password settings. Stop the website with Ctrl+C and restart:

```powershell
.\gradlew.bat runWeb
```

The app still uses `FOOTBALL_GUILD_ID` to share data with the bot, but voting has no Discord dependency. Old `FOOTBALL_FORMS_*` and `FOOTBALL_DISCORD_CLIENT_*` / `FOOTBALL_DISCORD_REDIRECT_URI` variables are ignored and can be removed.

A localhost link only works on your own computer. Friends need a reachable LAN address or hosted public website address. Open the website through that address before copying a voting link. Host the voting page, `/api/voting`, and static assets on the same origin as the Java service.

For a public HTTPS deployment set `FOOTBALL_WEB_SECURE_COOKIES=true` in the server environment or Docker `.env`. This protects voter and website access cookies in transit. Leave it false for local HTTP testing. Direct Java startup reads environment variables, not `.env` automatically.

## Existing Google polls

Existing players, scores, vote counts and games are retained. The website no longer reads Google responses, even if the old `FOOTBALL_FORMS_*` variables are still set; you can remove those variables.

Older polls are labelled separately. If they already have saved votes, **Use saved average** locks the last average stored in the database as the final score (management password required). It does not import newer Google responses. For a pending player without saved votes, remove and re-add the name to start website voting or enter a score. Re-adding preserves historical player identity and games.

Existing forms and responses remain in your Google Drive. Finish any old Google voting before switching if you need its latest responses, and delete those old forms manually afterwards. The new website never deletes or changes Google files. Do not combine old Google votes with new website votes: there is no reliable way to match anonymous old responses to browser identities.

The old Google classes and tests remain for migration regression coverage, but `runWeb` never initializes them. Only new website polls use the native voting tables. Back up the SQLite database before deploying an update as usual.
