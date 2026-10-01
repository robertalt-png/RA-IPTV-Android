<?php
/** Plugin Name: NenoTV isolated website QA guard */
if (!defined('ABSPATH')) { exit; }
if (!defined('NENOTV_WEBSITE_QA') || NENOTV_WEBSITE_QA !== true) {
    throw new RuntimeException('QA guard requires a dedicated local QA installation.');
}

// Runtime containers have no internet route; these guards also apply to WP-CLI.
add_filter('pre_option_mollie-payments-for-woocommerce_test_mode_enabled', function () { return 'yes'; });
add_filter('pre_option_mollie-payments-for-woocommerce_live_api_key', function () { return ''; });
add_filter('pre_option_blog_public', function () { return '0'; });
add_filter('pre_http_request', function ($pre, $args, $url) {
    $host = strtolower((string) wp_parse_url($url, PHP_URL_HOST));
    $allowed = array('api.wordpress.org', 'downloads.wordpress.org', 'wordpress', 'localhost', '127.0.0.1');
    if (!in_array($host, $allowed, true)) {
        return new WP_Error('nenotv_qa_network_blocked', 'External service blocked in isolated website QA.');
    }
    return $pre;
}, PHP_INT_MAX, 3);
add_action('phpmailer_init', function ($mail) {
    $mail->isSMTP();
    $mail->Host = 'mailpit';
    $mail->Port = 1025;
    $mail->SMTPAuth = false;
    $mail->SMTPAutoTLS = false;
    $mail->SMTPSecure = '';
});
add_action('send_headers', function () { header('X-Robots-Tag: noindex, nofollow, noarchive'); });
