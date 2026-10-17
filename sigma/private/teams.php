<?php
declare(strict_types=1);
function balancedTen(array $players): array {
 $best=INF;$chosen=[];$n=count($players);
 for($b=1;$b<7;$b++)for($c=$b+1;$c<8;$c++)for($d=$c+1;$d<9;$d++)for($e=$d+1;$e<10;$e++){
  $a=[0,$b,$c,$d,$e];$sum=0.0;$other=0.0;
  foreach($players as $i=>$p) { if(in_array($i,$a,true))$sum+=$p['rating'];else $other+=$p['rating']; }
  $gap=abs($sum-$other);if($gap<$best){$best=$gap;$chosen=$a;if($gap==0.0)break 4;}
 }
 return [array_values(array_filter($players,fn($i)=>in_array($i,$chosen,true),ARRAY_FILTER_USE_KEY)),array_values(array_filter($players,fn($i)=>!in_array($i,$chosen,true),ARRAY_FILTER_USE_KEY)),$best];
}
function makeTeams(array $players,bool $random): array {
 $n=count($players);if($n<10)fail('Select at least 10 rated players.');shuffle($players);
 if($random) return [array_slice($players,0,5),array_slice($players,5,5),array_slice($players,10)];
 if($n===10){[$a,$b]=balancedTen($players);return [$a,$b,[]];}
 return rankedTeamOptions($players)[0];
}

/** Three distinct splits; each unordered pair of five-player subsets appears once. */
function rankedTeamOptions(array $players): array {
 $n=count($players);if($n<10)fail('Select at least 10 rated players.');
 usort($players,fn($a,$b)=>strcmp($a['name'],$b['name']));
 if($n>32)fail('Choose at most 32 attendees for guaranteed optimal teams. Narrow the selection or use random shuffle.');
 if(count(array_unique(array_column($players,'id')))!==$n)fail('Choose different players.');
 foreach($players as $p)if(!is_finite((float)$p['rating']))fail('Every player needs a finite rating.');
 // Sorted sums bound the remaining gaps; pruning never depends on overlapping players.
 $choices=[];
 for($a=0;$a<$n-4;$a++)for($b=$a+1;$b<$n-3;$b++)for($c=$b+1;$c<$n-2;$c++)for($d=$c+1;$d<$n-1;$d++)for($e=$d+1;$e<$n;$e++)
  $choices[]=[(1<<$a)|(1<<$b)|(1<<$c)|(1<<$d)|(1<<$e),$players[$a]['rating']+$players[$b]['rating']+$players[$c]['rating']+$players[$d]['rating']+$players[$e]['rating']];
 usort($choices,fn($a,$b)=>($a[1]<=>$b[1])?:($a[0]<=>$b[0]));$size=count($choices);$best=[];
 for($i=0;$i<$size;$i++)for($j=$i+1;$j<$size;$j++){
  $gap=$choices[$j][1]-$choices[$i][1];if(count($best)===3&&$gap>=$best[2][2])break;
  if(($choices[$i][0]&$choices[$j][0])!==0)continue;
  $best[]=[$choices[$i][0],$choices[$j][0],$gap];usort($best,fn($a,$b)=>$a[2]<=>$b[2]);$best=array_slice($best,0,3);
 }
 $result=[];
 foreach($best as [$maskA,$maskB]){
  $a=[];$b=[];$subs=[];
  foreach($players as $k=>$p){if(($maskA&(1<<$k))!==0)$a[]=$p;elseif(($maskB&(1<<$k))!==0)$b[]=$p;else $subs[]=$p;}
  $result[]=[$a,$b,$subs];
 }
 return $result;
}
