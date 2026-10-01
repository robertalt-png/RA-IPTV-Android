import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { checkoutId, assertCheckoutPage } from './checks.mjs';

const base = 'http://localhost:8090';
const checkoutPageId = checkoutId(process.env.QA_CHECKOUT_ID);
const results = [];
async function json(url) {
  const response = await fetch(url);
  assert.equal(response.status, 200, url);
  return response.json();
}
const products = await json(`${base}/?rest_route=/wc/store/v1/products&per_page=100`);
const expected = { 'solo-yearly': '899', 'solo-lifetime': '1799', 'multi-yearly': '1999', 'multi-lifetime': '2999' };
for (const [slug, price] of Object.entries(expected)) {
  const product = products.find(p => p.slug === slug);
  assert.ok(product, `Missing ${slug}`);
  assert.equal(product.prices.price, price);
  assert.equal(product.prices.currency_code, 'EUR');
  assert.equal(product.is_purchasable, true);
  const response = await fetch(`${base}/?add-to-cart=${product.id}`, { redirect: 'manual' });
  assert.ok([200, 302, 303].includes(response.status));
  const cookies = response.headers.getSetCookie().map(c => c.split(';')[0]).join('; ');
  assert.ok(cookies.includes('wp_woocommerce_session_'), 'Missing cart session');
  const cartResponse = await fetch(`${base}/?rest_route=/wc/store/v1/cart`, { headers: { Cookie: cookies } });
  assert.equal(cartResponse.status, 200);
  const cart = await cartResponse.json();
  assert.equal(cart.items.length, 1);
  assert.equal(cart.items[0].id, product.id);
  assert.equal(cart.totals.total_price, price);
  assert.equal(Number(cart.totals.total_tax), Math.round(Number(price) - Number(price) / 1.21), 'Inclusive NL fixture VAT');
  const checkoutResponse = await fetch(`${base}/?page_id=${checkoutPageId}`, { headers: { Cookie: cookies } });
  assert.equal(checkoutResponse.status, 200);
  const checkout = await checkoutResponse.text();
  assertCheckoutPage(checkout, checkoutPageId);
  assert.doesNotMatch(checkout, /Something big is brewing/i);
  assert.match(checkoutResponse.headers.get('x-robots-tag'), /noindex/);
  results.push({ plan: slug, product: 'passed', cart: 'passed', checkoutPage: 'passed', payment: 'not-tested', entitlement: 'not-tested' });
}
const messages = await json('http://localhost:8025/api/v1/messages');
assert.ok(messages.messages.some(m => m.Subject === 'NenoTV QA mailbox check'), 'Mail capture missing');
await mkdir('website-qa/results', { recursive: true });
await writeFile('website-qa/results/smoke.json', JSON.stringify({ results, mailbox: 'passed', fullE2E: 'blocked: private NenoTV source and protected callback host required' }, null, 2));
console.log('Four product/cart/checkout-page checks and local mail capture passed. Payment and entitlement remain untested.');
