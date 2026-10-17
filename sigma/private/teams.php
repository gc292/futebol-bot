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
 $best=INF;$chosen=[];
 if($n<=24){
  $combinations=[];
  for($a=0;$a<$n-4;$a++)for($b=$a+1;$b<$n-3;$b++)for($c=$b+1;$c<$n-2;$c++)for($d=$c+1;$d<$n-1;$d++)for($e=$d+1;$e<$n;$e++)
   $combinations[]=[(1<<$a)|(1<<$b)|(1<<$c)|(1<<$d)|(1<<$e),$players[$a]['rating']+$players[$b]['rating']+$players[$c]['rating']+$players[$d]['rating']+$players[$e]['rating']];
  usort($combinations,fn($a,$b)=>$a[1]<=>$b[1]);$size=count($combinations);
  for($i=0;$i<$size;$i++)for($j=$i+1;$j<$size;$j++){
   $gap=$combinations[$j][1]-$combinations[$i][1];if($gap>=$best)break;
   if(($combinations[$i][0]&$combinations[$j][0])!==0)continue;
   $best=$gap;$mask=$combinations[$i][0]|$combinations[$j][0];$chosen=[];
   foreach($players as $k=>$p)if(($mask&(1<<$k))!==0)$chosen[]=$p;
   if($best==0.0)break 2;break;
  }
 } else {
  for($i=0;$i<4000;$i++){shuffle($players);$ten=array_slice($players,0,10);[,,$gap]=balancedTen($ten);if($gap<$best){$best=$gap;$chosen=$ten;if($gap==0.0)break;}}
 }
 [$a,$b]=balancedTen($chosen);$ids=array_column($chosen,'id');$subs=array_values(array_filter($players,fn($p)=>!in_array($p['id'],$ids,true)));return [$a,$b,$subs];
}

/** Three distinct splits; each unordered pair of five-player subsets appears once. */
function rankedTeamOptions(array $players): array {
 $n=count($players);if($n<10)fail('Select at least 10 rated players.');
 usort($players,fn($a,$b)=>strcmp($a['name'],$b['name']));
 if($n>24){
  $best=[];$seen=[];
  for($i=0;$i<4000;$i++){
   shuffle($players);
   foreach(rankedTeamOptions(array_slice($players,0,10)) as [$a,$b]){
    $aa=array_column($a,'id');$bb=array_column($b,'id');sort($aa);sort($bb);$parts=[implode(',',$aa),implode(',',$bb)];sort($parts);$key=implode('|',$parts);
    if(isset($seen[$key]))continue;$seen[$key]=true;
    $ids=array_column(array_merge($a,$b),'id');$subs=array_values(array_filter($players,fn($p)=>!in_array($p['id'],$ids,true)));
    $best[]=[$a,$b,$subs,abs(array_sum(array_column($a,'rating'))-array_sum(array_column($b,'rating')))];
    usort($best,fn($a,$b)=>$a[3]<=>$b[3]);$best=array_slice($best,0,3);
   }
   if(count($best)===3&&$best[2][3]==0.0)break;
  }
  return array_map(fn($x)=>array_slice($x,0,3),$best);
 }
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
