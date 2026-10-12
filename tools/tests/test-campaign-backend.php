<?php
define('ABSPATH', __DIR__);
define('DAY_IN_SECONDS', 86400);
$options=[]; $allowed=true; $failSave=false; $checks=0; $events=[];
function get_option($key,$default=false){global $options;return $options[$key]??$default;}
function update_option($key,$value,...$rest){global $options,$failSave;if($failSave)return false;$options[$key]=$value;return true;}
function current_user_can($cap){global $allowed;return $allowed;}
function wp_cache_delete(...$args){return true;}
function get_current_user_id(){return 99;}
function check($v,$message){global $checks;if(!$v)throw new RuntimeException($message);$checks++;}
class WP_REST_Request {public function __construct(public array $body){}public function get_json_params(){return $this->body;}}
class WP_REST_Response {public function __construct(public array $data,public int $status){}}
class TestDb {
    public string $prefix='fixture_';public array $rows=[];public bool $locked=false;public bool $failWrite=false;
    public function prepare($sql,...$args){return [$sql,$args];}
    public function get_var($q){return $this->locked?0:1;}
    public function update($table,$values,$where){if($this->failWrite)return false;$id=$where['id'];$r=$this->rows[$id]??[];foreach($where as $k=>$v)if(($r[$k]??null)!==$v)return 0;$this->rows[$id]=array_replace($r,$values);return 1;}
}
require __DIR__.'/../../server/sunnyiptv-test-campaign.php';
class Fixture {
    use SunnyIPTV_Test_Campaign;
    public static string $mode='live';public static array $records=[];public static array $devices=[];
    private static function mode(){return self::$mode;}
    private static function json($p,$s){return new WP_REST_Response($p,$s);}
    private static function free_device($p){return self::$records[$p['device_id']]??null;}
    private static function key_hash($key){return hash('sha256',$key);}
    private static function free_customer($id){return $id>0;}
    private static function free_device_key($id){return $id;}
    private static function free_links($id){$r=[];foreach(self::$records as $d=>$v)if($v['user_id']===$id)$r[$d]=true;return $r;}
    private static function find_device($id){return self::$devices[$id]??null;}
    private static function find_by_id($id){global $wpdb;return $wpdb->rows[$id]??null;}
    private static function entitlement_is_active($r){return $r['status']==='active'&&(!$r['expires_at']||strtotime($r['expires_at'].' UTC')>time());}
    private static function is_trial($r){return $r['level']==='pro_trial'||$r['plan']==='trial';}
    private static function ent_table(){return 'entitlements';}
    private static function now_mysql(){return gmdate('Y-m-d H:i:s');}
    private static function log_event(...$args){global $events;$events[]=$args;}
    public static function refresh($p){self::test_campaign_refresh($p);}
    public static function access($r){return self::test_campaign_access($r);}
}
$wpdb=new TestDb();$key=str_repeat('k',43);
function device($uid,$id=null){global $wpdb,$key;$id=$id??$uid;$d='device-'.$id;Fixture::$records[$d]=['user_id'=>$uid,'key_hash'=>hash('sha256',$key),'trial_consent'=>true];Fixture::$devices[$d]=['status'=>'active','device_key_hash'=>hash('sha256',$key),'entitlement_id'=>$uid];$wpdb->rows[$uid]??=['id'=>$uid,'source'=>'trial','source_ref'=>'trial-account:'.$uid,'level'=>'pro_trial','plan'=>'trial','status'=>'active','expires_at'=>gmdate('Y-m-d H:i:s',time()+14*86400)];return ['device_id'=>$d,'device_key'=>$key];}
function command($action,$verify=false){return Fixture::test_campaign_admin(new WP_REST_Request(['action'=>$action,'google_approved'=>$verify]));}
$p=device(1);Fixture::refresh($p);check(Fixture::test_campaign_status()['enrolled']===0,'Inactive campaign enrolled customer');
$allowed=false;check(command('start')->status===403,'Read-only admin obtained write access');$allowed=true;
Fixture::$mode='shadow';check(!command('start')->data['ok'],'Shadow granted tests');Fixture::$mode='live';
$failSave=true;check(!command('start')->data['ok'],'Failed save reported success');$failSave=false;
check(command('start')->data['ok'],'Start failed');
$wpdb->locked=true;Fixture::refresh($p);check(Fixture::test_campaign_status()['enrolled']===0,'Lock bypassed');$wpdb->locked=false;
Fixture::refresh(['device_id'=>$p['device_id'],'device_key'=>'wrong']);check(Fixture::test_campaign_status()['enrolled']===0,'Wrong device key enrolled');
Fixture::$records[$p['device_id']]['trial_consent']=false;Fixture::refresh($p);check(Fixture::test_campaign_status()['enrolled']===0,'Missing consent enrolled');Fixture::$records[$p['device_id']]['trial_consent']=true;
$failSave=true;Fixture::refresh($p);check($wpdb->rows[1]['expires_at']!==null,'Failed reservation extended rights');$failSave=false;
for($uid=1;$uid<=26;$uid++){Fixture::refresh(device($uid));check(Fixture::test_campaign_status()['enrolled']===min($uid,25),'Cap violated');}
check($wpdb->rows[26]['expires_at']!==null&&!Fixture::access($wpdb->rows[26]),'26th customer got tester rights');
check(Fixture::access($wpdb->rows[1])&&$wpdb->rows[1]['expires_at']===null,'Test does not last through campaign');
Fixture::refresh(device(1,100));check(Fixture::test_campaign_status()['enrolled']===25,'Second device consumed another slot');
check(!command('approve')->data['ok'],'Approval lacked explicit confirmation');
check(!command('approve','true')->data['ok'],'String approval accepted');
$wpdb->rows[2]['status']='revoked';$wpdb->rows[3]['level']='pro';$wpdb->rows[3]['plan']='lifetime';$wpdb->rows[3]['expires_at']=null;
check(command('approve',true)->data['ok'],'Approval failed');
$start=$options['sunnyiptv_test_campaign_v1']['approved_at'];
check($wpdb->rows[1]['level']==='pro'&&$wpdb->rows[1]['plan']==='annual','Tester not converted');
check(strtotime($wpdb->rows[1]['expires_at'].' UTC')-$start===365*86400,'Reward not exactly 365 days');
check($wpdb->rows[2]['status']==='revoked','Revoked test resurrected');check($wpdb->rows[3]['plan']==='lifetime'&&$wpdb->rows[3]['expires_at']===null,'Paid rights overwritten');
check(!Fixture::access($wpdb->rows[1]),'Pro still labeled Test');$snapshot=$wpdb->rows;
command('approve',true);command('start');Fixture::refresh(device(27));check($wpdb->rows[1]===$snapshot[1],'Repeated approval renewed year');check(Fixture::test_campaign_status()['enrolled']===25,'Approved campaign reopened');
echo "Test campaign checks: $checks passed\n";
