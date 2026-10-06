<?php
if (!defined('ABSPATH')) exit;

/**
 * One-tap device linking for visitors without a SunnyIPTV login.
 * The pairing code from the app identifies the device; pressing "Dit apparaat koppelen"
 * creates a customer account for it, signs that browser in and approves the pairing.
 * The email address is asked once, together with the provider details on /sunnyiptv-setup/.
 */
trait SunnyIPTV_Quick_Pairing {
    public static function quick_hooks(): void {
        add_action('admin_post_nopriv_sunnyiptv_quick_pair', [__CLASS__, 'quick_pair']);
        add_action('admin_post_sunnyiptv_quick_pair', [__CLASS__, 'quick_pair']);
        add_action(self::CRON, [__CLASS__, 'quick_cleanup']);
    }

    private static function quick_lang($value): string {
        return is_string($value) && in_array($value, ['nl','en','de'], true) ? $value : 'en';
    }

    private static function quick_text(string $lang, string $nl, string $en, string $de): string {
        return $lang === 'nl' ? $nl : ($lang === 'de' ? $de : $en);
    }

    /** Device block shown on My SunnyIPTV instead of the login form. Empty when the code cannot be linked here. */
    public static function quick_block(string $code, string $lang): string {
        if (is_user_logged_in()) return '';
        $code = self::pairing_code($code);
        $lang = self::quick_lang($lang);
        if ($code === '') return '';
        if (!self::pairing_rate('quick_view', (string)($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 30, 5*MINUTE_IN_SECONDS)) return '';
        $session = self::pairing_load($code);
        if (!$session || ($session['state'] ?? '') !== 'pending' || (int)($session['pairing_version'] ?? 1) < 3) return '';
        $t = static fn(string $nl, string $en, string $de): string => self::quick_text($lang, $nl, $en, $de);
        $shown = strlen($code) === 4 ? $code : substr($code, 0, 5).'-'.substr($code, 5);
        $html  = '<style id="sunnyiptv-quick-hide">#customer_login,.sunnyiptv-signin{display:none!important}</style>';
        $html .= '<style>.sunnyiptv-quick{margin:0 0 28px}.sunnyiptv-quick .sq-device{border:1px solid #e1e5ea;border-radius:12px;padding:18px;margin:16px 0}.sunnyiptv-quick .sq-device strong{display:block;font-size:1.25em}.sunnyiptv-quick .sq-device code{font-size:1.4em;letter-spacing:.15em}.sunnyiptv-quick button{display:block;width:100%;padding:16px 18px;border:0;border-radius:8px;background:#ffd600;color:#111;font-weight:700;font-size:1.1em;cursor:pointer}.sunnyiptv-quick .sq-login{display:inline-block;margin-top:16px}</style>';
        $html .= '<section class="sunnyiptv-quick"><h2>'.esc_html($t('Dit apparaat koppelen', 'Link this device', 'Dieses Gerät verbinden')).'</h2>';
        $html .= '<div class="sq-device"><strong>'.esc_html((string)($session['name'] ?? 'SunnyIPTV')).'</strong>';
        $html .= '<p><code>'.esc_html($shown).'</code></p>';
        if (!empty($session['public_device_id'])) $html .= '<p>'.esc_html((string)$session['public_device_id']).'</p>';
        $html .= '</div><form method="post" action="'.esc_url(admin_url('admin-post.php')).'">';
        $html .= '<input type="hidden" name="action" value="sunnyiptv_quick_pair"><input type="hidden" name="code" value="'.esc_attr($code).'"><input type="hidden" name="lang" value="'.esc_attr($lang).'">';
        $html .= wp_nonce_field('sunnyiptv_quick_pair_'.$code, '_wpnonce', true, false);
        $html .= '<button type="submit">'.esc_html($t('Dit apparaat koppelen', 'Link this device', 'Dieses Gerät verbinden')).'</button></form>';
        $html .= '<a class="sq-login" href="#sunny-login" onclick="var s=document.getElementById(\'sunnyiptv-quick-hide\');if(s)s.remove();">'.esc_html($t('Heb je al een account? Inloggen', 'Already have an account? Sign in', 'Schon ein Konto? Anmelden')).'</a>';
        $html .= '</section>';
        return $html;
    }

    public static function quick_pair(): void {
        nocache_headers();
        $code = self::pairing_code(is_scalar($_POST['code'] ?? null) ? (string)$_POST['code'] : '');
        $lang = self::quick_lang($_POST['lang'] ?? null);
        $t = static fn(string $nl, string $en, string $de): string => self::quick_text($lang, $nl, $en, $de);
        $nonce = is_scalar($_POST['_wpnonce'] ?? null) ? (string)$_POST['_wpnonce'] : '';
        if ($code === '' || !wp_verify_nonce($nonce, 'sunnyiptv_quick_pair_'.$code)) wp_die(esc_html($t('Deze pagina is verlopen. Tik in de app opnieuw op het gele blok.', 'This page has expired. Tap the yellow block in the app again.', 'Diese Seite ist abgelaufen. Tippe in der App erneut auf den gelben Block.')), 'SunnyIPTV', ['response'=>403]);
        // Signed-in visitors keep the existing approval page.
        if (is_user_logged_in()) { wp_safe_redirect(add_query_arg(['code'=>$code, 'lang'=>$lang, 'sunny_account'=>'1'], home_url('/nenotv-pair/'))); exit; }
        $ip = (string)($_SERVER['REMOTE_ADDR'] ?? 'unknown');
        if (!self::pairing_rate('quick_account', $ip, 5, HOUR_IN_SECONDS)) wp_die(esc_html($t('Te veel pogingen. Probeer het over een uur opnieuw.', 'Too many attempts. Please try again in an hour.', 'Zu viele Versuche. Bitte in einer Stunde erneut versuchen.')), 'SunnyIPTV', ['response'=>429]);
        if (!self::pairing_lock($code)) wp_die(esc_html($t('Even geduld en probeer het opnieuw.', 'Please try again.', 'Bitte erneut versuchen.')), 'SunnyIPTV', ['response'=>503]);
        $uid = 0;
        try {
            $session = self::pairing_load($code);
            if (!$session || ($session['state'] ?? '') !== 'pending' || (int)($session['pairing_version'] ?? 1) < 3)
                wp_die(esc_html($t('Deze koppelcode is verlopen of al gebruikt. Tik in de app op "Nieuwe koppelcode".', 'This pairing code has expired or was already used. Tap "New pairing code" in the app.', 'Dieser Code ist abgelaufen oder wurde bereits verwendet. Tippe in der App auf "Neuer Code".')), 'SunnyIPTV', ['response'=>410]);
            $login = '';
            for ($i = 0; $i < 5 && $login === ''; $i++) { $candidate = 'sunny-'.strtolower(wp_generate_password(12, false, false)); if (!username_exists($candidate)) $login = $candidate; }
            $uid = $login === '' ? 0 : wp_insert_user(['user_login'=>$login, 'user_pass'=>wp_generate_password(40, true, true), 'user_email'=>'', 'display_name'=>'SunnyIPTV', 'role'=>'customer']);
            if (is_wp_error($uid) || (int)$uid < 1 || !self::free_customer((int)$uid)) {
                if (!is_wp_error($uid) && (int)$uid > 0) { require_once ABSPATH.'wp-admin/includes/user.php'; wp_delete_user((int)$uid); }
                wp_die(esc_html($t('Het account kon niet worden aangemaakt. Probeer het opnieuw.', 'The account could not be created. Please try again.', 'Das Konto konnte nicht erstellt werden. Bitte erneut versuchen.')), 'SunnyIPTV', ['response'=>503]);
            }
            $uid = (int)$uid;
            update_user_meta($uid, '_sunnyiptv_quick_account', time());
            $session['account_user_id'] = $uid;
            $session['state'] = 'approved';
            if (!update_option('nenotv_pair_'.$code, $session, false)) {
                require_once ABSPATH.'wp-admin/includes/user.php'; wp_delete_user($uid);
                wp_die(esc_html($t('Koppelen is niet gelukt. Probeer het opnieuw.', 'Linking failed. Please try again.', 'Verbinden fehlgeschlagen. Bitte erneut versuchen.')), 'SunnyIPTV', ['response'=>503]);
            }
        } finally { self::pairing_unlock($code); }
        wp_set_current_user($uid);
        wp_set_auth_cookie($uid, true, is_ssl());
        setcookie('sunnyiptv_pair_return', '', ['expires'=>time()-3600, 'path'=>'/', 'secure'=>true, 'httponly'=>true, 'samesite'=>'Lax']);
        wp_safe_redirect(add_query_arg('lang', $lang, home_url('/sunnyiptv-setup/')));
        exit;
    }

    /** Email field on /sunnyiptv-setup/, only for accounts that have no email address yet. */
    private static function quick_email_field(int $uid, string $lang): string {
        $user = get_userdata($uid);
        if (!$user || (string)$user->user_email !== '') return '';
        $t = static fn(string $nl, string $en, string $de): string => self::quick_text(self::quick_lang($lang), $nl, $en, $de);
        return '<p><label>'.esc_html($t('Je e-mailadres', 'Your email address', 'Deine E-Mail-Adresse')).'<input name="account_email" type="email" maxlength="254" required autocomplete="email" inputmode="email"></label><small>'.esc_html($t('Hiermee kun je later inloggen op Mijn SunnyIPTV.', 'Use it to sign in to My SunnyIPTV later.', 'Damit meldest du dich später bei Mein SunnyIPTV an.')).'</small></p>';
    }

    /** Validates the email before anything is saved. Returns '' when the account already has one. */
    private static function quick_email_check(int $uid): string {
        $user = get_userdata($uid);
        if (!$user || (string)$user->user_email !== '') return '';
        $lang = self::source_language();
        $t = static fn(string $nl, string $en, string $de): string => self::quick_text($lang, $nl, $en, $de);
        $email = sanitize_email(self::source_post('account_email', 254));
        if ($email === '' || !is_email($email)) wp_die(esc_html($t('Vul een geldig e-mailadres in.', 'Enter a valid email address.', 'Gib eine gültige E-Mail-Adresse ein.')), 'SunnyIPTV', ['response'=>400, 'back_link'=>true]);
        if (email_exists($email)) wp_die(esc_html($t('Dit e-mailadres heeft al een SunnyIPTV-account. Log in met dat account en tik daarna in de app opnieuw op het gele blok.', 'This email address already has a SunnyIPTV account. Sign in with that account, then tap the yellow block in the app again.', 'Diese E-Mail-Adresse hat bereits ein SunnyIPTV-Konto. Melde dich damit an und tippe dann in der App erneut auf den gelben Block.')), 'SunnyIPTV', ['response'=>409, 'back_link'=>true]);
        return $email;
    }

    /** Stores the checked email after the provider details were saved, then sends the welcome mail. */
    private static function quick_email_store(int $uid, string $email): void {
        if ($email === '') return;
        $user = get_userdata($uid);
        if (!$user || (string)$user->user_email !== '' || email_exists($email)) return;
        add_filter('send_email_change_email', '__return_false', 99);
        $result = wp_update_user(['ID'=>$uid, 'user_email'=>$email]);
        remove_filter('send_email_change_email', '__return_false', 99);
        if (is_wp_error($result)) return;
        update_user_meta($uid, 'billing_email', $email);
        delete_user_meta($uid, '_sunnyiptv_quick_account');
        $user = get_userdata($uid);
        $key = $user ? get_password_reset_key($user) : null;
        if (!$user || is_wp_error($key)) return;
        $lang = self::source_language();
        $t = static fn(string $nl, string $en, string $de): string => self::quick_text($lang, $nl, $en, $de);
        $account = function_exists('wc_get_page_permalink') ? wc_get_page_permalink('myaccount') : home_url('/my-account/');
        $reset = function_exists('wc_get_endpoint_url') ? add_query_arg(['key'=>$key, 'id'=>$uid], wc_get_endpoint_url('lost-password', '', $account)) : wp_lostpassword_url();
        $subject = $t('Je SunnyIPTV-account is klaar', 'Your SunnyIPTV account is ready', 'Dein SunnyIPTV-Konto ist bereit');
        $body = '<p>'.esc_html($t('Je telefoon is gekoppeld en je lijst wordt klaargemaakt. De app ontvangt hem automatisch.', 'Your phone is linked and your list is being prepared. The app receives it automatically.', 'Dein Telefon ist verbunden und deine Liste wird vorbereitet. Die App empfängt sie automatisch.')).'</p>'
            .'<p>'.esc_html($t('Wil je later op een ander apparaat of op de website inloggen? Kies dan hier een wachtwoord:', 'Want to sign in later on another device or on the website? Choose a password here:', 'Möchtest du dich später auf einem anderen Gerät oder auf der Website anmelden? Wähle hier ein Passwort:')).'</p>'
            .'<p><a href="'.esc_url($reset).'">'.esc_html($t('Wachtwoord kiezen', 'Choose a password', 'Passwort wählen')).'</a></p>'
            .'<p>'.esc_html($t('Werkt de link niet meer? Gebruik "Wachtwoord vergeten" op Mijn SunnyIPTV.', 'Link expired? Use "Lost your password" on My SunnyIPTV.', 'Link abgelaufen? Nutze "Passwort vergessen" auf Mein SunnyIPTV.')).'</p>'
            .'<p>'.esc_html($t('Je aanbiedergegevens staan om veiligheidsredenen niet in deze mail.', 'For your security, your provider details are not included in this email.', 'Aus Sicherheitsgründen stehen deine Anbieterdaten nicht in dieser E-Mail.')).'</p>';
        wp_mail($email, $subject, $body, ['Content-Type: text/html; charset=UTF-8']);
    }

    /** Removes one-tap accounts that never received an email address within 7 days. */
    public static function quick_cleanup(): void {
        global $wpdb;
        $ids = $wpdb->get_col($wpdb->prepare("SELECT um.user_id FROM {$wpdb->usermeta} um JOIN {$wpdb->users} u ON u.ID=um.user_id WHERE um.meta_key=%s AND CAST(um.meta_value AS UNSIGNED)<%d AND u.user_email='' LIMIT 50", '_sunnyiptv_quick_account', time()-7*DAY_IN_SECONDS));
        if (!$ids) return;
        require_once ABSPATH.'wp-admin/includes/user.php';
        foreach ($ids as $id) {
            $id = (int)$id;
            $user = get_userdata($id);
            if ($user && (string)$user->user_email === '' && self::free_customer($id) && get_user_meta($id, '_sunnyiptv_quick_account', true)) wp_delete_user($id);
        }
    }
}
