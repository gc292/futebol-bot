<?php
declare(strict_types=1);
final class ApiError extends RuntimeException {
 public function __construct(public int $status, string $message) { parent::__construct($message); }
}
function fail(string $message, int $status=400): never { throw new ApiError($status,$message); }
function config(): array {
 static $config;
 if ($config !== null) return $config;
 $path=getenv('FOOTBALL_CONFIG') ?: __DIR__.'/config.php';
 if (!is_file($path)) throw new RuntimeException('Create the private config.php first.');
 $config=require $path;
 foreach (['host','database','user','password','guild_id','admin_password'] as $key)
  if (!isset($config[$key]) || !is_string($config[$key]) || $config[$key]==='' || str_contains($config[$key],'REPLACE')) throw new RuntimeException('Invalid private configuration: '.$key);
 if (strlen($config['admin_password'])<8) throw new RuntimeException('Use a management password of at least 8 characters.');
 return $config;
}
function db(): PDO {
 static $db;
 if ($db) return $db;
 $c=config(); $options=[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_EMULATE_PREPARES=>false,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC];
 if (!empty($c['ssl_ca'])) { $options[PDO::MYSQL_ATTR_SSL_CA]=$c['ssl_ca']; $options[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT]=true; }
 $db=new PDO('mysql:host='.$c['host'].';port='.($c['port']??3306).';dbname='.$c['database'].';charset=utf8mb4',$c['user'],$c['password'],$options);
 return $db;
}
function rows(string $sql,array $args=[]): array { $s=db()->prepare($sql);$s->execute($args);return $s->fetchAll(); }
function row(string $sql,array $args=[]): ?array { return rows($sql,$args)[0]??null; }
function sql(string $sql,array $args=[]): void { $s=db()->prepare($sql);$s->execute($args); }
function gid(): string { return config()['guild_id']; }
function now(): string { return (new DateTimeImmutable('now',new DateTimeZone('UTC')))->format('Y-m-d\TH:i:s.u').'000Z'; }
function uuid(): string { $b=random_bytes(16);$b[6]=chr((ord($b[6])&15)|64);$b[8]=chr((ord($b[8])&63)|128);$h=bin2hex($b);return substr($h,0,8).'-'.substr($h,8,4).'-'.substr($h,12,4).'-'.substr($h,16,4).'-'.substr($h,20); }
function transaction(callable $fn): mixed {
 if ((int)(row("SELECT GET_LOCK(CONCAT('football:',LEFT(SHA2(DATABASE(),256),48)),10) AS locked")['locked']??0)!==1) fail('Database is busy. Please try again.',503);
 try { db()->beginTransaction();$result=$fn();db()->commit();return $result; }
 catch(Throwable $e) { if(db()->inTransaction())db()->rollBack();throw $e; }
 finally { sql("DO RELEASE_LOCK(CONCAT('football:',LEFT(SHA2(DATABASE(),256),48)))"); }
}
/** CGI SCRIPT_NAME may point at the interpreter instead of the public API URL. */
function cookiePath(array $server, ?string $configured=null): string {
 if ($configured !== null) {
  if (!str_starts_with($configured,'/') || preg_match('/[\x00-\x20;?,#]/',$configured)) throw new RuntimeException('Invalid cookie_path in private configuration.');
  return rtrim($configured,'/').'/';
 }
 $uri=$server['REQUEST_URI']??'';
 $path=parse_url($uri,PHP_URL_PATH);
 if (!is_string($path) || !str_starts_with($path,'/') || !str_ends_with($path,'/api.php') || preg_match('/[\x00-\x20;?,#]/',$path))
  $path=$server['SCRIPT_NAME']??'/api.php';
 return rtrim(str_replace('\\','/',dirname($path)),'/').'/';
}
function cookie(string $name,string $value,int $expiry,string $sameSite='Strict'): void {
 $path=cookiePath($_SERVER,config()['cookie_path']??null);
 setcookie($name,$value,['expires'=>$expiry,'path'=>$path,'secure'=>config()['secure_cookies']??true,'httponly'=>true,'samesite'=>$sameSite]);
}
function checkPassword(string $kind,string $given,string $expected): void {
 $key=hash('sha256',gid().'|'.$kind.'|'.($_SERVER['REMOTE_ADDR']??'cli'));$t=time();
 sql('DELETE FROM web_password_attempts WHERE expires_at<=?',[$t]);
 $r=row('SELECT attempts FROM web_password_attempts WHERE attempt_key=?',[$key]);
 if (($r['attempts']??0)>=5) fail('Too many attempts. Try again in a minute.',429);
 if (!hash_equals($expected,$given)) {
  sql('INSERT INTO web_password_attempts(attempt_key,attempts,expires_at) VALUES(?,1,?) ON DUPLICATE KEY UPDATE attempts=attempts+1',[$key,$t+60]);
  fail('Incorrect password.',403);
 }
 sql('DELETE FROM web_password_attempts WHERE attempt_key=?',[$key]);
}
function serve(): void {
 ini_set('display_errors','0');
 header('Content-Type: application/json; charset=utf-8');header('Cache-Control: no-store');header('X-Content-Type-Options: nosniff');
 try {
  $c=config();$route=$_GET['route']??'';$method=$_SERVER['REQUEST_METHOD'];$post=$method==='POST';
  if (!in_array($method,['GET','POST'],true)) fail('Method not allowed.',405);
  $body=[];
  if($post) {
   if(!str_starts_with(strtolower($_SERVER['CONTENT_TYPE']??''),'application/json')) fail('Use JSON.',415);
   $raw=file_get_contents('php://input',false,null,0,16385);if(strlen($raw)>16384)fail('Request too large.',413);
   try { $body=json_decode($raw,true,32,JSON_THROW_ON_ERROR); }catch(JsonException){fail('Invalid JSON.');}
   if(!is_array($body)||array_is_list($body)&&$body!==[])fail('Expected a JSON object.');
   $origin=$_SERVER['HTTP_ORIGIN']??null;
   if($origin!==null) { $expected=(($c['secure_cookies']??true)?'https':'http').'://'.($_SERVER['HTTP_HOST']??'');if(!hash_equals($expected,$origin))fail('Invalid request origin.',403); }
  }
  if($post&&in_array($route,['/api/players','/api/players/vote','/api/players/remove','/api/rating-polls/close','/api/results/review','/api/results/confirm','/api/results/delete'],true)) checkPassword('admin',(string)($body['password']??''),$c['admin_password']);
  $result=transaction(fn()=>dispatch($route,$method,$body));
  echo json_encode($result,JSON_THROW_ON_ERROR|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
 } catch(ApiError $e){http_response_code($e->status);echo json_encode(['error'=>$e->getMessage()]);}
 catch(Throwable $e){error_log('Football: '.$e->getMessage());http_response_code(500);echo json_encode(['error'=>'The server could not complete this request. Check the private server error log.']);}
}
require __DIR__.'/teams.php';
require __DIR__.'/app.php';
