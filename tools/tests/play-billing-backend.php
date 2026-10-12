<?php
define('ABSPATH', __DIR__.'/');
define('ARRAY_A','ARRAY_A');
define('MINUTE_IN_SECONDS',60);
define('HOUR_IN_SECONDS',3600);
define('DAY_IN_SECONDS',86400);
$checks=0; $options=['sunnyiptv_play_billing'=>'1','fixture_auth'=>['refresh_enc'=>'fixture']];
$transients=[]; $jobs=[]; $events=[]; $google=[]; $requests=[]; $ackFailures=0; $networkFailure=false; $optionFailure=false; $locked=false; $revokeDuringLookup=false;
function check($ok,$message) { global $checks; if (!$ok) throw new RuntimeException($message); $checks++; }
function add_action(...$args) {} function add_filter(...$args) {}
function get_option($key,$default=false) { global $options; return $options[$key]??$default; }
function update_option($key,$value,...$args) { global $options,$optionFailure; if($optionFailure)return false; $options[$key]=$value; return true; }
function add_option($key,$value,...$args) { global $options,$optionFailure; if($optionFailure||array_key_exists($key,$options))return false; $options[$key]=$value; return true; }
function delete_option($key) { global $options; unset($options[$key]); return true; }
function get_transient($key) { global $transients; return ($transients[$key]['expires']??0)>time()?$transients[$key]['value']:false; }
function set_transient($key,$value,$ttl) { global $transients; $transients[$key]=['value'=>$value,'expires'=>time()+$ttl]; return true; }
function wp_next_scheduled($hook,$args=[]) { global $jobs; return $jobs[$hook.':'.json_encode($args)]['when']??false; }
function wp_schedule_single_event($when,$hook,$args=[]) { global $jobs; $jobs[$hook.':'.json_encode($args)]=compact('when','hook','args'); return true; }
function wp_schedule_event($when,$schedule,$hook) { return wp_schedule_single_event($when,$hook); }
if (($argv[1]??'')==='action-scheduler') {
    function as_has_scheduled_action($hook,$args,$group) { return (bool)wp_next_scheduled($hook,$args); }
    function as_schedule_single_action($when,$hook,$args,$group,$unique) { return wp_schedule_single_event($when,$hook,$args); }
}
function wp_salt($kind) { return 'fixture-salt-only'; }
function sanitize_key($value) { return preg_replace('/[^a-z0-9_\-]/','',strtolower($value)); }
function is_wp_error($r) { return isset($r['fixture_error']); }
function wp_remote_retrieve_response_code($r) { return $r['code']; }
function wp_remote_retrieve_body($r) { return json_encode($r['data']); }
function wp_remote_request($url,$args) {
    global $google,$requests,$ackFailures,$networkFailure,$revokeDuringLookup,$wpdb;
    $requests[]=['url'=>$url,'method'=>$args['method']];
    if($networkFailure)return ['fixture_error'=>true];
    preg_match('~/tokens/([^/:]+)~',$url,$m); $token=rawurldecode($m[1]??'');
    if(!isset($google[$token]))return ['code'=>404,'data'=>[]];
    if ($revokeDuringLookup && $args['method']==='GET') { $wpdb->rows[1]['status']='revoked'; $revokeDuringLookup=false; }
    if($args['method']==='POST') {
        if($ackFailures>0){$ackFailures--;return ['code'=>503,'data'=>[]];}
        if(isset($google[$token]['subscriptionState']))$google[$token]['acknowledgementState']='ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED';
        else $google[$token]['acknowledgementState']=1;
        return ['code'=>200,'data'=>[]];
    }
    return ['code'=>200,'data'=>$google[$token]];
}
function get_userdata($id) { return (object)['user_email'=>'fixture@example.invalid']; }
class WP_REST_Request {
    public function __construct(public array $data) {}
    public function get_body() { return json_encode($this->data); }
    public function get_json_params() { return $this->data; }
}
class WP_REST_Response { public function __construct(public array $data,public int $status) {} }
class NenoTV_Release_Notifications {
    const AUTH='fixture_auth';
    public static function client() { return []; }
    public static function token($data) { return ['access_token'=>'fixture-access']; }
}
class NenoTV_Tester_OAuth { public static function decrypt($value) { return 'fixture-refresh'; } }
class PlayDb {
    public string $options='fixture_options';
    public array $rows=[];
    public int $insert_id=0;
    public bool $writeFailure=false;
    public function prepare($sql,...$args) { return [$sql,$args]; }
    public function esc_like($s) { return addcslashes($s,'_%\\'); }
    public function insert($table,$values) { if($this->writeFailure)return false; $values['id']=++$this->insert_id; $this->rows[$this->insert_id]=$values; return 1; }
    public function update($table,$values,$where) {
        if($this->writeFailure)return false;
        if($table==='fixture_entitlements') {
            $id=$where['id'];if(!isset($this->rows[$id]))return 0;
            foreach ($where as $key=>$value) if (($this->rows[$id][$key]??null)!==$value) return 0;
            $this->rows[$id]=array_replace($this->rows[$id],$values);
        }
        return 1;
    }
    public function get_col($query) {
        global $options;
        [$sql,$args]=$query;
        if(str_contains($sql,'option_name')) {
            $keys=array_values(array_filter(array_keys($options),fn($key)=>str_starts_with($key,'sunnyiptv_play_ack_pending_')&&strcmp($key,$args[1])>0));
            sort($keys,SORT_STRING);return array_slice($keys,0,100);
        }
        return array_slice(array_keys(array_filter($this->rows,fn($r)=>$r['source']==='play'&&in_array($r['status'],['active','expired'],true)&&$r['id']>$args[0])),0,50);
    }
}
require __DIR__.'/../../server/sunnyiptv-play-billing.php';
class PlayFixture {
    use SunnyIPTV_Play_Billing;
    public static array $devices=[];
    public static string $mode='live';
    public static bool $bindFailure=false;
    private static function mode() { return self::$mode; }
    private static function trial_days() { return 14; }
    private static function free_lock($id) { global $locked; if($locked)return false; $locked=true; return true; }
    private static function free_unlock($id) { global $locked; $locked=false; }
    private static function json($data,$status) { return new WP_REST_Response($data,$status); }
    private static function pairing_rate(...$args) { return true; }
    private static function clean_app_payload($r) { return $r->data; }
    private static function ent_table() { return 'fixture_entitlements'; }
    private static function dev_table() { return 'fixture_devices'; }
    private static function now_mysql() { return gmdate('Y-m-d H:i:s'); }
    private static function normalize_email($email) { return $email; }
    private static function email_hash($email) { return hash('sha256',$email); }
    private static function normalize_language($language) { return $language; }
    private static function ref() { return 'fixture-reference'; }
    private static function key_hash($key) { return hash('sha256',$key); }
    public static array $freeRecords=[];
    private static function free_device($p) { return self::$freeRecords[$p['device_id']]??null; }
    private static function free_customer($id) { return $id===7; }
    private static function free_device_key($id) { return hash('sha256',$id); }
    private static function free_links($id) { $links=[];foreach(self::$freeRecords as $device=>$record)if($record['user_id']===$id)$links[self::free_device_key($device)]=true;return $links; }
    private static function find_trial_by_email($email) { global $wpdb;foreach($wpdb->rows as $row)if($row['source']==='trial'&&$row['email']===$email)return $row;return null; }
    private static function find_device($id) { return self::$devices[$id]??null; }
    private static function find_by_id($id) { global $wpdb; return $wpdb->rows[$id]??null; }
    private static function find_by_source($source,$ref) { global $wpdb; foreach($wpdb->rows as $r)if($r['source']===$source&&$r['source_ref']===$ref)return $r; return null; }
    private static function bind_device($ent,$p) {
        if(self::$bindFailure)return ['ok'=>false,'error'=>'device_limit'];
        self::$devices[$p['device_id']]=['entitlement_id'=>$ent['id'],'status'=>'active','device_key_hash'=>self::key_hash($p['device_key'])];
        return ['ok'=>true];
    }
    private static function entitlement_is_active($r) { return $r['status']==='active'&&(!$r['expires_at']||strtotime($r['expires_at'].' UTC')>time()); }
    private static function pro_payload($ent) { return ['level'=>'pro','status'=>$ent['status']]; }
    private static function log_event($type,$reference,$ref,$outcome,$message) { global $events; $events[]=compact('type','reference','ref','outcome','message'); }
    private static function app_entitlement($r,$action,$direct) { global $wpdb; $d=self::find_device($r->data['device_id']); $e=$d?self::find_by_id($d['entitlement_id']):null; return self::json(['ok'=>true,'level'=>$e&&self::entitlement_is_active($e)?'pro':'free'],200); }
}
$wpdb=new PlayDb();
$key=str_repeat('k',43);
$options['sunnyiptv_google_play_only']='1';
$offer=PlayFixture::app_entitlement_direct(new WP_REST_Request(['device_id'=>'fixture-device','device_key'=>$key]),'offer');
check($offer->data['google_play_test']===true,'Existing live Google Play testing flag preserved');
function purchase($token,$product='sunnyiptv_pro_solo',$device='fixture-device') {
    global $key;
    return PlayFixture::app_entitlement_direct(new WP_REST_Request(['device_id'=>$device,'device_key'=>$key,'purchase_token'=>$token,'product_id'=>$product]),'play');
}
function subscription($ack=false,$state='SUBSCRIPTION_STATE_ACTIVE') {
    return ['subscriptionState'=>$state,'lineItems'=>[['productId'=>'sunnyiptv_pro_solo','expiryTime'=>gmdate('c',time()+30*DAY_IN_SECONDS)]],'acknowledgementState'=>$ack?'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED':'ACKNOWLEDGEMENT_STATE_PENDING'];
}
function retryNow($id) { global $options,$jobs; $options['sunnyiptv_play_ack_pending_'.$id]['next']=time()-1; unset($jobs['sunnyiptv_play_ack_retry:'.json_encode([$id])]); PlayFixture::play_ack_retry($id); }
function checkNow($id) { global $transients; unset($transients['sunnyiptv_play_checked_'.$id]); PlayFixture::play_check($id); }

