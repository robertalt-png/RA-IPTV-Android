<?php
if (!defined('ABSPATH')) exit;

/**
 * SunnyIPTV Pro for the Android app:
 *  - app routes under nenotv/v1 for entitlement refresh, trial, activation code and Google Play purchases
 *    (the existing nenotv-backend/v1 routes stay for the signed bridge);
 *  - an automatic trial on the first account-linked refresh of a device (once per account and per device);
 *  - Google Play purchases verified server-side with the Play Developer API and acknowledged by the server.
 *
 * Switches (all off by default):
 *  nenotv_entitlement_mode = live      — existing master switch for Pro
 *  sunnyiptv_auto_trial = 1            — automatic trial at first pairing (length: nenotv_trial_days)
 *  sunnyiptv_play_billing = 1          — the app offers Google Play purchases
 * Google access uses the Play connection of "Updateberichten" (androidpublisher scope).
 */
trait SunnyIPTV_Play_Billing {
    private static array $play_products = [
        'sunnyiptv_pro_solo' => ['kind' => 'subs', 'devices' => 1, 'plan' => 'annual'],
        'sunnyiptv_pro_multi' => ['kind' => 'subs', 'devices' => 5, 'plan' => 'annual'],
        'sunnyiptv_pro_solo_lifetime' => ['kind' => 'inapp', 'devices' => 1, 'plan' => 'lifetime'],
        'sunnyiptv_pro_multi_lifetime' => ['kind' => 'inapp', 'devices' => 5, 'plan' => 'lifetime'],
    ];
    private static string $play_package = 'com.nenotv.player';
    private static ?string $play_access_token = null;

    public static function play_hooks(): void {
        add_action('rest_api_init', [__CLASS__, 'play_routes']);
        add_action('profile_update', [__CLASS__, 'play_sync_account_email'], 20, 1);
        add_filter('cron_schedules', [__CLASS__, 'play_cron_schedules']);
        add_action('init', [__CLASS__, 'play_schedule_maintenance']);
        add_action('sunnyiptv_play_maintenance', [__CLASS__, 'play_maintenance']);
        add_action('sunnyiptv_play_ack_retry', [__CLASS__, 'play_ack_retry'], 10, 1);
        add_action('sunnyiptv_play_check', [__CLASS__, 'play_check'], 10, 1);
    }

    public static function play_cron_schedules(array $schedules): array {
        $schedules['sunnyiptv_five_minutes'] = ['interval'=>300,'display'=>'SunnyIPTV purchase maintenance'];
        return $schedules;
    }

    public static function play_schedule_maintenance(): void {
        if (!wp_next_scheduled('sunnyiptv_play_maintenance')) wp_schedule_event(time()+60,'sunnyiptv_five_minutes','sunnyiptv_play_maintenance');
    }

    private static function play_schedule_job(string $hook, int $id, int $when): void {
        $args = [$id];
        if (function_exists('as_schedule_single_action') && function_exists('as_has_scheduled_action')) {
            if (!as_has_scheduled_action($hook,$args,'sunnyiptv-play')) as_schedule_single_action($when,$hook,$args,'sunnyiptv-play',true);
        } elseif (!wp_next_scheduled($hook,$args)) wp_schedule_single_event($when,$hook,$args);
    }

    /** Dispatch only: network work runs separately, and jobs contain ids rather than purchase tokens. */
    public static function play_maintenance(): void {
        if (!self::play_billing_enabled()) return;
        global $wpdb;
        $ack_cursor = (string)get_option('sunnyiptv_play_ack_cursor','');
        $keys = $wpdb->get_col($wpdb->prepare('SELECT option_name FROM '.$wpdb->options.' WHERE option_name LIKE %s AND option_name>%s ORDER BY option_name ASC LIMIT 100', $wpdb->esc_like('sunnyiptv_play_ack_pending_').'%', $ack_cursor));
        foreach ((array)$keys as $index=>$key) {
            $id = (int)substr($key,strlen('sunnyiptv_play_ack_pending_'));
            $pending = get_option($key,[]);
            if (is_array($pending)) self::play_schedule_job('sunnyiptv_play_ack_retry',$id,max(time()+$index,(int)($pending['next']??0)));
        }
        if (is_array($keys)) update_option('sunnyiptv_play_ack_cursor',count($keys)===100 ? (string)end($keys) : '',false);
        $cursor = (int)get_option('sunnyiptv_play_check_cursor',0);
        $ids = $wpdb->get_col($wpdb->prepare("SELECT id FROM ".self::ent_table()." WHERE source='play' AND status IN ('active','expired') AND id>%d AND (status='active' OR expires_at IS NULL OR expires_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY)) ORDER BY id ASC LIMIT 50",$cursor));
        if (!is_array($ids)) return;
        foreach ($ids as $index=>$id) self::play_schedule_job('sunnyiptv_play_check',(int)$id,time()+$index*5);
        update_option('sunnyiptv_play_check_cursor',count($ids)===50 ? (int)end($ids) : 0,false);
    }

