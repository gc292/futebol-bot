<?php
declare(strict_types=1);
function playerShape(array $p): array { return ['id'=>(string)$p['id'],'name'=>$p['name'],'rating'=>(float)$p['rating']]; }
function activePlayers(): array {
 return array_map('playerShape',rows('SELECT p.* FROM players p LEFT JOIN removed_players r ON r.guild_id=p.guild_id AND r.player_id=p.id WHERE p.guild_id=? AND r.player_id IS NULL ORDER BY p.roster_order,p.id',[gid()]));
}
function members(string $table,string $key,string $id,string $team): array {
 // Table/column names are private constants, never request input.
 return array_map('playerShape',rows("SELECT p.* FROM $table d JOIN players p ON p.id=d.player_id AND p.guild_id=d.guild_id WHERE d.guild_id=? AND d.$key=? AND d.team=? ORDER BY d.position",[gid(),$id,$team]));
}
function division(string $id): array {
 $r=row('SELECT * FROM divisions WHERE guild_id=? AND id=?',[gid(),$id]);if(!$r)fail('Teams not found.',404);
 return ['id'=>(string)$r['id'],'createdAt'=>$r['created_at'],'teamA'=>members('division_players','division_id',$id,'A'),'teamB'=>members('division_players','division_id',$id,'B')];
}
function pollShape(array $r): array {
 return ['id'=>$r['id'],'playerId'=>(string)$r['player_id'],'name'=>$r['name'],'url'=>(bool)$r['native']?'vote.html?id='.$r['id']:$r['form_url'],'status'=>$r['status'],'count'=>(int)$r['vote_count'],'average'=>$r['average']===null?null:(float)$r['average'],'updatedAt'=>$r['updated_at'],'native'=>(bool)$r['native'],'cleanupPending'=>false];
}
function polls(): array {
 return array_map('pollShape',rows('SELECT r.*,p.name,n.poll_id IS NOT NULL AS native FROM rating_polls r JOIN players p ON p.id=r.player_id AND p.guild_id=r.guild_id LEFT JOIN native_polls n ON n.poll_id=r.id LEFT JOIN removed_players x ON x.guild_id=r.guild_id AND x.player_id=r.player_id WHERE r.guild_id=? AND x.player_id IS NULL ORDER BY r.updated_at DESC',[gid()]));
}
function allPlayerStats(): array {
 $players=activePlayers();$stats=[];
 foreach($players as $p)$stats[$p['id']]=$p+['games'=>0,'wins'=>0,'draws'=>0,'losses'=>0,'winRate'=>null,'goalDifference'=>0,'points'=>0,'streak'=>'N/A','ratingReady'=>true];
 foreach(rows('SELECT dp.player_id,dp.team,m.goals_a,m.goals_b FROM matches m JOIN division_players dp ON dp.division_id=m.division_id AND dp.guild_id=m.guild_id WHERE m.guild_id=? ORDER BY m.played_at,m.id',[gid()]) as $r){
  $id=(string)$r['player_id'];if(!isset($stats[$id]))continue;$p=&$stats[$id];$gf=(int)$r[$r['team']==='A'?'goals_a':'goals_b'];$ga=(int)$r[$r['team']==='A'?'goals_b':'goals_a'];
  $type=$gf>$ga?'Wins':($gf===$ga?'Draws':'Losses');$field=strtolower($type);$p[$field]++;$p['games']++;$p['goalDifference']+=$gf-$ga;$p['points']+=$gf>$ga?3:($gf===$ga?1:0);
  $parts=explode(' ',$p['streak']);$p['streak']=(($parts[1]??'')===$type?((int)$parts[0]+1):1).' '.$type;unset($p);
 }
 foreach($stats as &$p)if($p['games'])$p['winRate']=100*$p['wins']/$p['games'];unset($p);
 foreach(polls() as $poll){$id=$poll['playerId'];if(isset($stats[$id])){$stats[$id]['ratingPoll']=$poll;$stats[$id]['ratingReady']=$poll['count']>0||$poll['status']==='CLOSED';}}
 return array_values($stats);
}
function score(mixed $value): float { if(!is_numeric($value)||!is_finite((float)$value)||(float)$value<0||(float)$value>100)fail('Enter a score between 0 and 100.');return (float)$value; }
function identifier(mixed $value): string { if(!is_scalar($value)||!preg_match('/^[1-9][0-9]{0,18}$/',(string)$value))fail('Invalid player or teams ID.');return (string)$value; }
function selectPlayers(mixed $ids): array {
 if(!is_array($ids)||count($ids)>200)fail('Choose players.');$ids=array_map('identifier',$ids);if(count(array_unique($ids))!==count($ids))fail('Choose each player once.');
 $all=[];foreach(allPlayerStats() as $p)$all[$p['id']]=$p;$out=[];
 foreach($ids as $id){if(!isset($all[$id]))fail('Player is no longer in the catalogue.');if(!$all[$id]['ratingReady'])fail('Wait for a player rating first.');$out[]=playerShape($all[$id]);}return $out;
}
function addPlayer(array $b,bool $voting): array {
 $name=trim((string)($b['name']??''));if($name===''||preg_match('/[\x00-\x1f]/u',$name)||preg_match_all('/./us',$name)>80)fail('Enter a player name of 1–80 characters.');
 $rating=$voting?0.0:score($b['rating']??null);
 $old=row('SELECT p.*,r.player_id AS removed FROM players p LEFT JOIN removed_players r ON r.guild_id=p.guild_id AND r.player_id=p.id WHERE p.guild_id=? AND LOWER(p.name)=LOWER(?)',[gid(),$name]);
 if($old&&!$old['removed'])fail('A player with that name already exists.');
 if($old){$name=$old['name'];$id=(string)$old['id'];sql('DELETE FROM rating_polls WHERE guild_id=? AND player_id=?',[gid(),$id]);sql('DELETE FROM removed_players WHERE guild_id=? AND player_id=?',[gid(),$id]);sql('UPDATE players SET name=?,rating=? WHERE guild_id=? AND id=?',[$name,$rating,gid(),$id]);}
 else{$order=(int)(row('SELECT COALESCE(MAX(roster_order),-1)+1 AS n FROM players WHERE guild_id=?',[gid()])['n']);sql('INSERT INTO players(guild_id,name,rating,roster_order) VALUES(?,?,?,?)',[gid(),$name,$rating,$order]);$id=db()->lastInsertId();}
 $player=['id'=>$id,'name'=>$name,'rating'=>$rating];
 if(!$voting)return $player;
 $poll=uuid();sql("INSERT INTO rating_polls(id,guild_id,player_id,form_url,status,updated_at) VALUES(?,?,?,?,'OPEN',?)",[$poll,gid(),$id,'vote.html?id='.$poll,now()]);sql('INSERT INTO native_polls VALUES(?)',[$poll]);return ['player'=>$player,'poll'=>array_values(array_filter(polls(),fn($p)=>$p['id']===$poll))[0]];
}
function vote(string $method,array $b): array {
 $id=(string)($_GET['id']??'');$r=row('SELECT r.*,p.name FROM rating_polls r JOIN native_polls n ON n.poll_id=r.id JOIN players p ON p.id=r.player_id AND p.guild_id=r.guild_id LEFT JOIN removed_players x ON x.guild_id=r.guild_id AND x.player_id=r.player_id WHERE r.guild_id=? AND r.id=? AND x.player_id IS NULL',[gid(),$id]);if(!$r)fail('Voting link not found.',404);
 $voter=$_COOKIE['football_vote_browser']??'';
 if(!preg_match('/^[a-f0-9-]{36}$/D',$voter)){if($method==='POST')fail('Open the voting link first and enable cookies.');$voter=uuid();cookie('football_vote_browser',$voter,time()+31536000,'Lax');}
 if($method==='POST'){
  if($r['status']!=='OPEN')fail('Voting has finished.');$s=score($b['score']??null);
  sql('INSERT INTO rating_votes VALUES(?,?,?) ON DUPLICATE KEY UPDATE score=VALUES(score)',[$id,$voter,$s]);$avg=row('SELECT COUNT(*) AS n,AVG(score) AS avg FROM rating_votes WHERE poll_id=?',[$id]);
  sql('UPDATE rating_polls SET vote_count=?,average=?,updated_at=? WHERE id=?',[$avg['n'],$avg['avg'],now(),$id]);sql('UPDATE players SET rating=? WHERE guild_id=? AND id=?',[$avg['avg'],gid(),$r['player_id']]);$r['vote_count']=$avg['n'];$r['average']=$avg['avg'];
 }
 $own=row('SELECT score FROM rating_votes WHERE poll_id=? AND voter_id=?',[$id,$voter]);return ['name'=>$r['name'],'status'=>$r['status'],'count'=>(int)$r['vote_count'],'average'=>$r['average']===null?null:(float)$r['average'],'ownScore'=>$own?(float)$own['score']:null];
}
function saveDivision(array $a,array $b,string $source='BOT'): string {
 sql('INSERT INTO divisions(guild_id,channel_id,created_by,created_at,source,mode,team_size) VALUES(?,?,?,?,?,?,?)',[gid(),'website','website',now(),$source,$source==='BOT'?'TEAMS':'MANUAL',count($a)]);$id=db()->lastInsertId();
 foreach(['A'=>$a,'B'=>$b] as $team=>$players)foreach($players as $i=>$p)sql('INSERT INTO division_players VALUES(?,?,?,?,?)',[gid(),$id,$p['id'],$team,$i]);return $id;
}
function goals(mixed $v): int { if(!is_scalar($v)||!preg_match('/^\d{1,10}$/D',(string)$v)||(float)$v>2147483647)fail('Enter non-negative integer goals.');return (int)$v; }
function review(array $b): array {
 $ga=goals($b['goalsA']??null);$gb=goals($b['goalsB']??null);$raw=(string)($b['playedAt']??'');
 if(!preg_match('/^\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d(\.\d{1,9})?)?(Z|[+-]\d\d:\d\d)$/D',$raw))fail('Enter a valid game date.');
 try{$date=new DateTimeImmutable($raw);$errors=DateTimeImmutable::getLastErrors();if($errors!==false&&($errors['warning_count']||$errors['error_count']))fail('Enter a valid game date.');$played=$date->setTimezone(new DateTimeZone('UTC'))->format('Y-m-d\TH:i:s.u').'000Z';}catch(Exception){fail('Enter a valid game date.');}
 if($date->getTimestamp()>time()+300)fail('The match date cannot be in the future.');
 if(!empty($b['divisionId'])){$id=identifier($b['divisionId']);$d=division($id);if(row('SELECT id FROM matches WHERE guild_id=? AND division_id=?',[gid(),$id]))fail('This lineup already has a result.');$a=$d['teamA'];$bb=$d['teamB'];}
 else{$a=selectPlayers($b['teamA']??null);$bb=selectPlayers($b['teamB']??null);$size=(int)row('SELECT manual_team_size AS n FROM guild_settings WHERE guild_id=?',[gid()])['n'];if(count($a)!==$size||count($bb)!==$size||count(array_unique(array_column(array_merge($a,$bb),'id')))!==2*$size)fail('Select exactly '.$size.' different players for each team.');$id=saveDivision($a,$bb,'MANUAL');}
 $draft=uuid();sql("INSERT INTO drafts(id,guild_id,user_id,channel_id,action,status,division_id,team_size,goals_a,goals_b,played_at,revision) VALUES(?,?,?,?,'CREATE','READY',?,?,?,?,?,1)",[$draft,gid(),'website','website',$id,count($a),$ga,$gb,$played]);
 return ['draftId'=>$draft,'revision'=>1,'goalsA'=>$ga,'goalsB'=>$gb,'playedAt'=>$played,'teamA'=>$a,'teamB'=>$bb];
}
function sameIds(array $a,array $b): bool { $aa=array_column($a,'id');$bb=array_column($b,'id');sort($aa);sort($bb);return $aa===$bb; }
function confirm(array $b): array {
 $d=row('SELECT * FROM drafts WHERE guild_id=? AND id=? AND user_id=?',[gid(),(string)($b['draftId']??''),'website']);if(!$d)fail('Result review not found.',404);
 if((int)($b['revision']??-1)!==(int)$d['revision'])fail('This review has changed. Open it again.');
 if($d['status']==='CONFIRMED')return ['id'=>(string)$d['confirmed_match_id']];if($d['action']!=='CREATE'||$d['status']!=='READY')fail('This review is no longer active.');
 if(row('SELECT id FROM matches WHERE guild_id=? AND division_id=?',[gid(),$d['division_id']]))fail('This lineup already has a result.');
 $div=division((string)$d['division_id']);
 foreach(rows('SELECT * FROM matches WHERE guild_id=? AND played_at=?',[gid(),$d['played_at']]) as $m){$other=division((string)$m['division_id']);
  if(($m['goals_a']==$d['goals_a']&&$m['goals_b']==$d['goals_b']&&sameIds($div['teamA'],$other['teamA'])&&sameIds($div['teamB'],$other['teamB']))||($m['goals_a']==$d['goals_b']&&$m['goals_b']==$d['goals_a']&&sameIds($div['teamA'],$other['teamB'])&&sameIds($div['teamB'],$other['teamA'])))fail('This game has already been logged.');
 }
 sql('INSERT INTO matches(guild_id,division_id,goals_a,goals_b,played_at,recorded_at,recorded_by) VALUES(?,?,?,?,?,?,?)',[gid(),$d['division_id'],$d['goals_a'],$d['goals_b'],$d['played_at'],now(),'website']);$id=db()->lastInsertId();sql("UPDATE drafts SET status='CONFIRMED',confirmed_match_id=? WHERE id=?",[$id,$d['id']]);return ['id'=>$id];
}
function dispatch(string $route,string $method,array $b): array {
 if($route==='/api/voting')return vote($method,$b);
 switch($method.' '.$route){
 case 'GET /api/players':$s=row('SELECT * FROM guild_settings WHERE guild_id=?',[gid()]);if(!$s)fail('Run the private database installer first.',503);return ['players'=>allPlayerStats(),'minimumGames'=>(int)$s['minimum_win_rate_games'],'teamSize'=>(int)$s['manual_team_size'],'formsConfigured'=>false,'formsSyncFailed'=>false,'votingConfigured'=>true,'adminConfigured'=>true];
 case 'POST /api/players':http_response_code(201);return addPlayer($b,false);
 case 'POST /api/players/vote':http_response_code(201);return addPlayer($b,true);
 case 'POST /api/players/remove':$id=identifier($b['id']??null);if(!in_array($id,array_column(activePlayers(),'id'),true))fail('Player not found.',404);sql('INSERT INTO removed_players VALUES(?,?,?)',[gid(),$id,now()]);return ['ok'=>true];
 case 'GET /api/rating-polls':return ['polls'=>polls()];
 case 'POST /api/rating-polls/close':$id=(string)($b['id']??'');$poll=array_values(array_filter(polls(),fn($p)=>$p['id']===$id))[0]??null;if(!$poll)fail('Voting not found.',404);if($poll['count']<1)fail('Wait for at least one vote.');sql("UPDATE rating_polls SET status='CLOSED',updated_at=? WHERE id=?",[now(),$id]);sql('DELETE FROM rating_votes WHERE poll_id=?',[$id]);$poll['status']='CLOSED';return $poll;
 case 'POST /api/teams':$players=selectPlayers($b['playerIds']??null);if(!($b['random']??false)&&($b['alternatives']??false)){
  $options=[];foreach(rankedTeamOptions($players) as [$a,$bb,$subs]){$id=saveDivision($a,$bb);$options[]=['id'=>$id,'teamA'=>$a,'teamB'=>$bb,'sumA'=>array_sum(array_column($a,'rating')),'sumB'=>array_sum(array_column($bb,'rating')),'substitutes'=>array_column($subs,'name'),'random'=>false];}
  http_response_code(201);return ['options'=>$options,'exact'=>count($players)<=24];
 }[$a,$bb,$subs]=makeTeams($players,(bool)($b['random']??false));$id=saveDivision($a,$bb);return ['id'=>$id,'teamA'=>$a,'teamB'=>$bb,'sumA'=>array_sum(array_column($a,'rating')),'sumB'=>array_sum(array_column($bb,'rating')),'substitutes'=>array_column($subs,'name'),'random'=>(bool)($b['random']??false)];
 case 'GET /api/divisions':$out=[];foreach(rows("SELECT d.id FROM divisions d LEFT JOIN matches m ON m.division_id=d.id WHERE d.guild_id=? AND d.source='BOT' AND m.id IS NULL ORDER BY d.id DESC LIMIT 100",[gid()]) as $r)$out[]=division((string)$r['id']);return ['divisions'=>$out];
 case 'POST /api/results/review':return review($b);
 case 'POST /api/results/confirm':return confirm($b);
 case 'POST /api/results/delete':
  $id=identifier($b['id']??null);$version=goals($b['version']??null);
  $m=row('SELECT version FROM matches WHERE guild_id=? AND id=?',[gid(),$id]);if(!$m)fail('Game not found. Refresh the history.',404);
  if($version!==(int)$m['version'])fail('This game changed. Refresh and review it before deleting.',409);
  sql('DELETE FROM matches WHERE guild_id=? AND id=? AND version=?',[gid(),$id,$version]);
  sql("UPDATE drafts SET status='CANCELLED',revision=revision+1 WHERE guild_id=? AND confirmed_match_id=? AND action='CREATE' AND status='CONFIRMED'",[gid(),$id]);
  return ['ok'=>true];
 case 'GET /api/history':$page=filter_var($_GET['page']??0,FILTER_VALIDATE_INT);if($page===false||$page<0||$page>100000)fail('Invalid page.');$offset=$page*12;$matches=rows("SELECT * FROM matches WHERE guild_id=? ORDER BY played_at DESC,id DESC LIMIT 13 OFFSET $offset",[gid()]);$more=count($matches)>12;$out=[];foreach(array_slice($matches,0,12) as $r){$d=division((string)$r['division_id']);$out[]=['id'=>(string)$r['id'],'version'=>(int)$r['version'],'playedAt'=>$r['played_at'],'goalsA'=>(int)$r['goals_a'],'goalsB'=>(int)$r['goals_b'],'teamA'=>array_column($d['teamA'],'name'),'teamB'=>array_column($d['teamB'],'name')];}return ['matches'=>$out,'hasMore'=>$more];
 default:fail('Endpoint not found.',404);
 }
}
