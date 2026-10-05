<?php
/** Explicit, revocable review access; never changes the commercial launch mode. */
trait SunnyIPTV_Review_Access {
    private static function app_services_available(): bool {
        return self::mode()==='live'||self::catalog_testing_enabled()||self::review_access_allowed();
    }

    public static function review_meta(): void {
        register_meta('user', '_sunnyiptv_review_account', [
            'type'=>'string', 'single'=>true, 'show_in_rest'=>true,
            'auth_callback'=>static fn() => current_user_can('manage_options'),
        ]);
    }

    private static function is_review_entitlement(array $ent): bool {
        return ($ent['source'] ?? '') === 'google_play_review';
    }

    private static function review_customer(int $id): bool {
        $user = get_userdata($id);
        return $user && $user->roles === ['customer']
            && get_user_meta($id, '_sunnyiptv_review_account', true) === '1'
            && !user_can($user, 'manage_options') && !user_can($user, 'edit_posts');
    }

    private static function review_access_allowed(?array $ent = null): bool {
        $config = get_option('sunnyiptv_review_access', []);
        if (!is_array($config) || ($config['enabled'] ?? false) !== true) return false;
        $id = (int)($config['entitlement_id'] ?? 0);
        $user_id = (int)($config['user_id'] ?? 0);
        if ($id < 1 || !self::review_customer($user_id)) return false;
        if ($ent === null) $ent = self::find_by_id($id);
        if (!is_array($ent) || (int)$ent['id'] !== $id || !self::is_review_entitlement($ent)
            || ($ent['source_ref'] ?? '') !== 'review-user:'.$user_id
            || !self::entitlement_is_active($ent)) return false;
        $user = get_userdata($user_id);
        return hash_equals((string)$ent['email_hash'], self::email_hash((string)$user->user_email));
    }

    public static function review_routes(): void {
        foreach (['provision', 'revoke'] as $action) {
            register_rest_route(self::NS, '/review/'.$action, [
                'methods' => 'POST',
                'permission_callback' => static fn() => is_user_logged_in() && current_user_can('manage_options'),
                'callback' => static fn($request) => self::review_admin($request, $action),
            ]);
        }
    }

    public static function review_admin(WP_REST_Request $request, string $action): WP_REST_Response {
        if (!is_user_logged_in() || !current_user_can('manage_options')) {
            return self::json(['ok'=>false, 'error'=>'forbidden'], 403);
        }
        if ($action === 'revoke') return self::review_revoke();
        if ($action !== 'provision') return self::json(['ok'=>false, 'error'=>'invalid_action'], 400);
        $params = $request->get_json_params();
        $id = is_array($params) ? ($params['user_id'] ?? null) : null;
        if (!is_int($id) || $id < 1 || !self::review_customer($id)) {
            return self::json(['ok'=>false, 'error'=>'dedicated_review_customer_required'], 400);
        }
        if (get_option('sunnyiptv_review_access', []) !== []) {
            return self::json(['ok'=>false, 'error'=>'review_already_configured'], 409);
        }
        global $wpdb;
        $user = get_userdata($id);
        $email = self::normalize_email((string)$user->user_email);
        if (!is_email($email)) return self::json(['ok'=>false, 'error'=>'invalid_account'], 400);
        $ref = 'review-user:'.$id;
        $lock = 'sunnyiptv_review_'.substr(hash('sha256', self::ent_table()), 0, 32);
        if ((int)$wpdb->get_var($wpdb->prepare('SELECT GET_LOCK(%s, 3)', $lock)) !== 1) {
            return self::json(['ok'=>false, 'error'=>'review_busy'], 409);
        }
        try {
            if (get_option('sunnyiptv_review_access', []) !== [] || self::find_by_source('google_play_review', $ref)) {
                return self::json(['ok'=>false, 'error'=>'review_already_configured'], 409);
            }
            $count = $wpdb->get_var($wpdb->prepare(
                'SELECT COUNT(*) FROM '.self::ent_table().' WHERE email_hash=%s', self::email_hash($email)
            ));
            if ($count === null || !is_numeric($count)) return self::json(['ok'=>false, 'error'=>'review_lookup_failed'], 503);
            if ((int)$count !== 0) return self::json(['ok'=>false, 'error'=>'existing_account_access'], 409);
            // 80 random bits, retaining the activation format accepted by existing Android builds.
            $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
            $raw = '';
            for ($i=0; $i<16; $i++) $raw .= $alphabet[random_int(0, strlen($alphabet)-1)];
            $token = 'NENO-'.implode('-', str_split($raw, 4));
            $now = self::now_mysql();
            $reference = self::ref();
            $written = $wpdb->insert(self::ent_table(), [
                'reference'=>$reference, 'email'=>$email, 'email_hash'=>self::email_hash($email),
                'level'=>'pro', 'plan'=>'lifetime', 'status'=>'active', 'max_devices'=>25,
                'source'=>'google_play_review', 'source_ref'=>$ref, 'payment_mode'=>'review', 'language'=>'en',
                'activation_hash'=>self::key_hash($token), 'activation_expires_at'=>null,
                'starts_at'=>$now, 'expires_at'=>null, 'created_at'=>$now, 'updated_at'=>$now,
            ]);
            $ent_id = (int)$wpdb->insert_id;
            if ($written === false || $ent_id < 1) return self::json(['ok'=>false, 'error'=>'review_write_failed'], 503);
            $config = ['enabled'=>true, 'entitlement_id'=>$ent_id, 'user_id'=>$id];
            if (!update_option('sunnyiptv_review_access', $config, false)
                && get_option('sunnyiptv_review_access', []) !== $config) {
                $wpdb->update(self::ent_table(), ['status'=>'revoked'], ['id'=>$ent_id]);
                return self::json(['ok'=>false, 'error'=>'review_config_failed'], 503);
            }
            self::log_event('review_provision', $reference, $ref, 'success', 'Dedicated review access provisioned. Commercial mode unchanged.');
            return self::json(['ok'=>true, 'activation_token'=>$token, 'expires_at'=>null, 'max_devices'=>25], 200);
        } finally {
            $wpdb->get_var($wpdb->prepare('SELECT RELEASE_LOCK(%s)', $lock));
        }
    }

