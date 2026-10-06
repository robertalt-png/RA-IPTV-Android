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

class BaseTestDB {
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
define('HOUR_IN_SECONDS', 3600);
class TestDB extends BaseTestDB {
    public function get_results($prepared,$type=null) {
        [$sql,$args]=$prepared;
        if (str_contains($sql,'JOIN')) {
            $out=[];
            foreach ($this->dev as $d) { $e=$this->ent[$d['entitlement_id']]??null; if ($e && $d['status']===$args[0] && ($e['email']??'')===$args[1] && in_array($e['source'],[$args[2],$args[3]],true)) $out[]=['device_id'=>$d['device_id'],'entitlement_id'=>$d['entitlement_id']]; }
            return $out;
        }
        throw new RuntimeException('Unmodelled get_results: '.$sql);
    }
    public function query($prepared) {
        [$sql,$args]=$prepared;
        if (str_starts_with($sql,'UPDATE') && str_contains($sql,'SET email=')) {
            $id=$args[3]; if (($this->ent[$id]['email']??null)!==$args[4]) return 0;
            $this->ent[$id]['email']=$args[0]; $this->ent[$id]['email_hash']=$args[1]; return 1;
        }
        return parent::query($prepared);
    }
    public function update($table,$values,$where,...$args) {
        if (isset($where['entitlement_id'])) { $n=0; foreach ($this->dev as $i=>$d) if ($d['entitlement_id']===$where['entitlement_id'] && $d['status']===$where['status']) { $this->dev[$i]=array_replace($d,$values); $n++; } return $n; }
        return parent::update($table,$values,$where,...$args);
    }
}
// Simulated Google Play: purchases keyed by token.
$GLOBALS['google']=[]; $GLOBALS['acks']=[]; $GLOBALS['google_calls']=0;
function wp_remote_request($url,$args) {
    $GLOBALS['google_calls']++;
    if (!str_contains($args['headers']['Authorization'],'FIXTURE-ACCESS')) return ['code'=>401,'body'=>'{}'];
    if (str_ends_with($url,':acknowledge')) { $GLOBALS['acks'][]=$url; return ['code'=>200,'body'=>'{}']; }
    foreach ($GLOBALS['google'] as $token=>$body) if (str_contains($url,'/tokens/'.rawurlencode($token))) return ['code'=>200,'body'=>json_encode($body)];
    return ['code'=>404,'body'=>'{}'];
}
function wp_remote_retrieve_response_code($r) { return $r['code']; }
function wp_remote_retrieve_body($r) { return $r['body']; }
class NenoTV_Tester_OAuth { const OPTION='fixture_client'; static function decrypt($v) { return 'refresh-'.$v; } }
class NenoTV_Release_Notifications {
    const AUTH='nenotv_release_oauth_credentials';
    static function token(array $body): array { return ['access_token'=>'FIXTURE-ACCESS']; }
    static function client(): array { return ['client_id'=>'x','client_secret'=>'y']; }
}
require __DIR__.'/../../../server/nenotv-entitlement-core.php';
$checks=[];
function check($name,$ok) { if (!$ok) throw new RuntimeException('FAILED: '.$name); $GLOBALS['checks'][]=$name; }
function fresh() {
    $GLOBALS['wpdb']=new TestDB();
    $GLOBALS['options']=['nenotv_entitlement_mode'=>'live','nenotv_trial_days'=>'14','sunnyiptv_auto_trial'=>'1','sunnyiptv_play_billing'=>'1',
        'nenotv_release_oauth_credentials'=>['refresh_enc'=>'enc']];
    $GLOBALS['transients']=[]; $GLOBALS['google']=[]; $GLOBALS['acks']=[];
    $GLOBALS['users']=[30=>(object)['roles'=>['customer'],'user_email'=>''],31=>(object)['roles'=>['customer'],'user_email'=>'b@example.invalid']];
    $GLOBALS['user_meta']=[]; $GLOBALS['option_fail']=false;
    (new ReflectionProperty(NenoTV_Entitlement_Core::class,'play_access_token'))->setValue(null,null);
}
function key_for($device) { return str_repeat(strtoupper(substr(md5($device),0,1)),40); }
function link_device($device,$uid) {
    $digest=hash_hmac('sha256',$device,wp_salt('auth'));
    $GLOBALS['options']['sunnyiptv_free_device_'.$digest]=['user_id'=>$uid,'key_hash'=>hash_hmac('sha256',key_for($device),wp_salt('auth'))];
    $links=$GLOBALS['user_meta'][$uid]['_sunnyiptv_free_devices']??[]; $links[$digest]=['name'=>$device,'linked_at'=>time()];
    $GLOBALS['user_meta'][$uid]['_sunnyiptv_free_devices']=$links;
}
function call($action,$device,array $extra=[]) {
    $r=new WP_REST_Request(['device_id'=>$device,'device_key'=>key_for($device),'platform'=>'android','app_version'=>'0.14.33']+$extra);
    return NenoTV_Entitlement_Core::app_entitlement_direct($r,$action)->data;
}
function sub($state,$expiresIn,$product='sunnyiptv_pro_solo',$ack=false,$linked='') {
    $x=['subscriptionState'=>$state,'acknowledgementState'=>$ack?'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED':'ACKNOWLEDGEMENT_STATE_PENDING','lineItems'=>[['productId'=>$product,'expiryTime'=>gmdate('Y-m-d\TH:i:s\Z',time()+$expiresIn)]]];
    if ($linked!=='') $x['linkedPurchaseToken']=$linked;
    return $x;
}

// Switches
fresh(); $GLOBALS['options']['nenotv_entitlement_mode']='shadow';
check('shadow: offer says no Play billing',call('offer','tv-1')['play_billing']===false);
check('shadow: no trial',call('refresh','tv-1')['entitlement']['level']==='free');
check('shadow: purchase refused',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'t1'])['error']==='play_billing_off');
fresh(); check('live: offer has Play billing and 14 days',call('offer','tv-1')['play_billing']===true && call('offer','tv-1')['trial_days']===14);
check('short device key refused',NenoTV_Entitlement_Core::app_entitlement_direct(new WP_REST_Request(['device_id'=>'x','device_key'=>'short']),'refresh')->status===400);

