<?php
/**
 * Plugin Name: NenoTV QA v2 E2E Fixture
 * Description: Isolated checkout/payment/entitlement fixture. NEVER active outside NENOTV_WEBSITE_QA.
 */
if (!defined('ABSPATH')) exit;
if (!defined('NENOTV_WEBSITE_QA') || NENOTV_WEBSITE_QA !== true) {
    throw new RuntimeException('NenoTV QA E2E fixture refused outside isolated QA.');
}

add_filter('woocommerce_payment_gateways', function($gateways) {
    $gateways[] = 'WC_Gateway_NenoTV_QA';
    return $gateways;
});

add_action('plugins_loaded', function() {
    if (!class_exists('WC_Payment_Gateway')) return;
    class WC_Gateway_NenoTV_QA extends WC_Payment_Gateway {
        public function __construct() {
            $this->id = 'nenotv_qa';
            $this->method_title = 'NenoTV QA instant success';
            $this->title = 'NenoTV QA instant success';
            $this->description = 'Synthetic isolated QA payment. No money leaves this environment.';
            $this->has_fields = false;
            $this->enabled = 'yes';
        }
        public function process_payment($order_id) {
            $order = wc_get_order($order_id);
            if (!$order) return ['result' => 'failure'];
            $order->payment_complete('qa-' . $order_id . '-' . time());
            $order->add_order_note('NenoTV QA synthetic payment completed.');
            return ['result' => 'success', 'redirect' => $this->get_return_url($order)];
        }
    }
});

function nenotv_qa_grant_entitlement($order_id) {
    $order = wc_get_order($order_id);
    if (!$order || $order->get_meta('_nenotv_qa_entitlement_granted')) return;
    $devices = 0; $term = ''; $plan = '';
    foreach ($order->get_items() as $item) {
        $product = $item->get_product();
        if (!$product || !$product->get_meta('_nenotv_qa_fixture')) continue;
        $devices = max($devices, (int)$product->get_meta('_nenotv_qa_expected_devices'));
        $term = (string)$product->get_meta('_nenotv_qa_expected_term');
        $plan = (string)$product->get_slug();
    }
    if (!$plan) return;
    $order->update_meta_data('_nenotv_qa_entitlement_granted', 'yes');
    $order->update_meta_data('_nenotv_qa_entitlement_plan', $plan);
    $order->update_meta_data('_nenotv_qa_entitlement_devices', $devices);
    $order->update_meta_data('_nenotv_qa_entitlement_term', $term);
    $order->update_meta_data('_nenotv_qa_activation_code', 'QA-' . strtoupper(wp_generate_password(12, false, false)));
    $order->save();
    if (!$order->has_status('completed')) $order->update_status('completed', 'NenoTV QA entitlement fixture completed.');
}
add_action('woocommerce_payment_complete', 'nenotv_qa_grant_entitlement', 20);
add_action('woocommerce_order_status_processing', 'nenotv_qa_grant_entitlement', 20);

add_action('woocommerce_thankyou', function($order_id) {
    $order = wc_get_order($order_id);
    if (!$order || !$order->get_meta('_nenotv_qa_entitlement_granted')) return;
    echo '<section id="nenotv-qa-proof" data-order-id="' . esc_attr($order->get_id()) . '"><h2>NenoTV QA entitlement created</h2><p data-plan="' .
      esc_attr($order->get_meta('_nenotv_qa_entitlement_plan')) . '">Plan: ' .
      esc_html($order->get_meta('_nenotv_qa_entitlement_plan')) . '</p><p>Devices: ' .
      esc_html($order->get_meta('_nenotv_qa_entitlement_devices')) . '</p><p>Activation: ' .
      esc_html($order->get_meta('_nenotv_qa_activation_code')) . '</p></section>';
});

add_action('rest_api_init', function() {
    register_rest_route('nenotv-qa/v1', '/config', [
        'methods' => 'GET',
        'permission_callback' => '__return_true',
        'callback' => function() {
            return rest_ensure_response([
                'checkout_url' => wc_get_checkout_url(),
                'cart_url' => wc_get_cart_url(),
                'account_url' => wc_get_page_permalink('myaccount'),
                'qa_gateway' => 'nenotv_qa'
            ]);
        }
    ]);
    register_rest_route('nenotv-qa/v1', '/order/(?P<id>\\d+)', [
        'methods' => 'GET',
        'permission_callback' => '__return_true',
        'callback' => function($request) {
            $order = wc_get_order((int)$request['id']);
            if (!$order) return new WP_Error('not_found', 'Order not found', ['status'=>404]);
            return rest_ensure_response([
                'id' => $order->get_id(),
                'status' => $order->get_status(),
                'payment_method' => $order->get_payment_method(),
                'total' => $order->get_total(),
                'currency' => $order->get_currency(),
                'entitlement_granted' => $order->get_meta('_nenotv_qa_entitlement_granted'),
                'plan' => $order->get_meta('_nenotv_qa_entitlement_plan'),
                'devices' => (int)$order->get_meta('_nenotv_qa_entitlement_devices'),
                'term' => $order->get_meta('_nenotv_qa_entitlement_term'),
                'activation_code' => $order->get_meta('_nenotv_qa_activation_code')
            ]);
        }
    ]);
});
