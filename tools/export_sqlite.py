#!/usr/bin/env python3
"""Export a consistent read-only SQLite snapshot for the Sigma CLI importer."""
import argparse, json, pathlib, sqlite3
TABLES=['guild_settings','authorized_roles','players','divisions','division_players','matches','drafts','draft_players','rating_polls','removed_players','rating_cleanup','native_polls','rating_votes']
p=argparse.ArgumentParser();p.add_argument('database',type=pathlib.Path);p.add_argument('output',type=pathlib.Path);a=p.parse_args()
source=sqlite3.connect(a.database.resolve().as_uri()+'?mode=ro',uri=True)
snapshot=sqlite3.connect(':memory:');source.backup(snapshot);source.close();snapshot.row_factory=sqlite3.Row
existing={r[0] for r in snapshot.execute("SELECT name FROM sqlite_master WHERE type='table'")}
if not {'guild_settings','players','matches'}.issubset(existing): raise SystemExit('This is not a football database.')
result={'schemaVersion':1,'tables':{t:[dict(r) for r in snapshot.execute('SELECT * FROM '+t)] if t in existing else [] for t in TABLES}}
# Refuse to overwrite an earlier export by mistake.
with a.output.open('x',encoding='utf-8') as f: json.dump(result,f,ensure_ascii=False,allow_nan=False)
print('Exported '+str(sum(map(len,result['tables'].values())))+' rows. Keep this file outside the public website.')