    public static function play_routes(): void {
        foreach (['refresh', 'trial', 'redeem', 'claim', 'play', 'offer'] as $action) {
            register_rest_route(self::APP_NS, '/entitlement/' . $action, [
                'methods' => 'POST',
                'permission_callback' => '__return_true',
                'callback' => static fn(WP_REST_Request $r) => self::app_entitlement_direct($r, $action),
            ], true); // override: the legacy nenotv-app-bridge proxy registers the same routes and would skip auto_trial and the Play recheck
        }
    }

    private static function play_billing_enabled(): bool { return get_option('sunnyiptv_play_billing', '0') === '1' && self::mode() === 'live'; }
    private static function auto_trial_enabled(): bool { return get_option('sunnyiptv_auto_trial', '0') === '1' && self::mode() === 'live'; }

    /** App routes: the device identity (device_id + device_key, hashed on the server) authenticates the request. */
    public static function app_entitlement_direct(WP_REST_Request $request, string $action): WP_REST_Response {
        if (strlen((string)$request->get_body()) > 8192) return self::json(['ok' => false, 'error' => 'request_too_large'], 413);
        $ip = (string)($_SERVER['REMOTE_ADDR'] ?? 'unknown');
        $limit = in_array($action, ['refresh', 'offer'], true) ? 120 : 20;
        if (!self::pairing_rate('entitlement_' . $action, $ip, $limit, HOUR_IN_SECONDS)) return self::json(['ok' => false, 'error' => 'rate_limited'], 429);
        $p = self::clean_app_payload($request);
        if (empty($p['device_id']) || strlen((string)($p['device_key'] ?? '')) < 32) return self::json(['ok' => false, 'error' => 'invalid_device'], 400);

        if ($action === 'offer') {
            return self::json(['ok' => true, 'play_billing' => self::play_billing_enabled(), 'trial_days' => self::trial_days(),
                'products' => array_keys(self::$play_products), 'mode' => self::mode(),
                'google_play_test' => get_option('sunnyiptv_google_play_only', '0') === '1'], 200);
        }
        if ($action === 'play') return self::play_purchase($request, $p);
        if ($action === 'refresh') {
            self::auto_trial($p);
            self::play_recheck_device($p);
        }
        return self::app_entitlement($request, $action, true);
    }

    /** Once per account and once per device: a device that ever had an entitlement never gets a new trial. */
    private static function auto_trial(array $p): void {
        if (!self::auto_trial_enabled()) return;
        if (self::find_device((string)$p['device_id'])) return;
        $record = self::free_device($p);
        if (!$record || !hash_equals((string)$record['key_hash'], self::key_hash((string)$p['device_key']))) return;
        $uid = (int)$record['user_id'];
        if (!self::free_customer($uid) || !isset(self::free_links($uid)[self::free_device_key((string)$p['device_id'])])) return;
        if (self::find_by_source('trial', 'trial-account:' . $uid)) return;
        $user = get_userdata($uid);
        $email = $user ? self::normalize_email((string)$user->user_email) : '';
        if ($email !== '' && self::find_trial_by_email($email)) return;
        global $wpdb;
        $now = self::now_mysql();
        $inserted = $wpdb->insert(self::ent_table(), [
            'reference' => self::ref(), 'email' => $email, 'email_hash' => self::email_hash($email),
            'level' => 'pro_trial', 'plan' => 'trial', 'status' => 'active', 'max_devices' => 1,
            'source' => 'trial', 'source_ref' => 'trial-account:' . $uid, 'payment_mode' => '',
            'language' => self::normalize_language((string)($p['language'] ?? 'en')), 'activation_hash' => '', 'activation_expires_at' => null,
            'starts_at' => $now, 'expires_at' => gmdate('Y-m-d H:i:s', time() + self::trial_days() * DAY_IN_SECONDS),
            'created_at' => $now, 'updated_at' => $now,
        ]);
        if (!$inserted) return; // unique (source, source_ref): a parallel request already created it
        $ent = self::find_by_id((int)$wpdb->insert_id);
        if (!is_array($ent)) return;
        $bound = self::bind_device($ent, $p);
        if (empty($bound['ok'])) { $wpdb->update(self::ent_table(), ['status' => 'revoked', 'updated_at' => self::now_mysql()], ['id' => (int)$ent['id']]); return; }
        self::log_event('auto_trial_started', (string)$ent['reference'], (string)$p['device_id'], 'success', 'Automatic ' . self::trial_days() . '-day trial at first pairing.');
    }

