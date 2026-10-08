<?php
/**
 * Plugin Name: SunnyIPTV App Monitor
 * Description: Collects crash reports from the SunnyIPTV app (technical data only), groups them by cause, mails admin@sunnyiptv.com on a new kind of crash and keeps them 30 days.
 * Version: 1.0.0
 * Requires PHP: 8.1
 */
if (!defined('ABSPATH')) exit;

final class SunnyIPTV_App_Monitor {
    const VERSION = '1';
    const NOTIFY = 'admin@sunnyiptv.com';
    const KEEP_DAYS = 30;

    public static function init(): void {
        add_action('rest_api_init', [self::class, 'routes']);
        add_action('admin_menu', [self::class, 'menu']);
        add_action('sunnyiptv_monitor_prune', [self::class, 'prune']);
        add_action('init', [self::class, 'maybe_install']);
    }

    public static function groups(): string { global $wpdb; return $wpdb->prefix . 'sunny_crash_groups'; }
    public static function reports(): string { global $wpdb; return $wpdb->prefix . 'sunny_crash_reports'; }

    public static function maybe_install(): void {
        if (get_option('sunnyiptv_monitor_db') === self::VERSION) return;
        global $wpdb;
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';
        $c = $wpdb->get_charset_collate();
        dbDelta("CREATE TABLE " . self::groups() . " (
  id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  signature char(40) NOT NULL,
  root_type varchar(190) NOT NULL DEFAULT '',
  top_frame varchar(255) NOT NULL DEFAULT '',
  first_seen datetime NOT NULL,
  last_seen datetime NOT NULL,
  hits int(10) unsigned NOT NULL DEFAULT 0,
  last_version varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY  (id),
  UNIQUE KEY signature (signature),
  KEY last_seen (last_seen)
) $c;");
        dbDelta("CREATE TABLE " . self::reports() . " (
  id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  signature char(40) NOT NULL,
  created_at datetime NOT NULL,
  app_version varchar(32) NOT NULL DEFAULT '',
  version_code int(10) unsigned NOT NULL DEFAULT 0,
  android varchar(40) NOT NULL DEFAULT '',
  model varchar(80) NOT NULL DEFAULT '',
  screen varchar(120) NOT NULL DEFAULT '',
  thread varchar(80) NOT NULL DEFAULT '',
  splits varchar(190) NOT NULL DEFAULT '',
  device_hash char(16) NOT NULL DEFAULT '',
  message text NULL,
  trace mediumtext NULL,
  PRIMARY KEY  (id),
  KEY signature_created (signature,created_at),
  KEY created_at (created_at)
) $c;");
        update_option('sunnyiptv_monitor_db', self::VERSION, false);
        if (!wp_next_scheduled('sunnyiptv_monitor_prune')) wp_schedule_event(time() + 3600, 'daily', 'sunnyiptv_monitor_prune');
    }

    public static function routes(): void {
        register_rest_route('sunnyiptv-monitor/v1', '/crash', [
            'methods' => 'POST', 'permission_callback' => '__return_true',
            'callback' => [self::class, 'receive'],
        ]);
    }

    /** Same cleaning as the app, applied again on the server: nothing that identifies a provider account or a person is stored. */
    public static function clean(string $s): string {
        $s = preg_replace('~\b([a-z][a-z0-9+.-]{1,15})://(?:[^\s/@]*@)?([^\s/:?#]+)\S*~i', '$1://$2/…', $s);
        $s = preg_replace('~[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}~', '[email]', $s);
        $s = preg_replace('~(user(name)?|pass(word)?|token|key|auth)=[^\s&,;]+~i', '$1=[x]', $s);
        $s = preg_replace('~\b[A-Za-z0-9_-]{32,}\b~', '[id]', $s);
        return (string)$s;
    }

    private static function field(array $d, string $k, int $max): string {
        $v = $d[$k] ?? '';
        if (!is_scalar($v)) return '';
        return mb_substr(self::clean(wp_strip_all_tags((string)$v, false)), 0, $max);
    }

    private static function rate(string $who, int $limit): bool {
        $key = 'sunny_mon_' . substr(hash_hmac('sha256', $who, wp_salt('auth')), 0, 32);
        $n = (int)get_transient($key);
        if ($n >= $limit) return false;
        set_transient($key, $n + 1, HOUR_IN_SECONDS);
        return true;
    }

    /** First "at com.nenotv..." / "at com.robertalt..." frame: where in our own code it went wrong. */
    public static function top_frame(string $trace): string {
        if (preg_match('~\n\s*at ((?:com\.nenotv|com\.robertalt)\.[^\n]+)~', $trace, $m)) return mb_substr(trim($m[1]), 0, 255);
        if (preg_match('~\n\s*at ([^\n]+)~', $trace, $m)) return mb_substr(trim($m[1]), 0, 255);
        return '';
    }

