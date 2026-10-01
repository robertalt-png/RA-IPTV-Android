import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkoutId, assertCheckoutPage } from './checks.mjs';

test('missing or malformed checkout IDs fail before requests', () => {
  for (const id of [undefined, '', '0', '-1', 'undefined', '12x', ' 12', '1&x=2', '9007199254740993']) {
    assert.throws(() => checkoutId(id));
  }
  assert.equal(checkoutId('12'), '12');
});
test('homepage with navigation or checkout scripts cannot pass', () => {
  assert.throws(() => assertCheckoutPage('<body class="home"><script>woocommerce checkout</script><a class="wc-block-checkout">Checkout</a>', '12'));
  assert.throws(() => assertCheckoutPage('<body class="page-id-12"><script>wp-block-woocommerce-checkout</script>', '12'));
  assert.throws(() => assertCheckoutPage('<body class="page-id-123"><div class="wp-block-woocommerce-checkout">', '12'));
});
test('real block and classic checkout markup passes on the correct page', () => {
  assert.doesNotThrow(() => assertCheckoutPage('<body class="page-id-12"><div class="wp-block-woocommerce-checkout alignwide">', '12'));
  assert.doesNotThrow(() => assertCheckoutPage('<body class="page-id-12"><form class="checkout woocommerce-checkout">', '12'));
});
