<?php
if (!defined('ABSPATH')) exit;

/** Account links are separate from paid entitlements and their source vaults. */
trait SunnyIPTV_Free_Pairing {
    private static function free_customer(int $id): bool {
        $user = get_userdata($id);
        if (!$user || $user->roles !== ['customer'] || get_user_meta($id,'_sunnyiptv_account_deleting',true)) return false;
        foreach (['manage_options','manage_woocommerce','edit_posts','edit_users','promote_users','install_plugins'] as $cap) if (user_can($user,$cap)) return false;
        return true;
    }

    private static function free_eligible(array $session, int $id): bool {
        return (int)($session['pairing_version'] ?? 1) >= 3 && self::free_customer($id);
    }

    private static function free_device_key(string $id): string {
        return hash_hmac('sha256', $id, wp_salt('auth'));
    }

    private static function free_lock(string $identity): bool {
        global $wpdb;
        return (int)$wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 3)', 'sunny_free_'.substr(hash('sha256',$identity),0,40))) === 1;
    }

    private static function free_unlock(string $identity): void {
        global $wpdb;
        $wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)', 'sunny_free_'.substr(hash('sha256',$identity),0,40)));
    }

    private static function free_links(int $id): array {
        $links = get_user_meta($id, '_sunnyiptv_free_devices', true);
        return is_array($links) ? $links : [];
    }

    private static function free_device(array $session): ?array {
        $value = get_option('sunnyiptv_free_device_'.self::free_device_key($session['device_id']), null);
        return is_array($value) ? $value : null;
    }

    private static function free_bind(array $session, bool $may_create): array {
        $id = (int)($session['account_user_id'] ?? 0);
        if (!self::free_eligible($session,$id)) return ['ok'=>false,'error'=>'account_unavailable'];
        $digest = self::free_device_key($session['device_id']);
        $accountLock = 'account:'.$id;
        $deviceLock = 'device:'.$digest;
        if (!self::free_lock($accountLock)) return ['ok'=>false,'error'=>'pairing_busy'];
        try {
            if (!self::free_lock($deviceLock)) return ['ok'=>false,'error'=>'pairing_busy'];
            try {
                if (!self::free_customer($id)) return ['ok'=>false,'error'=>'account_unavailable'];
                // Never transfer a paid device to a different account through free linking.
                $licensed = self::find_device($session['device_id']);
                if ($licensed) {
                    $ent = self::find_by_id((int)$licensed['entitlement_id']);
                    $user = get_userdata($id);
                    if (!$ent || !hash_equals((string)$licensed['device_key_hash'], $session['key_hash'])
                        || !hash_equals(self::email_hash((string)$ent['email']), self::email_hash((string)$user->user_email))) return ['ok'=>false,'error'=>'device_account_conflict'];
                }
                $record = self::free_device($session);
                $links = self::free_links($id);
                if ($record && ((int)$record['user_id'] !== $id || !hash_equals($record['key_hash'],$session['key_hash']))) return ['ok'=>false,'error'=>'device_account_conflict'];
                if (!$may_create && (!$record || !isset($links[$digest]))) return ['ok'=>false,'error'=>'device_not_linked'];
                if ($record && isset($links[$digest])) return ['ok'=>true];
                if (count($links) >= 10) return ['ok'=>false,'error'=>'device_limit'];
                $previous = $links;
                $links[$digest] = ['name'=>$session['name'], 'linked_at'=>time()];
                if (!update_user_meta($id,'_sunnyiptv_free_devices',$links) && self::free_links($id) !== $links) return ['ok'=>false,'error'=>'device_write_failed'];
                $option = 'sunnyiptv_free_device_'.$digest;
                $value = ['user_id'=>$id,'key_hash'=>$session['key_hash']];
                if (!$record && !add_option($option,$value,'',false)) {
                    update_user_meta($id,'_sunnyiptv_free_devices',$previous);
                    return ['ok'=>false,'error'=>'device_write_failed'];
                }
                return ['ok'=>true];
            } finally { self::free_unlock($deviceLock); }
        } finally { self::free_unlock($accountLock); }
    }

    private static function free_status(array $session, string $code): WP_REST_Response {
        $bound = self::free_bind($session, $session['state'] === 'approved');
        if (empty($bound['ok'])) return self::json($bound,403);
        if ($session['state'] !== 'complete') {
            $session['state'] = 'complete';
            if (!update_option('nenotv_pair_'.$code,$session,false)) return self::json(['ok'=>false,'error'=>'pairing_state_failed'],503);
        }
        return self::json(['ok'=>true,'state'=>'complete','account_link'=>self::free_account_payload((int)$session['account_user_id'],'free'),
            'entitlement'=>['level'=>'free','status'=>'active','max_devices'=>0,'account_scope'=>'']],200);
    }

    private static function free_account_payload(int $id, string $kind): array {
        return ['status'=>'active','kind'=>$kind,'account_id'=>hash_hmac('sha256','customer:'.$id,wp_salt('auth'))];
    }

    public static function free_account_status(WP_REST_Request $request): WP_REST_Response {
        if(strlen((string)$request->get_body())>8192)return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        if(!self::pairing_rate('account_status',(string)($_SERVER['REMOTE_ADDR']??'unknown'),120,MINUTE_IN_SECONDS))return self::json(['ok'=>false,'error'=>'rate_limited'],429);
        $p=self::clean_app_payload($request);
        if(empty($p['device_id'])||strlen((string)($p['device_key']??''))<32)return self::json(['ok'=>false,'error'=>'invalid_device'],400);
        $record=self::free_device($p);
        if(!$record||!hash_equals($record['key_hash'],self::key_hash($p['device_key']))||!self::free_customer((int)$record['user_id']))return self::json(['ok'=>false,'error'=>'account_not_linked'],403);
        $digest=self::free_device_key($p['device_id']);
        if(!isset(self::free_links((int)$record['user_id'])[$digest]))return self::json(['ok'=>false,'error'=>'account_not_linked'],403);
        return self::json(['ok'=>true,'account_link'=>self::free_account_payload((int)$record['user_id'],'account')],200);
    }

    public static function free_unlink(): void {
        if (!is_user_logged_in()) auth_redirect();
        $id = get_current_user_id();
        $digest = is_string($_POST['device'] ?? null) ? $_POST['device'] : '';
        $nonce = is_string($_POST['_wpnonce'] ?? null) ? $_POST['_wpnonce'] : '';
        if (!self::free_customer($id) || !preg_match('/^[a-f0-9]{64}$/D',$digest) || !wp_verify_nonce($nonce,'sunnyiptv_free_unlink_'.$digest)) wp_die('Security check failed.');
        if (!self::free_lock('account:'.$id)) wp_die('Please try again.');
        try {
            if (!self::free_lock('device:'.$digest)) wp_die('Please try again.');
            try {
                $links = self::free_links($id);
                $record = get_option('sunnyiptv_free_device_'.$digest, null);
                if (!isset($links[$digest]) || !is_array($record) || (int)$record['user_id'] !== $id) wp_die('Device not linked.');
                if (!delete_option('sunnyiptv_free_device_'.$digest)) wp_die('Please try again.');
                unset($links[$digest]);update_user_meta($id,'_sunnyiptv_free_devices',$links);
            } finally { self::free_unlock('device:'.$digest); }
        } finally { self::free_unlock('account:'.$id); }
        wp_safe_redirect(wc_get_page_permalink('myaccount'));exit;
    }

    public static function free_account_devices(): void {
        $id = get_current_user_id();
        if (!self::free_customer($id)) return;
        $links = self::free_links($id);
        if (!$links) return;
        $lang = self::account_language();
        echo '<section><h2>'.esc_html($lang==='nl'?'Gekoppelde apparaten':($lang==='de'?'Verbundene Geraete':'Linked devices')).'</h2>';
        foreach ($links as $digest=>$link) {
            $record = get_option('sunnyiptv_free_device_'.$digest,null);
            if (!is_array($record) || (int)$record['user_id'] !== $id) continue;
            echo '<form method="post" action="'.esc_url(admin_url('admin-post.php')).'"><p>'.esc_html($link['name']).'</p><input type="hidden" name="action" value="sunnyiptv_free_unlink"><input type="hidden" name="device" value="'.esc_attr($digest).'">';
            echo wp_nonce_field('sunnyiptv_free_unlink_'.$digest,'_wpnonce',true,false);
            echo '<button type="submit">'.esc_html($lang==='nl'?'Ontkoppelen':($lang==='de'?'Trennen':'Unlink')).'</button></form>';
        }
        echo '</section>';
    }

    public static function free_delete_user(int $id): void {
        update_user_meta($id,'_sunnyiptv_account_deleting',1);
        if (!self::free_lock('account:'.$id)) wp_die('Please try deleting the account again.');
        try {
        foreach (self::free_links($id) as $digest=>$link) {
            $key = 'sunnyiptv_free_device_'.$digest;
            $record = get_option($key,null);
            if (is_array($record) && (int)$record['user_id'] === $id) delete_option($key);
        }
        } finally { self::free_unlock('account:'.$id); }
    }
}
