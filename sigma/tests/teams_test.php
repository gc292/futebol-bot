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