    /** A quick account that adds an email later keeps its trial and Play entitlements linked to the same account. */
    public static function play_sync_account_email(int $uid): void {
        $user = get_userdata($uid);
        if (!$user || !self::free_customer($uid)) return;
        $email = self::normalize_email((string)$user->user_email);
        if ($email === '') return;
        global $wpdb;
        $digests = array_keys(self::free_links($uid));
        $ids = [];
        $trial = self::find_by_source('trial', 'trial-account:' . $uid);
        if ($trial) $ids[(int)$trial['id']] = true;
        if ($digests) {
            $rows = $wpdb->get_results($wpdb->prepare('SELECT d.device_id, d.entitlement_id FROM ' . self::dev_table() . ' d JOIN ' . self::ent_table() . ' e ON e.id=d.entitlement_id WHERE d.status=%s AND e.email=%s AND e.source IN (%s,%s)', 'active', '', 'trial', 'play'), ARRAY_A) ?: [];
            foreach ($rows as $row) if (in_array(self::free_device_key((string)$row['device_id']), $digests, true)) $ids[(int)$row['entitlement_id']] = true;
        }
        foreach (array_keys($ids) as $id) {
            $wpdb->query($wpdb->prepare('UPDATE ' . self::ent_table() . ' SET email=%s, email_hash=%s, updated_at=%s WHERE id=%d AND email=%s', $email, self::email_hash($email), self::now_mysql(), $id, ''));
        }
    }

    // ---------------------------------------------------------------- Google Play

    private static function play_token(): string {
        if (self::$play_access_token !== null) return self::$play_access_token;
        if (!class_exists('NenoTV_Release_Notifications') || !class_exists('NenoTV_Tester_OAuth')) throw new RuntimeException('play_not_connected');
        $auth = get_option(NenoTV_Release_Notifications::AUTH, []);
        if (empty($auth['refresh_enc'])) throw new RuntimeException('play_not_connected');
        $data = NenoTV_Release_Notifications::token(NenoTV_Release_Notifications::client() + ['grant_type' => 'refresh_token', 'refresh_token' => NenoTV_Tester_OAuth::decrypt($auth['refresh_enc'])]);
        return self::$play_access_token = (string)$data['access_token'];
    }

    private static function play_api(string $method, string $path): array {
        $url = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' . rawurlencode(self::$play_package) . $path;
        $args = ['method' => $method, 'timeout' => 20, 'redirection' => 0, 'sslverify' => true, 'headers' => ['Authorization' => 'Bearer ' . self::play_token(), 'Accept' => 'application/json']];
        if ($method === 'POST') { $args['headers']['Content-Type'] = 'application/json'; $args['body'] = '{}'; }
        $r = wp_remote_request($url, $args);
        if (is_wp_error($r)) throw new RuntimeException('play_unreachable');
        $code = (int)wp_remote_retrieve_response_code($r);
        if ($code === 404 || $code === 410) throw new RuntimeException('play_purchase_unknown');
        if ($code < 200 || $code >= 300) throw new RuntimeException('play_http_' . $code);
        $data = json_decode((string)wp_remote_retrieve_body($r), true);
        return is_array($data) ? $data : [];
    }

    private static function play_time(?string $rfc3339): ?string {
        if (!$rfc3339) return null;
        $ts = strtotime($rfc3339);
        return $ts ? gmdate('Y-m-d H:i:s', $ts) : null;
    }

