#!/usr/bin/env python3
"""HTTP acceptance test. Run ONLY against an isolated, freshly seeded test deployment.
python sigma/tests/http_smoke.py http://127.0.0.1:8082/futebol/ ADMIN_PASSWORD
"""
import http.cookiejar, json, sys, urllib.request, urllib.error, uuid
base,password=sys.argv[1:]; base=base.rstrip('/')+'/'
def browser(): return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def request(client,route,body=None,status=200):
    if body is not None and route.startswith('/api/results/') and 'password' not in body: body=dict(body,password=password)
    req=urllib.request.Request(base+'api.php?route='+route,data=None if body is None else json.dumps(body).encode(),headers={} if body is None else {'Content-Type':'application/json'})
    try: response=client.open(req)
    except urllib.error.HTTPError as e: response=e
    data=json.load(response)
    assert response.status==status,(route,response.status,data)
    return data
admin=browser();other=browser();voter1=browser();voter2=browser()
request(admin,'/api/players')
request(admin,'/api/login',{},404)
roster=request(admin,'/api/players')['players'];assert len(roster)>=18
name='HTTP '+str(uuid.uuid4())[:8]
request(admin,'/api/players',{'name':name,'rating':70,'password':'wrong'},403)
p=request(admin,'/api/players',{'name':name,'rating':70,'password':password},201)
request(admin,'/api/players',{'name':name.lower(),'rating':70,'password':password},400)
request(admin,'/api/players',{'name':'Invalid','rating':101,'password':password},400)
new=request(admin,'/api/players/vote',{'name':name+' vote','password':password},201);poll=new['poll'];pid=poll['id'];route='/api/voting&id='+pid
assert poll['url']=='vote.html?id='+pid
assert not next(x for x in request(admin,'/api/players')['players'] if x['id']==new['player']['id'])['ratingReady']
request(voter1,route,{'score':80},400)
request(voter1,route);request(voter2,route)
request(voter1,route,{'score':60});request(voter1,route,{'score':80});r=request(voter2,route,{'score':90});assert r['count']==2 and r['average']==85
request(admin,'/api/rating-polls/close',{'id':pid,'password':'wrong'},403)
request(admin,'/api/rating-polls/close',{'id':pid,'password':password})
r=request(voter1,route);assert r['status']=='CLOSED' and r['ownScore'] is None and r['average']==85 and r['count']==2
request(voter1,route,{'score':50},400)
request(other,'/api/players')
players=request(admin,'/api/players')['players'];ids=[p['id'] for p in players[:12]]
request(admin,'/api/teams',{'playerIds':ids[:9]},400)
t=request(admin,'/api/teams',{'playerIds':ids});assert len(t['teamA'])==len(t['teamB'])==5 and len(t['substitutes'])==2
review=request(admin,'/api/results/review',{'divisionId':t['id'],'goalsA':'3','goalsB':'1','playedAt':'2026-10-11T12:00:00.000Z'})
match=request(admin,'/api/results/confirm',{'draftId':review['draftId'],'revision':review['revision']})
assert request(admin,'/api/results/confirm',{'draftId':review['draftId'],'revision':review['revision']})==match
request(admin,'/api/results/review',{'divisionId':t['id'],'goalsA':3,'goalsB':1,'playedAt':'2026-10-11T12:00:00Z'},400)
manual={'teamA':[p['id'] for p in t['teamA']],'teamB':[p['id'] for p in t['teamB']],'goalsA':3,'goalsB':1,'playedAt':'2026-10-11T12:00:00Z'}
r=request(admin,'/api/results/review',manual);request(admin,'/api/results/confirm',{'draftId':r['draftId'],'revision':r['revision']},400)
manual['playedAt']='2026-10-11T13:00:00Z';manual['goalsA']=0;manual['goalsB']=0
r=request(admin,'/api/results/review',manual);request(admin,'/api/results/confirm',{'draftId':r['draftId'],'revision':r['revision']})
history=request(admin,'/api/history');assert len(history['matches'])==2
winner=t['teamA'][0];stats=next(x for x in request(admin,'/api/players')['players'] if x['id']==winner['id']);assert (stats['games'],stats['wins'],stats['draws'],stats['points'],stats['goalDifference'],stats['winRate'])==(2,1,1,4,2,50)
request(admin,'/api/players/remove',{'id':winner['id'],'password':password})
assert winner['id'] not in [p['id'] for p in request(admin,'/api/players')['players']]
assert winner['name'] in request(admin,'/api/history')['matches'][0]['teamA']
restored=request(admin,'/api/players',{'name':winner['name'],'rating':75,'password':password},201);assert restored['id']==winner['id']
# Password checks precede all game writes, including retried confirmations.
request(other,'/api/results/review',{'password':''},403)
request(other,'/api/results/confirm',{'password':'wrong'},403)
logged=history['matches'][1]
request(other,'/api/results/delete',{'id':logged['id'],'version':logged['version'],'password':''},403)
request(admin,'/api/results/delete',{'id':logged['id'],'version':logged['version']+1},409)
assert len(request(other,'/api/history')['matches'])==2
request(admin,'/api/results/delete',{'id':logged['id'],'version':logged['version']})
assert len(request(other,'/api/history')['matches'])==1
stats=next(x for x in request(other,'/api/players')['players'] if x['id']==winner['id']);assert (stats['games'],stats['wins'],stats['points'],stats['goalDifference'])==(1,0,1,0)
request(admin,'/api/results/confirm',{'draftId':review['draftId'],'revision':review['revision']},400)
print('PASS: public browsing, protected logging/deletion, stale deletion and statistics recalculation, fixed scores, anonymous editable votes, cleanup, teams, generated/manual results, duplicate protection, statistics, archive/restore and nested URLs.')

ids=[x['id'] for x in request(other,'/api/players')['players'] if x.get('ratingReady',True)][:11]
ranked=request(other,'/api/teams',{'playerIds':ids,'alternatives':True},201)
assert ranked['exact'] is True and len(ranked['options'])==3
gaps=[abs(o['sumA']-o['sumB']) for o in ranked['options']];assert gaps==sorted(gaps)
keys=set()
saved={x['id'] for x in request(other,'/api/divisions')['divisions']}
for o in ranked['options']:
    key=tuple(sorted(tuple(sorted(p['id'] for p in o[side])) for side in ['teamA','teamB']))
    assert key not in keys and o['id'] in saved;keys.add(key)
    assert len(o['teamA'])==len(o['teamB'])==5 and len(o['substitutes'])==len(ids)-10
print('PASS: ranked alternatives API and each option saved for logging.')
