<?php
if (!defined('ABSPATH')) exit;

trait SunnyIPTV_Feedback {
    private static function feedback_table(): string { global $wpdb; return $wpdb->prefix . 'sunnyiptv_feedback'; }

    public static function feedback_hooks(): void {
        add_action('init', [__CLASS__, 'feedback_install']);
        add_action('rest_api_init', static function () {
            register_rest_route(self::APP_NS, '/feedback', ['methods'=>'POST', 'permission_callback'=>'__return_true', 'callback'=>[__CLASS__, 'feedback_submit']]);
            register_rest_route('sunnyiptv-admin/v1', '/feedback', ['methods'=>'GET', 'permission_callback'=>[__CLASS__, 'admin_api_allowed'], 'callback'=>[__CLASS__, 'feedback_list']]);
        });
    }

    public static function feedback_install(): void {
        if (get_option('sunnyiptv_feedback_schema') === '1') return;
        global $wpdb;
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';
        $table = self::feedback_table();
        dbDelta("CREATE TABLE $table (
            id bigint unsigned NOT NULL AUTO_INCREMENT,
            request_hash char(64) NOT NULL,
            kind varchar(16) NOT NULL,
            message text NOT NULL,
            app_version varchar(64) NOT NULL,
            android_version varchar(32) NOT NULL,
            device_model varchar(120) NOT NULL,
            created_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY request_hash (request_hash),
            KEY kind_id (kind,id)
        ) {$wpdb->get_charset_collate()};");
        if ($wpdb->get_var($wpdb->prepare('SHOW TABLES LIKE %s', $wpdb->esc_like($table))) === $table) update_option('sunnyiptv_feedback_schema', '1', false);
    }

    public static function feedback_submit(WP_REST_Request $request): WP_REST_Response {
        global $wpdb;
        if (strlen($request->get_body()) > 16384) return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        if (!self::pairing_rate('feedback_ip',(string)($_SERVER['REMOTE_ADDR']??'unknown'),60,MINUTE_IN_SECONDS)) return self::json(['ok'=>false,'error'=>'rate_limited'],429);
        $p = self::clean_app_payload($request);
        $device = (string)($p['device_id'] ?? '');
        $key = (string)($p['device_key'] ?? '');
        if ($device === '' || strlen($key) < 32) return self::json(['ok'=>false,'error'=>'invalid_device'],403);
        $free = self::free_device($p);
        $paid = self::find_device($device);
        $free_ok = $free && self::free_customer((int)$free['user_id']) && isset(self::free_links((int)$free['user_id'])[self::free_device_key($device)]) && hash_equals((string)$free['key_hash'],self::key_hash($key));
        $paid_ok = $paid && $paid['status'] === 'active' && hash_equals((string)$paid['device_key_hash'],self::key_hash($key));
        if (!$free_ok && !$paid_ok) return self::json(['ok'=>false,'error'=>'account_not_linked'],403);
        $raw = $request->get_json_params();
        foreach (['kind','message','request_id','android_version','device_model'] as $field) if (!is_string($raw[$field] ?? null)) return self::json(['ok'=>false,'error'=>'invalid_feedback'],400);
        $kind = $raw['kind'];
        $message = trim(sanitize_textarea_field($raw['message']));
        if (!in_array($kind,['complaint','comment','tip'],true) || strlen($message) < 3 || strlen($message) > 8000 || !preg_match('/^[a-f0-9-]{36}$/D',$raw['request_id'])) return self::json(['ok'=>false,'error'=>'invalid_feedback'],400);
        $hash = self::key_hash($device . ':' . $raw['request_id']);
        $table = self::feedback_table();
        $existing = (int)$wpdb->get_var($wpdb->prepare("SELECT id FROM $table WHERE request_hash=%s",$hash));
        if ($existing) return self::json(['ok'=>true,'id'=>$existing],200);
        if (!self::pairing_rate('feedback_device',$device,5,HOUR_IN_SECONDS)) return self::json(['ok'=>false,'error'=>'rate_limited'],429);
        $ok = $wpdb->insert($table, ['request_hash'=>$hash,'kind'=>$kind,'message'=>$message,
            'app_version'=>substr(sanitize_text_field($p['app_version']??''),0,64),
            'android_version'=>substr(sanitize_text_field($raw['android_version']),0,32),
            'device_model'=>substr(sanitize_text_field($raw['device_model']),0,120),'created_at'=>gmdate('Y-m-d H:i:s')]);
        if (!$ok) {
            $existing = (int)$wpdb->get_var($wpdb->prepare("SELECT id FROM $table WHERE request_hash=%s",$hash));
            return $existing ? self::json(['ok'=>true,'id'=>$existing],200) : self::json(['ok'=>false,'error'=>'feedback_unavailable'],503);
        }
        return self::json(['ok'=>true,'id'=>(int)$wpdb->insert_id],201);
    }

    public static function feedback_list(WP_REST_Request $request): WP_REST_Response {
        global $wpdb;
        $table = self::feedback_table();
        $before = max(0,(int)$request->get_param('before'));
        $after = max(0,(int)$request->get_param('after'));
        $where = $before ? $wpdb->prepare('WHERE id < %d',$before) : '';
        $rows = $wpdb->get_results("SELECT id,kind,message,app_version,android_version,device_model,created_at FROM $table $where ORDER BY id DESC LIMIT 51",ARRAY_A);
        if (!is_array($rows)) return self::json(['ok'=>false,'error'=>'feedback_unavailable'],503);
        $more = count($rows) > 50;
        $rows = array_slice($rows,0,50);
        foreach ($rows as &$row) { $row['id'] = (int)$row['id']; $row['created_at'] = $row['created_at'].' UTC'; }
        unset($row);
        $unread = (int)$wpdb->get_var($wpdb->prepare("SELECT COUNT(*) FROM $table WHERE kind='complaint' AND id>%d",$after));
        $latest = (int)$wpdb->get_var("SELECT COALESCE(MAX(id),0) FROM $table WHERE kind='complaint'");
        return new WP_REST_Response(['ok'=>true,'items'=>$rows,'unread_complaints'=>$unread,'latest_complaint_id'=>$latest,'next_before'=>$more ? end($rows)['id'] : null],200,['Cache-Control'=>'no-store']);
    }
}
