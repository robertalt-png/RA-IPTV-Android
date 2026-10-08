<?php
/**
 * Plugin Name: SunnyIPTV App Telemetry
 * Description: Records app requests to nenotv/v1 (entitlement, account, pairing, catalog) into the App Bridge telemetry tables, in the same format as NenoTV_App_Bridge::record_request(). Needed because Entitlement Core overrides the entitlement routes (sunnyiptv-play-billing.php:33-38), so the App Bridge handler no longer runs. Never changes a response; App Bridge and Entitlement Core are not modified.
 * Version: 1.0.0
 */
if (!defined('ABSPATH')) exit;

final class SunnyIPTV_App_Telemetry {
    const ROUTES = '#^/nenotv/v1/(entitlement|account|pairing|catalog)/([a-z0-9_/-]{1,40})$#';
    private static array $started = [];

    public static function init(): void {
        add_filter('rest_request_before_callbacks', [self::class, 'before'], 1, 3);
        add_filter('rest_request_after_callbacks', [self::class, 'after'], 999, 3);
    }

    public static function before($response, $handler, $request) {
        if ($request instanceof WP_REST_Request && preg_match(self::ROUTES, $request->get_route())) {
            self::$started[spl_object_id($request)] = microtime(true);
        }
        return $response;
    }

    public static function after($response, $handler, $request) {
        try {
            if (!$request instanceof WP_REST_Request || !preg_match(self::ROUTES, $request->get_route(), $m)) return $response;
            if (self::from_app_bridge($handler['callback'] ?? null)) return $response; // the App Bridge records its own requests
            $p = $request->get_json_params();
            if (!is_array($p)) return $response;
            $device_id = is_scalar($p['device_id'] ?? null) ? trim((string)$p['device_id']) : '';
            if ($device_id === '' || strlen($device_id) > 190) return $response;
            $start = self::$started[spl_object_id($request)] ?? null;
            unset(self::$started[spl_object_id($request)]);
            $latency = $start ? max(0, (int)round((microtime(true) - $start) * 1000)) : 0;
            [$outcome, $error] = self::outcome($response);
            self::record($device_id, $p, substr($m[1] . '_' . str_replace('/', '_', $m[2]), 0, 32), $outcome, $latency, $error);
        } catch (Throwable $e) {
            // Telemetry must never break an app request.
        }
        return $response;
    }

    private static function from_app_bridge($cb): bool {
        try {
            if ($cb instanceof Closure) return basename((string)(new ReflectionFunction($cb))->getFileName()) === 'nenotv-app-bridge.php';
            if (is_array($cb) && isset($cb[0])) return (is_object($cb[0]) ? get_class($cb[0]) : (string)$cb[0]) === 'NenoTV_App_Bridge';
        } catch (Throwable $e) {}
        return false;
    }

    /** @return array{0:string,1:string} */
    private static function outcome($response): array {
        if ($response instanceof WP_Error) return ['error', sanitize_key(substr((string)$response->get_error_code(), 0, 64))];
        $status = $response instanceof WP_HTTP_Response ? $response->get_status() : 200;
        $data = $response instanceof WP_HTTP_Response ? $response->get_data() : $response;
        $error = is_array($data) && is_scalar($data['error'] ?? null) ? sanitize_key(substr((string)$data['error'], 0, 64)) : '';
        $ok = $status < 400 && !(is_array($data) && array_key_exists('ok', $data) && empty($data['ok']));
        return [$ok ? 'ok' : 'error', $ok ? '' : ($error !== '' ? $error : 'http_' . $status)];
    }

    private static function record(string $device_id, array $p, string $action, string $outcome, int $latency_ms, string $error_code): void {
        global $wpdb;
        $devices = $wpdb->prefix . 'nenotv_app_devices';
        $events = $wpdb->prefix . 'nenotv_app_events';
        static $ready = null;
        if ($ready === null) $ready = $wpdb->get_var($wpdb->prepare('SHOW TABLES LIKE %s', $wpdb->esc_like($devices))) === $devices;
        if (!$ready) return;
        // Same identity as NenoTV_App_Bridge::device_hash(): only the HMAC is stored, never the device id or key.
        $hash = hash_hmac('sha256', $device_id, wp_salt('auth'));
        $now = gmdate('Y-m-d H:i:s');
        $public = sanitize_text_field(substr(is_scalar($p['public_device_id'] ?? null) ? (string)$p['public_device_id'] : '', 0, 80));
        $platform = sanitize_key(substr(is_scalar($p['platform'] ?? null) ? (string)$p['platform'] : '', 0, 32));
        $version = sanitize_text_field(substr(is_scalar($p['app_version'] ?? null) ? (string)$p['app_version'] : '', 0, 32));
        $action = sanitize_key($action);
        $error_inc = $outcome === 'error' ? 1 : 0;
        $wpdb->query($wpdb->prepare(
            "INSERT INTO {$devices}
            (device_hash, public_device_id, platform, app_version, first_seen_gmt, last_seen_gmt, request_count, error_count, last_action, last_outcome, last_error_code, last_latency_ms, avg_latency_ms)
            VALUES (%s,%s,%s,%s,%s,%s,1,%d,%s,%s,%s,%d,%f)
            ON DUPLICATE KEY UPDATE
                avg_latency_ms=((avg_latency_ms * request_count) + VALUES(last_latency_ms)) / (request_count + 1),
                request_count=request_count+1,
                error_count=error_count+VALUES(error_count),
                public_device_id=VALUES(public_device_id),
                platform=VALUES(platform),
                app_version=VALUES(app_version),
                last_seen_gmt=VALUES(last_seen_gmt),
                last_action=VALUES(last_action),
                last_outcome=VALUES(last_outcome),
                last_error_code=VALUES(last_error_code),
                last_latency_ms=VALUES(last_latency_ms)",
            $hash, $public, $platform, $version, $now, $now, $error_inc, $action, $outcome, $error_code, $latency_ms, $latency_ms
        ));
        $wpdb->insert($events, [
            'created_gmt' => $now, 'device_hash' => $hash, 'public_device_id' => $public, 'platform' => $platform,
            'app_version' => $version, 'action' => $action, 'outcome' => $outcome, 'latency_ms' => $latency_ms, 'error_code' => $error_code,
        ], ['%s','%s','%s','%s','%s','%s','%s','%d','%s']);
    }
}
SunnyIPTV_App_Telemetry::init();
