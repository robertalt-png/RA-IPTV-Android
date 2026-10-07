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
                'products' => array_keys(self::$play_products), 'mode' => self::mode()], 200);
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
    private static function play_store_token(int $entitlement_id, string $product, string $token): void {
        $key = hash('sha256', 'sunnyiptv-play-token-v1|' . wp_salt('auth'), true);
        $iv = random_bytes(12); $tag = '';
        $cipher = openssl_encrypt($product . "\n" . $token, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
        if ($cipher === false) return;
        update_option('sunnyiptv_play_token_' . $entitlement_id, base64_encode($iv . $tag . $cipher), false);
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
        self::play_store_token((int)$ent['id'], $product, $token);
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
            // Google refunds purchases that are not acknowledged within three days.
            try { self::play_acknowledge($product, $token); }
            catch (Throwable $e) { self::log_event('play_ack_failed', (string)$ent['reference'], $ref, 'failed', $e->getMessage()); }
        }
        return self::json(['ok' => true, 'entitlement' => self::pro_payload(self::find_by_id((int)$ent['id'])), 'mode' => 'live', 'message' => 'SunnyIPTV Pro activated.'], 200);
    }

    /** On refresh: a Play subscription that reached its expiry date is checked again with Google (renewal), at most every 6 hours. */
    private static function play_recheck_device(array $p): void {
        if (!self::play_billing_enabled()) return;
        $device = self::find_device((string)$p['device_id']);
        if (!$device || ($device['status'] ?? '') !== 'active') return;
        $ent = self::find_by_id((int)$device['entitlement_id']);
        if (!is_array($ent) || ($ent['source'] ?? '') !== 'play' || empty($ent['expires_at'])) return;
        if (strtotime($ent['expires_at'] . ' UTC') > time() + HOUR_IN_SECONDS) return;
        $lock = 'sunnyiptv_play_recheck_' . (int)$ent['id'];
        if (get_transient($lock)) return;
        set_transient($lock, 1, 6 * HOUR_IN_SECONDS);
        $stored = self::play_read_token((int)$ent['id']);
        if (!$stored) return;
        try {
            [$active, $expires] = self::play_lookup($stored[0], $stored[1]);
            global $wpdb;
            $wpdb->update(self::ent_table(), ['status' => $active ? 'active' : 'expired', 'expires_at' => $expires, 'updated_at' => self::now_mysql()], ['id' => (int)$ent['id']]);
            self::log_event('play_recheck', (string)$ent['reference'], (string)$ent['source_ref'], $active ? 'renewed' : 'expired', 'Google Play subscription re-checked.');
        } catch (Throwable $e) {
            self::log_event('play_recheck_failed', (string)$ent['reference'], (string)$ent['source_ref'], 'failed', $e->getMessage());
        }
    }
}
