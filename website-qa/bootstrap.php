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
global $wpdb;
$rate_name = 'QA Netherlands 21';
$rate_id = $wpdb->get_var($wpdb->prepare("SELECT tax_rate_id FROM {$wpdb->prefix}woocommerce_tax_rates WHERE tax_rate_name = %s", $rate_name));
if (!$rate_id) {
    WC_Tax::_insert_tax_rate(array('tax_rate_country' => 'NL', 'tax_rate_state' => '',
        'tax_rate' => '21.0000', 'tax_rate_name' => $rate_name, 'tax_rate_priority' => 1,
        'tax_rate_compound' => 0, 'tax_rate_shipping' => 0, 'tax_rate_order' => 0, 'tax_rate_class' => ''));
}
WC_Cache_Helper::invalidate_cache_group('taxes');
// Only this new isolated database is opened for synthetic checkout tests.
update_option('woocommerce_coming_soon', 'no');
update_option('mollie-payments-for-woocommerce_test_mode_enabled', 'yes');
delete_option('mollie-payments-for-woocommerce_live_api_key');
delete_option('mollie-payments-for-woocommerce_test_api_key');
WC_Install::create_pages();
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
    $product->set_virtual(true);
    $product->set_tax_status('taxable');
    $product->set_regular_price($plan[2]);
    $product->update_meta_data('_nenotv_qa_fixture', true);
    // Fixture expectations, not production entitlement metadata.
    $product->update_meta_data('_nenotv_qa_expected_devices', $plan[3]);
    $product->update_meta_data('_nenotv_qa_expected_term', $plan[4]);
    $product->save();
}
add_action('wp_mail_failed', function ($error) {
    throw new RuntimeException('Local mail capture: ' . $error->get_error_message());
});
if (!wp_mail('customer@example.invalid', 'NenoTV QA mailbox check', 'Synthetic mail; no real recipient.')) {
    throw new RuntimeException('Local mail capture failed.');
}
echo "QA fixtures ready. No payment or entitlement has been simulated.\n";
