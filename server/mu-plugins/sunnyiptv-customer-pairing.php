<?php
/**
 * Plugin Name: SunnyIPTV Customer Pairing
 * Description: Keep device linking on My SunnyIPTV without changing authentication or entitlements.
 * Version: 0.1.3
 */
if (!defined('ABSPATH')) exit;

final class SunnyIPTV_Customer_Pairing {
    const COOKIE = 'sunnyiptv_pair_return';

    public static function init(): void {
        add_action('template_redirect', [self::class, 'incoming'], -10);
        add_filter('woocommerce_login_redirect', [self::class, 'login_redirect'], 90);
        add_filter('woocommerce_registration_redirect', [self::class, 'login_redirect'], 90);
        add_action('woocommerce_before_customer_login_form', [self::class, 'login_choices']);
        add_action('woocommerce_login_form_start', [self::class, 'login_intro']);
        add_action('woocommerce_register_form_start', [self::class, 'register_intro']);
        add_filter('gettext_woocommerce', [self::class, 'account_labels'], 20, 3);
        add_filter('option_woocommerce_registration_generate_username', [self::class, 'automatic_username']);
        add_filter('nsl_is_register_allowed', [self::class, 'google_registration'], 11, 2);
        add_filter('nsl_google_is_login_allowed', [self::class, 'customer_login'], 100, 3);
        add_filter('nsl_google_auto_link_allowed', [self::class, 'customer_login'], 100, 3);
        add_action('nsl_google_register_new_user', [self::class, 'new_customer'], 1);
    }

    public static function code($value): string {
        if (!is_string($value)) return '';
        $value = strtoupper(str_replace(['-', ' '], '', trim($value)));
        return preg_match('/^(?:[A-Z2-9]{4}|[A-F0-9]{10})$/D', $value) ? $value : '';
    }

    public static function language($value): string {
        return is_string($value) && in_array($value, ['nl', 'en', 'de'], true) ? $value : 'en';
    }

    public static function account_url(string $lang): string {
        $id = function_exists('wc_get_page_id') ? (int)wc_get_page_id('myaccount') : 0;
        if ($id > 0 && function_exists('pll_get_post')) $id = (int)(pll_get_post($id, $lang) ?: $id);
        return $id > 0 ? get_permalink($id) : home_url('/my-account/');
    }

    // This signed cookie is navigation state only. It never authorizes a device or user.
    public static function encode(string $code, string $lang, int $expires, string $kind = 'pair'): string {
        $body = base64_encode(wp_json_encode(['code'=>$code, 'lang'=>$lang, 'expires'=>$expires, 'kind'=>$kind]));
        return $body . '.' . hash_hmac('sha256', $body, wp_salt('nonce'));
    }

    public static function decode($cookie): array {
        if (!is_string($cookie) || strlen($cookie) > 512) return [];
        $parts = explode('.', $cookie);
        if (count($parts) !== 2 || !hash_equals(hash_hmac('sha256', $parts[0], wp_salt('nonce')), $parts[1])) return [];
        $data = json_decode((string)base64_decode($parts[0], true), true);
        if (!is_array($data) || !is_int($data['expires'] ?? null) || $data['expires'] <= time() || $data['expires'] > time()+600) return [];
        $code = self::code($data['code'] ?? null);
        $kind = $data['kind'] ?? 'pair';
        if (!in_array($kind, ['pair', 'setup', 'pro'], true) || !in_array($data['lang'] ?? null, ['nl', 'en', 'de'], true)) return [];
        if ($kind === 'setup') return ['code'=>'', 'lang'=>$data['lang'], 'kind'=>'setup'];
        if ($code === '') return [];
        return ['code'=>$code, 'lang'=>$data['lang'], 'kind'=>$kind];
    }

    private static function pending(): array {
        return self::decode(isset($_COOKIE[self::COOKIE]) ? wp_unslash($_COOKIE[self::COOKIE]) : null);
    }

    private static function remember(array $context): bool {
        $cookie = $context ? self::encode($context['code'], $context['lang'], time()+600, $context['kind'] ?? 'pair') : '';
        return setcookie(self::COOKIE, $cookie, ['expires'=>$context ? time()+600 : time()-3600, 'path'=>'/', 'secure'=>true, 'httponly'=>true, 'samesite'=>'Lax']);
    }

