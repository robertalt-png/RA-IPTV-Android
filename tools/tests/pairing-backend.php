<?php
define('ABSPATH', __DIR__.'/');
define('ARRAY_A', 'ARRAY_A');
define('MINUTE_IN_SECONDS', 60);
$options=[];$transients=[];$checks=0;$logged=true;$validNonce=true;$userEmail='owner@example.invalid';
function check($value,string $message): void {global $checks;if(!$value)throw new RuntimeException($message);$checks++;}
function add_action(...$args){} function register_activation_hook(...$args){} function register_deactivation_hook(...$args){}
function register_rest_route(...$args){} function wp_schedule_single_event(...$args){}
function wp_salt($kind){return 'fixture-salt-not-a-production-secret';}
function sanitize_text_field($value){return strip_tags((string)$value);}
function sanitize_email($value){return (string)$value;}
function esc_url_raw($value){return (string)$value;}
function wp_json_encode($value,...$flags){return json_encode($value,...$flags);}
function is_wp_error($value){return $value instanceof WP_Error;}
function sanitize_key($value){return preg_replace('/[^a-z0-9_\-]/','',strtolower($value));}
function get_option($key,$default=false){global $options;return $options[$key]??$default;}
function add_option($key,$value,...$unused){global $options;if(isset($options[$key]))return false;$options[$key]=$value;return true;}
function update_option($key,$value,...$unused){global $options;$changed=($options[$key]??null)!==$value;$options[$key]=$value;return $changed;}
function delete_option($key){global $options;unset($options[$key]);return true;}
function get_transient($key){global $transients;return $transients[$key]??false;}
function set_transient($key,$value,$expiry){global $transients;$transients[$key]=$value;return true;}
function home_url($path){return 'https://nenotv.com'.$path;}
function wp_generate_uuid4(){return 'fixture-event-uuid';}
function is_user_logged_in(){global $logged;return $logged;}
function get_current_user_id(){return 1;}
function wp_get_current_user(){global $userEmail;return (object)['user_email'=>$userEmail];}
function wp_verify_nonce(...$args){global $validNonce;return $validNonce;}
function wp_die($message){throw new RuntimeException($message);}
class Redirect extends RuntimeException {}
class WP_Error {
    public function __construct(public string $code,public string $message,public array $data){}
    public function get_error_code(){return $this->code;}
    public function get_error_message(){return $this->message;}
    public function get_error_data(){return $this->data;}
}
function auth_redirect(){throw new Redirect('login');}
function wp_safe_redirect($url){throw new Redirect($url);}
class WP_REST_Request {
    public function __construct(public array $payload){}
    public function get_json_params(){return $this->payload;}
    public function get_body(){return json_encode($this->payload);}
}
class WP_REST_Response {
    public array $headers=[];
    public function __construct(public array $data,public int $status){}
    public function header($key,$value){$this->headers[$key]=$value;}
}
class TestDb {
    public string $prefix='fixture_';public array $devices=[],$entitlements=[],$vaults=[],$events=[];
    public bool $writeFailure=false,$lockAvailable=true;
    public function prepare($sql,...$args){return [$sql,$args];}
    public function get_var($query){
        [$sql,$args]=$query;
        if(str_contains($sql,'GET_LOCK')){check(strlen($args[0])<=64,'MySQL lock name too long');return $this->lockAvailable?1:0;}
        if(str_contains($sql,'RELEASE_LOCK'))return 1;
        if(str_contains($sql,'COUNT(*)'))return count(array_filter($this->devices,fn($d)=>$d['entitlement_id']==$args[0]&&$d['status']==$args[1]));
        throw new RuntimeException('Unexpected count query');
    }
    public function get_row($query,$format){
        [$sql,$args]=$query;
        if(str_contains($sql,'nenotv_source_vault'))return $this->vaults[$args[0]]??null;
        if(str_contains($sql,'nenotv_devices')){foreach($this->devices as $d)if($d['device_id']===$args[0])return $d;return null;}
        if(str_contains($sql,'WHERE email_hash')){foreach($this->entitlements as $e)if($e['email_hash']===$args[0])return $e;return null;}
        if(str_contains($sql,'WHERE id='))return $this->entitlements[$args[0]]??null;
        throw new RuntimeException('Unexpected row query');
    }
    public function insert($table,$values){if($this->writeFailure)return false;$id=count($this->devices)+1;$values['id']=$id;$this->devices[$id]=$values;return 1;}
    public function update($table,$values,$where){if($this->writeFailure)return false;$id=$where['id'];$this->devices[$id]=array_merge($this->devices[$id],$values);return 1;}
    public function query($query){
        [$sql,$args]=$query;if($this->writeFailure)return false;
        if(str_contains($sql,'nenotv_entitlement_events')){$this->events[]=$args;return 1;}
        if(str_starts_with($sql,'INSERT IGNORE')){
            [$id,$revision,$payload,$iv,$tag,$updated]=$args;
            if(isset($this->vaults[$id]))return 0;
        }elseif(str_starts_with($sql,'UPDATE')){
            [$revision,$payload,$iv,$tag,$updated,$id,$base]=$args;
            if(!isset($this->vaults[$id])||$this->vaults[$id]['revision']!==$base)return 0;
        }else throw new RuntimeException('Unexpected vault write');
        $this->vaults[$id]=['revision'=>$revision,'payload'=>$payload,'iv'=>$iv,'tag'=>$tag,'updated_at'=>$updated];return 1;
    }
}
$wpdb=new TestDb();
require __DIR__.'/../../server/nenotv-entitlement-core.php';
$p=['device_id'=>'fixture-device','device_key'=>str_repeat('k',43),'public_device_id'=>'NT-FIXT-0001','platform'=>'android','app_version'=>'test','device_name'=>'Living room TV'];
function request(string $action,array $p): WP_REST_Response {return NenoTV_Entitlement_Core::pairing_request(new WP_REST_Request($p),$action);}
check(request('start',$p)->status===403,'Shadow mode allowed pairing');
$options['nenotv_entitlement_mode']='live';
$wpdb->entitlements[1]=['id'=>1,'reference'=>'fixture','email'=>'owner@example.invalid','email_hash'=>hash('sha256','owner@example.invalid'),'status'=>'active','plan'=>'annual','level'=>'pro','max_devices'=>1,'expires_at'=>null];
check(request('start',array_merge($p,['device_key'=>'short']))->status===400,'Weak device identity accepted');
$start=request('start',$p);check($start->data['ok']&&$start->status===200,'Pairing not created');
$code=$start->data['code'];$key='nenotv_pair_'.$code;$token=$start->data['poll_token'];
check(strlen($code)===10&&strlen($token)===64,'Wrong code or proof size');
check(!str_contains(json_encode($options[$key]),$p['device_key'])&&!str_contains(json_encode($options[$key]),$token),'Pairing stored raw secrets');
check($start->headers['Cache-Control']==='no-store','Pairing response cacheable');
$poll=array_merge($p,['code'=>$code,'poll_token'=>$token]);
check(request('status',$poll)->data['state']==='pending','Unapproved session completed');
check(count($wpdb->devices)===0,'Unapproved session bound device');
check(request('status',array_merge($poll,['poll_token'=>str_repeat('a',64)]))->status===403,'Wrong session proof accepted');
check(request('status',array_merge($poll,['device_key'=>str_repeat('x',43)]))->status===403,'Wrong device proof accepted');
$_POST=['code'=>$code,'_wpnonce'=>'fixture'];
$validNonce=false;
try{NenoTV_Entitlement_Core::pairing_approve();throw new LogicException('Missing nonce allowed approval');}catch(RuntimeException $e){check($e->getMessage()==='Security check failed.','Invalid nonce failed incorrectly');}
$validNonce=true;$logged=false;
try{NenoTV_Entitlement_Core::pairing_approve();throw new LogicException('Anonymous approval allowed');}catch(Redirect $e){check($e->getMessage()==='login','Anonymous approval did not require login');}
$logged=true;
$userEmail='unrelated@example.invalid';
try{NenoTV_Entitlement_Core::pairing_approve();throw new LogicException('Unrelated account allowed approval');}catch(RuntimeException $e){check($e->getMessage()==='An active NenoTV account is required.','Account isolation failed');}
check($options[$key]['state']==='pending','Unrelated account altered session');
$userEmail='owner@example.invalid';
try{NenoTV_Entitlement_Core::pairing_approve();throw new LogicException('No safe redirect');}catch(Redirect $e){check(str_starts_with($e->getMessage(),'https://nenotv.com/nenotv-pair/'),'Approval redirected off-site');}
check($options[$key]['state']==='approved'&&count($wpdb->devices)===0,'Approval bound without app proof');
$done=request('status',$poll);check($done->data['state']==='complete'&&count($wpdb->devices)===1,'Approved device not bound');
check($done->data['entitlement']['used_devices']===1&&$done->data['entitlement']['free_devices']===0,'Incorrect device places');
$payloadMethod=new ReflectionMethod(NenoTV_Entitlement_Core::class,'pro_payload');
$verifiedPayload=$payloadMethod->invoke(null,$wpdb->entitlements[1]);
check($verifiedPayload['used_devices']===1&&$verifiedPayload['free_devices']===0,'Refresh omitted device places');
$trialRow=array_merge($wpdb->entitlements[1],['plan'=>'trial','level'=>'pro_trial']);
check($payloadMethod->invoke(null,$trialRow)['used_devices']===1,'Trial omitted occupied device places');
check(request('status',$poll)->data['state']==='complete'&&count($wpdb->devices)===1,'Poll not idempotent');
check(count($wpdb->events)===1&&!str_contains(json_encode($wpdb->events),$token),'Pairing audit duplicated or stored proof');
$wpdb->devices[1]['status']='revoked';
check(request('status',$poll)->status===403&&$wpdb->devices[1]['status']==='revoked','Completed session restored revoked device');
$wpdb->devices[1]['status']='active';
$second=array_merge($p,['device_id'=>'second-device']);$start2=request('start',$second);$code2=$start2->data['code'];
$options['nenotv_pair_'.$code2]['state']='approved';$options['nenotv_pair_'.$code2]['entitlement_id']=1;
$poll2=array_merge($second,['code'=>$code2,'poll_token'=>$start2->data['poll_token']]);
check(request('status',$poll2)->data['error']==='device_limit'&&count($wpdb->devices)===1,'Device limit bypassed');
$wpdb->devices[1]['status']='revoked';$wpdb->writeFailure=true;
check(request('status',$poll2)->data['error']==='device_write_failed','Database failure reported success');
$wpdb->writeFailure=false;$wpdb->lockAvailable=false;
check(request('status',$poll2)->status===503,'Unavailable lock did not fail closed');
$wpdb->lockAvailable=true;
check(request('cancel',$poll2)->data['state']==='cancelled'&&!isset($options['nenotv_pair_'.$code2]),'Cancelled session retained proof');
$options[$key]['expires']=time()-1;
check(request('status',$poll)->data['state']==='expired'&&!isset($options[$key]),'Expired session retained proof');
$wpdb->devices[1]['status']='active';
$source=['id'=>'source-fixture','type'=>'XTREAM','name'=>'Private fixture','server'=>'https://example.invalid','username'=>'fixture-user','password'=>'fixture-private-password'];
function sources(string $action,array $p): WP_REST_Response{return NenoTV_Entitlement_Core::app_sources(new WP_REST_Request($p),$action);}
check(sources('pull',array_merge($p,['device_key'=>str_repeat('z',43)]))->status===403,'Source vault exposed without device proof');
$first=sources('push',array_merge($p,['sources'=>[$source],'base_revision'=>0]));
check($first->status===200&&$first->data['revision']===1,'Source vault not created with revision');
check(!str_contains($wpdb->vaults[1]['payload'],$source['password']),'Source credentials stored unencrypted');
check(sources('pull',$p)->data['sources'][0]['password']===$source['password'],'Encrypted source did not round trip');
$second=sources('push',array_merge($p,['sources'=>[],'base_revision'=>1]));
check($second->data['revision']===2&&count(sources('pull',$p)->data['sources'])===0,'Cloud removal not authoritative');
$stale=sources('push',array_merge($p,['sources'=>[$source],'base_revision'=>1]));
check($stale->status===409&&$stale->data['error']==='source_revision_conflict','Stale source update accepted');
check(count(sources('pull',$p)->data['sources'])===0,'Stale device restored deleted source');
check(sources('push',array_merge($p,['base_revision'=>2]))->status===400,'Missing sources cleared vault');
check(sources('push',array_merge($p,['sources'=>[$source]]))->status===409,'Unversioned source update accepted');
check(sources('push',array_merge($p,['sources'=>[$source,$source],'base_revision'=>2]))->status===400,'Duplicate source IDs accepted');
$original=$wpdb->vaults[1];$wpdb->vaults[1]['tag']=base64_encode(str_repeat('x',16));
check(sources('pull',$p)->status===503,'Unreadable vault reported empty source list');
$wpdb->vaults[1]=$original;$wpdb->writeFailure=true;
check(sources('push',array_merge($p,['sources'=>[$source],'base_revision'=>2]))->status===503,'Vault write error reported success');
$wpdb->writeFailure=false;
echo $checks." pairing/account authorization checks passed\n";
