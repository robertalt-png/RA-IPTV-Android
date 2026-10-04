<?php
require __DIR__.'/../../server/nenotv-catalog-package.php';
$checks=0;$opts=[];$tasks=[];$fixture=[];$vault=[];$mode='live';$allowed=true;
function check($b,$m){global $checks;$checks++;if(!$b)throw new Exception($m);}
function fails($f,$m){try{$f();}catch(Throwable $e){check(true,$m);return;}check(false,$m);}
function get_option($k,$d=false){global $opts;return $opts[$k]??$d;}
function update_option($k,$v,$a=false){global $opts;$opts[$k]=$v;return true;}
function delete_option($k){global $opts;unset($opts[$k]);}
function wp_salt($x){return 'qa-only-not-a-real-secret';}
function home_url(){return 'https://nenotv.example';}
define('ABSPATH',__DIR__.'/../../');
function as_enqueue_async_action($h,$args,$g,$unique){global $tasks;$tasks[]=$args;}
function wp_http_validate_url($u){return str_starts_with($u,'https://provider.example/');}
function wp_safe_remote_get($u,$args){global $fixture;if(!wp_http_validate_url($u))return new WP_Error();parse_str(parse_url($u,PHP_URL_QUERY)??'',$q);$data=$fixture[$q['action']??'m3u']??null;if($data===null)return new WP_Error();file_put_contents($args['filename'],$data);return ['status'=>200];}
function wp_remote_retrieve_response_code($r){return $r['status'];}
function is_wp_error($x){return $x instanceof WP_Error;}
class WP_Error{}
class WP_REST_Request {function __construct(public array $p,public string $route='/nenotv/v1/catalog/download'){}function get_json_params(){return $this->p;}function get_body(){return json_encode($this->p);}function get_route(){return $this->route;}}
class WP_REST_Response {function __construct(public $data,public $status=200,public $headers=[]){}function get_status(){return $this->status;}}
class Harness {
 use NenoTV_Catalog_Package;
 const APP_NS='nenotv/v1';const CRON='daily';
 static function mode(){global $mode;return $mode;}
 static function load_source_vault($id){global $vault;return ['sources'=>$vault[$id]??[]];}
 static function source_device_auth($p){global $allowed;return $allowed?['entitlement'=>['id'=>1,'email'=>'qa@example.test'],'device'=>['device_id'=>'qa-device']]:new WP_Error();}
 static function email_hash($e){return hash('sha256',$e);}
 static function json($d,$s){return new WP_REST_Response($d,$s);}
 static function wp_error_json($e){return self::json(['ok'=>false],403);}
}
$dir=sys_get_temp_dir().'/nenotv-format-test-'.bin2hex(random_bytes(6));mkdir($dir,0700);
try{
 $file=$dir.'/input';file_put_contents($file,json_encode([['x'=>'a } " b','n'=>['a'=>[1,2]]],['id'=>2]]));$rows=[];
 check(NenoTV_Catalog_Format::json_array($file,function($x)use(&$rows){$rows[]=$x;})===2,'stream parser');check($rows[1]['id']===2,'nested records');
 foreach(['','{}','[{},]','[{}','[{}]garbage','[null]','[{"x":bad}]'] as $bad){file_put_contents($file,$bad);fails(fn()=>NenoTV_Catalog_Format::json_array($file,fn($x)=>null),'reject malformed array');}
 file_put_contents($file,gzencode('[{"id":3}]'));check(NenoTV_Catalog_Format::json_array($file,fn($x)=>check($x['id']===3,'gzip provider data'))===1,'gzip input');
 file_put_contents($file,"#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"NL,TV\",Channel\nhttps://provider.example/live\n");$rows=[];check(NenoTV_Catalog_Format::m3u($file,function($x)use(&$rows){$rows[]=$x;})===1,'m3u');check($rows[0]['name']==='Channel'&&$rows[0]['group']==='NL,TV','quoted comma');
 file_put_contents($file,'<html>provider failed</html>');fails(fn()=>NenoTV_Catalog_Format::m3u($file,fn($x)=>null),'reject provider error');
 file_put_contents($file,str_repeat('private',20000));$sealed=$dir.'/sealed';$key=random_bytes(32);NenoTV_Catalog_Format::seal($file,$sealed,$key);$bytes='';NenoTV_Catalog_Format::unseal($sealed,$key,function($x)use(&$bytes){$bytes.=$x;});check($bytes===file_get_contents($file),'sealed roundtrip');check(!str_contains(file_get_contents($sealed),'private'),'sealed storage');fails(fn()=>NenoTV_Catalog_Format::unseal($sealed,random_bytes(32),fn($x)=>null),'wrong key');
 $b=file_get_contents($sealed);$b[40]=chr(ord($b[40])^1);file_put_contents($sealed,$b);fails(fn()=>NenoTV_Catalog_Format::unseal($sealed,$key,fn($x)=>null),'tampered frame');
 $source=['id'=>'qa-source','type'=>'XTREAM','server'=>'https://provider.example','username'=>'qa','password'=>'secret','m3u'=>'','enabled'=>true];$vault[1]=[$source];
 foreach(['live','vod','series'] as $type){$fixture['get_'.$type.'_categories']='[{"category_id":"1","category_name":"NL"}]';$action=$type==='series'?'get_series':'get_'.$type.'_streams';$fixture[$action]=json_encode([[$type==='series'?'series_id':'stream_id'=>10,'name'=>'QA '.$type,'category_id'=>'1']]);}
 Harness::catalog_sources_saved(1,[$source]);check(count($tasks)===1,'queued after web save');
 while($tasks){$args=array_shift($tasks);Harness::catalog_build(...$args);}
 $body=['source_id'=>'qa-source','account_scope'=>hash('sha256','qa@example.test')];$r=Harness::catalog_request(new WP_REST_Request($body),'status');check($r->status===200&&$r->data['state']==='ready','six-stage package ready');check(array_sum($r->data['counts'])===3,'catalog totals');check(!isset($r->data['parts'])&&!isset($r->data['token']),'no private paths');
 $manifest=$r->data;$body['fingerprint']=$manifest['fingerprint'];$r=Harness::catalog_request(new WP_REST_Request($body),'download');check($r->status===200&&$r->headers['Content-Length']==$manifest['bytes'],'binary response');ob_start();Harness::catalog_serve(false,$r,new WP_REST_Request($body),null);$package=ob_get_clean();check(hash('sha256',$package)===$manifest['sha256'],'whole package checksum');file_put_contents($dir.'/package.gz',$package);
 $gz=gzopen($dir.'/package.gz','rb');$plain='';while(!gzeof($gz))$plain.=gzread($gz,65536);gzclose($gz);$records=array_map(fn($l)=>json_decode($l,true),explode("\n",trim($plain)));check($records[0]['kind']==='header'&&end($records)['kind']==='end','concatenated gzip valid');check(count(array_filter($records,fn($r)=>$r['kind']==='item'))===3,'complete catalog');
 if(getenv('NENOTV_EXPORT_FIXTURE')){mkdir('android/app/src/androidTest/assets',0777,true);file_put_contents('android/app/src/androidTest/assets/catalog-fixture.gz',$package);file_put_contents('android/app/src/androidTest/assets/catalog-fixture.json',json_encode($manifest));}
 $body['sha256']=$manifest['sha256'];check(Harness::catalog_request(new WP_REST_Request($body),'ack')->status===200,'device ack');$body['sha256']='wrong';check(Harness::catalog_request(new WP_REST_Request($body),'ack')->status===400,'ack checksum');
 $bad=$body;$bad['account_scope']='another';check(Harness::catalog_request(new WP_REST_Request($bad),'download')->status===409,'account mismatch');$bad=$body;$bad['source_id']='other';check(Harness::catalog_request(new WP_REST_Request($bad),'download')->status===404,'source ownership');$bad=$body;$bad['fingerprint']='old';check(Harness::catalog_request(new WP_REST_Request($bad),'download')->status===409,'stale version');
 $allowed=false;check(Harness::catalog_request(new WP_REST_Request($body),'download')->status===403,'unlinked device');$allowed=true;$mode='shadow';check(Harness::catalog_request(new WP_REST_Request($body),'status')->status===403,'prelaunch remains blocked');$mode='live';
 $vault[1][0]['password']='changed';check(Harness::catalog_request(new WP_REST_Request($body),'download')->status===409,'changed credentials reject stale pack');Harness::catalog_sources_saved(1,[]);$vault[1]=[];
 check(Harness::catalog_request(new WP_REST_Request($body),'download')->status===404,'deleted source');
 echo "Catalog package: $checks checks passed\n";
}finally{foreach(glob($dir.'/*') as $p)unlink($p);rmdir($dir);}