$google['fixture-purchase']=subscription(); $ackFailures=2;
$result=purchase('fixture-purchase');
check($result->status===200&&$result->data['confirmation_pending'],'Verified purchase stays usable with durable confirmation pending');
check($options['sunnyiptv_play_ack_pending_1']['attempts']===1,'Initial ack failure persisted');
check(isset($jobs['sunnyiptv_play_ack_retry:[1]']),'Server retry scheduled without app');
check(!str_contains($options['sunnyiptv_play_token_1'],'fixture-purchase'),'Purchase token encrypted at rest');
check(!str_contains(json_encode($jobs),'fixture-purchase'),'No purchase token in job arguments');
check(!str_contains(json_encode($events),'fixture-purchase'),'No purchase token in audit log');
$before=count($requests); PlayFixture::play_ack_retry(1);
check(count($requests)===$before,'Future retry does not send premature request');
retryNow(1);
check($options['sunnyiptv_play_ack_pending_1']['attempts']===2,'Repeated outage retains retry');
check($options['sunnyiptv_play_ack_pending_1']['next']>=time()+119,'Retry backs off');
retryNow(1);
check(!isset($options['sunnyiptv_play_ack_pending_1']),'Successful retry removes only pending confirmation');
check($google['fixture-purchase']['acknowledgementState']==='ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED','Google delivery confirmed');
check(count($wpdb->rows)===1&&$wpdb->rows[1]['status']==='active','Retry neither duplicates nor deletes entitlement');
$before=count($requests);PlayFixture::play_ack_retry(1);check(count($requests)===$before,'Completed ack retry idempotent');
$restored=purchase('fixture-purchase');check(!$restored->data['confirmation_pending']&&count($wpdb->rows)===1,'Restore reuses acknowledged entitlement');

