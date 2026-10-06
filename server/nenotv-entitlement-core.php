<?php
/**
 * Plugin Name: SunnyIPTV Entitlement Core
 * Description: Central SunnyIPTV entitlement control plane for payment grants, refunds, device claims and the app bridge. Defaults to safe shadow mode until commercial launch.
 * Version: 0.1.25
 * Author: SunnyIPTV
 * Requires at least: 6.6
 * Requires PHP: 8.0
 */

if (!defined('ABSPATH')) exit;

require_once __DIR__ . '/nenotv-pairing.php';
require_once __DIR__ . '/nenotv-source-manager.php';
require_once __DIR__ . '/nenotv-catalog-package.php';
require_once __DIR__ . '/sunnyiptv-review-access.php';
require_once __DIR__ . '/sunnyiptv-account-setup.php';
require_once __DIR__ . '/sunnyiptv-play-billing.php';

final class NenoTV_Entitlement_Core {
    use NenoTV_Pairing;
    use NenoTV_Source_Manager;
    use NenoTV_Catalog_Package;
    use SunnyIPTV_Review_Access;
    use SunnyIPTV_Account_Setup;
    use SunnyIPTV_Play_Billing;
    const VERSION = '0.1.25';
    const DB_VERSION = '5';
    const NS = 'nenotv-backend/v1';
    const APP_NS = 'nenotv/v1';
    const OPT_MODE = 'nenotv_entitlement_mode'; // shadow|live
    const OPT_PLAN = 'nenotv_entitlement_plan_policy'; // unconfigured|annual|lifetime
    const OPT_MAX_DEVICES = 'nenotv_entitlement_default_max_devices';
    const OPT_TOKEN_DAYS = 'nenotv_entitlement_activation_token_days';
    const OPT_TRIAL_ENABLED = 'nenotv_trial_enabled';
    const OPT_TRIAL_DAYS = 'nenotv_trial_days';
    const OPT_DB_VERSION = 'nenotv_entitlement_db_version';
    const OPT_BRIDGE_URL = 'nenotv_backend_url';
    const OPT_BRIDGE_SECRET = 'nenotv_bridge_secret';
    const OPT_BRIDGE_ENABLED = 'nenotv_bridge_enabled';
    const OPT_RECONCILE_VERSION = 'nenotv_entitlement_reconcile_version';
    const OPT_RECONCILE_LAST_COUNT = 'nenotv_entitlement_reconcile_last_count';
    const CRON = 'nenotv_entitlement_daily_maintenance';

    private static function ent_table(): string { global $wpdb; return $wpdb->prefix . 'nenotv_entitlements'; }
    private static function dev_table(): string { global $wpdb; return $wpdb->prefix . 'nenotv_devices'; }
    private static function event_table(): string { global $wpdb; return $wpdb->prefix . 'nenotv_entitlement_events'; }
    private static function source_table(): string { global $wpdb; return $wpdb->prefix . 'nenotv_source_vault'; }

    public static function init(): void {
        self::pairing_hooks();
        self::account_setup_hooks();
        self::play_hooks();
        add_action('wp_enqueue_scripts', static function(){wp_enqueue_style('nenotv-account-flow', plugins_url('account-flow.css',__FILE__), [], self::VERSION . '.' . (string)filemtime(__DIR__.'/account-flow.css'));});
        self::catalog_hooks();
        add_action('init', [__CLASS__, 'review_meta']);
        add_action('rest_api_init', [__CLASS__, 'review_routes']);
        add_action('rest_api_init', [__CLASS__, 'register_routes']);
        add_action('admin_menu', [__CLASS__, 'admin_menu'], 65);
        add_action('admin_init', [__CLASS__, 'register_settings']);
        add_action('admin_init', [__CLASS__, 'maybe_shadow_reconcile'], 20);
        add_filter('nenotv_entitlement_request', [__CLASS__, 'operations_adapter'], 20, 4);
        add_action('woocommerce_thankyou', [__CLASS__, 'thankyou_activation'], 25, 1);
        add_action('woocommerce_email_after_order_table', [__CLASS__, 'email_activation'], 25, 4);
        add_action('init', [__CLASS__, 'maybe_upgrade']);
        add_filter('plugin_action_links_' . plugin_basename(__FILE__), [__CLASS__, 'plugin_action_links']);
        add_shortcode('nenotv_account_pro', [__CLASS__, 'shortcode_account_pro']);
        add_action('admin_post_nenotv_device_revoke', [__CLASS__, 'handle_device_revoke']);
        add_action('admin_post_nenotv_device_rename', [__CLASS__, 'handle_device_rename']);
        add_action('admin_post_nenotv_activation_regenerate', [__CLASS__, 'handle_activation_regenerate']);
        add_filter('nenotv_can_buy_plan', [__CLASS__, 'purchase_guard'], 20, 2);
        add_action('admin_post_nenotv_upgrade_multi', [__CLASS__, 'handle_upgrade_multi']);
        add_action('admin_post_nenotv_source_add', [__CLASS__, 'handle_source_add']);
        add_action('admin_post_nenotv_source_delete', [__CLASS__, 'handle_source_delete']);
        add_action('admin_post_nenotv_source_edit', [__CLASS__, 'handle_source_edit']);
        add_action(self::CRON, [__CLASS__, 'daily_maintenance']);
        add_filter('woocommerce_is_purchasable', [__CLASS__, 'upgrade_product_purchasable'], 20, 2);
        add_action('woocommerce_before_calculate_totals', [__CLASS__, 'price_upgrade_cart'], 20, 1);
        add_action('woocommerce_cart_emptied', [__CLASS__, 'clear_upgrade_session']);
        add_action('woocommerce_checkout_create_order', [__CLASS__, 'store_upgrade_order_meta'], 25, 1);
        add_action('woocommerce_store_api_checkout_update_order_from_request', [__CLASS__, 'store_upgrade_order_meta'], 25, 1);
    }

    public static function activate(): void {
        self::install_tables();
        self::set_default_option(self::OPT_MODE, 'shadow');
        self::set_default_option(self::OPT_PLAN, 'unconfigured');
        self::set_default_option(self::OPT_MAX_DEVICES, '5');
        self::set_default_option(self::OPT_TOKEN_DAYS, '30');
        self::set_default_option(self::OPT_TRIAL_ENABLED, '0');
        self::set_default_option(self::OPT_TRIAL_DAYS, '30');
        self::provision_bridge_config();
        if (!wp_next_scheduled(self::CRON)) wp_schedule_event(time() + 600, 'daily', self::CRON);
    }

    public static function deactivate(): void {
        $ts=wp_next_scheduled(self::CRON);
        if($ts)wp_unschedule_event($ts,self::CRON);
    }

    private static function set_default_option(string $name, $value): void {
        if (get_option($name, null) === null) add_option($name, $value, '', false);
    }

    public static function maybe_upgrade(): void {
        if ((string)get_option(self::OPT_DB_VERSION, '') !== self::DB_VERSION) self::install_tables();
        self::set_default_option(self::OPT_TRIAL_ENABLED, '0');
        self::set_default_option(self::OPT_TRIAL_DAYS, '30');
        self::provision_bridge_config();
        if (!wp_next_scheduled(self::CRON)) wp_schedule_event(time() + 600, 'daily', self::CRON);
    }

    private static function install_tables(): void {
        global $wpdb;
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';
        $charset = $wpdb->get_charset_collate();
        $ent = self::ent_table();
        $dev = self::dev_table();
        $evt = self::event_table();
        $src = self::source_table();

        dbDelta("CREATE TABLE {$ent} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            reference varchar(64) NOT NULL,
            email varchar(190) NOT NULL DEFAULT '',
            email_hash char(64) NOT NULL DEFAULT '',
            level varchar(32) NOT NULL DEFAULT 'pro',
            plan varchar(32) NOT NULL DEFAULT 'unconfigured',
            status varchar(32) NOT NULL DEFAULT 'shadow',
            max_devices int(11) NOT NULL DEFAULT 1,
            source varchar(32) NOT NULL DEFAULT '',
            source_ref varchar(100) NOT NULL DEFAULT '',
            payment_mode varchar(16) NOT NULL DEFAULT '',
            language varchar(8) NOT NULL DEFAULT 'en',
            activation_hash char(64) NOT NULL DEFAULT '',
            activation_expires_at datetime NULL,
            starts_at datetime NULL,
            expires_at datetime NULL,
            created_at datetime NOT NULL,
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY reference (reference),
            UNIQUE KEY source_ref_unique (source,source_ref),
            KEY email_hash (email_hash),
            KEY status (status),
            KEY activation_hash (activation_hash)
        ) {$charset};");

        dbDelta("CREATE TABLE {$dev} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            entitlement_id bigint(20) unsigned NOT NULL,
            device_id varchar(190) NOT NULL,
            public_device_id varchar(100) NOT NULL DEFAULT '',
            device_key_hash char(64) NOT NULL,
            platform varchar(100) NOT NULL DEFAULT '',
            display_name varchar(80) NOT NULL DEFAULT '',
            app_version varchar(60) NOT NULL DEFAULT '',
            status varchar(32) NOT NULL DEFAULT 'active',
            created_at datetime NOT NULL,
            last_seen_at datetime NOT NULL,
            revoked_at datetime NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY device_id (device_id),
            KEY entitlement_id (entitlement_id),
            KEY status (status)
        ) {$charset};");

        dbDelta("CREATE TABLE {$evt} (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            event_id varchar(100) NOT NULL,
            event_type varchar(60) NOT NULL,
            entitlement_ref varchar(64) NOT NULL DEFAULT '',
            source_ref varchar(100) NOT NULL DEFAULT '',
            outcome varchar(32) NOT NULL DEFAULT '',
            message varchar(255) NOT NULL DEFAULT '',
            created_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY event_id (event_id),
            KEY entitlement_ref (entitlement_ref),
            KEY source_ref (source_ref),
            KEY created_at (created_at)
        ) {$charset};");

        dbDelta("CREATE TABLE {$src} (
            entitlement_id bigint(20) unsigned NOT NULL,
            revision bigint(20) unsigned NOT NULL DEFAULT 1,
            payload longtext NOT NULL,
            iv varchar(64) NOT NULL DEFAULT '',
            tag varchar(64) NOT NULL DEFAULT '',
            updated_at datetime NOT NULL,
            PRIMARY KEY  (entitlement_id),
            KEY updated_at (updated_at)
        ) {$charset};");

        update_option(self::OPT_DB_VERSION, self::DB_VERSION, false);
    }

    private static function provision_bridge_config(): void {
        $base = rest_url('nenotv-backend');
        if (!filter_var((string)get_option(self::OPT_BRIDGE_URL, ''), FILTER_VALIDATE_URL)) {
            update_option(self::OPT_BRIDGE_URL, untrailingslashit($base), false);
        }
        $secret = (string)get_option(self::OPT_BRIDGE_SECRET, '');
        if (strlen($secret) < 32) {
            try { $secret = bin2hex(random_bytes(32)); }
            catch (Throwable $e) { $secret = wp_generate_password(64, true, true); }
            update_option(self::OPT_BRIDGE_SECRET, $secret, false);
        }
        // Never enable the production app bridge merely by installing this plugin.
        self::set_default_option(self::OPT_BRIDGE_ENABLED, '0');
    }

    private static function mode(): string {
        return get_option(self::OPT_MODE, 'shadow') === 'live' ? 'live' : 'shadow';
    }

    private static function plan(): string {
        // Legacy global policy retained only for backwards-compatible diagnostics.
        $v = (string)get_option(self::OPT_PLAN, 'unconfigured');
        return in_array($v, ['annual','lifetime'], true) ? $v : 'unconfigured';
    }

    private static function plan_for_order($order): string {
        if (!$order || !method_exists($order, 'get_items')) return 'unconfigured';

        $variants = [];
        foreach ($order->get_items() as $item) {
            $product = method_exists($item, 'get_product') ? $item->get_product() : null;
            if (!$product) return 'unconfigured';

            $sku = strtoupper(trim((string)$product->get_sku()));
            $variant = '';
            if (in_array($sku, ['NENOTV-PRO-1-YEARLY','NENOTV-PRO-5-YEARLY'], true)) {
                $variant = 'annual:' . ($sku === 'NENOTV-PRO-1-YEARLY' ? '1' : '5');
            } elseif (in_array($sku, ['NENOTV-PRO-1-LIFETIME','NENOTV-PRO-5-LIFETIME'], true)) {
                $variant = 'lifetime:' . ($sku === 'NENOTV-PRO-1-LIFETIME' ? '1' : '5');
            } elseif ($sku === 'NENOTV-PRO-YEARLY') {
                $variant = 'annual:5'; // Backwards compatibility with the old pre-launch SKU.
            } elseif ($sku === 'NENOTV-PRO-LIFETIME') {
                $variant = 'lifetime:5'; // Backwards compatibility with the old pre-launch SKU.
            } elseif ($sku === 'NENOTV-PRO') {
                $variant = 'test:' . max(1, min(25, absint(get_option(self::OPT_MAX_DEVICES, 5))));
            } elseif (str_starts_with($sku, 'NENOTV-')) {
                return 'unconfigured';
            } else {
                return 'unconfigured';
            }
            $variants[$variant] = true;
        }

        if (count($variants) !== 1) return 'mixed';
        $variant = (string)array_key_first($variants);
        return str_starts_with($variant, 'annual:') ? 'annual' : (str_starts_with($variant, 'lifetime:') ? 'lifetime' : (str_starts_with($variant, 'test:') ? 'test' : 'unconfigured'));
    }

    private static function max_devices_for_order($order): int {
        if (!$order || !method_exists($order, 'get_items')) return max(1, min(25, absint(get_option(self::OPT_MAX_DEVICES, 5))));

        $limits = [];
        foreach ($order->get_items() as $item) {
            $product = method_exists($item, 'get_product') ? $item->get_product() : null;
            if (!$product) continue;
            $sku = strtoupper(trim((string)$product->get_sku()));

            if (in_array($sku, ['NENOTV-PRO-1-YEARLY','NENOTV-PRO-1-LIFETIME'], true)) {
                $limits[1] = true;
            } elseif (in_array($sku, ['NENOTV-PRO-5-YEARLY','NENOTV-PRO-5-LIFETIME','NENOTV-PRO-YEARLY','NENOTV-PRO-LIFETIME'], true)) {
                $limits[5] = true;
            } elseif ($sku === 'NENOTV-PRO') {
                $limits[max(1, min(25, absint(get_option(self::OPT_MAX_DEVICES, 5))))] = true;
            }
        }

        if (count($limits) !== 1) return max(1, min(25, absint(get_option(self::OPT_MAX_DEVICES, 5))));
        return (int)array_key_first($limits);
    }

    private static function now_mysql(): string { return gmdate('Y-m-d H:i:s'); }
    private static function normalize_email(string $email): string { return strtolower(sanitize_email(trim($email))); }
    private static function normalize_language(string $language): string { $language=strtolower(sanitize_key($language)); return in_array($language,['en','nl','de'],true)?$language:'en'; }
    private static function email_hash(string $email): string { return $email === '' ? '' : hash('sha256', self::normalize_email($email)); }
    private static function key_hash(string $value): string { return hash_hmac('sha256', $value, wp_salt('auth')); }
    private static function ref(): string { return 'ent_' . strtolower(wp_generate_password(18, false, false)); }

    private static function log_event(string $event_type, string $ent_ref, string $source_ref, string $outcome, string $message, string $event_id = ''): void {
        global $wpdb;
        if ($event_id === '') $event_id = 'evt:' . sanitize_key($event_type) . ':' . wp_generate_uuid4();
        $wpdb->query($wpdb->prepare(
            'INSERT IGNORE INTO ' . self::event_table() . ' (event_id,event_type,entitlement_ref,source_ref,outcome,message,created_at) VALUES (%s,%s,%s,%s,%s,%s,%s)',
            substr($event_id,0,100), substr(sanitize_key($event_type),0,60), substr($ent_ref,0,64), substr($source_ref,0,100), substr(sanitize_key($outcome),0,32), substr(sanitize_text_field($message),0,255), self::now_mysql()
        ));
    }

    private static function order_payment_mode($order): string {
        if (!$order || !method_exists($order, 'get_meta')) return '';
        $mode = strtolower(trim((string)$order->get_meta('_mollie_payment_mode', true)));
        return in_array($mode, ['test','live'], true) ? $mode : '';
    }

    private static function order_email($order): string {
        if (!$order) return '';
        $email = method_exists($order, 'get_billing_email') ? (string)$order->get_billing_email() : '';
        return self::normalize_email($email);
    }

    private static function source_ref_for_order($order): string {
        return $order && method_exists($order, 'get_id') ? 'order:' . absint($order->get_id()) : '';
    }

    private static function find_by_source(string $source, string $source_ref): ?array {
        global $wpdb;
        $row = $wpdb->get_row($wpdb->prepare('SELECT * FROM ' . self::ent_table() . ' WHERE source=%s AND source_ref=%s LIMIT 1', $source, $source_ref), ARRAY_A);
        return is_array($row) ? $row : null;
    }

