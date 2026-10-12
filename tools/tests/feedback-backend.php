<?php
define('ABSPATH', __DIR__.'/');
define('ARRAY_A', 'ARRAY_A');
define('MINUTE_IN_SECONDS', 60);
define('HOUR_IN_SECONDS', 3600);
$routes = []; $administrator = false; $checks = 0;
function check($ok, $message) { global $checks; if (!$ok) throw new RuntimeException($message); $checks++; }
function add_action($hook,$callback,...$args) { if ($hook === 'rest_api_init') $callback(); }
function register_rest_route($ns,$path,$config) { global $routes; $routes[$ns.$path] = $config; }
function current_user_can($cap) { global $administrator; return $administrator; }
function sanitize_text_field($v) { return strip_tags($v); }
function sanitize_textarea_field($v) { return strip_tags($v); }
class WP_REST_Request {
    public function __construct(public array $payload) {}
    public function get_json_params() { return $this->payload; }
    public function get_param($key) { return $this->payload[$key] ?? null; }
    public function get_body() { return json_encode($this->payload); }
}
class WP_REST_Response {
    public function __construct(public array $data, public int $status, public array $headers=[]) {}
}
class NenoTV_Dashboard_API {
    public static function authorized_admin_app($r) { return ($r->payload['token']??'') === 'fixture-admin'; }
}
class FeedbackDb {
    public $prefix = 'fixture_', $insert_id = 0;
    public array $rows = [];
    public bool $fail = false;
    public function prepare($sql,...$args) { return [$sql,$args]; }
    public function get_var($q) {
        if (is_string($q)) return max(array_merge([0],array_column(array_filter($this->rows,fn($r)=>$r['kind']==='complaint'),'id')));
        [$sql,$args] = $q;
        if (str_contains($sql,'request_hash')) { foreach ($this->rows as $row) if ($row['request_hash']===$args[0]) return $row['id']; return 0; }
        if (str_contains($sql,'COUNT')) return count(array_filter($this->rows,fn($r)=>$r['kind']==='complaint' && $r['id']>$args[0]));
        throw new RuntimeException('Unexpected query');
    }
    public function insert($table,$values) {
        if ($this->fail) return false;
        $values['id'] = ++$this->insert_id; $this->rows[]=$values; return 1;
    }
    public function get_results($sql,$format) {
        $rows = array_reverse($this->rows);
        if (preg_match('/WHERE id < (\d+)/',$sql,$m)) $rows=array_values(array_filter($rows,fn($r)=>$r['id']<(int)$m[1]));
        return array_map(fn($r)=>array_diff_key($r,['request_hash'=>true]),array_slice($rows,0,51));
    }
}
// Model prepared numeric pagination queries while retaining bound values elsewhere.
class BoundFeedbackDb extends FeedbackDb {
    public function prepare($sql,...$args) { return str_starts_with($sql,'WHERE') ? sprintf($sql,...$args) : parent::prepare($sql,...$args); }
}
require __DIR__.'/../../server/sunnyiptv-feedback.php';
require __DIR__.'/../../server/sunnyiptv-admin-api.php';
class FeedbackFixture {
    use SunnyIPTV_Feedback, SunnyIPTV_Admin_API;
    const APP_NS = 'nenotv/v1';
    public static bool $linked=true,$paid=false,$allowRate=true;
    private static function json($data,$status) { return new WP_REST_Response($data,$status); }
    private static function pairing_rate(...$args) { return self::$allowRate; }
    private static function clean_app_payload($r) { return array_intersect_key($r->payload,array_flip(['device_id','device_key','app_version'])); }
    private static function key_hash($v) { return hash_hmac('sha256',$v,'fixture-salt'); }
    private static function free_device($p) { return self::$linked ? ['user_id'=>1,'key_hash'=>self::key_hash(str_repeat('k',32))] : null; }
    private static function free_customer($id) { return true; }
    private static function free_links($id) { return ['device'=>true]; }
    private static function free_device_key($id) { return $id; }
    private static function find_device($id) { return self::$paid ? ['status'=>'active','device_key_hash'=>self::key_hash(str_repeat('k',32))] : null; }
}
$wpdb = new BoundFeedbackDb();
FeedbackFixture::feedback_hooks();
check($routes['sunnyiptv-admin/v1/feedback']['permission_callback'] === [FeedbackFixture::class,'admin_api_allowed'],'Inbox must use existing admin authorization');
check(!FeedbackFixture::admin_api_allowed(new WP_REST_Request([])),'Anonymous inbox read rejected');
check(FeedbackFixture::admin_api_allowed(new WP_REST_Request(['token'=>'fixture-admin'])),'Paired admin accepted');
$body=['device_id'=>'device','device_key'=>str_repeat('k',32),'app_version'=>'0.14.46','kind'=>'complaint','message'=>'Playback stops','request_id'=>'00000000-0000-4000-8000-000000000001','android_version'=>'14','device_model'=>'Fixture phone'];
$send = fn($p)=>FeedbackFixture::feedback_submit(new WP_REST_Request($p));
check($send(array_replace($body,['device_key'=>'wrong']))->status===403,'Wrong key rejected');
FeedbackFixture::$linked=false;
check($send($body)->status===403,'Unlinked device rejected');
FeedbackFixture::$paid=true;
check($send($body)->status===201,'Paid linked device can complain');
FeedbackFixture::$paid=false; FeedbackFixture::$linked=true;
check($send($body)->data['id']===1 && count($wpdb->rows)===1,'Retry does not duplicate a message');
check($send(array_replace($body,['kind'=>'invalid']))->status===400,'Invalid kind rejected');
check($send(array_replace($body,['message'=>'x']))->status===400,'Empty/short message rejected');
check($send(array_replace($body,['message'=>str_repeat('x',8001)]))->status===400,'Oversized message rejected');
check($send(array_replace($body,['kind'=>[]]))->status===400,'Structured kind rejected');
check($send(array_replace($body,['message'=>str_repeat('x',20000)]))->status===413,'Oversized request rejected');
FeedbackFixture::$allowRate=false;
check($send($body)->status===429,'Rate limit enforced');
FeedbackFixture::$allowRate=true;
$wpdb->fail=true;
check($send(array_replace($body,['request_id'=>'00000000-0000-4000-8000-000000000002']))->status===503,'Failed storage is not acknowledged');
$wpdb->fail=false;
for ($i=2;$i<=55;$i++) check($send(array_replace($body,['request_id'=>sprintf('00000000-0000-4000-8000-%012d',$i),'kind'=>$i%2?'complaint':'tip']))->status===201,'Free linked feedback accepted');
$page=FeedbackFixture::feedback_list(new WP_REST_Request(['after'=>1]));
check(count($page->data['items'])===50 && $page->data['next_before']===6,'Pagination includes correct next cursor');
check($page->data['unread_complaints']===27 && $page->data['latest_complaint_id']===55,'Only unread complaints counted');
check(!isset($page->data['items'][0]['request_hash']),'Deduplication hash stays private');
$older=FeedbackFixture::feedback_list(new WP_REST_Request(['before'=>6,'after'=>55]));
check(count($older->data['items'])===5 && $older->data['next_before']===null && $older->data['unread_complaints']===0,'Older page and read watermark');
echo "Feedback backend: $checks checks passed\n";
