<?php
// Exercise the candidate's real class and traits against an isolated WordPress/database double.
define('ABSPATH', __DIR__.'/');
define('ARRAY_A', 'ARRAY_A');
define('MINUTE_IN_SECONDS', 60);
define('DAY_IN_SECONDS', 86400);
set_error_handler(static function($severity,$message,$file,$line) { throw new ErrorException($message,0,$severity,$file,$line); });
class WP_REST_Request {
    public array $headers = [];
    public function __construct(public array $params = []) {}
    public function get_json_params() { return $this->params; }
    public function get_body() { return json_encode($this->params); }
    public function get_header($name) { return $this->headers[$name] ?? ''; }
}
class WP_REST_Response {
    public array $headers = [];
    public function __construct(public $data, public int $status = 200, array $headers = []) { $this->headers=$headers; }
    public function header($name,$value) { $this->headers[$name]=$value; }
}
class WP_Error {
    public function __construct(private string $code, private string $message, private array $data) {}
    public function get_error_code() { return $this->code; }
    public function get_error_message() { return $this->message; }
    public function get_error_data() { return $this->data; }
}
function is_wp_error($v) { return $v instanceof WP_Error; }
function add_action(...$args) {}
function register_activation_hook(...$args) {}
function register_deactivation_hook(...$args) {}
function register_rest_route(...$args) { $GLOBALS['routes'][]=$args; }
function register_meta(...$args) { $GLOBALS['meta_registration']=$args; }
function sanitize_email($v) { return filter_var($v,FILTER_SANITIZE_EMAIL); }
function sanitize_text_field($v) { return strip_tags($v); }
function sanitize_key($v) { return preg_replace('/[^a-z0-9_\-]/','',strtolower($v)); }
function is_email($v) { return filter_var($v,FILTER_VALIDATE_EMAIL) !== false; }
function wp_salt($v) { return 'LOCAL-FIXTURE-SALT-NOT-A-PRODUCTION-SECRET'; }
function wp_generate_password($length,...$args) { return substr(bin2hex(random_bytes($length)),0,$length); }
function wp_generate_uuid4() { return bin2hex(random_bytes(16)); }
function current_time($type,$gmt=false) { return gmdate('Y-m-d H:i:s'); }
function absint($v) { return abs((int)$v); }
function get_userdata($id) { return $GLOBALS['users'][$id] ?? false; }
function get_user_meta($id,$key,$single=true) { return $GLOBALS['user_meta'][$id][$key] ?? ''; }
function update_user_meta($id,$key,$value) { $GLOBALS['user_meta'][$id][$key]=$value;return true; }
function user_can($user,$cap) { return in_array('administrator',$user->roles,true); }
function is_user_logged_in() { return $GLOBALS['logged_in']; }
function current_user_can($cap) { return $GLOBALS['admin']; }
function wp_get_current_user() { return get_userdata($GLOBALS['current_user']); }
function get_current_user_id() { return $GLOBALS['current_user']; }
function get_option($key,$default=false) { return $GLOBALS['options'][$key] ?? $default; }
function update_option($key,$value,$autoload=null) {
    if ($GLOBALS['option_fail']) return false;
    $GLOBALS['options'][$key]=$value; return true;
}
function add_option($key,$value,...$rest) {
    if (isset($GLOBALS['options'][$key])) return false;
    $GLOBALS['options'][$key]=$value; return true;
}
function get_transient($key) { return $GLOBALS['transients'][$key] ?? false; }
function set_transient($key,$value,$ttl) { $GLOBALS['transients'][$key]=$value; }
function wp_schedule_single_event(...$args) {}
function home_url($path='') { return 'https://example.invalid'.$path; }