    private static function private_headers(): void {
        if (!defined('DONOTCACHEPAGE')) define('DONOTCACHEPAGE', true);
        nocache_headers();
        header('Cache-Control: private, no-store, max-age=0');
        header('Referrer-Policy: no-referrer');
        header('X-Robots-Tag: noindex, nofollow');
        do_action('litespeed_control_set_nocache', 'SunnyIPTV customer pairing');
    }

    public static function account_target(array $context): string {
        if (($context['kind'] ?? '') === 'pro') return add_query_arg(['code'=>$context['code'], 'lang'=>$context['lang'], 'flow'=>'pro'], home_url('/nenotv-pair/'));
        if (($context['kind'] ?? '') === 'setup') return add_query_arg(['sunny_setup'=>'1', 'lang'=>$context['lang']], self::account_url($context['lang']));
        return add_query_arg(['sunny_pair'=>$context['code'], 'lang'=>$context['lang']], self::account_url($context['lang']));
    }

    public static function incoming(): void {
        if (is_admin() || (defined('REST_REQUEST') && REST_REQUEST)) return;
        $path = trim((string)parse_url($_SERVER['REQUEST_URI'] ?? '', PHP_URL_PATH), '/');
        if ($path === 'nenotv-pair' && ($_GET['flow'] ?? '') === 'pro') return;
        if ($path === 'sunnyiptv-setup') {
            self::private_headers();
            if (is_user_logged_in()) return;
            $context = ['code'=>'', 'lang'=>self::language($_GET['lang'] ?? null), 'kind'=>'setup'];
            if (!self::remember($context)) return;
            wp_safe_redirect(self::account_target($context), 303);
            exit;
        }
        $code = self::code(isset($_GET['code']) ? wp_unslash($_GET['code']) : null);
        if (in_array($path, ['koppelen','nenotv-pair'], true) && $code !== '') {
            self::private_headers();
            if (is_user_logged_in() && ($_GET['sunny_account'] ?? '') === '1') return;
            $context = ['code'=>$code, 'lang'=>self::language($_GET['lang'] ?? null)];
            if (!self::remember($context)) return;
            wp_safe_redirect(self::account_target($context), 303);
            exit;
        }
        if (!function_exists('is_account_page') || !is_account_page()) return;
        if (($_GET['sunny_setup'] ?? '') === '1') {
            self::private_headers();
            $context = ['code'=>'', 'lang'=>self::language($_GET['lang'] ?? null), 'kind'=>'setup'];
            if (is_user_logged_in()) {
                self::remember([]);
                wp_safe_redirect(add_query_arg(['lang'=>$context['lang']], home_url('/sunnyiptv-setup/')), 303);
                exit;
            }
            self::remember($context);
            return;
        }
        $code = self::code(isset($_GET['sunny_pair']) ? wp_unslash($_GET['sunny_pair']) : null);
        if ($code === '') return;
        self::private_headers();
        $context = ['code'=>$code, 'lang'=>self::language($_GET['lang'] ?? null)];
        if (is_user_logged_in()) {
            self::remember([]);
            wp_safe_redirect(add_query_arg(['code'=>$code, 'lang'=>$context['lang'], 'sunny_account'=>'1'], home_url('/nenotv-pair/')), 303);
            exit;
        }
        self::remember($context);
    }

    public static function login_redirect($url) {
        $pending = self::pending();
        return $pending ? self::account_target($pending) : $url;
    }

