import { test, expect } from '@playwright/test';

const base = process.env.LOCAL_BASE_URL || 'http://127.0.0.1:8090';
const plans = [
  ['solo-yearly','899',1,'yearly'],
  ['solo-lifetime','1799',1,'lifetime'],
  ['multi-yearly','1999',5,'yearly'],
  ['multi-lifetime','2999',5,'lifetime']
];

async function mailCount(request) {
  const r = await request.get('http://127.0.0.1:8025/api/v1/messages');
  expect(r.ok()).toBeTruthy();
  return (await r.json()).messages?.length ?? 0;
}

for (const [slug, cents, devices, term] of plans) {
  test(`complete isolated checkout: ${slug}`, async ({ page, request }) => {
    const productsResp = await request.get(`${base}/?rest_route=/wc/store/v1/products&per_page=100`);
    expect(productsResp.ok()).toBeTruthy();
    const products = await productsResp.json();
    const product = products.find(p => p.slug === slug);
    expect(product, `missing ${slug}`).toBeTruthy();
    expect(product.prices.price).toBe(cents);

    const beforeMail = await mailCount(request);
    await page.goto(`${base}/?add-to-cart=${product.id}`);
    await page.goto(`${base}/checkout/`);

    await page.locator('#billing_first_name').fill('NenoTV');
    await page.locator('#billing_last_name').fill('QA');
    await page.locator('#billing_address_1').fill('Teststraat 1');
    await page.locator('#billing_postcode').fill('2711AA');
    await page.locator('#billing_city').fill('Zoetermeer');
    await page.locator('#billing_email').fill(`qa+${slug}-${Date.now()}@example.invalid`);

    const gateway = page.locator('#payment_method_nenotv_qa');
    await expect(gateway).toBeVisible();
    await gateway.check();

    const terms = page.locator('#terms');
    if (await terms.count()) await terms.check();

    await page.locator('#place_order').click();
    await page.waitForURL(/order-received|checkout\/order-received/, { timeout: 45_000 });
    await expect(page.locator('#nenotv-qa-proof')).toBeVisible();
    await expect(page.locator('#nenotv-qa-proof')).toContainText(`Plan: ${slug}`);
    await expect(page.locator('#nenotv-qa-proof')).toContainText(`Devices: ${devices}`);
    await expect(page.locator('#nenotv-qa-proof')).toContainText(/Activation: QA-/);

    const m = page.url().match(/order-received\/(\d+)/);
    expect(m).toBeTruthy();
    const orderResp = await request.get(`${base}/?rest_route=/nenotv-qa/v1/order/${m[1]}`);
    expect(orderResp.ok()).toBeTruthy();
    const order = await orderResp.json();
    expect(order.status).toBe('completed');
    expect(order.payment_method).toBe('nenotv_qa');
    expect(order.entitlement_granted).toBe('yes');
    expect(order.plan).toBe(slug);
    expect(order.devices).toBe(devices);
    expect(order.term).toBe(term);
    expect(order.activation_code).toMatch(/^QA-/);

    await expect.poll(async () => await mailCount(request), { timeout: 15_000 }).toBeGreaterThan(beforeMail);
  });
}
