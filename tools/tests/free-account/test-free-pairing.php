<?php
define('ABSPATH',__DIR__);define('MINUTE_IN_SECONDS',60);
$options=[];$meta=[];$transients=[];$userId=1;$logged=true;$nonce=true;$write=true;$checks=0;
$users=[1=>(object)['roles'=>['customer'],'user_email'=>'one@example.invalid'],2=>(object)['roles'=>['customer'],'user_email'=>'two@example.invalid'],3=>(object)['roles'=>['administrator'],'user_email'=>'admin@example.invalid']];
function check($ok,$message){global $checks;if(!$ok)throw new RuntimeException($message);$checks++;}
function wp_salt($type){return 'test-only-salt';}function get_userdata($id){global $users;return $users[$id]??false;}
function user_can($user,$cap){return in_array('administrator',$user->roles,true);}
function get_option($key,$default=false){global $options;return $options[$key]??$default;}
function add_option($key,$value,...$rest){global $options,$write;if(!$write||isset($options[$key]))return false;$options[$key]=$value;return true;}
function update_option($key,$value,...$rest){global $options,$write;if(!$write)return false;$changed=($options[$key]??null)!==$value;$options[$key]=$value;return $changed;}
function delete_option($key){global $options;$exists=isset($options[$key]);unset($options[$key]);return $exists;}
function get_user_meta($id,$key,$single){global $meta;return $meta[$id][$key]??false;}
function update_user_meta($id,$key,$value){global $meta,$write;if(!$write)return false;$meta[$id][$key]=$value;return true;}
function get_transient($key){global $transients;return $transients[$key]??false;}function set_transient($key,$value,$expires){global $transients;$transients[$key]=$value;}
function wp_schedule_single_event(...$args){}function sanitize_text_field($v){return strip_tags($v);}function home_url($v){return 'https://sunnyiptv.com'.$v;}
function is_user_logged_in(){global $logged;return $logged;}function get_current_user_id(){global $userId;return $userId;}
function wp_verify_nonce(...$args){global $nonce;return $nonce;}function wp_die($message){throw new RuntimeException($message);}
class Redirect extends RuntimeException{}function auth_redirect(){throw new Redirect('login');}function wp_safe_redirect($url){throw new Redirect($url);}
class WP_REST_Request {function __construct(public array $payload){}function get_body(){return json_encode($this->payload);}function get_json_params(){return $this->payload;}}
class WP_REST_Response {function __construct(public array $data,public int $status){}}
class Db {public bool $available=true;function prepare($sql,...$args){return [$sql,$args];}function get_var($q){check(strlen($q[1][0])<=64,'lock too long');return $this->available?1:0;}}
$wpdb=new Db();
require __DIR__.'/nenotv-pairing.php';
class App {
 use NenoTV_Pairing;
 public static array $paid=[],$devices=[];public static bool $live=false;public static int $bindCalls=0;
 static function json($v,$status){return new WP_REST_Response($v,$status);}static function clean_app_payload($r){return $r->payload;}
 static function key_hash($v){return hash('sha256',$v);}static function email_hash($v){return hash('sha256',strtolower($v));}
 static function find_device($id){return self::$devices[$id]??null;}static function find_by_id($id){return self::$paid[$id]??null;}
 static function app_services_available(){return self::$live;}static function app_service_live($ent){return self::$live&&is_array($ent);}
 static function current_user_entitlement(){return self::$paid[get_current_user_id()]??null;}
 static function user_owns_entitlement($ent){return $ent['email']===get_userdata(get_current_user_id())->user_email;}
 static function entitlement_is_active($ent){return $ent['status']==='active';}
 static function bind_device($ent,$p){self::$bindCalls++;if(count(self::$devices)>=$ent['max_devices'])return ['ok'=>false,'error'=>'device_limit'];self::$devices[$p['device_id']]=['entitlement_id'=>$ent['id'],'device_key_hash'=>self::key_hash($p['device_key']),'status'=>'active'];return ['ok'=>true];}
 static function pro_payload($e){return ['level'=>'pro','status'=>'active','max_devices'=>$e['max_devices']];}static function count_active_devices($id){return count(self::$devices);}static function log_event(...$args){}
}
function req($action,$payload){return App::pairing_request(new WP_REST_Request($payload),$action);}
function approve($code){$_POST=['code'=>$code,'_wpnonce'=>'test'];try{App::pairing_approve();throw new LogicException('no redirect');}catch(Redirect $e){}}
function start($id='test-device',$version=3){global $transients;$transients=[];$p=['device_id'=>$id,'device_key'=>str_repeat('k',43),'public_device_id'=>'TEST','platform'=>'android','device_name'=>'Test phone','pairing_version'=>$version];$r=req('start',$p);check($r->status===200,'start failed');return array_merge($p,['code'=>$r->data['code'],'poll_token'=>$r->data['poll_token']]);}
$p=['device_id'=>'old','device_key'=>str_repeat('k',43),'pairing_version'=>2];check(req('start',$p)->status===403,'old Pro launch gate bypassed');
$p=start();check(req('status',$p)->data['state']==='pending','unapproved pairing completed');
check(req('status',array_merge($p,['poll_token'=>str_repeat('a',64)]))->status===403,'wrong polling proof accepted');
check(req('status',array_merge($p,['device_key'=>str_repeat('x',43)]))->status===403,'wrong device proof accepted');
$logged=false;try{approve($p['code']);}catch(Redirect $e){}check($options['nenotv_pair_'.$p['code']]['state']==='pending','anonymous approved');$logged=true;
$nonce=false;try{approve($p['code']);throw new LogicException('nonce accepted');}catch(RuntimeException $e){check($e->getMessage()==='Security check failed.','nonce check failed');}$nonce=true;
$userId=3;try{approve($p['code']);throw new LogicException('admin accepted');}catch(RuntimeException $e){check($e->getMessage()==='Please use a customer account.','privileged account accepted');}$userId=1;
approve($p['code']);check(App::$bindCalls===0,'web approval bound paid device');$done=req('status',$p);
check($done->status===200&&$done->data['entitlement']['level']==='free','free completion failed');
check($done->data['entitlement']['max_devices']===0&&$done->data['entitlement']['account_scope']==='','free paid rights leaked');
check(App::$devices===[]&&App::$paid===[]&&App::$bindCalls===0,'free touched paid tables');
check(preg_match('/^[a-f0-9]{64}$/D',$done->data['account_link']['account_id'])===1,'opaque account identity missing');
check(!str_contains(json_encode($options),$p['device_key'])&&!str_contains(json_encode($options),$p['poll_token']),'raw proofs persisted');
check(req('status',$p)->status===200,'completion not idempotent');
check(App::free_account_status(new WP_REST_Request($p))->status===200,'account refresh failed');
check(App::free_account_status(new WP_REST_Request(array_merge($p,['device_key'=>str_repeat('x',43)])))->status===403,'wrong proof refreshed');
$userId=2;$other=start();approve($other['code']);check(req('status',$other)->status===403,'cross-account transfer accepted');$userId=1;
$digest=hash_hmac('sha256',$p['device_id'],wp_salt('auth'));delete_option('sunnyiptv_free_device_'.$digest);$meta[1]['_sunnyiptv_free_devices']=[];
check(req('status',$p)->status===403,'completed session resurrected revoked account');
check(App::free_account_status(new WP_REST_Request($p))->status===403,'revoked account refreshed');
$p=start('expired');$options['nenotv_pair_'.$p['code']]['expires']=time()-1;check(req('status',$p)->data['state']==='expired','expired session accepted');
$p=start('failed-write');approve($p['code']);$write=false;check(req('status',$p)->status===403,'failed write granted access');$write=true;
$wpdb->available=false;check(req('status',$p)->status===503,'missing session lock allowed access');$wpdb->available=true;
$meta[1]['_sunnyiptv_free_devices']=array_fill_keys(range(1,10),['name'=>'test']);$p=start('limit');approve($p['code']);check(req('status',$p)->data['error']==='device_limit','free limit bypassed');$meta[1]['_sunnyiptv_free_devices']=[];
App::$live=true;App::$paid[1]=['id'=>1,'reference'=>'test','email'=>'one@example.invalid','status'=>'active','max_devices'=>1];
$p=start('paid');approve($p['code']);$paid=req('status',$p);check($paid->status===200&&$paid->data['entitlement']['level']==='pro'&&$paid->data['account_link']['kind']==='paid','paid pairing regressed');
$p2=start('paid-limit');approve($p2['code']);check(req('status',$p2)->data['error']==='device_limit','paid device limit bypassed');
$legacy=start('paid',2);approve($legacy['code']);App::$devices=[];check(req('status',$legacy)->data['entitlement']['level']==='pro','legacy Pro pairing regressed');
$users[1]->roles=['administrator'];check(App::free_account_status(new WP_REST_Request($p))->status===403,'role escalation retained account access');$users[1]->roles=['customer'];
App::free_delete_user(1);check(App::free_account_status(new WP_REST_Request($p))->status===403,'deleted user links remain');
echo "FREE_BASE_ACCOUNT: $checks checks passed\n";
