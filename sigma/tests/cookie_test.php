<?php
declare(strict_types=1);
require dirname(__DIR__).'/private/bootstrap.php';
$cases=[
 [['REQUEST_URI'=>'/ist123456/futebol/api.php?route=/api/login','SCRIPT_NAME'=>'/cgi-bin/php8.2'],null,'/ist123456/futebol/'],
 [['REQUEST_URI'=>'/~ist123456/futebol/api.php?route=%2Fapi%2Flogin','SCRIPT_NAME'=>'/cgi-bin/php'],null,'/~ist123456/futebol/'],
 [['REQUEST_URI'=>'/futebol/api.php?route=/api/login','SCRIPT_NAME'=>'/futebol/api.php'],null,'/futebol/'],
 [['REQUEST_URI'=>'/api.php?route=/api/login','SCRIPT_NAME'=>'/api.php'],null,'/'],
 [['SCRIPT_NAME'=>'/futebol/api.php'],null,'/futebol/'],
 [['REQUEST_URI'=>'/internal/api.php'], '/ist123456/futebol/', '/ist123456/futebol/'],
];
foreach($cases as [$server,$configured,$expected]) {
 $actual=cookiePath($server,$configured);
 if($actual!==$expected)throw new RuntimeException("Cookie path mismatch: $actual != $expected");
}
try { cookiePath([],"/bad;path");throw new LogicException('Accepted invalid path'); }
catch(RuntimeException $e) { if($e instanceof LogicException)throw $e; }
echo "PASS: CGI paths, personal-page aliases, root/subdirectory hosting, fallback and explicit cookie paths.\n";