    private static function review_revoke(): WP_REST_Response {
        $config = get_option('sunnyiptv_review_access', []);
        $id = is_array($config) ? (int)($config['entitlement_id'] ?? 0) : 0;
        $ent = $id > 0 ? self::find_by_id($id) : null;
        if (!is_array($ent) || !self::is_review_entitlement($ent)) {
            return self::json(['ok'=>false, 'error'=>'review_not_configured'], 404);
        }
        $config['enabled'] = false;
        if (!update_option('sunnyiptv_review_access', $config, false)
            && get_option('sunnyiptv_review_access', []) !== $config) {
            return self::json(['ok'=>false, 'error'=>'review_config_failed'], 503);
        }
        global $wpdb;
        $saved = $wpdb->update(self::ent_table(), ['status'=>'revoked', 'activation_hash'=>'', 'updated_at'=>self::now_mysql()], ['id'=>$id]);
        self::log_event('review_revoke', (string)$ent['reference'], (string)$ent['source_ref'], 'success', 'Review access disabled. Other accounts unchanged.');
        return self::json(['ok'=>$saved !== false, 'error'=>$saved === false ? 'review_write_failed' : ''], $saved === false ? 503 : 200);
    }

    private static function review_entitlement_request(array $p, string $action): ?WP_REST_Response {
        if ($action === 'refresh') {
            $dev = self::find_device((string)$p['device_id']);
            $ent = $dev ? self::find_by_id((int)$dev['entitlement_id']) : null;
            if (!is_array($ent) || !self::is_review_entitlement($ent)) return null;
            $verified = ($dev['status'] ?? '') === 'active'
                && hash_equals((string)$dev['device_key_hash'], self::key_hash((string)$p['device_key']))
                && self::review_access_allowed($ent);
            return self::json(['ok'=>true, 'entitlement'=>$verified ? self::pro_payload($ent) : self::free_entitlement(), 'mode'=>'review'], 200);
        }
        if (!in_array($action, ['redeem', 'claim'], true)) return null;
        $token = (string)($p['activation_token'] ?? '');
        $ent = self::find_by_activation($token);
        if (!is_array($ent) || !self::is_review_entitlement($ent)) return null;
        if ($action !== 'redeem') return self::json(['ok'=>false, 'error'=>'invalid_activation'], 200);
        if (!self::review_access_allowed($ent)) return self::json(['ok'=>false, 'error'=>'invalid_activation'], 200);
        $bound = self::bind_device($ent, $p);
        if (empty($bound['ok'])) return self::json($bound, 200);
        // Reusable only for this isolated entitlement; ordinary one-use purchase codes remain unchanged.
        self::log_event('review_device_linked', (string)$ent['reference'], (string)$p['device_id'], 'success', 'Device linked to dedicated review access.');
        return self::json(['ok'=>true, 'entitlement'=>self::pro_payload($ent), 'mode'=>'review'], 200);
    }
}