// Automatic trial
fresh();
check('unlinked device gets no trial',call('refresh','tv-1')['entitlement']['level']==='free' && !$GLOBALS['wpdb']->ent);
link_device('tv-1',30);
$r=call('refresh','tv-1');
check('linked device gets the trial',$r['entitlement']['level']==='pro_trial' && $r['entitlement']['status']==='trial_active');
$days=($r['entitlement']['expires_at_ms']/1000-time())/86400;
check('trial lasts 14 days',$days>13.9 && $days<=14.01);
check('second refresh keeps the same trial',call('refresh','tv-1')['entitlement']['level']==='pro_trial' && count($GLOBALS['wpdb']->ent)===1);
link_device('tv-2',30);
check('second device of the account gets no new trial',call('refresh','tv-2')['entitlement']['level']==='free' && count($GLOBALS['wpdb']->ent)===1);
link_device('tv-3',31);
check('other account gets its own trial',call('refresh','tv-3')['entitlement']['level']==='pro_trial' && count($GLOBALS['wpdb']->ent)===2);
$GLOBALS['wpdb']->ent[1]['expires_at']=gmdate('Y-m-d H:i:s',time()-60);
check('expired trial is reported, not renewed',call('refresh','tv-1')['entitlement']['status']==='trial_expired' && count($GLOBALS['wpdb']->ent)===2);
$GLOBALS['options']['sunnyiptv_auto_trial']='0'; link_device('tv-4',31);
check('trial switch off: no trial',call('refresh','tv-4')['entitlement']['level']==='free');

// Email added later to a quick account follows its trial
$GLOBALS['users'][30]->user_email='later@example.invalid';
NenoTV_Entitlement_Core::play_sync_account_email(30);
check('trial picks up the account email',$GLOBALS['wpdb']->ent[1]['email']==='later@example.invalid' && $GLOBALS['wpdb']->ent[1]['email_hash']===hash('sha256','later@example.invalid'));

