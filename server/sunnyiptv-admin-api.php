<?php
if (!defined('ABSPATH')) exit;

/**
 * SunnyIPTV Admin app, read-only additions to nenotv-dashboard/v1/admin-app:
 * customers and devices, entitlements by kind (internal test and review access counted apart from customers),
 * the Pro switches, the Google Play connection and the latest entitlement events.
 * Same authorisation as the existing admin app: the paired Bearer token (NenoTV_Dashboard_API) or a site administrator.
 * Returns counts and event types only: no names, e-mail addresses, IP addresses, device ids or order details.
 */
trait SunnyIPTV_Admin_API {
    public static function admin_api_hooks(): void {
        add_action('rest_api_init', static function () {
            register_rest_route('sunnyiptv-admin/v1', '/status', [
                'methods' => 'GET',
                'permission_callback' => [__CLASS__, 'admin_api_allowed'],
                'callback' => [__CLASS__, 'admin_api_status'],
            ]);
        });
        add_action('admin_menu', [__CLASS__, 'admin_api_menu'], 5);
    }

    /** Top-level wp-admin page for pairing the phone admin app (the WooCommerce submenu is hard to reach on a phone). */
    public static function admin_api_menu(): void {
        add_menu_page('SunnyIPTV Admin-app', 'Admin-app koppelen', 'manage_options', 'sunnyiptv-admin-app', [__CLASS__, 'admin_api_pair_page'], 'dashicons-smartphone', 3);
    }

    public static function admin_api_pair_page(): void {
        if (!current_user_can('manage_options')) return;
        $notice = '';
        if (($_SERVER['REQUEST_METHOD'] ?? '') === 'POST' && isset($_POST['sunny_admin_app_action'])) {
            check_admin_referer('sunny_admin_app_pair');
            $action = sanitize_key((string)$_POST['sunny_admin_app_action']);
            if ($action === 'generate') {
                $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
                $code = '';
                for ($i = 0; $i < 10; $i++) $code .= $alphabet[wp_rand(0, strlen($alphabet) - 1)];
                // Same storage as NenoTV_Dashboard_API, so POST nenotv-dashboard/v1/admin-pair accepts it.
                update_option('nenotv_admin_pair_hash', hash_hmac('sha256', $code, wp_salt('auth')), false);
                update_option('nenotv_admin_pair_expires', time() + 10 * MINUTE_IN_SECONDS, false);
                set_transient('nenotv_admin_pair_plain_once', $code, 10 * MINUTE_IN_SECONDS);
                $notice = 'Nieuwe koppelcode aangemaakt (10 minuten geldig).';
            } elseif ($action === 'revoke') {
                delete_option('nenotv_admin_app_access_hash');
                delete_option('nenotv_admin_pair_hash');
                delete_option('nenotv_admin_pair_expires');
                delete_transient('nenotv_admin_pair_plain_once');
                $notice = 'Toegang van de admin-app is ingetrokken.';
            }
        }
        $code = (string)get_transient('nenotv_admin_pair_plain_once');
        $expires = (int)get_option('nenotv_admin_pair_expires', 0);
        $paired = (string)get_option('nenotv_admin_app_access_hash', '') !== '';
        echo '<div class="wrap"><h1>SunnyIPTV Admin-app koppelen</h1>';
        echo '<p>De admin-app kan alleen lezen: overzicht, statistieken en waarschuwingen. Hij kan niets wijzigen.</p>';
        if ($notice !== '') echo '<div class="notice notice-success"><p>' . esc_html($notice) . '</p></div>';
        echo '<p><strong>Status:</strong> ' . ($paired ? 'gekoppeld' : 'nog niet gekoppeld') . '</p>';
        if ($code !== '' && $expires >= time()) {
            echo '<p>Typ deze code in de app:</p><div style="font-size:30px;font-weight:700;letter-spacing:4px;padding:14px 18px;background:#f6f7f7;border-radius:8px;display:inline-block;user-select:all">' . esc_html($code) . '</div>';
            echo '<p class="description">Geldig tot ' . esc_html(wp_date('H:i', $expires)) . '. Na het koppelen vervalt de code.</p>';
        }
        echo '<form method="post" style="margin-top:18px">';
        wp_nonce_field('sunny_admin_app_pair');
        echo '<button class="button button-primary button-hero" name="sunny_admin_app_action" value="generate">Nieuwe koppelcode maken</button> ';
        if ($paired) echo '<button class="button" name="sunny_admin_app_action" value="revoke" onclick="return confirm(\'Toegang van de huidige admin-app intrekken?\')">Toegang intrekken</button>';
        echo '</form></div>';
    }

    public static function admin_api_allowed(WP_REST_Request $request): bool {
        if (current_user_can('manage_options')) return true;
        return class_exists('NenoTV_Dashboard_API') && NenoTV_Dashboard_API::authorized_admin_app($request);
    }