    private static function find_by_ref(string $ref): ?array {
        global $wpdb;
        $row = $wpdb->get_row($wpdb->prepare('SELECT * FROM ' . self::ent_table() . ' WHERE reference=%s LIMIT 1', $ref), ARRAY_A);
        return is_array($row) ? $row : null;
    }

    private static function find_by_id(int $id): ?array {
        global $wpdb;
        $row = $wpdb->get_row($wpdb->prepare('SELECT * FROM ' . self::ent_table() . ' WHERE id=%d LIMIT 1', $id), ARRAY_A);
        return is_array($row) ? $row : null;
    }

    private static function activation_code(): string {
        $raw = strtoupper(wp_generate_password(16, false, false));
        return 'NENO-' . substr($raw,0,4) . '-' . substr($raw,4,4) . '-' . substr($raw,8,4) . '-' . substr($raw,12,4);
    }

    private static function upsert_order_entitlement($order, string $status, bool $issue_token): array {
        global $wpdb;
        $source = 'woocommerce';
        $source_ref = self::source_ref_for_order($order);
        $email = self::order_email($order);
        $payment_mode = self::order_payment_mode($order);
        $now = self::now_mysql();
        $existing = self::find_by_source($source, $source_ref);
        $reference = $existing['reference'] ?? self::ref();
        $max_devices = self::max_devices_for_order($order);
        $plan = self::plan_for_order($order);
        $starts = $status === 'active' ? $now : null;
        $expires = null;
        if ($status === 'active' && $plan === 'annual') $expires = gmdate('Y-m-d H:i:s', strtotime('+1 year', time()));

        $plain_token = '';
        $activation_hash = (string)($existing['activation_hash'] ?? '');
        $activation_expires = $existing['activation_expires_at'] ?? null;
        if ($issue_token) {
            $plain_token = self::activation_code();
            $activation_hash = self::key_hash($plain_token);
            $days = max(1, min(365, absint(get_option(self::OPT_TOKEN_DAYS, 30))));
            $activation_expires = gmdate('Y-m-d H:i:s', time() + DAY_IN_SECONDS * $days);
        }

        $data = [
            'reference' => $reference,
            'email' => $email,
            'email_hash' => self::email_hash($email),
            'level' => 'pro',
            'plan' => $plan,
            'status' => $status,
            'max_devices' => $max_devices,
            'source' => $source,
            'source_ref' => $source_ref,
            'payment_mode' => $payment_mode,
            'language' => self::order_language($order),
            'activation_hash' => $activation_hash,
            'activation_expires_at' => $activation_expires,
            'starts_at' => $existing['starts_at'] ?? $starts,
            'expires_at' => $existing['expires_at'] ?? $expires,
            'updated_at' => $now,
        ];

        if ($existing) {
            $wpdb->update(self::ent_table(), $data, ['id'=>(int)$existing['id']], null, ['%d']);
        } else {
            $data['created_at'] = $now;
            $wpdb->insert(self::ent_table(), $data);
        }

        $row = self::find_by_source($source, $source_ref);
        if ($order && method_exists($order, 'update_meta_data')) {
            $order->update_meta_data('_nenotv_entitlement_reference', $reference);
            if ($plain_token !== '') $order->update_meta_data('_nenotv_activation_token', $plain_token);
            $order->save();
        }
        return ['row'=>$row, 'token'=>$plain_token];
    }

    /**
     * Grant an idempotent promotional Pro entitlement.
     * Intended for non-payment rewards such as completed Founding Tester participation.
     * Safe by design: this refuses to grant anything while Entitlement Core is in shadow mode.
     */
    public static function grant_promotional_entitlement(array $args): array {
        global $wpdb;

        if (self::mode() !== 'live') {
            return ['ok'=>false,'code'=>'shadow_mode','message'=>'Promotional Pro cannot be granted while Entitlement Core is in shadow mode.'];
        }

        $email = self::normalize_email((string)($args['email'] ?? ''));
        $source = sanitize_key((string)($args['source'] ?? 'promotion'));
        $source_ref = substr(sanitize_text_field((string)($args['source_ref'] ?? '')),0,100);
        $language = self::normalize_language((string)($args['language'] ?? 'en'));
        $days = max(1,min(3650,absint($args['days'] ?? 365)));
        $max_devices = max(1,min(25,absint($args['max_devices'] ?? 1)));
        $rotate_token = !empty($args['rotate_token']);

        if (!is_email($email) || $source === '' || $source_ref === '') {
            return ['ok'=>false,'code'=>'invalid_promotion','message'=>'Email, source and source reference are required.'];
        }

        $existing = self::find_by_source($source,$source_ref);
        if (is_array($existing)) {
            if (($existing['status'] ?? '') !== 'active') {
                return ['ok'=>false,'code'=>'existing_inactive','message'=>'This promotional entitlement already exists but is not active.','reference'=>(string)($existing['reference'] ?? '')];
            }

            $token = '';
            if ($rotate_token) {
                $token = self::activation_code();
                $token_days = max(1,min(365,absint(get_option(self::OPT_TOKEN_DAYS,30))));
                $wpdb->update(self::ent_table(),[
                    'activation_hash'=>self::key_hash($token),
                    'activation_expires_at'=>gmdate('Y-m-d H:i:s',time()+DAY_IN_SECONDS*$token_days),
                    'updated_at'=>self::now_mysql(),
                ],['id'=>(int)$existing['id']]);
                self::log_event('promotion_token_rotated',(string)$existing['reference'],$source_ref,'success','Promotional activation code regenerated.');
            }

            return [
                'ok'=>true,
                'replay'=>true,
                'reference'=>(string)$existing['reference'],
                'activation_token'=>$token,
                'starts_at'=>(string)($existing['starts_at'] ?? ''),
                'expires_at'=>(string)($existing['expires_at'] ?? ''),
            ];
        }

        $now = self::now_mysql();
        $expires = gmdate('Y-m-d H:i:s',time()+DAY_IN_SECONDS*$days);
        $reference = self::ref();
        $token = self::activation_code();
        $token_days = max(1,min(365,absint(get_option(self::OPT_TOKEN_DAYS,30))));
        $activation_expires = gmdate('Y-m-d H:i:s',time()+DAY_IN_SECONDS*$token_days);

        $ok = $wpdb->insert(self::ent_table(),[
            'reference'=>$reference,
            'email'=>$email,
            'email_hash'=>self::email_hash($email),
            'level'=>'pro',
            'plan'=>'annual',
            'status'=>'active',
            'max_devices'=>$max_devices,
            'source'=>$source,
            'source_ref'=>$source_ref,
            'payment_mode'=>'promotion',
            'language'=>$language,
            'activation_hash'=>self::key_hash($token),
            'activation_expires_at'=>$activation_expires,
            'starts_at'=>$now,
            'expires_at'=>$expires,
            'created_at'=>$now,
            'updated_at'=>$now,
        ]);

        if (!$ok) {
            return ['ok'=>false,'code'=>'promotion_create_failed','message'=>'The promotional entitlement could not be created.'];
        }

        self::log_event('promotion_grant',$reference,$source_ref,'success','Promotional Pro entitlement granted.');
        return [
            'ok'=>true,
            'replay'=>false,
            'reference'=>$reference,
            'activation_token'=>$token,
            'starts_at'=>$now,
            'expires_at'=>$expires,
        ];
    }

    private static function change_order_entitlement_status($order, string $status): array {
        global $wpdb;
        $source_ref = self::source_ref_for_order($order);
        $row = self::find_by_source('woocommerce', $source_ref);
        if (!$row) return ['ok'=>false,'reference'=>'','message'=>'No entitlement exists for this order.'];
        $wpdb->update(self::ent_table(), ['status'=>$status,'updated_at'=>self::now_mysql()], ['id'=>(int)$row['id']], null, ['%d']);
        if (in_array($status, ['revoked','suspended'], true)) {
            $wpdb->update(self::dev_table(), ['status'=>'revoked','revoked_at'=>self::now_mysql()], ['entitlement_id'=>(int)$row['id']]);
        }
        if ($status === 'revoked' && $order && method_exists($order, 'delete_meta_data')) {
            $order->delete_meta_data('_nenotv_activation_token');
            $order->save();
        }
        self::log_event('entitlement_' . $status, $row['reference'], $source_ref, 'success', 'Entitlement status changed to ' . $status . '.');
        return ['ok'=>true,'reference'=>$row['reference'],'message'=>'Entitlement ' . $status . '.'];
    }

    private static function order_is_nenotv_only($order): bool {
        if (!$order || !method_exists($order, 'get_items')) return false;
        $items = $order->get_items();
        if (!$items) return false;
        $has_nenotv = false;
        foreach ($items as $item) {
            $product = method_exists($item, 'get_product') ? $item->get_product() : null;
            if (!$product) return false;
            $sku = strtoupper(trim((string)$product->get_sku()));
            if ($sku === '' || strpos($sku, 'NENOTV-') !== 0) return false;
            $has_nenotv = true;
        }
        return $has_nenotv;
    }

    /**
     * One-time safe reconciliation for paid SunnyIPTV test orders that existed before
     * Entitlement Core was installed. Shadow mode never grants Pro access.
     */
    public static function maybe_shadow_reconcile(): void {
        if (!current_user_can('manage_woocommerce') && !current_user_can('manage_options')) return;
        if (self::mode() !== 'shadow') return;
        if ((string)get_option(self::OPT_RECONCILE_VERSION, '') === '1') return;
        if (!function_exists('wc_get_orders')) return;

        $count = 0;
        try {
            $orders = wc_get_orders([
                'limit' => 100,
                'status' => ['processing', 'completed', 'refunded'],
                'orderby' => 'date',
                'order' => 'DESC',
            ]);
            foreach ($orders as $order) {
                if (!$order || !method_exists($order, 'get_date_paid') || !$order->get_date_paid()) continue;
                if (!self::order_is_nenotv_only($order)) continue;
                $payment_mode = self::order_payment_mode($order);
                if ($payment_mode !== '' && $payment_mode !== 'test') continue;

                $source_ref = self::source_ref_for_order($order);
                $existing = self::find_by_source('woocommerce', $source_ref);
                if ($existing) continue;

                $rec = self::upsert_order_entitlement($order, 'shadow', false);
                $ref = is_array($rec['row'] ?? null) ? (string)$rec['row']['reference'] : '';
                if ($ref === '') continue;

                self::log_event(
                    'reconcile_shadow',
                    $ref,
                    $source_ref,
                    'success',
                    'Existing paid SunnyIPTV test order reconciled into shadow entitlement ledger.',
                    'evt:reconcile-shadow:' . absint($order->get_id())
                );
                if (method_exists($order, 'add_order_note')) {
                    $order->add_order_note('SunnyIPTV Entitlement Core: existing paid test order reconciled into the shadow entitlement ledger. No Pro access was issued.');
                }
                do_action('nenotv_entitlement_shadow_reconciled', absint($order->get_id()), $order, $ref);
                $count++;
            }
        } catch (Throwable $e) {
            self::log_event('reconcile_shadow', '', '', 'failed', 'Shadow reconciliation failed: ' . sanitize_text_field($e->getMessage()));
            return; // Do not mark complete; retry safely on the next admin request.
        }

        update_option(self::OPT_RECONCILE_LAST_COUNT, (string)$count, false);
        update_option(self::OPT_RECONCILE_VERSION, '1', false);
    }

    private static function tester_reward_order_adapter(string $action, $order): array {
        $blocked = ['handled'=>true,'success'=>false,'message'=>'Tester gift must be managed by the tester reward workflow.','reference'=>''];
        if ($action !== 'grant' || (float)$order->get_total() !== 0.0) return $blocked;
        $key = (string)$order->get_meta('_nenotv_tester_reward_key', true);
        if (!preg_match('/^30-day-solo-v1:([1-9][0-9]*)$/', $key, $match)) return $blocked;
        $ref = (string)$order->get_meta('_nenotv_entitlement_reference', true);
        $row = self::find_by_ref($ref);
        if (!$row || ($row['source'] ?? '') !== 'founding_tester_30'
            || ($row['source_ref'] ?? '') !== 'tester:' . $match[1] . ':30-day-solo-v1'
            || self::normalize_email((string)$row['email']) !== self::order_email($order)
            || self::plan_for_order($order) !== 'annual' || self::max_devices_for_order($order) !== 1
            || !self::entitlement_is_active($row)) return $blocked;
        return ['handled'=>true,'success'=>true,'message'=>'Existing tester gift confirmed; no paid entitlement created.','reference'=>$ref];
    }

    public static function operations_adapter($default, $action, $order, $context) {
        if (!is_array($default)) $default = [];
        if (!$order || !method_exists($order, 'get_id')) return $default;
        $action = sanitize_key((string)$action);
        if ($order->get_created_via() === 'nenotv_tester_reward') {
            return self::tester_reward_order_adapter($action, $order);
        }
        $payment_mode = self::order_payment_mode($order);
        $source_ref = self::source_ref_for_order($order);

        if ($action === 'grant') {
            if (self::is_upgrade_order($order)) {
                return self::handle_upgrade_grant($order, self::mode() === 'shadow');
            }

            if (self::mode() === 'shadow') {
                $rec = self::upsert_order_entitlement($order, 'shadow', false);
                $ref = is_array($rec['row'] ?? null) ? (string)$rec['row']['reference'] : '';
                self::log_event('grant_shadow', $ref, $source_ref, $payment_mode === 'test' ? 'success' : 'blocked', 'Payment entitlement recorded in shadow mode.');
                if ($payment_mode === 'test' || $payment_mode === '') {
                    return ['handled'=>true,'success'=>true,'message'=>'TEST payment recorded in entitlement shadow mode; no production Pro access issued.','reference'=>$ref];
                }
                return ['handled'=>true,'success'=>false,'message'=>'Entitlement Core is still in shadow mode. Live Pro access was not issued.','reference'=>$ref];
            }

            $order_plan = self::plan_for_order($order);
            if (!in_array($order_plan, ['annual','lifetime'], true)) {
                self::log_event('grant_live', '', $source_ref, 'blocked', 'Live grant blocked because the order does not resolve to one supported commercial plan.', 'evt:grant-live-blocked-plan:' . absint($order->get_id()));
                return ['handled'=>true,'success'=>false,'message'=>'This order does not map to a supported SunnyIPTV Pro plan.','reference'=>''];
            }

            $existing = self::find_by_source('woocommerce', $source_ref);
            if (is_array($existing)) {
                $existing_status = (string)($existing['status'] ?? '');
                $existing_plan = (string)($existing['plan'] ?? '');
                if ($existing_status === 'active') {
                    if ($existing_plan === $order_plan && self::entitlement_is_active($existing)) {
                        self::log_event('grant_live_replay', (string)$existing['reference'], $source_ref, 'success', 'Duplicate live grant ignored; existing entitlement remains active.', 'evt:grant-live-replay:' . absint($order->get_id()));
                        return ['handled'=>true,'success'=>true,'message'=>'SunnyIPTV Pro access was already granted for this payment.','reference'=>(string)$existing['reference']];
                    }
                    self::log_event('grant_live_replay', (string)$existing['reference'], $source_ref, 'blocked', 'Duplicate live grant blocked because the existing entitlement is expired or does not match the paid plan.', 'evt:grant-live-replay-blocked:' . absint($order->get_id()));
                    return ['handled'=>true,'success'=>false,'message'=>'The existing entitlement for this payment cannot be re-granted automatically.','reference'=>(string)$existing['reference']];
                }
                if (in_array($existing_status, ['revoked','suspended'], true)) {
                    self::log_event('grant_live_replay', (string)$existing['reference'], $source_ref, 'blocked', 'Duplicate live grant blocked because this payment entitlement was previously ' . $existing_status . '.', 'evt:grant-live-replay-blocked-status:' . absint($order->get_id()));
                    return ['handled'=>true,'success'=>false,'message'=>'This payment entitlement was previously ' . $existing_status . ' and will not be reactivated by a replayed payment event.','reference'=>(string)$existing['reference']];
                }
                if ($existing_status !== 'shadow') {
                    return ['handled'=>true,'success'=>false,'message'=>'The existing entitlement is in an unsupported state for automatic grant.','reference'=>(string)$existing['reference']];
                }
            }

            $rec = self::upsert_order_entitlement($order, 'active', true);
            $ref = is_array($rec['row'] ?? null) ? (string)$rec['row']['reference'] : '';
            if ($ref === '') return ['handled'=>true,'success'=>false,'message'=>'Entitlement could not be stored.','reference'=>''];
            self::log_event('grant_live', $ref, $source_ref, 'success', 'Live Pro entitlement granted.', 'evt:grant-live:' . absint($order->get_id()));
            return ['handled'=>true,'success'=>true,'message'=>'SunnyIPTV Pro access granted automatically.','reference'=>$ref];
        }

        if ($action === 'revoke') {
            if (self::is_upgrade_order($order)) return self::revert_upgrade_order($order, 'refund');
            $r = self::change_order_entitlement_status($order, 'revoked');
            if (!$r['ok'] && $payment_mode === 'test') return ['handled'=>true,'success'=>true,'message'=>'TEST refund recorded; no production entitlement existed.','reference'=>''];
            return ['handled'=>true,'success'=>(bool)$r['ok'],'message'=>$r['message'],'reference'=>$r['reference']];
        }

        if ($action === 'suspend') {
            if (self::is_upgrade_order($order)) return self::revert_upgrade_order($order, 'payment_regression');
            $r = self::change_order_entitlement_status($order, 'suspended');
            if (!$r['ok'] && $payment_mode === 'test') return ['handled'=>true,'success'=>true,'message'=>'TEST suspension recorded; no production entitlement existed.','reference'=>''];
            return ['handled'=>true,'success'=>(bool)$r['ok'],'message'=>$r['message'],'reference'=>$r['reference']];
        }

        return $default;
    }

