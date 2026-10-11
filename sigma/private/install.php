<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require __DIR__.'/bootstrap.php';
try {
 $schema=file_get_contents(is_file(__DIR__.'/schema.sql')?__DIR__.'/schema.sql':dirname(__DIR__,2).'/src/main/resources/schema-mariadb.sql');
 foreach(explode(';',$schema) as $statement)if(trim($statement)!=='')sql($statement);
 if((int)row('SELECT version FROM schema_metadata WHERE id=1')['version']!==1)throw new RuntimeException('Unsupported schema version.');
 $tables=['guild_settings','authorized_roles','players','divisions','division_players','matches','drafts','draft_players','rating_polls','removed_players','rating_cleanup','native_polls','rating_votes'];
 if(($argv[1]??'')==='--import'){
  if(!isset($argv[2]))throw new RuntimeException('Usage: php install.php --import /private/path/export.json');
  $data=json_decode(file_get_contents($argv[2]),true,512,JSON_THROW_ON_ERROR);
  if(($data['schemaVersion']??null)!==1||array_keys($data['tables']??[])!==$tables)throw new RuntimeException('Unsupported SQLite export.');
  transaction(function()use($tables,$data){
   foreach($tables as $table)if((int)row("SELECT COUNT(*) AS n FROM $table")['n']!==0)throw new RuntimeException('Import requires empty application tables. No data was changed.');
   foreach($tables as $table){
    $allowed=array_column(rows("SHOW COLUMNS FROM $table"),'Field');
    foreach($data['tables'][$table] as $record){$columns=array_keys($record);if(!$columns||array_diff($columns,$allowed))throw new RuntimeException('Invalid columns for '.$table);
     sql('INSERT INTO '.$table.' (`'.implode('`,`',$columns).'`) VALUES('.implode(',',array_fill(0,count($columns),'?')).')',array_values($record));
    }
   }
   if(!row('SELECT guild_id FROM guild_settings WHERE guild_id=?',[gid()]))throw new RuntimeException('The configured guild_id does not exist in this export.');
  });
  echo "SQLite data imported. Player IDs, results, polls and votes preserved.\n";
 }elseif(count($argv)===1){
  transaction(function(){
   if(row('SELECT guild_id FROM guild_settings WHERE guild_id=?',[gid()]))return;
   sql('INSERT INTO guild_settings(guild_id) VALUES(?)',[gid()]);
   $catalogue=['Ribeiro'=>86,'Charola'=>86,'Salvador'=>80,'Francisco'=>73.8,'Caria'=>71.9,'Xavi'=>71.9,'Pedro'=>69.3,'Ronald'=>68.3,'Gui'=>67.9,'Miguel'=>67.3,'Sal'=>65,'André'=>59.1,'Filipe'=>58.1,'Rodrigo'=>56.4,'João'=>53.1,'Tiago'=>51.9,'Fontes'=>51.6,'Pipa'=>5];$order=0;
   foreach($catalogue as $name=>$rating)sql('INSERT INTO players(guild_id,name,rating,roster_order) VALUES(?,?,?,?)',[gid(),$name,$rating,$order++]);
  });echo "Schema ready. Existing data retained.\n";
 }else throw new RuntimeException('Usage: php install.php [--import /private/path/export.json]');
} catch(Throwable $e){fwrite(STDERR,'Install failed: '.$e->getMessage()."\n");exit(1);}