    /** Asks Google about one purchase. Returns [active, expires_at (UTC, null = lifetime), test, acknowledged, linked token]. */
    private static function play_lookup(string $product, string $token): array {
        $conf = self::$play_products[$product];
        if ($conf['kind'] === 'subs') {
            $s = self::play_api('GET', '/purchases/subscriptionsv2/tokens/' . rawurlencode($token));
            $state = (string)($s['subscriptionState'] ?? '');
            $expires = null;
            foreach ((array)($s['lineItems'] ?? []) as $item) {
                if (($item['productId'] ?? '') !== $product) continue;
                $e = self::play_time($item['expiryTime'] ?? null);
                if ($e && (!$expires || $e > $expires)) $expires = $e;
            }
            if ($expires === null) throw new RuntimeException('play_product_mismatch');
            // Cancelled subscriptions stay usable until their paid period ends.
            $active = in_array($state, ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'], true) && strtotime($expires . ' UTC') > time();
            return [$active, $expires, isset($s['testPurchase']), ($s['acknowledgementState'] ?? '') === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED', (string)($s['linkedPurchaseToken'] ?? '')];
        }
        $s = self::play_api('GET', '/purchases/products/' . rawurlencode($product) . '/tokens/' . rawurlencode($token));
        $active = (int)($s['purchaseState'] ?? 1) === 0;
        return [$active, null, isset($s['purchaseType']) && (int)$s['purchaseType'] === 0, (int)($s['acknowledgementState'] ?? 0) === 1, ''];
    }

    private static function play_acknowledge(string $product, string $token): void {
        $conf = self::$play_products[$product];
        $path = $conf['kind'] === 'subs'
            ? '/purchases/subscriptions/' . rawurlencode($product) . '/tokens/' . rawurlencode($token) . ':acknowledge'
            : '/purchases/products/' . rawurlencode($product) . '/tokens/' . rawurlencode($token) . ':acknowledge';
        self::play_api('POST', $path);
    }

    private static function play_ref(string $token): string { return 'play:' . hash('sha256', $token); }

    /** Records the purchase token encrypted so the server can re-check renewals; never returned to anyone. */
    private static function play_store_token(int $entitlement_id, string $product, string $token): bool {
        $key = hash('sha256', 'sunnyiptv-play-token-v1|' . wp_salt('auth'), true);
        $iv = random_bytes(12); $tag = '';
        $cipher = openssl_encrypt($product . "\n" . $token, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
        if ($cipher === false) return false;
        update_option('sunnyiptv_play_token_' . $entitlement_id, base64_encode($iv . $tag . $cipher), false);
        return self::play_read_token($entitlement_id) === [$product,$token];
    }

    private static function play_read_token(int $entitlement_id): ?array {
        $raw = base64_decode((string)get_option('sunnyiptv_play_token_' . $entitlement_id, ''), true);
        if (!$raw || strlen($raw) < 29) return null;
        $key = hash('sha256', 'sunnyiptv-play-token-v1|' . wp_salt('auth'), true);
        $plain = openssl_decrypt(substr($raw, 28), 'aes-256-gcm', $key, OPENSSL_RAW_DATA, substr($raw, 0, 12), substr($raw, 12, 16));
        if (!is_string($plain) || !str_contains($plain, "\n")) return null;
        [$product, $token] = explode("\n", $plain, 2);
        return isset(self::$play_products[$product]) ? [$product, $token] : null;
    }

    private static function play_purchase(WP_REST_Request $request, array $p): WP_REST_Response {
        if (!self::play_billing_enabled()) return self::json(['ok' => false, 'error' => 'play_billing_off'], 200);
        $raw = $request->get_json_params(); if (!is_array($raw)) $raw = [];
        $product = sanitize_key((string)($raw['product_id'] ?? ''));
        $token = substr(trim((string)($raw['purchase_token'] ?? '')), 0, 2000);
        if (!isset(self::$play_products[$product]) || $token === '' || !preg_match('/^[A-Za-z0-9._:-]+$/D', $token)) return self::json(['ok' => false, 'error' => 'invalid_purchase'], 400);
        $device = self::find_device((string)$p['device_id']);
        if ($device && !hash_equals((string)$device['device_key_hash'], self::key_hash((string)$p['device_key']))) return self::json(['ok' => false, 'error' => 'device_key_mismatch'], 200);
        try {
            [$active, $expires, $test, $acknowledged, $linked] = self::play_lookup($product, $token);
        } catch (Throwable $e) {
            self::log_event('play_verify_failed', '', self::play_ref($token), 'failed', $e->getMessage());
            return self::json(['ok' => false, 'error' => in_array($e->getMessage(), ['play_not_connected', 'play_purchase_unknown', 'play_product_mismatch'], true) ? $e->getMessage() : 'play_unavailable'], 200);
        }
        if (!$active) return self::json(['ok' => false, 'error' => 'play_purchase_inactive'], 200);

        global $wpdb;
        $conf = self::$play_products[$product];
        $ref = self::play_ref($token);
        $now = self::now_mysql();
        $email = '';
        $record = self::free_device($p);
        if ($record && hash_equals((string)$record['key_hash'], self::key_hash((string)$p['device_key']))) { $u = get_userdata((int)$record['user_id']); if ($u) $email = self::normalize_email((string)$u->user_email); }
        $ent = self::find_by_source('play', $ref);
        if ($ent) {
            $wpdb->update(self::ent_table(), ['status' => 'active', 'expires_at' => $expires, 'max_devices' => $conf['devices'], 'updated_at' => $now], ['id' => (int)$ent['id']]);
        } else {
            $wpdb->insert(self::ent_table(), [
                'reference' => self::ref(), 'email' => $email, 'email_hash' => self::email_hash($email),
                'level' => 'pro', 'plan' => $conf['plan'], 'status' => 'active', 'max_devices' => $conf['devices'],
                'source' => 'play', 'source_ref' => $ref, 'payment_mode' => $test ? 'test' : 'live',
                'language' => self::normalize_language((string)($p['language'] ?? 'en')), 'activation_hash' => '', 'activation_expires_at' => null,
                'starts_at' => $now, 'expires_at' => $expires, 'created_at' => $now, 'updated_at' => $now,
            ]);
            self::log_event('play_purchase', '', $ref, 'success', 'Google Play ' . $product . ($test ? ' (test purchase)' : '') . ' verified.');
        }
        $ent = self::find_by_source('play', $ref);
        if (!is_array($ent)) return self::json(['ok' => false, 'error' => 'entitlement_write_failed'], 503);
        if (!self::play_store_token((int)$ent['id'], $product, $token)) return self::json(['ok'=>false,'error'=>'purchase_storage_failed'],503);
        // An upgrade or resubscription replaces the previous subscription.
        if ($linked !== '' && $linked !== $token) {
            $old = self::find_by_source('play', self::play_ref($linked));
            if ($old && (int)$old['id'] !== (int)$ent['id']) {
                $wpdb->update(self::ent_table(), ['status' => 'replaced', 'updated_at' => $now], ['id' => (int)$old['id']]);
                $wpdb->update(self::dev_table(), ['entitlement_id' => (int)$ent['id']], ['entitlement_id' => (int)$old['id'], 'status' => 'active']);
            }
        }
        // A device whose previous entitlement ended (expired trial or subscription) may move to this purchase.
        if ($device && ($device['status'] ?? '') === 'active' && (int)$device['entitlement_id'] !== (int)$ent['id']) {
            $previous = self::find_by_id((int)$device['entitlement_id']);
            if (!is_array($previous) || !self::entitlement_is_active($previous)) $wpdb->update(self::dev_table(), ['status' => 'released', 'revoked_at' => $now], ['id' => (int)$device['id']]);
        }
        $bound = self::bind_device($ent, $p);
        if (empty($bound['ok'])) return self::json($bound, 200);
        if (!$acknowledged) {
            if (!self::play_queue_ack((int)$ent['id'])) return self::json(['ok'=>false,'error'=>'purchase_confirmation_unavailable'],503);
            self::play_ack_retry((int)$ent['id']);
        }
        $current = self::find_by_id((int)$ent['id']);
        if (!$current || !self::entitlement_is_active($current)) return self::json(['ok'=>false,'error'=>'play_purchase_inactive'],409);
        return self::json(['ok' => true, 'entitlement' => self::pro_payload($current), 'confirmation_pending'=>is_array(get_option('sunnyiptv_play_ack_pending_'.(int)$ent['id'],null)), 'mode' => 'live', 'message' => 'SunnyIPTV Pro activated.'], 200);
    }

    private static function play_queue_ack(int $id): bool {
        $key = 'sunnyiptv_play_ack_pending_'.$id;
        if (!is_array(get_option($key,null))) add_option($key,['attempts'=>0,'created'=>time(),'next'=>time()],'',false);
        $pending = get_option($key,null);
        if (!is_array($pending)) return false;
        self::play_schedule_job('sunnyiptv_play_ack_retry',$id,max(time()+60,(int)$pending['next']));
        return true;
    }

    public static function play_ack_retry(int $id): void {
        if ($id < 1 || !self::play_billing_enabled() || !self::free_lock('play-ent:'.$id)) return;
        $key = 'sunnyiptv_play_ack_pending_'.$id;
        try {
            $pending = get_option($key,null);
            if (!is_array($pending) || (int)$pending['next'] > time()) return;
            $ent = self::find_by_id($id);
            if (!$ent || $ent['source'] !== 'play' || !in_array($ent['status'],['active','expired'],true)) { delete_option($key); return; }
            try {
                $stored = self::play_read_token($id);
                if (!$stored) throw new RuntimeException('purchase_storage_failed');
                [$active,$expires,,$acknowledged] = self::play_lookup($stored[0],$stored[1]);
                if (!$active) {
                    self::play_apply_state($ent,false,$expires);
                    delete_option($key);
                    return;
                }
                if (!$acknowledged) self::play_acknowledge($stored[0],$stored[1]);
                delete_option($key);
                self::log_event('play_ack_success',(string)$ent['reference'],(string)$ent['source_ref'],'success','Google Play purchase confirmation completed.');
            } catch (Throwable $e) {
                $pending['attempts'] = (int)$pending['attempts']+1;
                $pending['next'] = time()+min(3600,60*(2**min(6,$pending['attempts']-1)));
                update_option($key,$pending,false);
                self::play_schedule_job('sunnyiptv_play_ack_retry',$id,(int)$pending['next']);
                self::log_event('play_ack_failed',(string)$ent['reference'],(string)$ent['source_ref'],'failed','Google Play confirmation failed; durable retry remains pending.');
            }
        } finally { self::free_unlock('play-ent:'.$id); }
    }

    private static function play_apply_state(array $ent, bool $active, ?string $expires): void {
        global $wpdb;
        $status = $active ? 'active' : ($expires === null ? 'revoked' : 'expired');
        $where = ['id'=>(int)$ent['id'],'source'=>'play','status'=>$ent['status'],'expires_at'=>$ent['expires_at']??null];
        if (isset($ent['updated_at'])) $where['updated_at'] = $ent['updated_at'];
        $written = $wpdb->update(self::ent_table(),['status'=>$status,'expires_at'=>$expires,'updated_at'=>self::now_mysql()],$where);
        if ($written === false) throw new RuntimeException('entitlement_write_failed');
        if ($written === 0) {
            $current = self::find_by_id((int)$ent['id']);
            if (!$current || $current['status'] !== $status || ($current['expires_at']??null) !== $expires) throw new RuntimeException('purchase_state_changed');
        }
        if ($ent['status'] !== $status || ($ent['expires_at']??null) !== $expires) self::log_event('play_recheck',(string)$ent['reference'],(string)$ent['source_ref'],$active?'renewed':'expired','Google Play purchase state synchronized.');
    }

    /** Re-check both subscriptions and lifetime purchases; preserve access on transport errors. */
    public static function play_check(int $id): void {
        if ($id < 1 || !self::play_billing_enabled() || !self::free_lock('play-ent:'.$id)) return;
        try {
            $ent = self::find_by_id($id);
            if (!$ent || $ent['source'] !== 'play' || !in_array($ent['status'],['active','expired'],true)) return;
            if (get_transient('sunnyiptv_play_checked_'.$id)) return;
            try {
                $stored = self::play_read_token($id);
                if (!$stored) throw new RuntimeException('purchase_storage_failed');
                [$active,$expires,,$acknowledged] = self::play_lookup($stored[0],$stored[1]);
                self::play_apply_state($ent,$active,$expires);
                set_transient('sunnyiptv_play_checked_'.$id,1,15*MINUTE_IN_SECONDS);
                if ($active && !$acknowledged && !self::play_queue_ack($id)) throw new RuntimeException('purchase_confirmation_unavailable');
                if (!$active || $acknowledged) delete_option('sunnyiptv_play_ack_pending_'.$id);
            } catch (Throwable $e) {
                set_transient('sunnyiptv_play_checked_'.$id,1,MINUTE_IN_SECONDS);
                self::log_event('play_recheck_failed',(string)$ent['reference'],(string)$ent['source_ref'],'failed','Google Play purchase check failed; existing access was preserved.');
            }
        } finally { self::free_unlock('play-ent:'.$id); }
    }

    private static function play_recheck_device(array $p): void {
        if (!self::play_billing_enabled()) return;
        $device = self::find_device((string)$p['device_id']);
        if (!$device || ($device['status'] ?? '') !== 'active') return;
        if (!hash_equals((string)$device['device_key_hash'],self::key_hash((string)($p['device_key']??'')))) return;
        self::play_check((int)$device['entitlement_id']);
    }
}