    public static function register_routes(): void {
        self::pairing_routes();
        register_rest_route(self::NS, '/health', [
            'methods'=>'GET', 'callback'=>[__CLASS__,'health'], 'permission_callback'=>'__return_true'
        ]);
        foreach (['refresh','claim','redeem','trial'] as $action) {
            register_rest_route(self::NS, '/entitlement/' . $action, [
                'methods'=>'POST',
                'callback'=>function(WP_REST_Request $r) use ($action) { return NenoTV_Entitlement_Core::app_entitlement($r, $action); },
                'permission_callback'=>'__return_true',
            ]);
        }
        register_rest_route(self::NS, '/provider/chargeback', [
            'methods'=>'POST', 'callback'=>[__CLASS__,'provider_chargeback'], 'permission_callback'=>'__return_true'
        ]);
        foreach (['pull','push'] as $action) {
            register_rest_route(self::APP_NS, '/sources/' . $action, [
                'methods'=>'POST',
                'callback'=>function(WP_REST_Request $r) use ($action) { return NenoTV_Entitlement_Core::app_sources($r, $action); },
                'permission_callback'=>'__return_true',
            ]);
        }
    }

    public static function health(WP_REST_Request $request): WP_REST_Response {
        return self::json(['ok'=>true], 200);
    }

    private static function authenticate_backend_request(WP_REST_Request $request): true|WP_Error {
        // Select one complete header family; both require the existing HMAC and replay checks.
        $prefix = $request->get_header('x-sunnyiptv-signature') !== '' ? 'x-sunnyiptv-' : 'x-nenotv-';
        $timestamp = (string)$request->get_header($prefix.'timestamp');
        $event_id = substr(sanitize_text_field((string)$request->get_header($prefix.'event-id')), 0, 100);
        $signature = (string)$request->get_header($prefix.'signature');
        if ($timestamp === '' || $event_id === '' || $signature === '') return new WP_Error('missing_auth','Missing backend authentication headers.',['status'=>401]);
        if (!ctype_digit($timestamp) || abs(time() - (int)$timestamp) > 300) return new WP_Error('stale_request','Request timestamp is outside the allowed window.',['status'=>401]);
        if (get_transient('nenotv_evt_' . md5($event_id))) return new WP_Error('replay','Duplicate backend event.',['status'=>409]);
        $secret = (string)get_option(self::OPT_BRIDGE_SECRET, '');
        if (strlen($secret) < 32) return new WP_Error('not_configured','Backend secret is not configured.',['status'=>503]);
        $body = (string)$request->get_body();
        $expected = 'v1=' . hash_hmac('sha256', $timestamp . '.' . $body, $secret);
        if (!hash_equals($expected, $signature)) return new WP_Error('bad_signature','Invalid backend signature.',['status'=>401]);
        set_transient('nenotv_evt_' . md5($event_id), 1, 10 * MINUTE_IN_SECONDS);
        return true;
    }

    private static function clean_app_payload(WP_REST_Request $request): array {
        $raw = $request->get_json_params(); if (!is_array($raw)) $raw=[];
        $allowed=['device_id','public_device_id','device_key','platform','app_version','email','order_id','activation_token','language'];
        $out=[];
        foreach($allowed as $k){ if(!array_key_exists($k,$raw))continue; $v=is_scalar($raw[$k])?(string)$raw[$k]:''; $out[$k]=substr(trim($v),0,in_array($k,['device_key','activation_token'],true)?512:190); }
        if (!empty($out['email'])) $out['email']=self::normalize_email($out['email']);
        if (!empty($out['language'])) $out['language']=self::normalize_language($out['language']);
        return $out;
    }

    private static function free_entitlement(): array {
        return ['level'=>'free','status'=>'free','max_devices'=>1,'expires_at_ms'=>0,'server_time_ms'=>time()*1000,'email'=>''];
    }

    private static function trial_enabled(): bool {
        return get_option(self::OPT_TRIAL_ENABLED, '0') === '1';
    }

    private static function trial_days(): int {
        return max(1, min(90, absint(get_option(self::OPT_TRIAL_DAYS, 30))));
    }

    private static function is_trial(array $row): bool {
        return ($row['plan'] ?? '') === 'trial' || ($row['level'] ?? '') === 'pro_trial';
    }

    private static function trial_payload(array $row): array {
        $expires_ms = 0;
        if (!empty($row['expires_at'])) {
            $ts = strtotime($row['expires_at'] . ' UTC');
            if ($ts) $expires_ms = $ts * 1000;
        }
        $active = (($row['status'] ?? '') === 'active') && ($expires_ms === 0 || $expires_ms > (time() * 1000));
        return [
            'level'=>'pro_trial',
            'status'=>$active ? 'trial_active' : 'trial_expired',
            'trial_state'=>$active ? 'active' : 'expired',
            'plan'=>'trial',
            'max_devices'=>(int)($row['max_devices'] ?? 1),
            'used_devices'=>self::count_active_devices((int)$row['id']),
            'expires_at_ms'=>$expires_ms,
            'server_time_ms'=>time()*1000,
            'email'=>(string)($row['email'] ?? ''),
        ];
    }

    private static function pro_payload(array $row): array {
        if (self::is_trial($row)) return self::trial_payload($row);
        $expires_ms = 0;
        if (!empty($row['expires_at'])) { $ts = strtotime($row['expires_at'] . ' UTC'); if ($ts) $expires_ms = $ts * 1000; }
        $used = self::count_active_devices((int)$row['id']);
        return ['level'=>'pro','status'=>'active','plan'=>(string)($row['plan'] ?? 'unconfigured'),'max_devices'=>(int)$row['max_devices'],'used_devices'=>$used,'free_devices'=>max(0,(int)$row['max_devices']-$used),'expires_at_ms'=>$expires_ms,'server_time_ms'=>time()*1000,'email'=>(string)$row['email']];
    }

    private static function find_trial_by_email(string $email): ?array {
        global $wpdb;
        $hash=self::email_hash($email);
        if ($hash==='') return null;
        $row=$wpdb->get_row($wpdb->prepare(
            'SELECT * FROM '.self::ent_table().' WHERE email_hash=%s AND (plan=%s OR level=%s) ORDER BY id DESC LIMIT 1',
            $hash,'trial','pro_trial'
        ),ARRAY_A);
        return is_array($row)?$row:null;
    }

    private static function start_trial(array $p): WP_REST_Response {
        if (self::mode() !== 'live') {
            return self::json(['ok'=>false,'error'=>'trial_not_live','message'=>'The SunnyIPTV trial is not live yet.'],200);
        }
        if (!self::trial_enabled()) {
            return self::json(['ok'=>false,'error'=>'trial_not_enabled','message'=>'The SunnyIPTV trial is not enabled yet.'],200);
        }

        $email=self::normalize_email((string)($p['email']??''));
        if ($email==='' || !is_email($email)) {
            return self::json(['ok'=>false,'error'=>'email_required','message'=>'A valid email address is required to start the SunnyIPTV trial.'],400);
        }

        $device_id=(string)($p['device_id']??'');
        $device_key=(string)($p['device_key']??'');
        $existing_device=self::find_device($device_id);
        if ($existing_device) {
            if (!hash_equals((string)$existing_device['device_key_hash'],self::key_hash($device_key))) {
                return self::json(['ok'=>false,'error'=>'device_key_mismatch','message'=>'This device identity could not be verified.'],200);
            }
            $existing_ent=self::find_by_id((int)$existing_device['entitlement_id']);
            if (is_array($existing_ent) && self::is_trial($existing_ent)) {
                return self::json(['ok'=>true,'entitlement'=>self::trial_payload($existing_ent),'mode'=>'live','message'=>'This device already has its SunnyIPTV trial record.'],200);
            }
            if (($existing_device['status']??'')==='active' && is_array($existing_ent) && self::entitlement_is_active($existing_ent)) {
                return self::json(['ok'=>true,'entitlement'=>self::pro_payload($existing_ent),'mode'=>'live','message'=>'SunnyIPTV Pro is already active on this device.'],200);
            }
        }

        $existing=self::find_trial_by_email($email);
        if (is_array($existing)) {
            if (self::entitlement_is_active($existing)) {
                $bound=self::bind_device($existing,$p);
                if (!$bound['ok']) return self::json($bound,200);
            }
            return self::json([
                'ok'=>true,
                'entitlement'=>self::trial_payload($existing),
                'mode'=>'live',
                'message'=>self::entitlement_is_active($existing)
                    ? 'Your existing SunnyIPTV trial is active.'
                    : 'Your SunnyIPTV trial has already ended.',
            ],200);
        }

        global $wpdb;
        $now=self::now_mysql();
        $expires=gmdate('Y-m-d H:i:s', time() + (self::trial_days() * DAY_IN_SECONDS));
        $reference=self::ref();
        $email_hash=self::email_hash($email);
        $inserted=$wpdb->insert(self::ent_table(),[
            'reference'=>$reference,
            'email'=>$email,
            'email_hash'=>$email_hash,
            'level'=>'pro_trial',
            'plan'=>'trial',
            'status'=>'active',
            'max_devices'=>1,
            'source'=>'trial',
            'source_ref'=>'trial-email:'.$email_hash,
            'payment_mode'=>'',
            'language'=>self::normalize_language((string)($p['language']??'en')),
            'activation_hash'=>'',
            'activation_expires_at'=>null,
            'starts_at'=>$now,
            'expires_at'=>$expires,
            'created_at'=>$now,
            'updated_at'=>$now,
        ]);
        if (!$inserted) {
            return self::json(['ok'=>false,'error'=>'trial_create_failed','message'=>'The SunnyIPTV trial could not be created.'],503);
        }

        $ent=self::find_by_id((int)$wpdb->insert_id);
        if (!is_array($ent)) {
            return self::json(['ok'=>false,'error'=>'trial_create_failed','message'=>'The SunnyIPTV trial could not be loaded.'],503);
        }
        $bound=self::bind_device($ent,$p);
        if (!$bound['ok']) {
            $wpdb->update(self::ent_table(),['status'=>'revoked','updated_at'=>self::now_mysql()],['id'=>(int)$ent['id']]);
            return self::json($bound,200);
        }

        self::log_event('trial_started',(string)$ent['reference'],(string)$p['device_id'],'success','SunnyIPTV 30-day trial started for this account and device.');
        $welcome_event='evt:trial-started:'.(int)$ent['id'];
        if (!self::event_exists($welcome_event)) {
            if (self::send_trial_notice($ent,'started')) {
                self::log_event('trial_email_started',(string)$ent['reference'],(string)$p['device_id'],'success','Trial start email sent automatically.',$welcome_event);
            } else {
                self::log_event('trial_email_failed',(string)$ent['reference'],(string)$p['device_id'],'failed','Trial start email could not be sent.');
            }
        }
        return self::json(['ok'=>true,'entitlement'=>self::trial_payload($ent),'mode'=>'live','message'=>'Your SunnyIPTV trial has started.'],200);
    }

    private static function entitlement_is_active(array $row): bool {
        if (($row['status'] ?? '') !== 'active') return false;
        if (!empty($row['expires_at'])) { $ts=strtotime($row['expires_at'].' UTC'); if ($ts && $ts < time()) return false; }
        return true;
    }

    private static function find_device(string $device_id): ?array {
        global $wpdb;
        $row=$wpdb->get_row($wpdb->prepare('SELECT * FROM '.self::dev_table().' WHERE device_id=%s LIMIT 1',$device_id),ARRAY_A);
        return is_array($row)?$row:null;
    }

    private static function count_active_devices(int $entitlement_id): int {
        global $wpdb;
        return (int)$wpdb->get_var($wpdb->prepare('SELECT COUNT(*) FROM '.self::dev_table().' WHERE entitlement_id=%d AND status=%s',$entitlement_id,'active'));
    }

    private static function find_by_activation(string $token): ?array {
        global $wpdb;
        if ($token==='') return null;
        $hash=self::key_hash($token);
        $row=$wpdb->get_row($wpdb->prepare('SELECT * FROM '.self::ent_table().' WHERE activation_hash=%s LIMIT 1',$hash),ARRAY_A);
        return is_array($row)?$row:null;
    }

    private static function bind_device(array $ent, array $p): array {
        global $wpdb;
        $lock='nenotv_bind_'.substr(hash('sha256',self::dev_table()),0,40);
        if((int)$wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 3)',$lock))!==1)return ['ok'=>false,'error'=>'device_busy','message'=>'Please retry linking this device.'];
        try{
            $current=self::find_by_id((int)$ent['id']);
            if(!is_array($current)||!self::entitlement_is_active($current))return ['ok'=>false,'error'=>'pro_inactive','message'=>'SunnyIPTV access is no longer active.'];
            return self::bind_device_unlocked($current,$p);
        }
        finally{$wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)',$lock));}
    }

    private static function bind_device_unlocked(array $ent, array $p): array {
        global $wpdb;
        $device_id = sanitize_text_field((string)($p['device_id'] ?? ''));
        $device_key = (string)($p['device_key'] ?? '');
        if ($device_id==='' || $device_key==='') return ['ok'=>false,'error'=>'invalid_device','message'=>'Missing SunnyIPTV device identity.'];
        $existing=self::find_device($device_id);
        $key_hash=self::key_hash($device_key);
        $now=self::now_mysql();
        if ($existing) {
            if (!hash_equals((string)$existing['device_key_hash'],$key_hash)) return ['ok'=>false,'error'=>'device_key_mismatch','message'=>'This device identity could not be verified.'];
            if ((int)$existing['entitlement_id'] !== (int)$ent['id'] && $existing['status']==='active') {
                $current=self::find_by_id((int)$existing['entitlement_id']);
                $trial_to_paid=is_array($current) && self::is_trial($current) && !self::is_trial($ent);
                if (!$trial_to_paid) return ['ok'=>false,'error'=>'device_already_linked','message'=>'This device is already linked to another SunnyIPTV entitlement.'];
                if (self::count_active_devices((int)$ent['id']) >= (int)$ent['max_devices']) {
                    return ['ok'=>false,'error'=>'device_limit','message'=>'The SunnyIPTV Pro device limit has been reached.'];
                }
            }
            if (($existing['status'] ?? '') !== 'active' && self::count_active_devices((int)$ent['id']) >= (int)$ent['max_devices']) {
                return ['ok'=>false,'error'=>'device_limit','message'=>'The SunnyIPTV Pro device limit has been reached.'];
            }
            $saved=$wpdb->update(self::dev_table(),[
                'entitlement_id'=>(int)$ent['id'],'public_device_id'=>sanitize_text_field((string)($p['public_device_id']??'')),'platform'=>sanitize_text_field((string)($p['platform']??'')),'app_version'=>sanitize_text_field((string)($p['app_version']??'')),'status'=>'active','last_seen_at'=>$now,'revoked_at'=>null
            ],['id'=>(int)$existing['id']]);
            if($saved===false)return ['ok'=>false,'error'=>'device_write_failed','message'=>'Device could not be linked.'];
            return ['ok'=>true];
        }
        if (self::count_active_devices((int)$ent['id']) >= (int)$ent['max_devices']) return ['ok'=>false,'error'=>'device_limit','message'=>'The SunnyIPTV Pro device limit has been reached.'];
        $saved=$wpdb->insert(self::dev_table(),[
            'entitlement_id'=>(int)$ent['id'],'device_id'=>$device_id,'public_device_id'=>sanitize_text_field((string)($p['public_device_id']??'')),'device_key_hash'=>$key_hash,'platform'=>sanitize_text_field((string)($p['platform']??'')),'app_version'=>sanitize_text_field((string)($p['app_version']??'')),'status'=>'active','created_at'=>$now,'last_seen_at'=>$now
        ]);
        if($saved===false)return ['ok'=>false,'error'=>'device_write_failed','message'=>'Device could not be linked.'];
        return ['ok'=>true];
    }

    private static function claim_activation_code(array $ent): bool {
        global $wpdb;
        $hash=(string)($ent['activation_hash']??'');
        if($hash==='')return false;
        $changed=$wpdb->query($wpdb->prepare(
            'UPDATE '.self::ent_table().' SET activation_hash=%s,activation_expires_at=NULL,updated_at=%s WHERE id=%d AND activation_hash=%s',
            '',self::now_mysql(),(int)$ent['id'],$hash
        ));
        return $changed===1;
    }

    private static function restore_activation_code(array $ent): void {
        global $wpdb;
        $hash=(string)($ent['activation_hash']??'');
        if($hash==='')return;
        $wpdb->query($wpdb->prepare(
            'UPDATE '.self::ent_table().' SET activation_hash=%s,activation_expires_at=%s,updated_at=%s WHERE id=%d AND activation_hash=%s',
            $hash,($ent['activation_expires_at']??null),self::now_mysql(),(int)$ent['id'],''
        ));
    }

