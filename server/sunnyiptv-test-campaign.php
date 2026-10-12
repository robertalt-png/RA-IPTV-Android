<?php
if (!defined('ABSPATH')) exit;

trait SunnyIPTV_Test_Campaign {
    private static function test_campaign(): array {
        $value = get_option('sunnyiptv_test_campaign_v1', []);
        return is_array($value) ? $value : [];
    }

    private static function test_campaign_lock(): bool {
        global $wpdb;
        $locked = (int)$wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 3)', $wpdb->prefix . 'sunny_test_campaign_v1')) === 1;
        if ($locked) {
            // Do not reuse an option cached before another request held the database lock.
            wp_cache_delete('sunnyiptv_test_campaign_v1', 'options');
            wp_cache_delete('notoptions', 'options');
        }
        return $locked;
    }

    private static function test_campaign_unlock(): void {
        global $wpdb;
        $wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)', $wpdb->prefix . 'sunny_test_campaign_v1'));
    }

    public static function test_campaign_status(): array {
        $c = self::test_campaign();
        return ['state'=>$c['state'] ?? 'inactive', 'limit'=>25, 'enrolled'=>count($c['members'] ?? []),
            'approved_at'=>$c['approved_at'] ?? 0, 'reward_days'=>365];
    }

    public static function test_campaign_hooks(): void {
        add_action(self::CRON, [__CLASS__, 'test_campaign_retry']);
        add_action('rest_api_init', static function () {
            register_rest_route('sunnyiptv-admin/v1', '/test-campaign', [
                ['methods'=>'GET', 'permission_callback'=>[__CLASS__, 'admin_api_allowed'],
                    'callback'=>static fn() => self::json(['ok'=>true, 'campaign'=>self::test_campaign_status()], 200)],
                ['methods'=>'POST', 'permission_callback'=>static fn() => current_user_can('manage_options'),
                    'callback'=>[__CLASS__, 'test_campaign_admin']],
            ]);
        });
        add_action('admin_menu', static function () {
            add_submenu_page('sunnyiptv-admin-app', 'Testperiode', 'Testperiode', 'manage_options',
                'sunnyiptv-test-campaign', [__CLASS__, 'test_campaign_page']);
        });
    }

    public static function test_campaign_admin(WP_REST_Request $r): WP_REST_Response {
        if (!current_user_can('manage_options')) return self::json(['ok'=>false], 403);
        $p = $r->get_json_params();
        if (!is_array($p)) return self::json(['ok'=>false], 400);
        return self::json(self::test_campaign_change((string)($p['action'] ?? ''), ($p['google_approved'] ?? false) === true), 200);
    }

    private static function test_campaign_change(string $action, bool $verified): array {
        if (!current_user_can('manage_options') || self::mode() !== 'live') return ['ok'=>false, 'error'=>'not_allowed'];
        if (!in_array($action, ['start', 'approve'], true)) return ['ok'=>false, 'error'=>'invalid_action'];
        if (!self::test_campaign_lock()) return ['ok'=>false, 'error'=>'busy'];
        try {
            $c = self::test_campaign();
            if ($action === 'start') {
                if ($c) return ['ok'=>true, 'campaign'=>self::test_campaign_status()];
                $c = ['state'=>'open', 'started_at'=>time(), 'members'=>[]];
            } else {
                if (!$verified || !$c) return ['ok'=>false, 'error'=>'google_approval_required'];
                if (($c['state'] ?? '') !== 'approved') {
                    $c['state'] = 'approved';
                    $c['approved_at'] = time();
                    $c['approved_by'] = get_current_user_id();
                }
            }
            if (!update_option('sunnyiptv_test_campaign_v1', $c, false) && self::test_campaign() !== $c)
                return ['ok'=>false, 'error'=>'save_failed'];
            if ($action === 'approve') self::test_campaign_rewards($c);
            return ['ok'=>true, 'campaign'=>self::test_campaign_status()];
        } finally { self::test_campaign_unlock(); }
    }

    /** Enrollment uses an approved device binding, never a client-supplied tester flag. */
    private static function test_campaign_refresh(array $p): void {
        if (self::mode() !== 'live' || !self::test_campaign()) return;
        $record = self::free_device($p);
        if (!$record || ($record['trial_consent'] ?? false) !== true
            || !hash_equals((string)$record['key_hash'], self::key_hash((string)$p['device_key']))) return;
        $uid = (int)$record['user_id'];
        if (!self::free_customer($uid) || !isset(self::free_links($uid)[self::free_device_key((string)$p['device_id'])])) return;
        $device = self::find_device((string)$p['device_id']);
        if (!$device || ($device['status'] ?? '') !== 'active'
            || !hash_equals((string)$device['device_key_hash'], self::key_hash((string)$p['device_key']))) return;
        if (!self::test_campaign_lock()) return;
        try {
            $c = self::test_campaign();
            if (($c['state'] ?? '') === 'approved') { self::test_campaign_rewards($c); return; }
            if (($c['state'] ?? '') !== 'open') return;
            $ent = self::find_by_id((int)$device['entitlement_id']);
            if (!$ent || !self::entitlement_is_active($ent) || ($ent['source'] ?? '') !== 'trial'
                || ($ent['source_ref'] ?? '') !== 'trial-account:' . $uid || !self::is_trial($ent)) return;
            if (isset($c['members'][$uid]) && (int)$c['members'][$uid] !== (int)$ent['id']) return;
            if (!isset($c['members'][$uid])) {
                if (count($c['members'] ?? []) >= 25) return;
                $c['members'][$uid] = (int)$ent['id'];
                if (!update_option('sunnyiptv_test_campaign_v1', $c, false)) return;
            }
            global $wpdb;
            // Compare-and-swap protects a simultaneous purchase or administrator revocation.
            $wpdb->update(self::ent_table(), ['expires_at'=>null, 'updated_at'=>self::now_mysql()],
                ['id'=>(int)$ent['id'], 'status'=>'active', 'level'=>'pro_trial', 'plan'=>'trial',
                    'source'=>'trial', 'source_ref'=>'trial-account:' . $uid]);
        } finally { self::test_campaign_unlock(); }
    }

    private static function test_campaign_access(array $row): bool {
        $c = self::test_campaign();
        return in_array($c['state'] ?? '', ['open','approved'], true) && in_array((int)($row['id'] ?? 0), $c['members'] ?? [], true)
            && ($row['status'] ?? '') === 'active' && ($row['level'] ?? '') === 'pro_trial'
            && ($row['source'] ?? '') === 'trial' && empty($row['expires_at']);
    }

    private static function test_campaign_rewards(array $c): void {
        if (($c['state'] ?? '') !== 'approved' || empty($c['approved_at'])) return;
        global $wpdb;
        foreach ($c['members'] ?? [] as $uid=>$id) {
            $changed = $wpdb->update(self::ent_table(), ['level'=>'pro', 'plan'=>'annual', 'payment_mode'=>'promotion',
                'starts_at'=>gmdate('Y-m-d H:i:s', (int)$c['approved_at']),
                'expires_at'=>gmdate('Y-m-d H:i:s', (int)$c['approved_at'] + 365 * DAY_IN_SECONDS),
                'updated_at'=>self::now_mysql()], ['id'=>(int)$id, 'source'=>'trial',
                    'source_ref'=>'trial-account:' . (int)$uid, 'status'=>'active', 'level'=>'pro_trial', 'plan'=>'trial']);
            if ($changed === 1) self::log_event('play_test_year_granted', '', '', 'success', 'Approved tester received 365 days of promotional Pro.');
        }
    }

    public static function test_campaign_retry(): void {
        if (self::mode() !== 'live' || !self::test_campaign_lock()) return;
        try { self::test_campaign_rewards(self::test_campaign()); }
        finally { self::test_campaign_unlock(); }
    }

    public static function test_campaign_page(): void {
        if (!current_user_can('manage_options')) return;
        $message = '';
        if (($_SERVER['REQUEST_METHOD'] ?? '') === 'POST') {
            check_admin_referer('sunnyiptv_test_campaign');
            $r = self::test_campaign_change(sanitize_key((string)($_POST['campaign_action'] ?? '')), !empty($_POST['google_approved']));
            $message = !empty($r['ok']) ? 'Opgeslagen. De apps ontvangen de status bij het vernieuwen.' : 'Niet gewijzigd: ' . ($r['error'] ?? 'unknown');
        }
        $c = self::test_campaign_status();
        echo '<div class="wrap"><h1>SunnyIPTV Test</h1><p>' . esc_html($message) . '</p><p>Status: ' . esc_html($c['state'])
            . ' | Testers: ' . (int)$c['enrolled'] . '/25</p><form method="post">';
        wp_nonce_field('sunnyiptv_test_campaign');
        if ($c['state'] === 'inactive') echo '<button class="button button-primary" name="campaign_action" value="start">Testperiode openen</button>';
        elseif ($c['state'] === 'open') echo '<p><label><input type="checkbox" name="google_approved" value="1" required> Google heeft productietoegang goedgekeurd. Ik bevestig het gratis jaar Pro voor deze testers.</label></p><button class="button button-primary" name="campaign_action" value="approve">Bevestigen en gratis jaar starten</button>';
        else echo '<button class="button" name="campaign_action" value="approve">Verwerking opnieuw controleren</button><input type="hidden" name="google_approved" value="1">';
        echo '</form></div>';
    }
}
