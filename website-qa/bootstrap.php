<?php
if (!defined('NENOTV_WEBSITE_QA') || NENOTV_WEBSITE_QA !== true ||
    get_option('home') !== 'http://localhost:8090') {
    throw new RuntimeException('Refusing to seed outside localhost QA.');
}
if (!class_exists('WooCommerce')) { throw new RuntimeException('WooCommerce is required.'); }

update_option('blog_public', 0);
update_option('woocommerce_currency', 'EUR');
update_option('woocommerce_default_country', 'NL');
update_option('woocommerce_calc_taxes', 'yes');
update_option('woocommerce_prices_include_tax', 'yes');
update_option('woocommerce_tax_display_shop', 'incl');
update_option('woocommerce_tax_display_cart', 'incl');
update_option('woocommerce_tax_based_on', 'base');
update_option('woocommerce_default_customer_address', 'base');
update_option('woocommerce_enable_guest_checkout', 'yes');
update_option('woocommerce_enable_signup_and_login_from_checkout', 'yes');
update_option('woocommerce_coming_soon', 'no');
update_option('mollie-payments-for-woocommerce_test_mode_enabled', 'yes');
delete_option('mollie-payments-for-woocommerce_live_api_key');
delete_option('mollie-payments-for-woocommerce_test_api_key');

global $wpdb;
$rate_name = 'QA Netherlands 21';
$rate_id = $wpdb->get_var($wpdb->prepare("SELECT tax_rate_id FROM {$wpdb->prefix}woocommerce_tax_rates WHERE tax_rate_name = %s", $rate_name));
if (!$rate_id) {
    WC_Tax::_insert_tax_rate(array(
        'tax_rate_country' => 'NL', 'tax_rate_state' => '', 'tax_rate' => '21.0000',
        'tax_rate_name' => $rate_name, 'tax_rate_priority' => 1,
        'tax_rate_compound' => 0, 'tax_rate_shipping' => 0,
        'tax_rate_order' => 0, 'tax_rate_class' => ''
    ));
}
WC_Cache_Helper::invalidate_cache_group('taxes');
WC_Install::create_pages();

// Force deterministic classic checkout markup for Playwright in this disposable environment.
$checkout_id = wc_get_page_id('checkout');
$cart_id = wc_get_page_id('cart');
$account_id = wc_get_page_id('myaccount');
if ($checkout_id > 0) wp_update_post(['ID'=>$checkout_id, 'post_content'=>'[woocommerce_checkout]']);
if ($cart_id > 0) wp_update_post(['ID'=>$cart_id, 'post_content'=>'[woocommerce_cart]']);
if ($account_id > 0) wp_update_post(['ID'=>$account_id, 'post_content'=>'[woocommerce_my_account]']);

$plans = array(
    array('solo-yearly', 'Solo Yearly', '8.99', 1, 'yearly'),
    array('solo-lifetime', 'Solo Lifetime', '17.99', 1, 'lifetime'),
    array('multi-yearly', 'Multi Yearly', '19.99', 5, 'yearly'),
    array('multi-lifetime', 'Multi Lifetime', '29.99', 5, 'lifetime'),
);
foreach ($plans as $plan) {
    $sku = 'qa-' . $plan[0];
    $id = wc_get_product_id_by_sku($sku);
    $product = $id ? wc_get_product($id) : new WC_Product_Simple();
    $product->set_name('NenoTV QA ' . $plan[1]);
    $product->set_slug($plan[0]);
    $product->set_sku($sku);
    $product->set_status('publish');
    $product->set_catalog_visibility('visible');
    $product->set_virtual(true);
    $product->set_tax_status('taxable');
    $product->set_regular_price($plan[2]);
    $product->set_price($plan[2]);
    $product->update_meta_data('_nenotv_qa_fixture', true);
    $product->update_meta_data('_nenotv_qa_expected_devices', $plan[3]);
    $product->update_meta_data('_nenotv_qa_expected_term', $plan[4]);
    $product->save();
}
flush_rewrite_rules(false);

add_action('wp_mail_failed', function ($error) {
    throw new RuntimeException('Local mail capture: ' . $error->get_error_message());
});
if (!wp_mail('customer@example.invalid', 'NenoTV QA mailbox check', 'Synthetic mail; no real recipient.')) {
    throw new RuntimeException('Local mail capture failed.');
}
echo "QA v2 fixtures ready. Synthetic local gateway enabled; Mollie remains TEST with no key.\n";