    private static function finalize_activation_code(array $ent): void {
        $source_ref=(string)($ent['source_ref']??'');
        if(preg_match('/^order:(\d+)$/',$source_ref,$m)&&function_exists('wc_get_order')){
            $order=wc_get_order((int)$m[1]);
            if($order){
                $order->delete_meta_data('_nenotv_activation_token');
                $order->save();
            }
        }
    }

    public static function app_entitlement(WP_REST_Request $request, string $action, bool $device_auth=false): WP_REST_Response {
        // nenotv/v1 app routes authenticate with the device identity (SunnyIPTV_Play_Billing); nenotv-backend/v1 needs the signed bridge.
        if (!$device_auth) { $auth=self::authenticate_backend_request($request); if (is_wp_error($auth)) return self::wp_error_json($auth); }
        $p=self::clean_app_payload($request);
        if (empty($p['device_id']) || empty($p['device_key'])) return self::json(['ok'=>false,'error'=>'invalid_device','message'=>'Missing SunnyIPTV device identity.'],400);

        $review = self::review_entitlement_request($p, $action);
        if ($review !== null) return $review;

        // Safe by design: shadow mode never returns fabricated Pro access.
        if (self::mode() !== 'live') {
            if ($action==='refresh') return self::json(['ok'=>true,'entitlement'=>self::free_entitlement(),'mode'=>'shadow','message'=>'SunnyIPTV Pro is not live yet.'],200);
            return self::json(['ok'=>false,'error'=>'pro_not_live','message'=>'SunnyIPTV Pro activation is not live yet.'],200);
        }

        if ($action==='refresh') {
            $dev=self::find_device((string)$p['device_id']);
            if (!$dev || $dev['status']!=='active' || !hash_equals((string)$dev['device_key_hash'],self::key_hash((string)$p['device_key']))) return self::json(['ok'=>true,'entitlement'=>self::free_entitlement(),'mode'=>'live'],200);
            $ent=self::find_by_id((int)$dev['entitlement_id']);
            if (!is_array($ent)) return self::json(['ok'=>true,'entitlement'=>self::free_entitlement(),'mode'=>'live'],200);
            global $wpdb; $wpdb->update(self::dev_table(),['last_seen_at'=>self::now_mysql(),'platform'=>sanitize_text_field((string)($p['platform']??'')),'app_version'=>sanitize_text_field((string)($p['app_version']??''))],['id'=>(int)$dev['id']]);
            if (self::is_trial($ent)) return self::json(['ok'=>true,'entitlement'=>self::trial_payload($ent),'mode'=>'live'],200);
            if (!self::entitlement_is_active($ent)) return self::json(['ok'=>true,'entitlement'=>self::free_entitlement(),'mode'=>'live'],200);
            return self::json(['ok'=>true,'entitlement'=>self::pro_payload($ent),'mode'=>'live'],200);
        }

        if ($action==='trial') return self::start_trial($p);

        $token=(string)($p['activation_token']??'');
        $ent=self::find_by_activation($token);
        if (!$ent || !self::entitlement_is_active($ent)) return self::json(['ok'=>false,'error'=>'invalid_activation','message'=>'This SunnyIPTV activation code is invalid or inactive.'],200);
        if (!empty($ent['activation_expires_at']) && strtotime($ent['activation_expires_at'].' UTC') < time()) return self::json(['ok'=>false,'error'=>'activation_expired','message'=>'This SunnyIPTV activation code has expired.'],200);
        if(!self::claim_activation_code($ent)){
            return self::json(['ok'=>false,'error'=>'activation_used','message'=>'This SunnyIPTV activation code has already been used. Generate a new code in My SunnyIPTV for another device.'],200);
        }
        $bound=self::bind_device($ent,$p);
        if (!$bound['ok']) {
            self::restore_activation_code($ent);
            return self::json($bound,200);
        }
        self::finalize_activation_code($ent);
        self::log_event('device_'.$action,(string)$ent['reference'],(string)$p['device_id'],'success','Device linked to Pro entitlement; one-time activation code consumed atomically.');
        return self::json(['ok'=>true,'entitlement'=>self::pro_payload($ent),'mode'=>'live','message'=>'SunnyIPTV Pro activated.'],200);
    }

    private static function source_vault_key(): string {
        return hash('sha256', 'nenotv-source-vault-v1|' . wp_salt('auth'), true);
    }

    private static function sanitize_source_list($value): array {
        if (!is_array($value)) return [];
        $out=[];
        foreach (array_slice($value,0,20) as $row) {
            if (!is_array($row)) continue;
            $id=sanitize_text_field(substr((string)($row['id']??''),0,80));
            if ($id==='') $id=wp_generate_uuid4();
            $type=strtoupper(sanitize_key((string)($row['type']??'M3U')));
            if (!in_array($type,['M3U','XTREAM'],true)) $type='M3U';
            $epg_extra=[];
            foreach (array_slice((array)($row['epg_extra']??[]),0,8) as $epg_url) {
                $epg_url=esc_url_raw(substr(trim((string)$epg_url),0,2000));
                if ($epg_url!=='' && (str_starts_with($epg_url,'https://') || str_starts_with($epg_url,'http://'))) $epg_extra[]=$epg_url;
            }
            $clean=[
                'id'=>$id,
                'type'=>$type,
                'name'=>sanitize_text_field(substr((string)($row['name']??'TV source'),0,120)),
                'server'=>substr(trim((string)($row['server']??'')),0,1000),
                'username'=>substr(trim((string)($row['username']??'')),0,500),
                'password'=>substr((string)($row['password']??''),0,500),
                'm3u'=>substr(trim((string)($row['m3u']??'')),0,2000),
                'epg'=>substr(trim((string)($row['epg']??'')),0,2000),
                'epg_extra'=>$epg_extra,
                'bridge'=>substr(trim((string)($row['bridge']??'')),0,2000),
                'bridge_token'=>substr((string)($row['bridge_token']??''),0,1000),
                'enabled'=>!isset($row['enabled']) || (bool)$row['enabled'],
                'priority'=>max(0,min(99,(int)($row['priority']??0))),
                'updated_at'=>max(0,(int)($row['updated_at']??0)),
            ];
            $out[]=$clean;
        }
        usort($out,static fn($a,$b)=>($a['priority']<=>$b['priority']));
        return $out;
    }

    private static function vault_encrypt(array $sources): array {
        if (!function_exists('openssl_encrypt')) throw new RuntimeException('OpenSSL unavailable');
        $iv=random_bytes(12); $tag='';
        $json=wp_json_encode(array_values($sources), JSON_UNESCAPED_SLASHES);
        $cipher=openssl_encrypt($json,'aes-256-gcm',self::source_vault_key(),OPENSSL_RAW_DATA,$iv,$tag,'nenotv-source-v1');
        if ($cipher===false || $tag==='') throw new RuntimeException('Source encryption failed');
        return ['payload'=>base64_encode($cipher),'iv'=>base64_encode($iv),'tag'=>base64_encode($tag)];
    }

    private static function vault_decrypt(array $row): array {
        if (empty($row['payload']) || empty($row['iv']) || empty($row['tag'])) throw new RuntimeException('Source vault unreadable');
        $cipher=base64_decode((string)$row['payload'],true);
        $iv=base64_decode((string)$row['iv'],true);
        $tag=base64_decode((string)$row['tag'],true);
        if ($cipher===false || $iv===false || $tag===false) throw new RuntimeException('Source vault unreadable');
        $plain=openssl_decrypt($cipher,'aes-256-gcm',self::source_vault_key(),OPENSSL_RAW_DATA,$iv,$tag,'nenotv-source-v1');
        if (!is_string($plain) || $plain==='') throw new RuntimeException('Source vault unreadable');
        $data=json_decode($plain,true);
        if(!is_array($data))throw new RuntimeException('Source vault unreadable');
        return self::sanitize_source_list($data);
    }

    private static function load_source_vault(int $entitlement_id): array {
        if ($entitlement_id<0) return self::account_vault(-$entitlement_id);
        global $wpdb;
        $row=$wpdb->get_row($wpdb->prepare('SELECT * FROM '.self::source_table().' WHERE entitlement_id=%d LIMIT 1',$entitlement_id),ARRAY_A);
        if (!is_array($row)) return ['revision'=>0,'sources'=>[]];
        return ['revision'=>(int)$row['revision'],'sources'=>self::vault_decrypt($row)];
    }

    private static function save_source_vault(int $entitlement_id, array $sources, int $base_revision): array {
        if ($entitlement_id<0) return self::account_save_vault(-$entitlement_id,$sources,$base_revision);
        global $wpdb;
        if($base_revision<0)throw new InvalidArgumentException('Invalid source revision');
        $revision=$base_revision+1;
        $enc=self::vault_encrypt(self::sanitize_source_list($sources));
        $now=self::now_mysql();
        if($base_revision===0){
            $sql=$wpdb->prepare(
                'INSERT IGNORE INTO '.self::source_table().' (entitlement_id,revision,payload,iv,tag,updated_at) VALUES (%d,%d,%s,%s,%s,%s)',
                $entitlement_id,$revision,$enc['payload'],$enc['iv'],$enc['tag'],$now
            );
        }else{
            $sql=$wpdb->prepare(
                'UPDATE '.self::source_table().' SET revision=%d,payload=%s,iv=%s,tag=%s,updated_at=%s WHERE entitlement_id=%d AND revision=%d',
                $revision,$enc['payload'],$enc['iv'],$enc['tag'],$now,$entitlement_id,$base_revision
            );
        }
        $written=$wpdb->query($sql);
        if($written===false)throw new RuntimeException('Source vault write failed');
        if($written!==1)throw new UnexpectedValueException('SOURCE_REVISION_CONFLICT');
        self::catalog_sources_saved($entitlement_id,self::sanitize_source_list($sources));
        return ['revision'=>$revision,'sources'=>self::sanitize_source_list($sources)];
    }

    private static function source_device_auth(array $p): array|WP_Error {
        $device_id=sanitize_text_field((string)($p['device_id']??''));
        $device_key=(string)($p['device_key']??'');
        if ($device_id==='' || $device_key==='') return new WP_Error('invalid_device','Missing SunnyIPTV device identity.',['status'=>400]);
        $dev=self::find_device($device_id);
        if (!$dev || ($dev['status']??'')!=='active') return new WP_Error('device_not_linked','This device is not linked to SunnyIPTV Pro.',['status'=>403]);
        if (!hash_equals((string)$dev['device_key_hash'],self::key_hash($device_key))) return new WP_Error('device_key_mismatch','This device identity could not be verified.',['status'=>403]);
        $ent=self::find_by_id((int)$dev['entitlement_id']);
        if (!is_array($ent) || !self::entitlement_is_active($ent)) return new WP_Error('pro_inactive','SunnyIPTV Pro is not active for this device.',['status'=>403]);
        return ['device'=>$dev,'entitlement'=>$ent];
    }

    public static function app_sources(WP_REST_Request $request, string $action): WP_REST_Response {
        if (!self::app_services_available()) return self::json(['ok'=>false,'error'=>'pro_not_live','message'=>'SunnyIPTV Pro source sync is not live yet.'],403);
        if(strlen((string)$request->get_body())>1048576)return self::json(['ok'=>false,'error'=>'request_too_large'],413);
        $raw=$request->get_json_params(); if(!is_array($raw))$raw=[];
        $auth=self::source_device_auth($raw); if(is_wp_error($auth))return self::wp_error_json($auth);
        $ent=(array)$auth['entitlement'];
        if(!self::app_service_live($ent))return self::json(['ok'=>false,'error'=>'pro_not_live'],403);
        $entitlement_id=(int)$ent['id'];
        $scope=$raw['account_scope']??null;
        if(!is_string($scope)||!hash_equals(self::email_hash((string)$ent['email']),$scope))return self::json(['ok'=>false,'error'=>'source_account_changed'],409);
        try {
            if ($action==='pull') {
                $vault=self::load_source_vault($entitlement_id);
                return self::json(['ok'=>true,'revision'=>(int)$vault['revision'],'sources'=>$vault['sources']],200);
            }
            if ($action==='push') {
                if(!isset($raw['sources'])||!is_array($raw['sources']))return self::json(['ok'=>false,'error'=>'invalid_sources'],400);
                if(count($raw['sources'])>20)return self::json(['ok'=>false,'error'=>'source_limit'],400);
                if(!isset($raw['base_revision'])||!is_int($raw['base_revision'])||$raw['base_revision']<0)return self::json(['ok'=>false,'error'=>'source_version_required'],409);
                $sources=self::sanitize_source_list($raw['sources']);
                if(count($sources)!==count($raw['sources'])||count(array_unique(array_column($sources,'id')))!==count($sources))return self::json(['ok'=>false,'error'=>'invalid_sources'],400);
                $vault=self::save_source_vault($entitlement_id,$sources,$raw['base_revision']);
                return self::json(['ok'=>true,'revision'=>(int)$vault['revision'],'sources'=>$vault['sources']],200);
            }
        } catch (UnexpectedValueException $e) {
            return self::json(['ok'=>false,'error'=>'source_revision_conflict','revision'=>self::load_source_vault($entitlement_id)['revision']],409);
        } catch (Throwable $e) {
            return self::json(['ok'=>false,'error'=>'source_sync_failed','message'=>'SunnyIPTV source sync could not be completed.'],503);
        }
        return self::json(['ok'=>false,'error'=>'invalid_action'],400);
    }

    public static function provider_chargeback(WP_REST_Request $request): WP_REST_Response {
        $auth=self::authenticate_backend_request($request); if (is_wp_error($auth)) return self::wp_error_json($auth);
        $p=$request->get_json_params(); if(!is_array($p))$p=[];
        $order_id=absint($p['order_id']??0);
        if (!$order_id || !function_exists('wc_get_order')) return self::json(['ok'=>false,'error'=>'invalid_order'],400);
        $order=wc_get_order($order_id); if(!$order)return self::json(['ok'=>false,'error'=>'order_not_found'],404);
        $r=self::is_upgrade_order($order) ? self::revert_upgrade_order($order,'chargeback') : self::change_order_entitlement_status($order,'revoked');
        if ($r['ok']) {
            if (method_exists($order,'add_order_note')) $order->add_order_note('SunnyIPTV Entitlement Core: provider chargeback event revoked Pro access automatically.');
            do_action('nenotv_chargeback_processed',$order_id,$order,$p);
        }
        return self::json(['ok'=>(bool)$r['ok'],'reference'=>$r['reference'],'message'=>$r['message']],$r['ok']?200:409);
    }

    private static function wp_error_json(WP_Error $e): WP_REST_Response {
        $data=$e->get_error_data(); $status=is_array($data)&&isset($data['status'])?(int)$data['status']:400;
        return self::json(['ok'=>false,'error'=>$e->get_error_code(),'message'=>$e->get_error_message()],$status);
    }

    private static function json(array $data, int $status): WP_REST_Response {
        $r=new WP_REST_Response($data,$status); $r->header('Cache-Control','no-store'); $r->header('X-SunnyIPTV-Entitlement-Core',self::VERSION); return $r;
    }



    private static function account_language(): string {
        if (function_exists('nenotv_current_language')) {
            $theme_lang = strtolower((string)nenotv_current_language());
            if (in_array($theme_lang, ['en','nl','de'], true)) return $theme_lang;
        }

        $lang = strtolower(sanitize_key((string)($_GET['lang'] ?? '')));
        if (in_array($lang, ['en','nl','de'], true)) return $lang;

        if (function_exists('pll_get_post_language')) {
            $post_id = get_the_ID();
            $post_lang = $post_id ? strtolower((string)pll_get_post_language($post_id, 'slug')) : '';
            if (in_array($post_lang, ['en','nl','de'], true)) return $post_lang;
        }

        if (function_exists('pll_current_language')) {
            $pll = strtolower((string)pll_current_language('slug'));
            if (in_array($pll, ['en','nl','de'], true)) return $pll;
        }
        return 'en';
    }

