import assert from 'node:assert/strict';

export function checkoutId(value) {
  assert.match(value ?? '', /^[1-9][0-9]*$/, 'QA_CHECKOUT_ID must be a positive integer');
  assert.ok(Number.isSafeInteger(Number(value)), 'Checkout ID is outside the supported range');
  return value;
}

export function assertCheckoutPage(html, id) {
  checkoutId(id);
  assert.match(html, new RegExp(`\\bpage-id-${id}\\b`), 'Response is not the configured checkout page');
  const block = /<div\b[^>]*\bclass\s*=\s*["'][^"']*\bwp-block-woocommerce-checkout\b/i;
  const classic = /<form\b[^>]*\bclass\s*=\s*["'][^"']*\bwoocommerce-checkout\b/i;
  assert.ok(block.test(html) || classic.test(html), 'Checkout form or block is missing');
}