$networkFailure=true; $old=$wpdb->rows[1];checkNow(1);
check($wpdb->rows[1]===$old,'Network failure preserves access and expiry');
check($transients['sunnyiptv_play_checked_1']['expires']<=time()+60,'Outage uses short recheck throttle');
$networkFailure=false;$google['fixture-purchase']['subscriptionState']='SUBSCRIPTION_STATE_EXPIRED';checkNow(1);
check($wpdb->rows[1]['status']==='expired','Revoked subscription stops access before stored end date');
$google['fixture-purchase']=subscription(true,'SUBSCRIPTION_STATE_CANCELED');checkNow(1);
check($wpdb->rows[1]['status']==='active','Cancellation keeps paid period usable');
$google['fixture-purchase']=subscription(true,'SUBSCRIPTION_STATE_IN_GRACE_PERIOD');checkNow(1);
check($wpdb->rows[1]['status']==='active','Grace period keeps access');
$google['fixture-purchase']=subscription(true,'SUBSCRIPTION_STATE_ON_HOLD');checkNow(1);
check($wpdb->rows[1]['status']==='expired','Payment hold stops Pro access');
$google['fixture-purchase']=subscription(true);checkNow(1);
check($wpdb->rows[1]['status']==='active','Recovered payment restores access');
$google['fixture-purchase']['lineItems'][0]['expiryTime']=gmdate('c',time()+60*DAY_IN_SECONDS);checkNow(1);
check(strtotime($wpdb->rows[1]['expires_at'].' UTC')>time()+59*DAY_IN_SECONDS,'Renewal extends server entitlement');
$before=count($requests);PlayFixture::play_check(1);check(count($requests)===$before,'Successful checks throttled');
$wrong=PlayFixture::app_entitlement_direct(new WP_REST_Request(['device_id'=>'fixture-device','device_key'=>str_repeat('z',43)]),'refresh');
check(count($requests)===$before,'Wrong device key cannot trigger Google request');