    private static function account_strings(string $lang): array {
        $all = [
            'en' => [
                'kicker'=>'My SunnyIPTV','title'=>'Access & devices','none'=>'No active SunnyIPTV trial or Pro access is linked to this account yet.','solo'=>'Solo','multi'=>'Multi',
                'plan'=>'Access','status'=>'Status','devices'=>'Linked devices','expires'=>'Valid until','lifetime'=>'Lifetime',
                'annual'=>'Yearly','unconfigured'=>'Not configured','active'=>'Active','expired'=>'Expired','shadow'=>'Pre-launch test','suspended'=>'Suspended','revoked'=>'Revoked','renew'=>'Renew Pro','renew_text'=>'Your Yearly plan has expired. Renew the same plan without changing your device tier.','upgrade'=>'Upgrade to Multi','upgrade_text'=>'Move from Solo to Multi when you need up to 5 devices. Upgrade pricing is not active yet.','upgrade_locked'=>'Upgrade coming soon',
                'used'=>'devices used','last_seen'=>'Last active','app'=>'App','remove'=>'Remove device',
                'removed'=>'Device removed. You can now link another device.','support'=>'Need help? Create a support ticket',
                'privacy'=>'Device details are shown here for account management. If you choose Pro source sync, IPTV source credentials are stored encrypted and are not shown back in full.','activate'=>'Link a new device','activate_text'=>'Generate a fresh activation code when you want to link another device.','generate'=>'Generate activation code','code'=>'New activation code','code_note'=>'Use this code in SunnyIPTV. Keep it private; it expires automatically.'
            ],
            'nl' => [
                'kicker'=>'Mijn SunnyIPTV','title'=>'Toegang & apparaten','none'=>'Er is nog geen actieve SunnyIPTV-proefperiode of Pro-toegang aan dit account gekoppeld.','solo'=>'Solo','multi'=>'Multi',
                'plan'=>'Toegang','status'=>'Status','devices'=>'Gekoppelde apparaten','expires'=>'Geldig tot','lifetime'=>'Lifetime',
                'annual'=>'Jaarlijks','unconfigured'=>'Niet geconfigureerd','active'=>'Actief','expired'=>'Verlopen','shadow'=>'Testfase vóór lancering','suspended'=>'Geschorst','revoked'=>'Ingetrokken','renew'=>'Pro verlengen','renew_text'=>'Je Jaarplan is verlopen. Verleng hetzelfde plan zonder je apparaattier te wijzigen.','upgrade'=>'Upgrade naar Multi','upgrade_text'=>'Ga van Solo naar Multi als je maximaal 5 apparaten nodig hebt. De upgradeprijs is nog niet actief.','upgrade_locked'=>'Upgrade binnenkort',
                'used'=>'apparaten gebruikt','last_seen'=>'Laatst actief','app'=>'App','remove'=>'Apparaat verwijderen',
                'removed'=>'Apparaat verwijderd. Je kunt nu een ander apparaat koppelen.','support'=>'Hulp nodig? Maak een supportticket aan',
                'privacy'=>'Hier tonen we apparaatgegevens voor accountbeheer. Kies je voor Pro-bronsynchronisatie, dan worden IPTV-brongegevens versleuteld opgeslagen en nooit opnieuw volledig getoond.','activate'=>'Nieuw apparaat koppelen','activate_text'=>'Genereer een nieuwe activatiecode wanneer je een ander apparaat wilt koppelen.','generate'=>'Activatiecode genereren','code'=>'Nieuwe activatiecode','code_note'=>'Gebruik deze code in SunnyIPTV. Houd hem privé; hij verloopt automatisch.'
            ],
            'de' => [
                'kicker'=>'Mein SunnyIPTV','title'=>'Zugang & Geräte','none'=>'Diesem Konto ist noch keine aktive SunnyIPTV-Testphase oder Pro-Berechtigung zugeordnet.','solo'=>'Solo','multi'=>'Multi',
                'plan'=>'Zugang','status'=>'Status','devices'=>'Verknüpfte Geräte','expires'=>'Gültig bis','lifetime'=>'Lifetime',
                'annual'=>'Jährlich','unconfigured'=>'Nicht konfiguriert','active'=>'Aktiv','expired'=>'Abgelaufen','shadow'=>'Testphase vor dem Start','suspended'=>'Gesperrt','revoked'=>'Widerrufen','renew'=>'Pro verlängern','renew_text'=>'Dein Jahresplan ist abgelaufen. Verlängere denselben Plan, ohne die Gerätestufe zu ändern.','upgrade'=>'Auf Multi upgraden','upgrade_text'=>'Wechsle von Solo zu Multi, wenn du bis zu 5 Geräte brauchst. Der Upgrade-Preis ist noch nicht aktiv.','upgrade_locked'=>'Upgrade demnächst',
                'used'=>'Geräte verwendet','last_seen'=>'Zuletzt aktiv','app'=>'App','remove'=>'Gerät entfernen',
                'removed'=>'Gerät entfernt. Du kannst jetzt ein anderes Gerät verbinden.','support'=>'Hilfe nötig? Support-Ticket erstellen',
                'privacy'=>'Hier werden Gerätedaten für die Kontoverwaltung angezeigt. Wenn du die Pro-Quellensynchronisierung nutzt, werden IPTV-Zugangsdaten verschlüsselt gespeichert und nie vollständig angezeigt.','activate'=>'Neues Gerät verbinden','activate_text'=>'Erstelle einen neuen Aktivierungscode, wenn du ein weiteres Gerät verbinden möchtest.','generate'=>'Aktivierungscode erstellen','code'=>'Neuer Aktivierungscode','code_note'=>'Verwende diesen Code in SunnyIPTV. Halte ihn geheim; er läuft automatisch ab.'
            ],
        ];
        return $all[$lang] ?? $all['en'];
    }

    private static function current_user_entitlement(): ?array {
        if (!is_user_logged_in()) return null;
        global $wpdb;
        $user = wp_get_current_user();
        $email_hash = self::email_hash((string)$user->user_email);
        $row = $wpdb->get_row($wpdb->prepare(
            'SELECT * FROM '.self::ent_table().' WHERE email_hash=%s ORDER BY FIELD(status,%s,%s,%s,%s), updated_at DESC LIMIT 1',
            $email_hash, 'active', 'shadow', 'suspended', 'revoked'
        ), ARRAY_A);
        if (is_array($row)) return $row;

        if (function_exists('wc_get_orders')) {
            $order_ids = wc_get_orders(['customer_id'=>get_current_user_id(),'limit'=>50,'return'=>'ids','orderby'=>'date','order'=>'DESC']);
            foreach ($order_ids as $order_id) {
                $candidate = $wpdb->get_row($wpdb->prepare(
                    'SELECT * FROM '.self::ent_table().' WHERE source_ref=%s ORDER BY updated_at DESC LIMIT 1',
                    'order:' . absint($order_id)
                ), ARRAY_A);
                if (is_array($candidate)) return $candidate;
            }
        }
        return null;
    }

    private static function user_owns_entitlement(array $ent): bool {
        if (!is_user_logged_in()) return false;
        $user = wp_get_current_user();
        if (hash_equals((string)($ent['email_hash'] ?? ''), self::email_hash((string)$user->user_email))) return true;

        $source_ref = (string)($ent['source_ref'] ?? '');
        if (preg_match('/^order:(\d+)$/', $source_ref, $m) && function_exists('wc_get_order')) {
            $order = wc_get_order((int)$m[1]);
            return $order && (int)$order->get_customer_id() === get_current_user_id();
        }
        return false;
    }

    private static function account_devices(int $entitlement_id): array {
        global $wpdb;
        $rows = $wpdb->get_results($wpdb->prepare(
            'SELECT id,display_name,public_device_id,platform,app_version,status,created_at,last_seen_at FROM '.self::dev_table().' WHERE entitlement_id=%d AND status=%s ORDER BY last_seen_at DESC',
            $entitlement_id, 'active'
        ), ARRAY_A);
        return is_array($rows) ? $rows : [];
    }





    private static function upgrade_product(): ?WC_Product {
        if (!function_exists('wc_get_product_id_by_sku') || !function_exists('wc_get_product')) return null;
        $id = (int)wc_get_product_id_by_sku('NENOTV-PRO-UPGRADE-MULTI');
        if (!$id) return null;
        $product = wc_get_product($id);
        return $product instanceof WC_Product ? $product : null;
    }

    private static function product_price_by_sku(string $sku): float {
        if (!function_exists('wc_get_product_id_by_sku') || !function_exists('wc_get_product')) return 0.0;
        $id = (int)wc_get_product_id_by_sku($sku);
        $product = $id ? wc_get_product($id) : null;
        return $product instanceof WC_Product ? (float)$product->get_price() : 0.0;
    }

    private static function upgrade_offer_for_entitlement(array $ent): array {
        $base = [
            'eligible'=>false,
            'mode'=>'',
            'price'=>0.0,
            'remaining_days'=>0,
            'previous_max_devices'=>max(1, (int)($ent['max_devices'] ?? 1)),
            'previous_expires_at'=>(string)($ent['expires_at'] ?? ''),
            'target_expires_at'=>(string)($ent['expires_at'] ?? ''),
            'plan'=>(string)($ent['plan'] ?? ''),
        ];

        if ((int)($ent['max_devices'] ?? 1) > 1) return $base;
        if (($ent['status'] ?? '') !== 'active' || !self::entitlement_is_active($ent)) return $base;

        $plan = (string)($ent['plan'] ?? '');
        if (!in_array($plan, ['annual','lifetime'], true)) return $base;

        if ($plan === 'lifetime') {
            $solo = self::product_price_by_sku('NENOTV-PRO-1-LIFETIME');
            $multi = self::product_price_by_sku('NENOTV-PRO-5-LIFETIME');
            $diff = round($multi - $solo, 2);
            if ($solo <= 0 || $multi <= 0 || $diff <= 0) return $base;

            $base['eligible'] = true;
            $base['mode'] = 'lifetime';
            $base['price'] = $diff;
            $base['target_expires_at'] = '';
            return $base;
        }

        $expiry = (string)($ent['expires_at'] ?? '');
        $expiry_ts = $expiry !== '' ? strtotime($expiry . ' UTC') : 0;
        if (!$expiry_ts || $expiry_ts <= time()) return $base;

        $remaining_seconds = max(0, $expiry_ts - time());
        $remaining_days = (int)ceil($remaining_seconds / DAY_IN_SECONDS);
        $solo = self::product_price_by_sku('NENOTV-PRO-1-YEARLY');
        $multi = self::product_price_by_sku('NENOTV-PRO-5-YEARLY');
        $diff = round($multi - $solo, 2);
        if ($solo <= 0 || $multi <= 0 || $diff <= 0) return $base;

        $base['eligible'] = true;
        $base['remaining_days'] = $remaining_days;

        if ($remaining_seconds > 30 * DAY_IN_SECONDS) {
            $base['mode'] = 'annual_prorata';
            $base['price'] = round($diff * ($remaining_seconds / YEAR_IN_SECONDS), 2);
            $base['target_expires_at'] = $expiry;
        } else {
            $base['mode'] = 'annual_renew';
            $base['price'] = round($multi, 2);
            $base['target_expires_at'] = gmdate('Y-m-d H:i:s', strtotime('+1 year', $expiry_ts));
        }

        if ($base['price'] <= 0) $base['eligible'] = false;
        return $base;
    }

