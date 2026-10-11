<?php
declare(strict_types=1);
$private = getenv('FOOTBALL_PRIVATE_DIR') ?: dirname(__DIR__, 2).'/football-private';
require $private.'/bootstrap.php';
serve();
