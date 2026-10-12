<?php
declare(strict_types=1);
// Copy to config.php outside ~/web. Never publish this file.
return [
 'host'=>'db.tecnico.ulisboa.pt', 'port'=>3306,
 'database'=>'ist123456', 'user'=>'ist123456', 'password'=>'REPLACE_DATABASE_PASSWORD',
 'guild_id'=>'REPLACE_WITH_EXISTING_DISCORD_SERVER_ID',
 'admin_password'=>'REPLACE_WITH_ADMIN_PASSWORD',
 'secure_cookies'=>true,
 // Usually detected automatically. Optional override: '/ist123456/futebol/'.
 'cookie_path'=>null,
 // For a verified TLS connection, set the CA file supplied by your database administrator.
 'ssl_ca'=>null,
];