    private static function current_upgrade_offer(): array {
        $ent = self::current_user_entitlement();
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) return ['eligible'=>false];
        $offer = self::upgrade_offer_for_entitlement($ent);
        $offer['entitlement_id'] = (int)$ent['id'];
        $offer['reference'] = (string)$ent['reference'];
        return $offer;
    }

    public static function upgrade_product_purchasable($purchasable, $product) {
        if (!$product instanceof WC_Product || strtoupper((string)$product->get_sku()) !== 'NENOTV-PRO-UPGRADE-MULTI') return $purchasable;
        if (!is_user_logged_in()) return false;
        if (get_option('nenotv_upgrade_enabled', 'no') !== 'yes') return false;
        if (!function_exists('nenotv_public_sales_ready') || !nenotv_public_sales_ready()) return false;
        $offer = self::current_upgrade_offer();
        return !empty($offer['eligible']);
    }

    public static function clear_upgrade_session(): void {
        if (function_exists('WC') && WC()->session) WC()->session->set('nenotv_upgrade_payload', null);
    }

    public static function handle_upgrade_multi(): void {
        if (!is_user_logged_in()) auth_redirect();
        $entitlement_id = absint($_POST['entitlement_id'] ?? 0);
        $lang = strtolower(sanitize_key((string)($_POST['lang'] ?? 'en')));
        if (!$entitlement_id || !wp_verify_nonce(sanitize_text_field((string)($_POST['_wpnonce'] ?? '')), 'nenotv_upgrade_multi_' . $entitlement_id)) {
            wp_die('Security check failed.');
        }
        if (get_option('nenotv_upgrade_enabled', 'no') !== 'yes' || !function_exists('nenotv_public_sales_ready') || !nenotv_public_sales_ready()) {
            wp_die('SunnyIPTV upgrades are not available yet.');
        }

        $ent = self::find_by_id($entitlement_id);
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) wp_die('You cannot upgrade this entitlement.');

        $offer = self::upgrade_offer_for_entitlement($ent);
        if (empty($offer['eligible'])) wp_die('This SunnyIPTV Pro entitlement is not eligible for a Multi upgrade.');

        $product = self::upgrade_product();
        if (!$product || $product->get_status() !== 'publish') wp_die('The SunnyIPTV Multi upgrade product is not available.');

        if (function_exists('wc_load_cart') && (!function_exists('WC') || !WC()->cart)) wc_load_cart();
        if (!function_exists('WC') || !WC()->cart || !WC()->session) wp_die('Checkout is not available.');

        WC()->cart->empty_cart();

        $payload = [
            'entitlement_id'=>$entitlement_id,
            'reference'=>(string)$ent['reference'],
            'mode'=>(string)$offer['mode'],
            'price'=>number_format((float)$offer['price'], 2, '.', ''),
            'plan'=>(string)$offer['plan'],
            'previous_max_devices'=>(int)$offer['previous_max_devices'],
            'previous_expires_at'=>(string)$offer['previous_expires_at'],
            'target_expires_at'=>(string)$offer['target_expires_at'],
            'remaining_days'=>(int)$offer['remaining_days'],
        ];
        WC()->session->set('nenotv_upgrade_payload', $payload);

        $added = WC()->cart->add_to_cart($product->get_id(), 1, 0, [], ['nenotv_upgrade'=>$payload]);
        if (!$added) {
            WC()->session->set('nenotv_upgrade_payload', null);
            wp_die('The SunnyIPTV Multi upgrade could not be added to checkout.');
        }

        $checkout = wc_get_checkout_url();
        if (in_array($lang, ['nl','de'], true)) $checkout = add_query_arg('lang', $lang, $checkout);
        wp_safe_redirect($checkout);
        exit;
    }

    public static function price_upgrade_cart($cart): void {
        if (!is_object($cart) || !method_exists($cart, 'get_cart')) return;
        foreach ($cart->get_cart() as $key => $item) {
            $product = $item['data'] ?? null;
            if (!$product instanceof WC_Product || strtoupper((string)$product->get_sku()) !== 'NENOTV-PRO-UPGRADE-MULTI') continue;

            $payload = is_array($item['nenotv_upgrade'] ?? null) ? $item['nenotv_upgrade'] : [];
            $ent_id = absint($payload['entitlement_id'] ?? 0);
            $ent = $ent_id ? self::find_by_id($ent_id) : null;
            if (!is_array($ent) || !self::user_owns_entitlement($ent)) continue;

            $offer = self::upgrade_offer_for_entitlement($ent);
            if (empty($offer['eligible'])) continue;

            $price = round((float)$offer['price'], 2);
            $product->set_price($price);
            $cart->cart_contents[$key]['nenotv_upgrade']['price'] = number_format($price, 2, '.', '');
            $cart->cart_contents[$key]['nenotv_upgrade']['mode'] = (string)$offer['mode'];
            $cart->cart_contents[$key]['nenotv_upgrade']['target_expires_at'] = (string)$offer['target_expires_at'];
            $cart->cart_contents[$key]['nenotv_upgrade']['remaining_days'] = (int)$offer['remaining_days'];

            if (function_exists('WC') && WC()->session) {
                WC()->session->set('nenotv_upgrade_payload', $cart->cart_contents[$key]['nenotv_upgrade']);
            }
        }
    }

    private static function order_has_upgrade_product($order): bool {
        if (!$order || !method_exists($order, 'get_items')) return false;
        foreach ($order->get_items() as $item) {
            $product = method_exists($item, 'get_product') ? $item->get_product() : null;
            if ($product instanceof WC_Product && strtoupper((string)$product->get_sku()) === 'NENOTV-PRO-UPGRADE-MULTI') return true;
        }
        return false;
    }

    public static function store_upgrade_order_meta($order): void {
        if (!$order || !method_exists($order, 'update_meta_data')) return;
        if (!function_exists('WC') || !WC()->session) return;

        $payload = WC()->session->get('nenotv_upgrade_payload');
        if (!is_array($payload) || empty($payload['entitlement_id']) || empty($payload['mode'])) return;

        $order->update_meta_data('_nenotv_upgrade_type', 'solo_to_multi');
        $order->update_meta_data('_nenotv_upgrade_entitlement_id', absint($payload['entitlement_id']));
        $order->update_meta_data('_nenotv_upgrade_entitlement_reference', sanitize_text_field((string)($payload['reference'] ?? '')));
        $order->update_meta_data('_nenotv_upgrade_mode', sanitize_key((string)$payload['mode']));
        $order->update_meta_data('_nenotv_upgrade_price', wc_format_decimal((string)$payload['price'], 2));
        $order->update_meta_data('_nenotv_upgrade_previous_max_devices', absint($payload['previous_max_devices'] ?? 1));
        $order->update_meta_data('_nenotv_upgrade_previous_expires_at', sanitize_text_field((string)($payload['previous_expires_at'] ?? '')));
        $order->update_meta_data('_nenotv_upgrade_target_expires_at', sanitize_text_field((string)($payload['target_expires_at'] ?? '')));
        $order->update_meta_data('_nenotv_upgrade_remaining_days', absint($payload['remaining_days'] ?? 0));
    }

    private static function is_upgrade_order($order): bool {
        return $order && method_exists($order, 'get_meta') && $order->get_meta('_nenotv_upgrade_type', true) === 'solo_to_multi';
    }

    private static function handle_upgrade_grant($order, bool $shadow): array {
        if (!self::is_upgrade_order($order)) return ['handled'=>false];

        $ent_id = absint($order->get_meta('_nenotv_upgrade_entitlement_id', true));
        $ent = $ent_id ? self::find_by_id($ent_id) : null;
        $source_ref = self::source_ref_for_order($order);
        if (!is_array($ent)) return ['handled'=>true,'success'=>false,'message'=>'The original Solo entitlement could not be found.','reference'=>''];

        $email_hash = self::email_hash(self::order_email($order));
        if ($email_hash === '' || !hash_equals((string)$ent['email_hash'], $email_hash)) {
            return ['handled'=>true,'success'=>false,'message'=>'The upgrade order does not belong to the original Solo entitlement.','reference'=>''];
        }

        $mode = sanitize_key((string)$order->get_meta('_nenotv_upgrade_mode', true));
        if (!in_array($mode, ['annual_prorata','annual_renew','lifetime'], true)) {
            return ['handled'=>true,'success'=>false,'message'=>'The upgrade mode is invalid.','reference'=>''];
        }

        if ($shadow) {
            self::log_event('upgrade_shadow', (string)$ent['reference'], $source_ref, 'success', 'Solo-to-Multi upgrade recorded in shadow mode; entitlement was not changed.');
            return ['handled'=>true,'success'=>true,'message'=>'TEST upgrade recorded in shadow mode; the Solo entitlement was not changed.','reference'=>(string)$ent['reference']];
        }

        if ($order->get_meta('_nenotv_upgrade_applied', true) === 'yes') {
            return ['handled'=>true,'success'=>true,'message'=>'SunnyIPTV Pro Multi upgrade was already applied.','reference'=>(string)$ent['reference']];
        }

        if ((int)$ent['max_devices'] > 1) {
            return ['handled'=>true,'success'=>false,'message'=>'This entitlement is already Multi.','reference'=>(string)$ent['reference']];
        }

        $data = [
            'max_devices'=>5,
            'status'=>'active',
            'updated_at'=>self::now_mysql(),
        ];

        if ($mode === 'annual_renew') {
            $target = sanitize_text_field((string)$order->get_meta('_nenotv_upgrade_target_expires_at', true));
            if ($target === '' || !strtotime($target . ' UTC')) {
                return ['handled'=>true,'success'=>false,'message'=>'The renewed Multi expiry date is invalid.','reference'=>(string)$ent['reference']];
            }
            $data['expires_at'] = $target;
        } elseif ($mode === 'lifetime') {
            $data['expires_at'] = null;
        }

        global $wpdb;
        $ok = $wpdb->update(self::ent_table(), $data, ['id'=>$ent_id]);
        if ($ok === false) return ['handled'=>true,'success'=>false,'message'=>'The Solo entitlement could not be upgraded.','reference'=>(string)$ent['reference']];

        $order->update_meta_data('_nenotv_upgrade_applied', 'yes');
        $order->update_meta_data('_nenotv_entitlement_reference', (string)$ent['reference']);
        $order->save();

        self::log_event('upgrade_live', (string)$ent['reference'], $source_ref, 'success', 'Solo entitlement upgraded to Multi automatically.');
        return ['handled'=>true,'success'=>true,'message'=>'SunnyIPTV Pro upgraded from Solo to Multi automatically.','reference'=>(string)$ent['reference']];
    }

    private static function revert_upgrade_order($order, string $reason): array {
        if (!self::is_upgrade_order($order)) return ['handled'=>false];
        $ent_id = absint($order->get_meta('_nenotv_upgrade_entitlement_id', true));
        $ent = $ent_id ? self::find_by_id($ent_id) : null;
        if (!is_array($ent)) return ['handled'=>true,'success'=>false,'message'=>'The original entitlement could not be found.','reference'=>''];

        if ($order->get_meta('_nenotv_upgrade_applied', true) !== 'yes') {
            return ['handled'=>true,'success'=>true,'message'=>'Upgrade was not applied; no entitlement change was needed.','reference'=>(string)$ent['reference']];
        }
        if ($order->get_meta('_nenotv_upgrade_reverted', true) === 'yes') {
            return ['handled'=>true,'success'=>true,'message'=>'Upgrade was already reverted.','reference'=>(string)$ent['reference']];
        }

        $previous_max = max(1, absint($order->get_meta('_nenotv_upgrade_previous_max_devices', true)));
        $previous_expiry = sanitize_text_field((string)$order->get_meta('_nenotv_upgrade_previous_expires_at', true));
        $data = [
            'max_devices'=>$previous_max,
            'updated_at'=>self::now_mysql(),
        ];
        if ((string)$ent['plan'] === 'annual') $data['expires_at'] = $previous_expiry !== '' ? $previous_expiry : null;

        global $wpdb;
        $ok = $wpdb->update(self::ent_table(), $data, ['id'=>$ent_id]);
        if ($ok === false) return ['handled'=>true,'success'=>false,'message'=>'The Multi upgrade could not be reverted.','reference'=>(string)$ent['reference']];

        $order->update_meta_data('_nenotv_upgrade_reverted', 'yes');
        $order->save();

        self::log_event('upgrade_revert', (string)$ent['reference'], self::source_ref_for_order($order), 'success', 'Solo-to-Multi upgrade reverted after ' . sanitize_key($reason) . '.');
        return ['handled'=>true,'success'=>true,'message'=>'SunnyIPTV Pro reverted from Multi to the previous Solo entitlement.','reference'=>(string)$ent['reference']];
    }

    public static function purchase_guard($allowed, $plan) {
        if (!$allowed || !is_user_logged_in()) return $allowed;
        $plan = sanitize_key((string)$plan);
        if (!in_array($plan, ['solo-yearly','solo-lifetime','multi-yearly','multi-lifetime'], true)) return $allowed;

        $ent = self::current_user_entitlement();
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) return $allowed;
        if (!self::entitlement_is_active($ent)) return $allowed;

        // Any active Pro entitlement blocks a second normal Pro purchase.
        // Solo-to-Multi uses the dedicated server-side upgrade checkout instead.
        return false;
    }

    private static function format_account_price(float $price, string $lang): string {
        $number = number_format($price, 2, $lang === 'en' ? '.' : ',', '');
        return $lang === 'de' ? $number . ' €' : '€' . $number;
    }

    public static function shortcode_account_pro(): string {
        if (!is_user_logged_in()) return '';
        $lang = self::account_language();
        $s = self::account_strings($lang);
        $ent = self::current_user_entitlement();

        $html = '<section class="nv-pro-account">';
        $html .= '<div class="nv-pro-account-head"><div><span class="nv-commerce-kicker">' . esc_html($s['kicker']) . '</span><h2>' . esc_html($s['title']) . '</h2></div>';
        $html .= '<img src="' . esc_url(get_template_directory_uri() . '/assets/brand/nenotv-mark.svg') . '" width="72" height="72" alt=""></div>';

        $html .= self::source_account_notice($ent, $lang);

        if (!empty($_GET['device_removed'])) {
            $html .= '<div class="nv-account-notice">' . esc_html($s['removed']) . '</div>';
        }

        if (!$ent) {
            $html .= '<p class="nv-pro-empty">' . esc_html($s['none']) . '</p></section>';
            return $html;
        }

        $plan = (string)($ent['plan'] ?? 'unconfigured');
        $status = (string)($ent['status'] ?? '');
        $max = max(1, (int)($ent['max_devices'] ?? 1));
        $devices = self::account_devices((int)$ent['id']);
        $active_devices = array_values(array_filter($devices, static fn($d) => ($d['status'] ?? '') === 'active'));
        $used = count($active_devices);
        $pct = min(100, (int)round(($used / $max) * 100));

        $expiry_ts = !empty($ent['expires_at']) ? strtotime($ent['expires_at'] . ' UTC') : 0;
        $is_trial = self::is_trial($ent);
        $is_expired = ($plan === 'annual' || $is_trial) && $expiry_ts > 0 && $expiry_ts < time();

        if ($is_trial) {
            $plan_label = $lang === 'nl' ? 'Pro-proefperiode van 30 dagen' : ($lang === 'de' ? '30-tägige Pro-Testphase' : '30-day Pro trial');
            if ($is_expired) {
                $status_label = $lang === 'nl' ? 'Proefperiode verlopen' : ($lang === 'de' ? 'Testphase abgelaufen' : 'Trial expired');
            } else {
                $days_left = $expiry_ts ? max(1, (int)ceil(($expiry_ts - time()) / DAY_IN_SECONDS)) : self::trial_days();
                $status_label = $lang === 'nl'
                    ? 'Actief · nog ' . $days_left . ' dagen'
                    : ($lang === 'de' ? 'Aktiv · noch ' . $days_left . ' Tage' : 'Active · ' . $days_left . ' days left');
            }
        } else {
            if ($status === 'shadow') {
                $plan_label = $lang === 'nl' ? 'SunnyIPTV-testtoegang' : ($lang === 'de' ? 'SunnyIPTV-Testzugang' : 'SunnyIPTV test access');
            } else {
                $plan_label = 'SunnyIPTV Pro ' . ($max === 1 ? 'Solo' : 'Multi');
            }
            $status_label = $is_expired ? $s['expired'] : ($s[$status] ?? ucfirst($status));
        }
        $expires = $expiry_ts ? wp_date(get_option('date_format'), $expiry_ts) : '—';

        $html .= '<div class="nv-pro-stats">';
        $html .= '<div><span>' . esc_html($s['plan']) . '</span><strong>' . esc_html($plan_label) . '</strong></div>';
        $html .= '<div><span>' . esc_html($s['status']) . '</span><strong>' . esc_html($status_label) . '</strong></div>';
        $html .= '<div><span>' . esc_html($s['devices']) . '</span><strong>' . esc_html($used . ' / ' . $max) . '</strong><span>' . esc_html(($lang === 'nl' ? 'Vrij' : ($lang === 'de' ? 'Frei' : 'Available')) . ': ' . max(0,$max-$used)) . '</span></div>';
        $html .= '<div><span>' . esc_html($s['expires']) . '</span><strong>' . esc_html($expires) . '</strong></div>';
        $html .= '</div>';


        $sales_ready = function_exists('nenotv_public_sales_ready') && nenotv_public_sales_ready();
        if ($is_trial) {
            $pro_url = $lang === 'nl' ? home_url('/language/nl/nenotv-pro-nl/') : ($lang === 'de' ? home_url('/language/de/nenotv-pro-de/') : home_url('/pro/'));
            $trial_title = $is_expired
                ? ($lang === 'nl' ? 'Je proefperiode is afgelopen' : ($lang === 'de' ? 'Deine Testphase ist beendet' : 'Your trial has ended'))
                : ($lang === 'nl' ? 'Je proefperiode is actief' : ($lang === 'de' ? 'Deine Testphase ist aktiv' : 'Your trial is active'));
            $trial_text = $is_expired
                ? ($lang === 'nl' ? 'Je Pro-proefperiode is afgelopen en SunnyIPTV gaat verder als Light. Je lokale instellingen en spelergegevens blijven staan. Je kunt later Pro activeren in dezelfde app.' : ($lang === 'de' ? 'Deine Pro-Testphase ist beendet und SunnyIPTV läuft als Light weiter. Deine lokalen Einstellungen und Player-Daten bleiben erhalten. Du kannst Pro später in derselben App aktivieren.' : 'Your Pro trial has ended and SunnyIPTV continues as Light. Your local settings and player data remain in place. You can activate Pro later in the same app.'))
                : ($lang === 'nl' ? 'Na de Pro-proefperiode volgt geen automatische betaling. Zonder Pro-aankoop gaat SunnyIPTV verder als Light; je kunt Pro tijdens de proefperiode of later activeren.' : ($lang === 'de' ? 'Nach der Pro-Testphase erfolgt keine automatische Zahlung. Ohne Pro-Kauf läuft SunnyIPTV als Light weiter; Pro kann während der Testphase oder später aktiviert werden.' : 'There is no automatic payment after the Pro trial. Without a Pro purchase, SunnyIPTV continues as Light; you can activate Pro during the trial or later.'));
            $trial_button = $lang === 'nl' ? 'Bekijk SunnyIPTV Pro' : ($lang === 'de' ? 'SunnyIPTV Pro ansehen' : 'View SunnyIPTV Pro');
            $html .= '<div class="nv-license-action is-trial"><div><h3>' . esc_html($trial_title) . '</h3><p>' . esc_html($trial_text) . '</p></div><a href="' . esc_url($pro_url) . '">' . esc_html($trial_button) . '</a></div>';
        }

        $activation_once = get_transient('nenotv_activation_once_' . get_current_user_id());
        if (is_array($activation_once) && (int)($activation_once['entitlement_id'] ?? 0) === (int)$ent['id'] && !empty($activation_once['token'])) {
            delete_transient('nenotv_activation_once_' . get_current_user_id());
            $html .= '<div class="nv-activation-code"><span>' . esc_html($s['code']) . '</span><code>' . esc_html((string)$activation_once['token']) . '</code><p>' . esc_html($s['code_note']) . '</p></div>';
        }

        if ($status === 'active' && self::mode() === 'live' && self::entitlement_is_active($ent) && $used < $max) {
            $html .= '<div class="nv-activation-action"><div><h3>' . esc_html($s['activate']) . '</h3><p>' . esc_html($s['activate_text']) . '</p></div>';
            $html .= '<form method="post" action="' . esc_url(admin_url('admin-post.php')) . '">';
            $html .= '<input type="hidden" name="action" value="nenotv_activation_regenerate"><input type="hidden" name="entitlement_id" value="' . esc_attr((string)$ent['id']) . '"><input type="hidden" name="lang" value="' . esc_attr($lang) . '">';
            $html .= wp_nonce_field('nenotv_activation_regenerate_' . (int)$ent['id'], '_wpnonce', true, false);
            $html .= '<button type="submit">' . esc_html($s['generate']) . '</button></form></div>';
        }

        if ($devices) {
            $html .= '<div class="nv-device-grid">';
            foreach ($devices as $device) {
                $is_active = ($device['status'] ?? '') === 'active';
                $platform = trim((string)($device['platform'] ?? '')) ?: 'SunnyIPTV device';
                $display_name = trim((string)($device['display_name'] ?? '')) ?: $platform;
                $public = trim((string)($device['public_device_id'] ?? ''));
                $app = trim((string)($device['app_version'] ?? ''));
                $last = !empty($device['last_seen_at']) ? wp_date('j M Y H:i', strtotime($device['last_seen_at'] . ' UTC')) : '—';

                $html .= '<article class="nv-device-card' . ($is_active ? '' : ' is-revoked') . '">';
                $html .= '<div class="nv-device-icon" aria-hidden="true">▣</div><div class="nv-device-copy">';
                $html .= '<h3>' . esc_html($display_name) . '</h3>';
                if ($public !== '') $html .= '<code>' . esc_html($public) . '</code>';
                $html .= '<p>' . esc_html($s['last_seen']) . ': <strong>' . esc_html($last) . '</strong>';
                if ($app !== '') $html .= '<br>' . esc_html($s['app']) . ': <strong>' . esc_html($app) . '</strong>';
                $html .= '</p>';
                $html .= '<form method="post" action="' . esc_url(admin_url('admin-post.php')) . '">';
                $html .= '<input type="hidden" name="action" value="nenotv_device_rename"><input type="hidden" name="device_id" value="' . esc_attr((string)$device['id']) . '">';
                $html .= wp_nonce_field('nenotv_rename_device_' . (int)$device['id'], '_wpnonce', true, false);
                $html .= '<label>' . esc_html($lang === 'nl' ? 'Apparaatnaam' : ($lang === 'de' ? 'Gerätename' : 'Device name')) . '<input required name="device_name" maxlength="80" value="' . esc_attr($display_name) . '"></label>';
                $html .= '<button type="submit">' . esc_html($lang === 'nl' ? 'Naam opslaan' : ($lang === 'de' ? 'Name speichern' : 'Save name')) . '</button></form></div>';

                if ($is_active && self::user_owns_entitlement($ent)) {
                    $html .= '<form class="nv-device-remove" method="post" action="' . esc_url(admin_url('admin-post.php')) . '">';
                    $html .= '<input type="hidden" name="action" value="nenotv_device_revoke"><input type="hidden" name="device_id" value="' . esc_attr((string)$device['id']) . '"><input type="hidden" name="lang" value="' . esc_attr($lang) . '">';
                    $html .= wp_nonce_field('nenotv_revoke_device_' . (int)$device['id'], '_wpnonce', true, false);
                    $html .= '<button type="submit">' . esc_html($s['remove']) . '</button></form>';
                }
                $html .= '</article>';
            }
            $html .= '</div>';
        }

        if (self::user_owns_entitlement($ent)) {
            $html .= self::source_account_panel($ent, $lang);
        }

        $support = $lang === 'nl' ? home_url('/language/nl/contact-nl/') : ($lang === 'de' ? home_url('/language/de/kontakt/') : home_url('/contact/'));
        $html .= '<p class="nv-pro-account-foot">' . esc_html($s['privacy']) . ' <a href="' . esc_url($support) . '">' . esc_html($s['support']) . '</a></p>';
        $html .= '</section>';
        return $html;
    }

    public static function handle_source_add(): void {self::handle_source_action('add');}

    public static function handle_source_delete(): void {self::handle_source_action('delete');}

    public static function handle_device_rename(): void {
        if (!is_user_logged_in()) auth_redirect();
        $device_id = absint($_POST['device_id'] ?? 0);
        if (!$device_id || !wp_verify_nonce(sanitize_text_field((string)($_POST['_wpnonce'] ?? '')), 'nenotv_rename_device_' . $device_id)) wp_die('Security check failed.');
        $raw = $_POST['device_name'] ?? '';
        if (!is_scalar($raw)) wp_die('Invalid device name.');
        $name = trim(sanitize_text_field(wp_unslash((string)$raw)));
        $length = function_exists('mb_strlen') ? mb_strlen($name,'UTF-8') : strlen($name);
        if ($name === '' || $length > 80) wp_die('Invalid device name.');
        global $wpdb;
        $device = $wpdb->get_row($wpdb->prepare('SELECT * FROM '.self::dev_table().' WHERE id=%d LIMIT 1', $device_id), ARRAY_A);
        if (!is_array($device)) wp_die('Device not found.');
        $ent = self::find_by_id((int)$device['entitlement_id']);
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) wp_die('You cannot manage this device.');
        if ($name !== (string)($device['display_name'] ?? '')) {
            $updated = $wpdb->update(self::dev_table(), ['display_name'=>$name], ['id'=>$device_id,'entitlement_id'=>(int)$ent['id']]);
            if ($updated !== 1) wp_die('Device changed or could not be saved. Reload your account.');
            self::log_event('device_self_rename', (string)$ent['reference'], (string)$device['device_id'], 'success', 'Customer changed device name.');
        }
        $url = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
        wp_safe_redirect($url); exit;
    }

    public static function handle_device_revoke(): void {
        if (!is_user_logged_in()) auth_redirect();
        $device_id = absint($_POST['device_id'] ?? 0);
        $lang = strtolower(sanitize_key((string)($_POST['lang'] ?? 'en')));
        if (!$device_id || !wp_verify_nonce(sanitize_text_field((string)($_POST['_wpnonce'] ?? '')), 'nenotv_revoke_device_' . $device_id)) {
            wp_die('Security check failed.');
        }

        global $wpdb;
        $device = $wpdb->get_row($wpdb->prepare('SELECT * FROM '.self::dev_table().' WHERE id=%d LIMIT 1', $device_id), ARRAY_A);
        if (!is_array($device)) wp_die('Device not found.');

        $ent = self::find_by_id((int)$device['entitlement_id']);
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) wp_die('You cannot manage this device.');

        if (($device['status'] ?? '') === 'active') {
            $updated = $wpdb->update(self::dev_table(), [
                'status'=>'revoked',
                'revoked_at'=>self::now_mysql(),
                'last_seen_at'=>self::now_mysql(),
            ], ['id'=>$device_id,'entitlement_id'=>(int)$ent['id']]);
            if ($updated !== 1) wp_die('Device changed or could not be removed. Reload your account.');
            self::log_event('device_self_revoke', (string)$ent['reference'], (string)$device['device_id'], 'success', 'Customer removed device from My SunnyIPTV.');
        }

        $url = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
        if (in_array($lang, ['nl','de'], true)) $url = add_query_arg('lang', $lang, $url);
        $url = add_query_arg('device_removed', '1', $url);
        wp_safe_redirect($url);
        exit;
    }



    public static function handle_activation_regenerate(): void {
        if (!is_user_logged_in()) auth_redirect();

        $entitlement_id = absint($_POST['entitlement_id'] ?? 0);
        $lang = strtolower(sanitize_key((string)($_POST['lang'] ?? 'en')));
        if (!$entitlement_id || !wp_verify_nonce(sanitize_text_field((string)($_POST['_wpnonce'] ?? '')), 'nenotv_activation_regenerate_' . $entitlement_id)) {
            wp_die('Security check failed.');
        }

        $ent = self::find_by_id($entitlement_id);
        if (!is_array($ent) || !self::user_owns_entitlement($ent)) wp_die('You cannot manage this entitlement.');
        if (self::mode() !== 'live' || !self::entitlement_is_active($ent)) wp_die('SunnyIPTV Pro is not active for this account.');
        if (self::count_active_devices((int)$ent['id']) >= (int)$ent['max_devices']) wp_die('The SunnyIPTV Pro device limit has been reached. Remove a device before generating a new activation code.');

        global $wpdb;
        $token = self::activation_code();
        $days = max(1, min(365, absint(get_option(self::OPT_TOKEN_DAYS, 30))));
        $expires = gmdate('Y-m-d H:i:s', time() + ($days * DAY_IN_SECONDS));
        $wpdb->update(self::ent_table(), [
            'activation_hash'=>self::key_hash($token),
            'activation_expires_at'=>$expires,
            'updated_at'=>self::now_mysql(),
        ], ['id'=>$entitlement_id]);

        set_transient('nenotv_activation_once_' . get_current_user_id(), [
            'entitlement_id'=>$entitlement_id,
            'token'=>$token,
        ], 10 * MINUTE_IN_SECONDS);

        self::log_event('activation_self_regenerate', (string)$ent['reference'], 'account:' . get_current_user_id(), 'success', 'Customer generated a fresh device activation code in My SunnyIPTV.');

        $url = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
        if (in_array($lang, ['nl','de'], true)) $url = add_query_arg('lang', $lang, $url);
        $url = add_query_arg('activation_created', '1', $url);
        wp_safe_redirect($url);
        exit;
    }

    private static function order_language($order): string {
        if (function_exists('nenotv_email_order_language')) {
            $lang=(string)nenotv_email_order_language($order);
            if(in_array($lang,['en','nl','de'],true))return $lang;
        }
        if($order&&method_exists($order,'get_meta')){
            $lang=strtolower((string)$order->get_meta('_nenotv_order_language',true));
            if(in_array($lang,['en','nl','de'],true))return $lang;
        }
        return 'en';
    }

    private static function event_exists(string $event_id): bool {
        global $wpdb;
        return (bool)$wpdb->get_var($wpdb->prepare('SELECT 1 FROM '.self::event_table().' WHERE event_id=%s LIMIT 1',$event_id));
    }

    private static function send_trial_notice(array $ent,string $kind): bool {
        $email=self::normalize_email((string)($ent['email']??''));
        if($email===''||!is_email($email))return false;
        $lang=self::normalize_language((string)($ent['language']??'en'));
        $strings=[
            'en'=>[
                'started_subject'=>'Your 30-day SunnyIPTV Pro trial has started',
                'started_title'=>'Your SunnyIPTV Pro trial is active',
                'started_body'=>'You now have 30 days of the complete SunnyIPTV Pro experience with your own authorised source. No payment is taken automatically. Without a Pro purchase, SunnyIPTV will continue as Light after the trial.',
                '7d_subject'=>'7 days left in your SunnyIPTV Pro trial',
                '7d_title'=>'Your SunnyIPTV Pro trial has 7 days left',
                '7d_body'=>'Your settings and player data stay in place. If you do not purchase Pro, SunnyIPTV will continue as Light after the trial. You can purchase Pro now or later.',
                '1d_subject'=>'1 day left in your SunnyIPTV Pro trial',
                '1d_title'=>'Your SunnyIPTV Pro trial ends tomorrow',
                '1d_body'=>'There is no automatic charge. If the Pro trial ends without a Pro purchase, SunnyIPTV switches to Light while your local settings and player data remain in place.',
                'expired_subject'=>'Your SunnyIPTV Pro trial has ended',
                'expired_title'=>'Your 30-day SunnyIPTV Pro trial has ended',
                'expired_body'=>'SunnyIPTV now continues as Light. Your local settings and player data were not deliberately deleted. You can activate Pro later in the same app without rebuilding your local setup.',
                'followup_subject'=>'Upgrade from SunnyIPTV Light to Pro anytime',
                'followup_title'=>'SunnyIPTV Light keeps your setup ready',
                'followup_body'=>'Your Pro trial has ended and SunnyIPTV continues as Light. You can upgrade to Pro later in the same app. SunnyIPTV still does not provide IPTV channels, subscriptions, playlists, movies or series.',
                'button'=>'View SunnyIPTV Pro',
            ],
            'nl'=>[
                'started_subject'=>'Je SunnyIPTV Pro-proefperiode van 30 dagen is gestart',
                'started_title'=>'Je SunnyIPTV Pro-proefperiode is actief',
                'started_body'=>'Je krijgt nu 30 dagen de volledige SunnyIPTV Pro-ervaring met je eigen geautoriseerde bron. Er wordt niet automatisch betaald. Zonder Pro-aankoop gaat SunnyIPTV daarna verder als Light.',
                '7d_subject'=>'Nog 7 dagen SunnyIPTV Pro-proefperiode',
                '7d_title'=>'Je SunnyIPTV Pro-proefperiode duurt nog 7 dagen',
                '7d_body'=>'Je instellingen en spelergegevens blijven bewaard. Zonder Pro-aankoop gaat SunnyIPTV na de proefperiode verder als Light. Je kunt Pro nu of later kopen.',
                '1d_subject'=>'Nog 1 dag SunnyIPTV Pro-proefperiode',
                '1d_title'=>'Je SunnyIPTV Pro-proefperiode eindigt morgen',
                '1d_body'=>'Er volgt geen automatische afschrijving. Eindigt de Pro-proefperiode zonder Pro-aankoop, dan schakelt SunnyIPTV over naar Light terwijl je lokale instellingen en spelergegevens blijven staan.',
                'expired_subject'=>'Je SunnyIPTV Pro-proefperiode is afgelopen',
                'expired_title'=>'Je SunnyIPTV Pro-proefperiode van 30 dagen is afgelopen',
                'expired_body'=>'SunnyIPTV gaat nu verder als Light. Je lokale instellingen en spelergegevens zijn niet bewust verwijderd. Je kunt later Pro activeren in dezelfde app zonder je lokale setup opnieuw op te bouwen.',
                'followup_subject'=>'Upgrade SunnyIPTV Light wanneer je wilt naar Pro',
                'followup_title'=>'SunnyIPTV Light houdt je setup klaar',
                'followup_body'=>'Je Pro-proefperiode is afgelopen en SunnyIPTV gaat verder als Light. Je kunt later in dezelfde app upgraden naar Pro. SunnyIPTV levert nog steeds geen IPTV-zenders, abonnementen, playlists, films of series.',
                'button'=>'Bekijk SunnyIPTV Pro',
            ],
            'de'=>[
                'started_subject'=>'Deine 30-tägige SunnyIPTV-Pro-Testphase hat begonnen',
                'started_title'=>'Deine SunnyIPTV-Pro-Testphase ist aktiv',
                'started_body'=>'Du erhältst jetzt 30 Tage die vollständige SunnyIPTV-Pro-Erfahrung mit deiner eigenen autorisierten Quelle. Es erfolgt keine automatische Zahlung. Ohne Pro-Kauf läuft SunnyIPTV danach als Light weiter.',
                '7d_subject'=>'Noch 7 Tage SunnyIPTV-Pro-Testphase',
                '7d_title'=>'Deine SunnyIPTV-Pro-Testphase läuft noch 7 Tage',
                '7d_body'=>'Deine Einstellungen und Player-Daten bleiben erhalten. Ohne Pro-Kauf läuft SunnyIPTV nach der Testphase als Light weiter. Du kannst Pro jetzt oder später kaufen.',
                '1d_subject'=>'Noch 1 Tag SunnyIPTV-Pro-Testphase',
                '1d_title'=>'Deine SunnyIPTV-Pro-Testphase endet morgen',
                '1d_body'=>'Es gibt keine automatische Abbuchung. Endet die Pro-Testphase ohne Pro-Kauf, wechselt SunnyIPTV zu Light, während deine lokalen Einstellungen und Player-Daten erhalten bleiben.',
                'expired_subject'=>'Deine SunnyIPTV-Pro-Testphase ist beendet',
                'expired_title'=>'Deine 30-tägige SunnyIPTV-Pro-Testphase ist beendet',
                'expired_body'=>'SunnyIPTV läuft jetzt als Light weiter. Deine lokalen Einstellungen und Player-Daten wurden nicht absichtlich gelöscht. Du kannst Pro später in derselben App aktivieren, ohne deine lokale Einrichtung neu aufzubauen.',
                'followup_subject'=>'SunnyIPTV Light jederzeit auf Pro upgraden',
                'followup_title'=>'SunnyIPTV Light hält deine Einrichtung bereit',
                'followup_body'=>'Deine Pro-Testphase ist beendet und SunnyIPTV läuft als Light weiter. Du kannst später in derselben App auf Pro upgraden. SunnyIPTV liefert weiterhin keine IPTV-Sender, Abonnements, Playlists, Filme oder Serien.',
                'button'=>'SunnyIPTV Pro ansehen',
            ],
        ];
        $s=$strings[$lang]??$strings['en'];
        $key=in_array($kind,['started','7d','1d','expired','followup'],true)?$kind:'expired';
        $subject=$s[$key.'_subject'];
        $title=$s[$key.'_title'];
        $body_text=$s[$key.'_body'];
        $pro=$lang==='nl'?home_url('/language/nl/nenotv-pro-nl/'):($lang==='de'?home_url('/language/de/nenotv-pro-de/'):home_url('/pro/'));
        $body='<div style="font-family:Arial,sans-serif;max-width:620px;margin:auto"><h2>'.esc_html($title).'</h2><p>'.esc_html($body_text).'</p><p><a href="'.esc_url($pro).'" style="display:inline-block;padding:12px 18px;background:#111820;color:#ffd400;text-decoration:none;border-radius:8px;font-weight:700">'.esc_html($s['button']).'</a></p><p style="color:#64748b;font-size:12px">SunnyIPTV · Tube Beheer B.V.</p></div>';
        return (bool)wp_mail($email,$subject,$body,['Content-Type: text/html; charset=UTF-8']);
    }

    private static function send_renewal_notice(array $ent,string $kind): bool {
        $email=self::normalize_email((string)($ent['email']??''));
        if($email===''||!is_email($email))return false;
        $lang='en';
        $source_ref=(string)($ent['source_ref']??'');
        if(preg_match('/^order:(\d+)$/',$source_ref,$m)&&function_exists('wc_get_order')){
            $order=wc_get_order((int)$m[1]);
            if($order)$lang=self::order_language($order);
        }
        $strings=[
            'en'=>[
                'soon_subject'=>'Your SunnyIPTV Pro Yearly plan expires soon',
                'expired_subject'=>'Your SunnyIPTV Pro Yearly plan has expired',
                'soon_title'=>'Your SunnyIPTV Pro Yearly plan expires in 7 days',
                'expired_title'=>'Your SunnyIPTV Pro Yearly plan has expired',
                'soon_body'=>'Your yearly Pro access will end when the current period expires. Your SunnyIPTV settings remain in place; renew Pro to continue playback after the end date.',
                'expired_body'=>'Your yearly Pro access has ended. Your SunnyIPTV settings remain in place and you can renew the same Solo or Multi tier from My SunnyIPTV.',
                'button'=>'Open My SunnyIPTV',
            ],
            'nl'=>[
                'soon_subject'=>'Je SunnyIPTV Pro Jaarplan verloopt binnenkort',
                'expired_subject'=>'Je SunnyIPTV Pro Jaarplan is verlopen',
                'soon_title'=>'Je SunnyIPTV Pro Jaarplan verloopt over 7 dagen',
                'expired_title'=>'Je SunnyIPTV Pro Jaarplan is verlopen',
                'soon_body'=>'Je jaarlijkse Pro-toegang eindigt wanneer de huidige periode afloopt. Je SunnyIPTV-instellingen blijven staan; verleng Pro om na de einddatum te blijven afspelen.',
                'expired_body'=>'Je jaarlijkse Pro-toegang is afgelopen. Je SunnyIPTV-instellingen blijven staan en je kunt hetzelfde Solo- of Multi-plan via Mijn SunnyIPTV verlengen.',
                'button'=>'Open Mijn SunnyIPTV',
            ],
            'de'=>[
                'soon_subject'=>'Dein SunnyIPTV Pro Jahresplan läuft bald ab',
                'expired_subject'=>'Dein SunnyIPTV Pro Jahresplan ist abgelaufen',
                'soon_title'=>'Dein SunnyIPTV Pro Jahresplan läuft in 7 Tagen ab',
                'expired_title'=>'Dein SunnyIPTV Pro Jahresplan ist abgelaufen',
                'soon_body'=>'Dein jährlicher Pro-Zugang endet mit Ablauf des aktuellen Zeitraums. Deine SunnyIPTV-Einstellungen bleiben erhalten; verlängere Pro, um danach weiterzuschauen.',
                'expired_body'=>'Dein jährlicher Pro-Zugang ist beendet. Deine SunnyIPTV-Einstellungen bleiben erhalten und du kannst denselben Solo- oder Multi-Tarif in My SunnyIPTV verlängern.',
                'button'=>'My SunnyIPTV öffnen',
            ],
        ];
        $s=$strings[$lang]??$strings['en'];
        $account=function_exists('wc_get_page_permalink')?wc_get_page_permalink('myaccount'):home_url('/my-account/');
        if(in_array($lang,['nl','de'],true))$account=add_query_arg('lang',$lang,$account);
        $subject=$kind==='expired'?$s['expired_subject']:$s['soon_subject'];
        $title=$kind==='expired'?$s['expired_title']:$s['soon_title'];
        $body_text=$kind==='expired'?$s['expired_body']:$s['soon_body'];
        $body='<div style="font-family:Arial,sans-serif;max-width:620px;margin:auto"><h2>'.esc_html($title).'</h2><p>'.esc_html($body_text).'</p><p><a href="'.esc_url($account).'" style="display:inline-block;padding:12px 18px;background:#111820;color:#ffd400;text-decoration:none;border-radius:8px;font-weight:700">'.esc_html($s['button']).'</a></p><p style="color:#64748b;font-size:12px">SunnyIPTV · Tube Beheer B.V.</p></div>';
        return (bool)wp_mail($email,$subject,$body,['Content-Type: text/html; charset=UTF-8']);
    }

    public static function daily_maintenance(): void {
        if(self::mode()!=='live')return;
        global $wpdb;
        $now=self::now_mysql();

        $expired_codes=$wpdb->get_results($wpdb->prepare(
            'SELECT id,source_ref,reference FROM '.self::ent_table().' WHERE activation_expires_at IS NOT NULL AND activation_expires_at<%s',
            $now
        ),ARRAY_A);
        foreach((array)$expired_codes as $row){
            $wpdb->update(self::ent_table(),['activation_hash'=>'','activation_expires_at'=>null,'updated_at'=>$now],['id'=>(int)$row['id']]);
            $source_ref=(string)($row['source_ref']??'');
            if(preg_match('/^order:(\d+)$/',$source_ref,$m)&&function_exists('wc_get_order')){
                $order=wc_get_order((int)$m[1]);
                if($order){
                    $order->delete_meta_data('_nenotv_activation_token');
                    $order->save();
                }
            }
            self::log_event('activation_cleanup',(string)($row['reference']??''),$source_ref,'success','Expired plaintext activation material was cleaned automatically.','evt:activation-cleanup:'.(int)$row['id']);
        }

        $trials=$wpdb->get_results(
            "SELECT * FROM ".self::ent_table()." WHERE plan='trial' AND expires_at IS NOT NULL AND status IN ('active','expired') AND expires_at<=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 DAY) AND expires_at>=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 5 DAY)",
            ARRAY_A
        );
        foreach((array)$trials as $ent){
            $ts=strtotime((string)$ent['expires_at'].' UTC');
            if(!$ts)continue;
            $id=(int)$ent['id'];
            $now_ts=time();

            if($ts<=$now_ts){
                if(($ent['status']??'')!=='expired'){
                    $wpdb->update(self::ent_table(),['status'=>'expired','updated_at'=>$now],['id'=>$id]);
                    $ent['status']='expired';
                    self::log_event('trial_expired',(string)$ent['reference'],(string)$ent['source_ref'],'success','SunnyIPTV trial expired; local player data is not deleted.','evt:trial-expired-state:'.$id);
                }

                $expired_event='evt:trial-email-expired:'.$id;
                if(!self::event_exists($expired_event)){
                    if(self::send_trial_notice($ent,'expired')){
                        self::log_event('trial_reminder',(string)$ent['reference'],(string)$ent['source_ref'],'success','Trial expiry email sent automatically.',$expired_event);
                    }else{
                        self::log_event('trial_reminder_failed',(string)$ent['reference'],(string)$ent['source_ref'],'failed','Trial expiry email could not be sent.');
                    }
                    continue;
                }

                if(($now_ts-$ts)>=2*DAY_IN_SECONDS){
                    $follow_event='evt:trial-email-followup:'.$id;
                    if(!self::event_exists($follow_event)){
                        if(self::send_trial_notice($ent,'followup')){
                            self::log_event('trial_reminder',(string)$ent['reference'],(string)$ent['source_ref'],'success','Trial follow-up email sent automatically.',$follow_event);
                        }else{
                            self::log_event('trial_reminder_failed',(string)$ent['reference'],(string)$ent['source_ref'],'failed','Trial follow-up email could not be sent.');
                        }
                    }
                }
                continue;
            }

            $remaining=(int)ceil(($ts-$now_ts)/DAY_IN_SECONDS);
            $kind=$remaining<=1?'1d':($remaining<=7?'7d':'');
            if($kind==='')continue;
            $event_id='evt:trial-email-'.$kind.':'.$id;
            if(self::event_exists($event_id))continue;
            if(self::send_trial_notice($ent,$kind)){
                self::log_event('trial_reminder',(string)$ent['reference'],(string)$ent['source_ref'],'success',$kind==='1d'?'One-day trial reminder sent automatically.':'Seven-day trial reminder sent automatically.',$event_id);
            }else{
                self::log_event('trial_reminder_failed',(string)$ent['reference'],(string)$ent['source_ref'],'failed','Trial reminder email could not be sent.');
            }
        }

        $renewals=$wpdb->get_results(
            "SELECT * FROM ".self::ent_table()." WHERE status='active' AND plan='annual' AND expires_at IS NOT NULL AND expires_at<=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY) AND expires_at>=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 DAY)",
            ARRAY_A
        );
        foreach((array)$renewals as $ent){
            $ts=strtotime((string)$ent['expires_at'].' UTC');
            if(!$ts)continue;
            $kind=$ts<=time()?'expired':'7d';
            $event_id='evt:renewal-'.$kind.':'.(int)$ent['id'];
            if(self::event_exists($event_id))continue;
            if(self::send_renewal_notice($ent,$kind)){
                self::log_event('renewal_reminder',(string)$ent['reference'],(string)$ent['source_ref'],'success',$kind==='expired'?'Expiry renewal notice sent automatically.':'Seven-day renewal reminder sent automatically.',$event_id);
            }else{
                self::log_event('renewal_reminder_failed',(string)$ent['reference'],(string)$ent['source_ref'],'failed','Renewal reminder email could not be sent.');
            }
        }
    }

    public static function thankyou_activation($order_id): void {
        if (self::mode()!=='live' || !$order_id || !function_exists('wc_get_order')) return;
        $order=wc_get_order($order_id); if(!$order)return;
        $token=(string)$order->get_meta('_nenotv_activation_token',true);
        if($token==='')return;
        $lang=self::order_language($order);
        $all=[
            'en'=>['title'=>'SunnyIPTV Pro activation','body'=>'Your payment is confirmed. Use this activation code in SunnyIPTV:','note'=>'Keep this code private. It is only used to link your own SunnyIPTV devices.'],
            'nl'=>['title'=>'SunnyIPTV Pro activeren','body'=>'Je betaling is bevestigd. Gebruik deze activatiecode in SunnyIPTV:','note'=>'Houd deze code privé. De code wordt alleen gebruikt om je eigen SunnyIPTV-apparaten te koppelen.'],
            'de'=>['title'=>'SunnyIPTV Pro aktivieren','body'=>'Deine Zahlung wurde bestätigt. Verwende diesen Aktivierungscode in SunnyIPTV:','note'=>'Halte diesen Code geheim. Er wird nur verwendet, um deine eigenen SunnyIPTV-Geräte zu verbinden.'],
        ];$s=$all[$lang]??$all['en'];
        echo '<section class="woocommerce-order-details"><h2>'.esc_html($s['title']).'</h2><p>'.esc_html($s['body']).'</p><p><code style="font-size:1.15em;font-weight:700">'.esc_html($token).'</code></p><p>'.esc_html($s['note']).'</p></section>';
    }

    public static function email_activation($order, $sent_to_admin, $plain_text, $email): void {
        if ($sent_to_admin || self::mode()!=='live' || !$order || !method_exists($order,'get_meta')) return;
        $email_id = is_object($email) && isset($email->id) ? (string)$email->id : '';
        if (!in_array($email_id, ['customer_processing_order','customer_completed_order'], true)) return;
        $ref=(string)$order->get_meta('_nenotv_entitlement_reference',true);
        $ent=$ref!==''?self::find_by_ref($ref):null;
        if(!$ent || !self::entitlement_is_active($ent)) return;
        $token=(string)$order->get_meta('_nenotv_activation_token',true); if($token==='')return;
        $lang=self::order_language($order);
        $all=[
            'en'=>['title'=>'SunnyIPTV Pro activation','code'=>'Activation code','note'=>'Keep this code private. It expires automatically and can be replaced from My SunnyIPTV.'],
            'nl'=>['title'=>'SunnyIPTV Pro activeren','code'=>'Activatiecode','note'=>'Houd deze code privé. De code verloopt automatisch en kan via Mijn SunnyIPTV worden vervangen.'],
            'de'=>['title'=>'SunnyIPTV Pro aktivieren','code'=>'Aktivierungscode','note'=>'Halte diesen Code geheim. Er läuft automatisch ab und kann in My SunnyIPTV ersetzt werden.'],
        ];$s=$all[$lang]??$all['en'];
        if ($plain_text) echo "\n".$s['title']."\n".$s['code'].": {$token}\n".$s['note']."\n";
        else echo '<h2>'.esc_html($s['title']).'</h2><p>'.esc_html($s['code']).': <strong><code>'.esc_html($token).'</code></strong></p><p>'.esc_html($s['note']).'</p>';
    }

    public static function register_settings(): void {
        register_setting('nenotv_entitlements', self::OPT_MODE, ['sanitize_callback'=>function($v){return $v==='live'?'live':'shadow';}]);
        register_setting('nenotv_entitlements', self::OPT_PLAN, ['sanitize_callback'=>function($v){return in_array($v,['annual','lifetime'],true)?$v:'unconfigured';}]);
        register_setting('nenotv_entitlements', self::OPT_MAX_DEVICES, ['sanitize_callback'=>function($v){return (string)max(1,min(25,absint($v)));}]);
        register_setting('nenotv_entitlements', self::OPT_TOKEN_DAYS, ['sanitize_callback'=>function($v){return (string)max(1,min(365,absint($v)));}]);
        register_setting('nenotv_entitlements', self::OPT_TRIAL_ENABLED, ['sanitize_callback'=>function($v){return $v==='1'?'1':'0';}]);
        register_setting('nenotv_entitlements', self::OPT_TRIAL_DAYS, ['sanitize_callback'=>function($v){return (string)max(1,min(90,absint($v)));}]);
    }

    public static function admin_menu(): void {
        if (class_exists('NenoTV_Admin_Bridge')) add_submenu_page('nenotv-admin','SunnyIPTV Entitlements','Entitlements','manage_options','nenotv-entitlements',[__CLASS__,'admin_page']);
        else add_management_page('SunnyIPTV Entitlements','SunnyIPTV Entitlements','manage_options','nenotv-entitlements',[__CLASS__,'admin_page']);
    }

    public static function plugin_action_links(array $links): array {
        $url=admin_url('tools.php?page=nenotv-entitlements');
        if (class_exists('NenoTV_Admin_Bridge')) $url=admin_url('admin.php?page=nenotv-entitlements');
        array_unshift($links,'<a href="'.esc_url($url).'">Status</a>');
        return $links;
    }

    public static function admin_page(): void {
        if (!current_user_can('manage_options')) return;
        global $wpdb;
        $ent_count=(int)$wpdb->get_var('SELECT COUNT(*) FROM '.self::ent_table());
        $active_count=(int)$wpdb->get_var($wpdb->prepare('SELECT COUNT(*) FROM '.self::ent_table().' WHERE status=%s','active'));
        $dev_count=(int)$wpdb->get_var($wpdb->prepare('SELECT COUNT(*) FROM '.self::dev_table().' WHERE status=%s','active'));
        $ops_connected = class_exists('NenoTV_Operations_Support')
            && has_action('woocommerce_payment_complete', ['NenoTV_Operations_Support','payment_complete']) !== false;
        $reconciled_count = absint(get_option(self::OPT_RECONCILE_LAST_COUNT, 0));
        $bridge_ready = get_option(self::OPT_BRIDGE_ENABLED,'0')==='1' && strlen((string)get_option(self::OPT_BRIDGE_SECRET,''))>=32;
        ?>
        <div class="wrap"><h1>SunnyIPTV Entitlement Core</h1>
        <p>This is the central control plane between payments, refunds and SunnyIPTV Pro device access. It is deliberately installed in <strong>shadow mode</strong> until launch.</p>
        <table class="widefat striped" style="max-width:960px"><tbody>
        <tr><th>Version</th><td><?php echo esc_html(self::VERSION); ?></td></tr>
        <tr><th>Mode</th><td><strong><?php echo esc_html(strtoupper(self::mode())); ?></strong><?php if(self::mode()==='shadow') echo ' — records test lifecycle but never gives the app fabricated Pro access.'; ?></td></tr>
        <tr><th>Commercial plan</th><td><strong>Per product SKU</strong> — Solo = 1 device, Multi = 5 devices; Yearly = annual, Lifetime = lifetime. Test/unknown/mixed SKUs are blocked in LIVE mode.</td></tr>
        <tr><th>Solo → Multi upgrade</th><td><strong>Enabled policy</strong> — Yearly &gt;30 days: prorated €11.00 annual price difference; Yearly ≤30 days: current Multi Yearly price with one year appended; Lifetime: current Solo/Multi Lifetime price difference. Public checkout still follows the global launch safety lock.</td></tr>
        <tr><th>Operations adapter</th><td><?php echo $ops_connected?'<strong style="color:#177245">Connected</strong>':'<strong style="color:#b32d2e">Not connected</strong>'; ?></td></tr>
        <tr><th>Shadow reconciliation</th><td><?php echo esc_html((string)$reconciled_count); ?> existing paid test order(s) imported into the entitlement ledger.</td></tr>
        <tr><th>App bridge</th><td><?php echo $bridge_ready?'<strong style="color:#177245">Enabled</strong>':'Prepared but disabled until launch'; ?></td></tr>
        <tr><th>Entitlement records</th><td><?php echo esc_html((string)$ent_count); ?> total / <?php echo esc_html((string)$active_count); ?> active</td></tr>
        <tr><th>Active devices</th><td><?php echo esc_html((string)$dev_count); ?></td></tr>
        </tbody></table>
        <h2>Launch safety</h2><p>Do not switch this to LIVE until final tax/VAT handling, Mollie Live, any store-specific payment requirements and the app Pro module are approved. Commercial device limits are resolved per SKU: Solo = 1, Multi = 5. In LIVE mode an unconfigured or mixed commercial plan intentionally blocks a paid order from silently completing.</p>
        <h2>Configuration</h2>
        <form method="post" action="options.php"><?php settings_fields('nenotv_entitlements'); ?>
        <table class="form-table"><tbody>
        <tr><th>Mode</th><td><select name="<?php echo esc_attr(self::OPT_MODE); ?>"><option value="shadow" <?php selected(self::mode(),'shadow'); ?>>Shadow (safe now)</option><option value="live" <?php selected(self::mode(),'live'); ?>>Live</option></select></td></tr>
        <tr><th>Pro plans</th><td><code>NENOTV-PRO-1-YEARLY</code> → Solo Annual · <code>NENOTV-PRO-1-LIFETIME</code> → Solo Lifetime<br><code>NENOTV-PRO-5-YEARLY</code> → Multi Annual · <code>NENOTV-PRO-5-LIFETIME</code> → Multi Lifetime<br><span class="description">Legacy pre-launch Yearly/Lifetime SKUs resolve to Multi for backwards compatibility. The €1 <code>NENOTV-PRO</code> test SKU and unknown/mixed SunnyIPTV SKUs cannot grant live Pro access.</span></td></tr>
        <tr><th>Fallback/test device limit</th><td><input type="number" min="1" max="25" name="<?php echo esc_attr(self::OPT_MAX_DEVICES); ?>" value="<?php echo esc_attr((string)get_option(self::OPT_MAX_DEVICES,'5')); ?>"> <span class="description">Used only for the €1 test SKU or fallback diagnostics. Commercial Solo/Multi device limits come from the product SKU.</span></td></tr>
        <tr><th>Activation code validity</th><td><input type="number" min="1" max="365" name="<?php echo esc_attr(self::OPT_TOKEN_DAYS); ?>" value="<?php echo esc_attr((string)get_option(self::OPT_TOKEN_DAYS,'30')); ?>"> days</td></tr>
        </tbody></table><?php submit_button('Save'); ?></form>
        <p><strong>Security:</strong> device keys and activation codes are stored as keyed hashes. The bridge secret is generated automatically and is never shown on this page.</p>
        </div><?php
    }
}

register_activation_hook(__FILE__, ['NenoTV_Entitlement_Core','activate']);
register_deactivation_hook(__FILE__, ['NenoTV_Entitlement_Core','deactivate']);
add_action('plugins_loaded', ['NenoTV_Entitlement_Core','init']);