class TestDB {
    public string $prefix='wp_test_';
    public int $insert_id=0;
    public array $ent=[];
    public array $dev=[];
    public array $events=[];
    public bool $insert_fail=false;
    public bool $update_fail=false;
    public bool $lock=true;
    public bool $lookup_fail=false;
    public function prepare($sql,...$args) { return [$sql,$args]; }
    public function get_row($prepared,$type=null) {
        [$sql,$args]=$prepared;
        $rows=str_contains($sql,'nenotv_devices') ? $this->dev : $this->ent;
        if (str_contains($sql,'source_vault')) return null;
        foreach ($rows as $row) {
            if (str_contains($sql,'WHERE device_id=') && $row['device_id']===$args[0]) return $row;
            if (str_contains($sql,'WHERE activation_hash=') && ($row['activation_hash']??'')===$args[0]) return $row;
            if (str_contains($sql,'WHERE id=') && $row['id']===$args[0]) return $row;
            if (str_contains($sql,'WHERE source=') && ($row['source']??'')===$args[0] && ($row['source_ref']??'')===$args[1]) return $row;
            if (str_contains($sql,'WHERE email_hash=') && $row['email_hash']===$args[0]) return $row;
        }
        return null;
    }
    public function get_var($prepared) {
        [$sql,$args]=$prepared;
        if (str_contains($sql,'GET_LOCK')) return $this->lock ? 1 : 0;
        if (str_contains($sql,'RELEASE_LOCK')) return 1;
        if (str_contains($sql,'WHERE email_hash=')) return $this->lookup_fail ? null : count(array_filter($this->ent,fn($r)=>$r['email_hash']===$args[0]));
        if (str_contains($sql,'COUNT(*)') && str_contains($sql,'nenotv_devices')) return count(array_filter($this->dev,fn($r)=>$r['entitlement_id']===$args[0]&&$r['status']===$args[1]));
        throw new RuntimeException('Unmodelled SELECT: '.$sql);
    }
    public function insert($table,$values,...$args) {
        if ($this->insert_fail) return false;
        if (str_contains($table,'nenotv_devices')) {
            $id=count($this->dev)+1; $this->dev[$id]=['id'=>$id]+$values;
        } else { $id=count($this->ent)+1; $this->ent[$id]=['id'=>$id]+$values; }
        $this->insert_id=$id; return 1;
    }
    public function update($table,$values,$where,...$args) {
        if ($this->update_fail) return false;
        $bucket=str_contains($table,'nenotv_devices') ? 'dev' : 'ent';
        $id=$where['id']; if (!isset($this->{$bucket}[$id])) return 0;
        $this->{$bucket}[$id]=array_replace($this->{$bucket}[$id],$values); return 1;
    }
    public function query($prepared) {
        [$sql,$args]=$prepared;
        if (str_starts_with($sql,'INSERT IGNORE INTO')) { $this->events[]=$args; return 1; }
        if (str_starts_with($sql,'UPDATE') && str_contains($sql,'activation_hash=')) {
            $id=$args[2];
            if (($this->ent[$id]['activation_hash']??'')!==$args[3]) return 0;
            $this->ent[$id]['activation_hash']=$args[0]; $this->ent[$id]['activation_expires_at']=null; return 1;
        }
        throw new RuntimeException('Unmodelled write: '.$sql);
    }
}
require __DIR__.'/../../../server/nenotv-entitlement-core.php';
function reset_fixture() {
    $GLOBALS['wpdb']=new TestDB();
    $GLOBALS['options']=['nenotv_entitlement_mode'=>'shadow','nenotv_bridge_secret'=>str_repeat('FIXTURE',8)];
    $GLOBALS['transients']=[]; $GLOBALS['routes']=[];
    $GLOBALS['users']=[17=>(object)['roles'=>['customer'],'user_email'=>'review@example.invalid'],18=>(object)['roles'=>['administrator'],'user_email'=>'owner@example.invalid']];
    $GLOBALS['user_meta']=[17=>['_sunnyiptv_review_account'=>'1']];
    $GLOBALS['logged_in']=true; $GLOBALS['admin']=true; $GLOBALS['current_user']=17; $GLOBALS['option_fail']=false;
}
$checks=[];
function check($name,$ok) {
    if (!$ok) throw new RuntimeException('FAILED: '.$name);
    $GLOBALS['checks'][]=$name;
}
function private_call($name,...$args) {
    return (new ReflectionMethod(NenoTV_Entitlement_Core::class,$name))->invoke(null,...$args);
}
function provision($id=17) { return NenoTV_Entitlement_Core::review_admin(new WP_REST_Request(['user_id'=>$id]),'provision'); }
function app($action,$device='device-1',$key=null,$token=null,$sign=true) {
    $params=['device_id'=>$device,'device_key'=>$key??str_repeat('D',40),'platform'=>'android','app_version'=>'0.14.7'];
    if ($token!==null) $params['activation_token']=$token;
    $r=new WP_REST_Request($params);
    if ($sign) {
        $ts=(string)time();
        $r->headers=['x-sunnyiptv-timestamp'=>$ts,'x-sunnyiptv-event-id'=>wp_generate_uuid4(),'x-sunnyiptv-signature'=>'v1='.hash_hmac('sha256',$ts.'.'.$r->get_body(),get_option('nenotv_bridge_secret'))];
    }
    return NenoTV_Entitlement_Core::app_entitlement($r,$action);
}
reset_fixture();
check('review disabled by default',private_call('review_access_allowed')===false);
check('unsigned backend denied',app('refresh',sign:false)->status===401);
$legacy=new WP_REST_Request(['device_id'=>'legacy','device_key'=>str_repeat('L',40)]);
$ts=(string)time();
$legacy->headers=['x-nenotv-timestamp'=>$ts,'x-nenotv-event-id'=>wp_generate_uuid4(),'x-nenotv-signature'=>'v1='.hash_hmac('sha256',$ts.'.'.$legacy->get_body(),get_option('nenotv_bridge_secret'))];
check('legacy signed requests remain compatible',NenoTV_Entitlement_Core::app_entitlement($legacy,'refresh')->data['ok']===true);
check('replay remains rejected',NenoTV_Entitlement_Core::app_entitlement($legacy,'refresh')->status===409);
$legacy->headers['x-nenotv-event-id']=wp_generate_uuid4();
$legacy->headers['x-sunnyiptv-signature']='v1=invalid';
check('mixed incomplete header families denied',NenoTV_Entitlement_Core::app_entitlement($legacy,'refresh')->status===401);
$legacy->headers=['x-sunnyiptv-timestamp'=>$ts,'x-sunnyiptv-event-id'=>wp_generate_uuid4(),'x-sunnyiptv-signature'=>'v1=invalid'];
check('bad Sunny signature remains rejected',NenoTV_Entitlement_Core::app_entitlement($legacy,'refresh')->status===401);
check('ordinary refresh remains free',app('refresh')->data['entitlement']['level']==='free');
check('ordinary redeem blocked',app('redeem',token:'NENO-AAAA-BBBB-CCCC-DDDD')->data['error']==='pro_not_live');
check('ordinary trial blocked',app('trial')->data['error']==='pro_not_live');
$GLOBALS['admin']=false;
check('customer cannot provision',provision()->status===403);
$GLOBALS['admin']=true; $GLOBALS['logged_in']=false;
check('logged-out cannot provision',provision()->status===403);
$GLOBALS['logged_in']=true;
check('administrator cannot be review customer',provision(18)->status===400);
check('unknown customer denied',provision(99)->status===400);
check('string user id denied',provision('17')->status===400);
$GLOBALS['user_meta'][17]=[];
check('unmarked personal customer denied',provision()->status===400);
$GLOBALS['user_meta'][17]=['_sunnyiptv_review_account'=>'1'];
$GLOBALS['wpdb']->lock=false;
check('provision lock failure denied',provision()->data['error']==='review_busy');
$GLOBALS['wpdb']->lock=true;
$p=provision(); $token=$p->data['activation_token']; $id=$GLOBALS['options']['sunnyiptv_review_access']['entitlement_id'];
check('dedicated provision succeeds',$p->data['ok']===true);
check('code compatible with Android',preg_match('/^NENO-(?:[A-Z0-9]{4}-){3}[A-Z0-9]{4}$/D',$token)===1);
check('code response never cached',$p->headers['Cache-Control']==='no-store');
check('no end date',$GLOBALS['wpdb']->ent[$id]['expires_at']===null&&$p->data['expires_at']===null);
check('only hash stored',$GLOBALS['wpdb']->ent[$id]['activation_hash']!==$token && !str_contains(json_encode($GLOBALS['wpdb']->ent),$token));
check('commercial mode unchanged',get_option('nenotv_entitlement_mode')==='shadow');
check('review service scoped',private_call('review_access_allowed')===true);
check('repeat provisioning cannot rotate silently',provision()->status===409);
check('wrong code cannot grant',app('redeem',token:'NENO-AAAA-BBBB-CCCC-DDDD')->data['error']==='pro_not_live');
check('review claim shortcut denied',app('claim',token:$token)->data['error']==='invalid_activation');
$r=app('redeem',token:$token);
check('review redeem gives normal Pro',$r->data['entitlement']['level']==='pro'&&$r->data['entitlement']['status']==='active');
check('review payload has no expiry',$r->data['entitlement']['expires_at_ms']===0);
check('review code reusable second device',app('redeem','device-2',str_repeat('E',40),$token)->data['ok']===true);
check('review refresh succeeds',app('refresh')->data['entitlement']['level']==='pro');
check('wrong device key cannot refresh',app('refresh',key:str_repeat('X',40))->data['entitlement']['level']==='free');
check('wrong key cannot redeem existing device',app('redeem',key:str_repeat('X',40),token:$token)->data['error']==='device_key_mismatch');
check('unknown device remains free',app('refresh','stranger')->data['entitlement']['level']==='free');
check('review trials remain blocked',app('trial','stranger')->data['error']==='pro_not_live');
check('tokens absent from event logs',!str_contains(json_encode($GLOBALS['wpdb']->events),$token));
check('portal returns dedicated entitlement',private_call('current_user_entitlement')['id']===$id);
$review=$GLOBALS['wpdb']->ent[$id];
check('review functions available',private_call('app_service_live',$review)===true);
$normal=$review; $normal['id']=42; $normal['source']='woocommerce'; $normal['source_ref']='order:1'; $normal['email']='other@example.invalid'; $normal['email_hash']=hash('sha256',$normal['email']);
$GLOBALS['wpdb']->ent[42]=$normal;
check('unrelated entitlement not enabled',private_call('app_service_live',$normal)===false);
$scope=hash('sha256',$review['email']);
$params=['device_id'=>'device-1','device_key'=>str_repeat('D',40),'account_scope'=>$scope];
$access=NenoTV_Entitlement_Core::catalog_request(new WP_REST_Request($params),'access');
check('catalog access uses actual review binding',$access->data['entitlement']['level']==='pro');
$params['account_scope']=hash('sha256','other@example.invalid');
check('other account scope denied',NenoTV_Entitlement_Core::catalog_request(new WP_REST_Request($params),'access')->status===409);
$params['account_scope']=$scope; $params['device_key']=str_repeat('X',40);
check('catalog bad key denied',NenoTV_Entitlement_Core::catalog_request(new WP_REST_Request($params),'access')->status===403);
$params['device_key']=str_repeat('D',40);
check('sources require same account binding',NenoTV_Entitlement_Core::app_sources(new WP_REST_Request(array_replace($params,['account_scope'=>'wrong'])),'pull')->status===409);
check('review can read its own empty source vault',NenoTV_Entitlement_Core::app_sources(new WP_REST_Request($params),'pull')->data===['ok'=>true,'revision'=>0,'sources'=>[]]);
check('unlinked device cannot use source vault',NenoTV_Entitlement_Core::app_sources(new WP_REST_Request(array_replace($params,['device_id'=>'stranger'])),'pull')->status===403);
$GLOBALS['wpdb']->dev[50]=['id'=>50,'device_id'=>'other-device','device_key_hash'=>private_call('key_hash',str_repeat('O',40)),'status'=>'active','entitlement_id'=>42];
$other_params=['device_id'=>'other-device','device_key'=>str_repeat('O',40),'account_scope'=>hash('sha256',$normal['email'])];
check('other active licence cannot use catalog in shadow',NenoTV_Entitlement_Core::catalog_request(new WP_REST_Request($other_params),'access')->data['error']==='pro_not_live');
check('other active licence cannot use sources in shadow',NenoTV_Entitlement_Core::app_sources(new WP_REST_Request($other_params),'pull')->data['error']==='pro_not_live');
$pair=NenoTV_Entitlement_Core::pairing_request(new WP_REST_Request(['device_id'=>'pair-device','device_key'=>str_repeat('Z',40)]),'start');
check('normal pairing entrypoint available',$pair->data['ok']===true);
check('pairing session alone gives no entitlement',app('refresh','pair-device',str_repeat('Z',40))->data['entitlement']['level']==='free');
$GLOBALS['wpdb']->ent[$id]['max_devices']=2;
check('device cap enforced',app('redeem','device-3',str_repeat('F',40),$token)->data['error']==='device_limit');
check('existing device can refresh at cap',app('refresh')->data['entitlement']['level']==='pro');
$GLOBALS['wpdb']->ent[$id]['max_devices']=25;
$GLOBALS['users'][17]->roles=['administrator'];
check('role escalation disables review',app('refresh')->data['entitlement']['level']==='free');
$GLOBALS['users'][17]->roles=['customer'];
$GLOBALS['users'][17]->user_email='changed@example.invalid';
check('changed account email disables review',app('refresh')->data['entitlement']['level']==='free');
$GLOBALS['users'][17]->user_email=$review['email'];
$GLOBALS['wpdb']->ent[$id]['status']='revoked';
check('revoked entitlement disables review',app('refresh')->data['entitlement']['level']==='free');
$GLOBALS['wpdb']->ent[$id]['status']='active';
$GLOBALS['options']['nenotv_entitlement_mode']='live';
check('review works in live without special payload',app('refresh')->data['entitlement']['level']==='pro');
check('normal service not blocked in live',private_call('app_service_live',$normal)===true);
$GLOBALS['options']['sunnyiptv_review_access']['enabled']=false;
check('disabled review cannot use live fallback',app('refresh')->data['entitlement']['level']==='free');
check('disabled review cannot redeem in live',app('redeem',token:$token)->data['error']==='invalid_activation');
check('disabled review cannot claim in live',app('claim',token:$token)->data['error']==='invalid_activation');
check('disabled review cannot use source services in live',private_call('app_service_live',$review)===false);
$normal_token='NENO-ABCD-EFGH-JKLM-NPQR';
$GLOBALS['wpdb']->ent[42]['activation_hash']=private_call('key_hash',$normal_token);
$GLOBALS['wpdb']->ent[42]['activation_expires_at']=gmdate('Y-m-d H:i:s',time()+86400);
check('ordinary purchase activation still works',app('redeem','paying-device',str_repeat('P',40),$normal_token)->data['ok']===true);
check('ordinary code remains one use',app('redeem','paying-device-2',str_repeat('Q',40),$normal_token)->data['error']==='invalid_activation');
check('normal device refresh still works',app('refresh','paying-device',str_repeat('P',40))->data['entitlement']['level']==='pro');
$GLOBALS['options']['sunnyiptv_review_access']['enabled']=true;
$before=$GLOBALS['wpdb']->ent[42];
$revoke=NenoTV_Entitlement_Core::review_admin(new WP_REST_Request(),'revoke');
check('revoke succeeds',$revoke->data['ok']===true);
check('revoke preserves other entitlement',$GLOBALS['wpdb']->ent[42]===$before);
check('revoke clears only review activation',$GLOBALS['wpdb']->ent[$id]['activation_hash']==='');
check('revoke prevents existing device refresh',app('refresh')->data['entitlement']['level']==='free');
check('review config remains disabled',get_option('sunnyiptv_review_access')['enabled']===false);
reset_fixture(); $GLOBALS['wpdb']->insert_fail=true;
check('database insert failure fails closed',provision()->data['error']==='review_write_failed'&&!private_call('review_access_allowed'));
reset_fixture(); $GLOBALS['option_fail']=true;
check('option failure fails closed',provision()->data['error']==='review_config_failed'&&!private_call('review_access_allowed'));
check('partial provision marked revoked',array_values($GLOBALS['wpdb']->ent)[0]['status']==='revoked');
reset_fixture(); $GLOBALS['wpdb']->lookup_fail=true;
check('account lookup failure fails closed',provision()->data['error']==='review_lookup_failed'&&count($GLOBALS['wpdb']->ent)===0);
reset_fixture();
$GLOBALS['wpdb']->ent[1]=['id'=>1,'email_hash'=>hash('sha256','review@example.invalid')];
check('existing customer licence not overwritten',provision()->data['error']==='existing_account_access');
NenoTV_Entitlement_Core::review_routes();
check('admin routes registered',count($GLOBALS['routes'])===2);
$GLOBALS['admin']=false;
check('route permission denies customer',($GLOBALS['routes'][0][2]['permission_callback'])()===false);
NenoTV_Entitlement_Core::review_meta();
check('customer cannot set reviewer marker',($GLOBALS['meta_registration'][2]['auth_callback'])()===false);
$GLOBALS['admin']=true;
check('administrator can set explicit reviewer marker',($GLOBALS['meta_registration'][2]['auth_callback'])()===true);
reset_fixture();
$freeParams=['device_id'=>'base-customer-device','device_key'=>str_repeat('X',43),'pairing_version'=>3,'device_name'=>'Light test'];
$freeStart=NenoTV_Entitlement_Core::pairing_request(new WP_REST_Request($freeParams),'start');
check('real core permits base account start in shadow',$freeStart->status===200);
$freeCode=$freeStart->data['code'];$session=$GLOBALS['options']['nenotv_pair_'.$freeCode];
$session['state']='approved';$session['account_user_id']=17;update_option('nenotv_pair_'.$freeCode,$session,false);
$freePoll=array_merge($freeParams,['code'=>$freeCode,'poll_token'=>$freeStart->data['poll_token']]);
$freeDone=NenoTV_Entitlement_Core::pairing_request(new WP_REST_Request($freePoll),'status');
check('real core base account completes without licence',$freeDone->status===200&&$freeDone->data['entitlement']['level']==='free');
check('real core free linking creates no paid rows',$GLOBALS['wpdb']->ent===[]&&$GLOBALS['wpdb']->dev===[]);
check('real core base refresh works',NenoTV_Entitlement_Core::free_account_status(new WP_REST_Request($freeParams))->status===200);
check('real core ordinary entitlement remains free',app('refresh',$freeParams['device_id'],$freeParams['device_key'])->data['entitlement']['level']==='free');
check('real core source vault remains blocked',NenoTV_Entitlement_Core::app_sources(new WP_REST_Request(array_merge($freeParams,['account_scope'=>'fake'])),'pull')->status!==200);
$report=json_encode(['status'=>'passed','checks'=>count($checks),'cases'=>$checks,'environment'=>'isolated real candidate class; database and WordPress test doubles; no network','live_deployed'=>false,'account_created'=>false,'google_credentials_shared'=>false],JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES)."\n";
if (isset($argv[1])) {
    if (file_put_contents($argv[1],$report)===false) throw new RuntimeException('Could not write report');
}
echo $report;