// Google Play purchases
fresh();
$GLOBALS['options']['nenotv_release_oauth_credentials']=[];
check('Play not connected reported',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'t1'])['error']==='play_not_connected');
fresh();
check('unknown product refused',call('play','tv-1',['product_id'=>'other','purchase_token'=>'t1'])['error']==='invalid_purchase');
check('token with odd characters refused',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'a b'])['error']==='invalid_purchase');
check('unknown token refused',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'nope'])['error']==='play_purchase_unknown');
$GLOBALS['google']['expired']=sub('SUBSCRIPTION_STATE_EXPIRED',-60);
check('expired subscription refused',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'expired'])['error']==='play_purchase_inactive');
$GLOBALS['google']['other-product']=sub('SUBSCRIPTION_STATE_ACTIVE',86400,'sunnyiptv_pro_multi');
check('token of another product refused',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'other-product'])['error']==='play_product_mismatch');
$GLOBALS['google']['solo-1']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400);
$r=call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'solo-1']);
check('active subscription gives Pro',$r['ok']===true && $r['entitlement']['level']==='pro' && $r['entitlement']['max_devices']===1);
check('server acknowledged the purchase',count($GLOBALS['acks'])===1 && str_contains($GLOBALS['acks'][0],'/purchases/subscriptions/sunnyiptv_pro_solo/tokens/solo-1:acknowledge'));
check('purchase token never stored in plain text',!str_contains(json_encode($GLOBALS['options']),'solo-1') && !str_contains(json_encode($GLOBALS['wpdb']->ent),'solo-1'));
check('refresh keeps Pro',call('refresh','tv-1')['entitlement']['level']==='pro');
check('same purchase again is idempotent',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'solo-1'])['ok']===true && count($GLOBALS['wpdb']->ent)===1 && count($GLOBALS['acks'])===1+0+1);
check('Solo: second device refused',call('play','tv-2',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'solo-1'])['error']==='device_limit');
$GLOBALS['google']['multi-1']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400,'sunnyiptv_pro_multi',true);
check('Multi: first device',call('play','tv-5',['product_id'=>'sunnyiptv_pro_multi','purchase_token'=>'multi-1'])['entitlement']['max_devices']===5);
check('Multi: second device restores the same purchase',call('play','tv-6',['product_id'=>'sunnyiptv_pro_multi','purchase_token'=>'multi-1'])['ok']===true);
$GLOBALS['google']['life-1']=['purchaseState'=>0,'acknowledgementState'=>0,'purchaseType'=>0];
$r=call('play','tv-7',['product_id'=>'sunnyiptv_pro_solo_lifetime','purchase_token'=>'life-1']);
check('lifetime purchase gives Pro without end date',$r['ok']===true && $r['entitlement']['expires_at_ms']===0);
$life=null; foreach ($GLOBALS['wpdb']->ent as $e) if ($e['plan']==='lifetime') $life=$e;
check('test purchase marked as test',$life['payment_mode']==='test');
check('lifetime acknowledged via products API',str_contains(end($GLOBALS['acks']),'/purchases/products/sunnyiptv_pro_solo_lifetime/tokens/life-1:acknowledge'));
$GLOBALS['google']['life-cancelled']=['purchaseState'=>1,'acknowledgementState'=>1];
check('cancelled one-time purchase refused',call('play','tv-8',['product_id'=>'sunnyiptv_pro_multi_lifetime','purchase_token'=>'life-cancelled'])['error']==='play_purchase_inactive');

// Trial to paid on the same device
fresh(); link_device('tv-1',30);
check('trial first',call('refresh','tv-1')['entitlement']['level']==='pro_trial');
$GLOBALS['google']['paid']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400);
check('trial device can buy',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'paid'])['entitlement']['level']==='pro');
check('refresh shows the paid plan',call('refresh','tv-1')['entitlement']['level']==='pro');

// Expired subscription device can buy again
$GLOBALS['wpdb']->ent[2]['expires_at']=gmdate('Y-m-d H:i:s',time()-60); $GLOBALS['google']['paid']=sub('SUBSCRIPTION_STATE_EXPIRED',-60);
$GLOBALS['google']['again']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400);
check('device of an ended subscription can buy again',call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'again'])['entitlement']['level']==='pro');

// Renewal re-check
fresh();
$GLOBALS['google']['renew']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400);
call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'renew']);
$GLOBALS['wpdb']->ent[1]['expires_at']=gmdate('Y-m-d H:i:s',time()-60);
$calls=$GLOBALS['google_calls'];
check('renewed subscription found on refresh',call('refresh','tv-1')['entitlement']['level']==='pro');
check('renewal asked Google once',$GLOBALS['google_calls']===$calls+1);
$GLOBALS['wpdb']->ent[1]['expires_at']=gmdate('Y-m-d H:i:s',time()-60);
check('re-check waits 6 hours',call('refresh','tv-1')['entitlement']['level']==='free' && $GLOBALS['google_calls']===$calls+1);
$GLOBALS['transients']=[]; $GLOBALS['google']['renew']=sub('SUBSCRIPTION_STATE_EXPIRED',-60);
check('ended subscription becomes free',call('refresh','tv-1')['entitlement']['level']==='free' && $GLOBALS['wpdb']->ent[1]['status']==='expired');

// Upgrade replaces the previous subscription
fresh();
$GLOBALS['google']['solo-up']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400);
call('play','tv-1',['product_id'=>'sunnyiptv_pro_solo','purchase_token'=>'solo-up']);
$GLOBALS['google']['multi-up']=sub('SUBSCRIPTION_STATE_ACTIVE',365*86400,'sunnyiptv_pro_multi',false,'solo-up');
$r=call('play','tv-1',['product_id'=>'sunnyiptv_pro_multi','purchase_token'=>'multi-up']);
check('upgrade to Multi',$r['entitlement']['max_devices']===5 && $GLOBALS['wpdb']->ent[1]['status']==='replaced');

// Signed backend routes unchanged
check('backend route still needs the signature',NenoTV_Entitlement_Core::app_entitlement(new WP_REST_Request(['device_id'=>'tv-1','device_key'=>key_for('tv-1')]),'refresh')->status===401);

echo json_encode(['play_billing_checks'=>count($checks)],JSON_PRETTY_PRINT),"\n";