    private static function admin_api_internal_source(string $source): bool {
        return in_array($source, ['internal_catalog_test', 'google_play_review'], true);
    }

    public static function admin_api_status(WP_REST_Request $request): WP_REST_Response {
        global $wpdb;
        $now = time();

        // Accounts and linked devices.
        $users = count_users();
        $customers = (int)($users['avail_roles']['customer'] ?? 0);
        $quick = (int)$wpdb->get_var($wpdb->prepare("SELECT COUNT(DISTINCT user_id) FROM {$wpdb->usermeta} WHERE meta_key=%s", '_sunnyiptv_quick_account'));
        $linked_accounts = (int)$wpdb->get_var($wpdb->prepare("SELECT COUNT(DISTINCT user_id) FROM {$wpdb->usermeta} WHERE meta_key=%s AND meta_value<>%s AND meta_value<>%s", '_sunnyiptv_free_devices', '', 'a:0:{}'));
        $linked_devices = (int)$wpdb->get_var($wpdb->prepare("SELECT COUNT(*) FROM {$wpdb->options} WHERE option_name LIKE %s", $wpdb->esc_like('sunnyiptv_free_device_') . '%'));

        // Entitlements: customers versus internal rows.
        $rows = $wpdb->get_results('SELECT source, level, plan, status, expires_at, max_devices, id FROM ' . self::ent_table(), ARRAY_A) ?: [];
        $ent = ['trial_active' => 0, 'trial_expired' => 0, 'pro_active' => 0, 'pro_ended' => 0, 'internal_active' => 0, 'by_source' => []];
        foreach ($rows as $r) {
            $source = (string)$r['source'];
            $active = self::entitlement_is_active($r);
            $ent['by_source'][$source] = ($ent['by_source'][$source] ?? 0) + 1;
            if (self::admin_api_internal_source($source)) { if ($active) $ent['internal_active']++; continue; }
            if (self::is_trial($r)) { if ($active) $ent['trial_active']++; else $ent['trial_expired']++; continue; }
            if ($active) $ent['pro_active']++; else $ent['pro_ended']++;
        }
        $devices = $wpdb->get_results('SELECT status, COUNT(*) n FROM ' . self::dev_table() . ' GROUP BY status', ARRAY_A) ?: [];
        $pro_devices = [];
        foreach ($devices as $d) $pro_devices[(string)$d['status']] = (int)$d['n'];

        // Switches and connections (booleans only, never secrets).
        $auth = get_option('nenotv_release_oauth_credentials', []);
        $switches = [
            'pro_mode' => self::mode(),
            'auto_trial' => get_option('sunnyiptv_auto_trial', '0') === '1',
            'play_billing' => get_option('sunnyiptv_play_billing', '0') === '1',
            'email_trial' => self::trial_enabled(),
            'trial_days' => self::trial_days(),
            'shop_coming_soon' => get_option('woocommerce_coming_soon', 'no') === 'yes',
            'mollie_test_mode' => get_option('mollie-payments-for-woocommerce_test_mode_enabled', 'no') === 'yes',
            'play_connected' => is_array($auth) && !empty($auth['refresh_enc']),
        ];

        // Latest events: type, outcome and the fixed system message; no references.
        $events = $wpdb->get_results('SELECT id, event_type, outcome, message, created_at FROM ' . self::event_table() . ' ORDER BY id DESC LIMIT 40', ARRAY_A) ?: [];
        foreach ($events as &$e) { $e['id'] = (int)$e['id']; $e['created_at'] = gmdate('c', (int)strtotime($e['created_at'] . ' UTC')); }
        unset($e);

        $warnings = [];
        if ($switches['pro_mode'] === 'live' && !$switches['play_connected']) $warnings[] = 'play_not_connected';
        if ($switches['play_billing'] && !$switches['play_connected']) $warnings[] = 'play_billing_without_connection';
        if ($switches['mollie_test_mode']) $warnings[] = 'mollie_test_mode';
        foreach ($events as $e) if (in_array($e['event_type'], ['play_verify_failed', 'play_ack_failed', 'play_recheck_failed'], true) && strtotime($e['created_at']) > $now - DAY_IN_SECONDS) { $warnings[] = 'play_errors_24h'; break; }

        return new WP_REST_Response([
            'ok' => true,
            'generated_at' => gmdate('c', $now),
            'customers' => ['accounts' => $customers, 'quick_accounts' => $quick, 'accounts_with_devices' => $linked_accounts, 'linked_devices' => $linked_devices],
            'entitlements' => $ent,
            'pro_devices' => $pro_devices,
            'switches' => $switches,
            'warnings' => array_values(array_unique($warnings)),
            'events' => $events,
        ], 200, ['Cache-Control' => 'no-store']);
    }
}