    public static function receive(WP_REST_Request $r): WP_REST_Response {
        if (strlen((string)$r->get_body()) > 65536) return new WP_REST_Response(['ok' => false, 'error' => 'too_large'], 413);
        $d = $r->get_json_params();
        if (!is_array($d)) return new WP_REST_Response(['ok' => false, 'error' => 'invalid'], 400);
        $ip = (string)($_SERVER['REMOTE_ADDR'] ?? 'unknown');
        $dev = self::field($d, 'public_device_id', 40);
        if (!self::rate('ip|' . $ip, 60) || !self::rate('dev|' . ($dev ?: $ip), 20)) return new WP_REST_Response(['ok' => false, 'error' => 'rate_limited'], 429);
        $trace = self::field($d, 'trace', 16000);
        $root = self::field($d, 'root_type', 190) ?: self::field($d, 'type', 190);
        if ($trace === '' || $root === '') return new WP_REST_Response(['ok' => false, 'error' => 'invalid'], 400);
        self::maybe_install();
        global $wpdb;
        $frame = self::top_frame($trace);
        $sig = sha1($root . '|' . preg_replace('~:\d+\)~', ')', $frame));
        $now = current_time('mysql', true);
        $version = self::field($d, 'app_version', 32);
        $wpdb->insert(self::reports(), [
            'signature' => $sig, 'created_at' => $now, 'app_version' => $version,
            'version_code' => (int)($d['version_code'] ?? 0),
            'android' => self::field($d, 'android', 40), 'model' => self::field($d, 'model', 80),
            'screen' => self::field($d, 'screen', 120), 'thread' => self::field($d, 'thread', 80),
            'splits' => self::field($d, 'splits', 190),
            'device_hash' => $dev === '' ? '' : substr(hash_hmac('sha256', $dev, wp_salt('auth')), 0, 16),
            'message' => self::field($d, 'message', 2000), 'trace' => $trace,
        ]);
        $existing = $wpdb->get_var($wpdb->prepare('SELECT id FROM ' . self::groups() . ' WHERE signature=%s', $sig));
        if ($existing) {
            $wpdb->query($wpdb->prepare('UPDATE ' . self::groups() . ' SET hits=hits+1, last_seen=%s, last_version=%s WHERE id=%d', $now, $version, (int)$existing));
        } else {
            $wpdb->insert(self::groups(), ['signature' => $sig, 'root_type' => $root, 'top_frame' => $frame, 'first_seen' => $now, 'last_seen' => $now, 'hits' => 1, 'last_version' => $version]);
            $body = "Nieuwe soort crash in de SunnyIPTV-app\n\nVersie: $version\nType: $root\nPlek: $frame\nScherm: " . self::field($d, 'screen', 120) . "\nToestel: " . self::field($d, 'model', 80) . ' · Android ' . self::field($d, 'android', 40)
                . "\n\nAlle details: " . admin_url('tools.php?page=sunnyiptv-app-monitor');
            wp_mail(self::NOTIFY, 'SunnyIPTV: nieuwe app-crash (' . $version . ')', $body);
        }
        return new WP_REST_Response(['ok' => true], 201);
    }

    public static function prune(): void {
        global $wpdb;
        $cut = gmdate('Y-m-d H:i:s', time() - self::KEEP_DAYS * DAY_IN_SECONDS);
        $wpdb->query($wpdb->prepare('DELETE FROM ' . self::reports() . ' WHERE created_at < %s', $cut));
        $wpdb->query($wpdb->prepare('DELETE FROM ' . self::groups() . ' WHERE last_seen < %s', $cut));
    }

    public static function menu(): void {
        add_management_page('SunnyIPTV app-crashes', 'SunnyIPTV app-crashes', 'manage_options', 'sunnyiptv-app-monitor', [self::class, 'page']);
    }

    public static function page(): void {
        if (!current_user_can('manage_options')) return;
        self::maybe_install();
        global $wpdb;
        echo '<div class="wrap"><h1>SunnyIPTV app-crashes</h1><p>Technische crashrapporten uit de app (30 dagen bewaard). Geen accountgegevens, wachtwoorden of streamlinks.</p>';
        $sig = isset($_GET['sig']) ? preg_replace('~[^a-f0-9]~', '', (string)$_GET['sig']) : '';
        if ($sig !== '') {
            $rows = $wpdb->get_results($wpdb->prepare('SELECT * FROM ' . self::reports() . ' WHERE signature=%s ORDER BY id DESC LIMIT 20', $sig), ARRAY_A);
            echo '<p><a href="' . esc_url(admin_url('tools.php?page=sunnyiptv-app-monitor')) . '">← Alle crashes</a></p>';
            foreach ($rows as $row) {
                echo '<h3>' . esc_html($row['created_at'] . ' UTC · ' . $row['app_version'] . ' · ' . $row['model'] . ' · Android ' . $row['android'] . ' · ' . $row['screen']) . '</h3>';
                echo '<p>Pro-module: ' . esc_html($row['splits'] ?: '-') . ' · thread: ' . esc_html($row['thread']) . ' · apparaat: ' . esc_html($row['device_hash']) . '</p>';
                echo '<pre style="white-space:pre-wrap;background:#fff;padding:12px;border:1px solid #ccd0d4;max-height:420px;overflow:auto">' . esc_html($row['trace']) . '</pre>';
            }
            echo '</div>';
            return;
        }
        $groups = $wpdb->get_results('SELECT * FROM ' . self::groups() . ' ORDER BY last_seen DESC LIMIT 100', ARRAY_A);
        if (!$groups) { echo '<p>Nog geen crashes ontvangen.</p></div>'; return; }
        echo '<table class="widefat striped"><thead><tr><th>Laatst (UTC)</th><th>Aantal</th><th>Versie</th><th>Type</th><th>Plek in de code</th><th></th></tr></thead><tbody>';
        foreach ($groups as $g) {
            echo '<tr><td>' . esc_html($g['last_seen']) . '</td><td>' . (int)$g['hits'] . '</td><td>' . esc_html($g['last_version']) . '</td><td>' . esc_html($g['root_type']) . '</td><td><code>' . esc_html($g['top_frame']) . '</code></td><td><a href="' . esc_url(admin_url('tools.php?page=sunnyiptv-app-monitor&sig=' . $g['signature'])) . '">Details</a></td></tr>';
        }
        echo '</tbody></table></div>';
    }
}
SunnyIPTV_App_Monitor::init();