$google['fixture-lifetime']=['purchaseState'=>0,'acknowledgementState'=>0];
$lifetime=purchase('fixture-lifetime','sunnyiptv_pro_solo_lifetime','lifetime-device');
check($lifetime->status===200&&!$lifetime->data['confirmation_pending'],'Lifetime purchase acknowledged');
check($wpdb->rows[2]['expires_at']===null,'Lifetime expiry preserved as null');
$google['fixture-lifetime']['purchaseState']=1;checkNow(2);
check($wpdb->rows[2]['status']==='revoked','Refunded lifetime purchase loses access');
check(isset(PlayFixture::$devices['lifetime-device']),'Refund keeps linked device and customer data');
$before=count($requests);$wpdb->rows[2]['status']='revoked';checkNow(2);
check(count($requests)===$before&&$wpdb->rows[2]['status']==='revoked','Explicit admin revocation not overwritten');

$google['fixture-unconfirmed']=subscription();$ackFailures=1;
purchase('fixture-unconfirmed','sunnyiptv_pro_solo','unconfirmed-device');
$google['fixture-unconfirmed']['subscriptionState']='SUBSCRIPTION_STATE_EXPIRED';retryNow(3);
check(!isset($options['sunnyiptv_play_ack_pending_3'])&&$wpdb->rows[3]['status']==='expired','Inactive pending purchase not acknowledged');
$google['fixture-unconfirmed']['subscriptionState']='SUBSCRIPTION_STATE_ACTIVE';$wpdb->rows[3]['status']='active';checkNow(3);
check(isset($options['sunnyiptv_play_ack_pending_3']),'Sweep recovers historical unacknowledged purchase');
$wpdb->writeFailure=true;$google['fixture-unconfirmed']['subscriptionState']='SUBSCRIPTION_STATE_EXPIRED';$old=$wpdb->rows[3];retryNow(3);
check(isset($options['sunnyiptv_play_ack_pending_3'])&&$wpdb->rows[3]===$old,'Failed state write retains recovery job');
$wpdb->writeFailure=false;
$locked=true;$before=count($requests);PlayFixture::play_ack_retry(3);check(count($requests)===$before,'Concurrent worker yields to lock');$locked=false;

