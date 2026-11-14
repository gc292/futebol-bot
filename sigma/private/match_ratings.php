<?php
declare(strict_types=1);

function ratingMultiplier(int $margin): float {
 return $margin<=1 ? 1.0 : min(1.7999999999999998,1-0.8*expm1(-($margin-1)/2));
}
function ratingExpected(float $own,float $opponent): float {
 return 1/(1+10**(($opponent-$own)/20));
}
function applyMatchRatings(array &$ratings,array $a,array $b,int $goalsA,int $goalsB): void {
 if(!$a||!$b||$goalsA<0||$goalsB<0)fail('Invalid match for rating calculation.');
 $seen=[];
 foreach(array_merge($a,$b) as $id){
  $rating=$ratings[$id]??null;
  if(isset($seen[$id])||$rating===null||!is_finite($rating)||$rating<0||$rating>100)
   fail('Invalid participant or rating in match history.');
  $seen[$id]=true;
 }
 // The order of summation does not depend on team position.
 sort($a,SORT_NUMERIC);sort($b,SORT_NUMERIC);
 $avgA=array_sum(array_map(fn($id)=>$ratings[$id],$a))/count($a);
 $avgB=array_sum(array_map(fn($id)=>$ratings[$id],$b))/count($b);
 $actual=$goalsA===$goalsB?0.5:($goalsA>$goalsB?1.0:0.0);
 $delta=max(-3,min(3,3*($actual-ratingExpected($avgA,$avgB))*ratingMultiplier(abs($goalsA-$goalsB))));
 foreach($a as $id)$ratings[$id]=max(0,min(100,$ratings[$id]+$delta));
 foreach($b as $id)$ratings[$id]=max(0,min(100,$ratings[$id]-$delta));
}

function seedRatingBaselines(): bool {
 $s=db()->prepare('INSERT INTO player_rating_baselines(guild_id,player_id,rating)
  SELECT p.guild_id,p.id,p.rating FROM players p
  LEFT JOIN player_rating_baselines b ON b.guild_id=p.guild_id AND b.player_id=p.id
  WHERE p.guild_id=? AND b.player_id IS NULL');
 $s->execute([gid()]);
 return $s->rowCount()>0;
}
function initializeRatingScope(): void {
 if(row('SELECT guild_id FROM match_rating_scope WHERE guild_id=?',[gid()]))return;
 $latest=row('SELECT id FROM matches WHERE guild_id=? ORDER BY played_at DESC,id DESC LIMIT 1',[gid()]);
 $ceiling=row('SELECT COALESCE(MAX(id),0) AS max_id FROM matches WHERE guild_id=?',[gid()]);
 sql('INSERT INTO match_rating_scope(guild_id,historical_max_id,historical_match_id) VALUES(?,?,?)',[gid(),$ceiling['max_id'],$latest['id']??null]);
 replayMatchRatings();
}
function replayMatchRatings(): void {
 seedRatingBaselines();
 $ratings=[];
 foreach(rows('SELECT player_id,rating FROM player_rating_baselines WHERE guild_id=?',[gid()]) as $p)
  $ratings[$p['player_id']]=(float)$p['rating'];
 $overrides=rows('SELECT player_id,rating,effective_at FROM player_rating_overrides WHERE guild_id=? ORDER BY effective_at,id',[gid()]);
 $next=0;
 foreach(rows('SELECT m.division_id,m.goals_a,m.goals_b,m.played_at FROM matches m JOIN match_rating_scope s ON s.guild_id=m.guild_id WHERE m.guild_id=? AND (m.id=s.historical_match_id OR m.id>s.historical_max_id) ORDER BY m.played_at,m.id',[gid()]) as $game){
  while(isset($overrides[$next])&&strcmp($overrides[$next]['effective_at'],$game['played_at'])<=0){
   $edit=$overrides[$next++];$ratings[$edit['player_id']]=(float)$edit['rating'];
  }
  $teams=['A'=>[],'B'=>[]];
  foreach(rows('SELECT player_id,team FROM division_players WHERE guild_id=? AND division_id=?',[gid(),$game['division_id']]) as $p)
   $teams[$p['team']][]=$p['player_id'];
  applyMatchRatings($ratings,$teams['A'],$teams['B'],(int)$game['goals_a'],(int)$game['goals_b']);
 }
 while(isset($overrides[$next])){
  $edit=$overrides[$next++];$ratings[$edit['player_id']]=(float)$edit['rating'];
 }
 foreach($ratings as $id=>$rating){
  $value=sprintf('%.17g',$rating);
  sql('UPDATE players SET rating=? WHERE guild_id=? AND id=? AND rating<>?',[$value,gid(),$id,$value]);
 }
}

function overridePlayerRating(string $id,float $rating): void {
 seedRatingBaselines();
 sql('INSERT INTO player_rating_overrides(guild_id,player_id,rating,effective_at) VALUES(?,?,?,?)',[gid(),$id,sprintf('%.17g',$rating),now()]);
 replayMatchRatings();
}
