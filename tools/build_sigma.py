#!/usr/bin/env python3
"""Build the Sigma upload directories from the shared frontend and schema."""
import argparse, hashlib, pathlib, shutil
root=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('destination',type=pathlib.Path);args=p.parse_args()
public=args.destination/'web'/'futebol';private=args.destination/'football-private'
public.mkdir(parents=True,exist_ok=True);private.mkdir(parents=True,exist_ok=True)
for source in (root/'sigma/private').glob('*.php'):
    if source.name=='config.php': continue
    shutil.copy2(source,private/source.name)
shutil.copy2(root/'src/main/resources/schema-mariadb.sql',private/'schema.sql')
shutil.copy2(root/'sigma/public/api.php',public/'api.php')
assets = ['app.js','vote.js','style.css']
versions = {name: hashlib.sha256((root/'src/main/resources/web'/name).read_bytes()).hexdigest()[:16] for name in assets}
for name in ['index.html','vote.html',*assets]:
    text=(root/'src/main/resources/web'/name).read_text(encoding='utf-8')
    if name.endswith('.html'):
        text=text.replace('<head>','<head><meta name="football-api" content="api.php">')
        for asset in assets:
            text=text.replace('"/'+asset+'"','"'+asset+'?v='+versions[asset]+'"')
        text=text.replace('href="/"','href="./"')
    (public/name).write_text(text,encoding='utf-8')
shutil.copy2(root/'docs/sigma.md',args.destination/'SETUP.md')
print(args.destination.resolve())