$google['fixture-storage']=subscription();$optionFailure=true;
$failed=purchase('fixture-storage','sunnyiptv_pro_solo','storage-device');
check($failed->status===503&&$failed->data['error']==='purchase_storage_failed','Failed encrypted storage not reported as successful purchase');
$optionFailure=false;
$google['fixture-bind']=subscription();PlayFixture::$bindFailure=true;
$failed=purchase('fixture-bind','sunnyiptv_pro_solo','bind-device');
check(!$failed->data['ok']&&!isset($options['sunnyiptv_play_ack_pending_5']),'Failed delivery is not acknowledged');
PlayFixture::$bindFailure=false;

$jobs=[];PlayFixture::play_schedule_maintenance();PlayFixture::play_maintenance();
check(isset($jobs['sunnyiptv_play_maintenance:[]']),'Recurring server maintenance scheduled');
check(isset($jobs['sunnyiptv_play_ack_retry:[3]']),'Maintenance repairs lost ack job');
check(isset($jobs['sunnyiptv_play_check:[1]']),'Active purchases checked even with closed app');
check(!isset($jobs['sunnyiptv_play_check:[2]']),'Explicit revoked row not queued');
check(!str_contains(json_encode($jobs),'fixture-purchase'),'Maintenance jobs carry ids only');
for($i=100;$i<205;$i++)$options['sunnyiptv_play_ack_pending_'.$i]=['attempts'=>1,'next'=>time()-1];
$jobs=[];PlayFixture::play_maintenance();PlayFixture::play_maintenance();
for($i=100;$i<205;$i++)check(isset($jobs['sunnyiptv_play_ack_retry:'.json_encode([$i])]),'Paged acknowledgement scan must not starve later purchases');
$old=$wpdb->rows[1];PlayFixture::$mode='shadow';$before=count($requests);checkNow(1);
check(count($requests)===$before&&$wpdb->rows[1]===$old,'Commercial switch respected');
PlayFixture::$mode='live';$revokeDuringLookup=true;checkNow(1);
check($wpdb->rows[1]['status']==='revoked','Concurrent admin revocation survives in-flight Google response');
$wpdb=new PlayDb();PlayFixture::$devices=[];$options['sunnyiptv_auto_trial']='1';
$trial=new ReflectionMethod(PlayFixture::class,'auto_trial');$p=['device_id'=>'trial-device','device_key'=>$key];
PlayFixture::$freeRecords['trial-device']=['user_id'=>7,'key_hash'=>hash('sha256',$key)];
$trial->invoke(null,$p);check($wpdb->rows===[],'Signing in or linking must not silently start a trial');
PlayFixture::$freeRecords['trial-device']['trial_consent']=true;
$trial->invoke(null,array_merge($p,['device_key'=>str_repeat('x',43)]));check($wpdb->rows===[],'Trial consent cannot replace device proof');
$trial->invoke(null,$p);check(count($wpdb->rows)===1&&$wpdb->rows[1]['level']==='pro_trial','Explicit consent starts trial');
$trial->invoke(null,$p);check(count($wpdb->rows)===1,'Retry created a second trial');
$wpdb->rows[1]['expires_at']=gmdate('Y-m-d H:i:s',time()-60);$trial->invoke(null,$p);check(count($wpdb->rows)===1&&strtotime($wpdb->rows[1]['expires_at'].' UTC')<time(),'Expired trial restarted');
PlayFixture::$freeRecords['second-trial-device']=['user_id'=>7,'key_hash'=>hash('sha256',$key),'trial_consent'=>true];
$trial->invoke(null,['device_id'=>'second-trial-device','device_key'=>$key]);check(count($wpdb->rows)===1,'Another device renewed account trial');
echo "Play billing backend: $checks checks passed (".(($argv[1]??'')==='action-scheduler'?'Action Scheduler':'WP-Cron').")\n";