    public static function login_choices(): void {
        if (is_user_logged_in()) return;
        $context = self::pending();
        if (($context['kind'] ?? '') === 'pro') return;
        $lang = $context['lang'] ?? self::language(function_exists('pll_current_language') ? pll_current_language('slug') : null);
        $target = $context ? self::account_target($context) : self::account_url($lang);
        // One-tap linking: show the device with "Dit apparaat koppelen" instead of the login form.
        $quickCode = self::code(isset($_GET['sunny_pair']) ? wp_unslash($_GET['sunny_pair']) : null);
        if ($quickCode === '') $quickCode = (string)($context['code'] ?? '');
        $quickLang = isset($_GET['lang']) ? self::language(wp_unslash($_GET['lang'])) : $lang;
        if ($quickCode !== '' && class_exists('NenoTV_Entitlement_Core') && method_exists('NenoTV_Entitlement_Core', 'quick_block')) echo NenoTV_Entitlement_Core::quick_block($quickCode, $quickLang);
        echo '<style>#customer_login{display:flex;flex-wrap:wrap;gap:24px}#customer_login>.col-1,#customer_login>.col-2{float:none;width:auto;flex:1 1 300px;min-width:0}#customer_login>.col-2{order:-1}.sunnyiptv-signin nav{display:flex;flex-wrap:wrap;gap:16px;margin:20px 0}.sunnyiptv-signin nav a{display:inline-block;padding:12px 18px;border-radius:6px;background:#ffd600;color:#111;font-weight:700}.sunnyiptv-signin nav a:last-child{background:#e7ebef}#sunny-register,#sunny-login{scroll-margin-top:140px}.sunnyiptv-signin input{max-width:100%}</style>';
        echo '<section class="sunnyiptv-signin"><h2>' . esc_html(self::words('Mijn SunnyIPTV', 'My SunnyIPTV', 'Mein SunnyIPTV')) . '</h2>';
        echo '<nav aria-label="' . esc_attr(self::words('Accountkeuze', 'Account options', 'Kontoauswahl')) . '"><a href="#sunny-register">' . esc_html(self::words('Nieuw? Account maken', 'New? Create an account', 'Neu? Konto erstellen')) . '</a><a href="#sunny-login">' . esc_html(self::words('Al klant? Inloggen', 'Already a customer? Sign in', 'Schon Kunde? Anmelden')) . '</a></nav>';
        if (class_exists('NextendSocialLogin') && isset(NextendSocialLogin::$enabledProviders['google'])) {
            echo do_shortcode('[nextend_social_login provider="google" redirect="' . esc_url($target) . '"]');
        }
        echo '</section>';
    }

    public static function words(string $nl, string $en, string $de): string {
        $context = self::pending();
        $lang = $context['lang'] ?? self::language(function_exists('pll_current_language') ? pll_current_language('slug') : null);
        return $lang === 'nl' ? $nl : ($lang === 'de' ? $de : $en);
    }

    public static function remember_pro(string $code, string $lang): void {
        self::private_headers();
        self::remember(['code'=>$code, 'lang'=>self::language($lang), 'kind'=>'pro']);
    }

    public static function account_labels($translated, $original, $domain) {
        if (is_admin() || is_user_logged_in() || !function_exists('is_account_page') || !is_account_page()) return $translated;
        if ($original === 'Login') return self::words('Al klant? Inloggen', 'Already a customer? Sign in', 'Schon Kunde? Anmelden');
        if ($original === 'Register') return self::words('Account maken', 'Create an account', 'Konto erstellen');
        return $translated;
    }

    public static function automatic_username($value) {
        // Customers only enter email and password; WordPress membership stays closed.
        return 'yes';
    }

    public static function login_intro(): void {
        echo '<p id="sunny-login">' . esc_html(self::words('Gebruik de gegevens van uw bestaande SunnyIPTV-account.', 'Use your existing SunnyIPTV account details.', 'Verwenden Sie Ihre bestehenden SunnyIPTV-Zugangsdaten.')) . '</p>';
    }

    public static function register_intro(): void {
        echo '<p id="sunny-register">' . esc_html(self::words('Nieuw bij SunnyIPTV? Vul uw e-mailadres in en kies een wachtwoord.', 'New to SunnyIPTV? Enter your email address and choose a password.', 'Neu bei SunnyIPTV? Geben Sie Ihre E-Mail-Adresse ein und wählen Sie ein Passwort.')) . '</p>';
    }

    public static function customer_login($allowed, $provider, $id): bool {
        $user = get_userdata((int)$id);
        if (!$allowed || !$user || !in_array('customer', (array)$user->roles, true)) return false;
        foreach (['manage_options', 'manage_woocommerce', 'edit_posts', 'edit_users', 'promote_users', 'install_plugins'] as $cap) {
            if (user_can($user, $cap)) return false;
        }
        return true;
    }

    public static function google_registration($allowed, $provider): bool {
        // Permit the configured customer provider without opening WordPress membership.
        if (!is_object($provider) || !method_exists($provider, 'getId') || $provider->getId() !== 'google') return (bool)$allowed;
        return class_exists('NextendSocialLogin') && isset(NextendSocialLogin::$enabledProviders['google']);
    }

    public static function new_customer($id): void {
        $user = get_userdata((int)$id);
        if (!$user) return;
        foreach (['manage_options', 'manage_woocommerce', 'edit_users', 'promote_users'] as $cap) if (user_can($user, $cap)) return;
        $user->set_role('customer');
    }
}
SunnyIPTV_Customer_Pairing::init();

