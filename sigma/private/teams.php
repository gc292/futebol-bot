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
