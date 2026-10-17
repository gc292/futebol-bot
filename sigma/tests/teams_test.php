<?php
declare(strict_types=1);
require dirname(__DIR__).'/private/bootstrap.php';
function check(bool $ok,string $message):void{if(!$ok)throw new RuntimeException($message);}
function oracle(array $p):float {
 $n=count($p);$comb=[];
 for($a=0;$a<$n-4;$a++)for($b=$a+1;$b<$n-3;$b++)for($c=$b+1;$c<$n-2;$c++)for($d=$c+1;$d<$n-1;$d++)for($e=$d+1;$e<$n;$e++)$comb[]=[[$a,$b,$c,$d,$e],$p[$a]['rating']+$p[$b]['rating']+$p[$c]['rating']+$p[$d]['rating']+$p[$e]['rating']];
 $best=INF;foreach($comb as $a)foreach($comb as $b)if(!array_intersect($a[0],$b[0]))$best=min($best,abs($a[1]-$b[1]));return $best;
}
foreach([[89,87,77,67,62,32,24,21,16,14],[98,83,72,62,51,44,39,31,22,13,7],[99,97,83,78,67,62,48,34,27,23,19,8],[10,10,10,10,10,10,10,10,10,10,10]] as $scores){
 $p=[];foreach($scores as $i=>$score)$p[]=['id'=>(string)($i+1),'name'=>'Player '.$i,'rating'=>$score];
 [$a,$b,$subs]=makeTeams($p,false);$gap=abs(array_sum(array_column($a,'rating'))-array_sum(array_column($b,'rating')));
 check(abs($gap-oracle($p))<1e-9,'Not globally optimal');check(count(array_unique(array_column(array_merge($a,$b,$subs),'id')))===count($p),'Duplicate/missing players');
}
$p=[];for($i=0;$i<24;$i++)$p[]=['id'=>(string)$i,'name'=>'P'.$i,'rating'=>($i*17)%101];$t=microtime(true);[$a,$b,$subs]=makeTeams($p,false);check(count($subs)===14,'24-player substitutes');
echo 'PASS: exact teams match independent exhaustive oracle; 24-player run '.round(microtime(true)-$t,3).'s, peak memory '.round(memory_get_peak_usage(true)/1048576,1)." MiB\n";

foreach([[89,87,77,67,62,32,24,21,16,14],[98,83,72,62,51,44,39,31,22,13,7],[50,50,50,50,50,50,50,50,50,50,50,50],[8.1,8.2,8.3,4.4,5.5,6.6,7.7,9.8,9.9,10.1]] as $scores){
 $players=[];foreach($scores as $i=>$score)$players[]=['id'=>(string)$i,'name'=>'P'.$i,'rating'=>$score];
 $masks=[];$n=count($scores);for($mask=0;$mask<(1<<$n);$mask++)if(substr_count(decbin($mask),'1')===5){$sum=0;foreach($scores as $i=>$score)if(($mask&(1<<$i))!==0)$sum+=$score;$masks[]=[$mask,$sum];}
 $gaps=[];foreach($masks as $i=>$a)foreach(array_slice($masks,$i+1) as $b)if(($a[0]&$b[0])===0)$gaps[]=abs($a[1]-$b[1]);sort($gaps);
 $options=rankedTeamOptions($players);check(count($options)===3,'Need 3 alternatives');$keys=[];
 foreach($options as $i=>[$a,$b,$subs]){
  check(abs(abs(array_sum(array_column($a,'rating'))-array_sum(array_column($b,'rating')))-$gaps[$i])<1e-9,'Wrong ranked gap');
  $aa=array_column($a,'id');$bb=array_column($b,'id');sort($aa);sort($bb);$sides=[implode(',',$aa),implode(',',$bb)];sort($sides);$key=implode('|',$sides);check(!isset($keys[$key]),'Duplicate or swapped alternative');$keys[$key]=true;
  check(count($a)===5&&count($b)===5&&count(array_unique(array_column(array_merge($a,$b,$subs),'id')))===$n,'Missing/duplicate attendee');
 }
}
foreach([24,25] as $n){$p=[];for($i=0;$i<$n;$i++)$p[]=['id'=>(string)$i,'name'=>'P'.$i,'rating'=>50];$options=rankedTeamOptions($p);check(count($options)===3,'Large group alternatives');foreach($options as [$a,$b,$subs])check(count($subs)===$n-10,'Missing substitutes');}
echo "PASS: top three match exhaustive ranked oracle, ties, fractional ratings, no swapped duplicates, and 24/25-player pools.\n";
