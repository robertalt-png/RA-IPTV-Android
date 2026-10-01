import { test, expect } from '@playwright/test';

const base = process.env.STAGING_BASE_URL;
const status = process.env.MOLLIE_TEST_STATUS || 'paid';
const plans = ['solo-yearly','solo-lifetime','multi-yearly','multi-lifetime'];
const langs = ['en','nl','de'];

test.beforeAll(() => {
  if (!base) throw new Error('STAGING_BASE_URL required');
});

for (const lang of langs) {
  for (const plan of plans) {
    test(`Mollie TEST ${status}: ${lang} ${plan}`, async ({ page }) => {
      await page.goto(`${base}/?nenotv-buy=${plan}&lang=${lang}`, { waitUntil: 'domcontentloaded' });
      await page.waitForURL(/checkout|order-pay/, { timeout: 30_000 });

      const email = `qa+${lang}-${plan}-${Date.now()}@example.invalid`;
      const first = page.locator('#billing_first_name');
      if (await first.count()) {
        await first.fill('NenoTV');
        await page.locator('#billing_last_name').fill('QA');
        await page.locator('#billing_address_1').fill('Teststraat 1');
        await page.locator('#billing_postcode').fill('2711AA');
        await page.locator('#billing_city').fill('Zoetermeer');
        await page.locator('#billing_email').fill(email);
      }

      const mollie = page.locator('input[name="payment_method"][value*="mollie"]').first();
      if (await mollie.count()) await mollie.check();
      const terms = page.locator('#terms');
      if (await terms.count()) await terms.check();

      await page.locator('#place_order, button.wc-block-components-checkout-place-order-button').first().click();
      await page.waitForURL(/mollie|checkout|payments/i, { timeout: 45_000 });

      // Mollie test checkout allows selecting a simulated payment state.
      const combo = page.getByRole('combobox').first();
      if (await combo.count()) {
        const wanted = status === 'paid' ? /paid|betaald/i :
          status === 'failed' ? /failed|mislukt/i :
          status === 'cancelled' ? /cancel/i :
          status === 'expired' ? /expir|verlopen/i : /pending|open/i;
        const options = await combo.locator('option').allTextContents();
        const label = options.find(x => wanted.test(x));
        if (!label) throw new Error(`Mollie test status option not found for ${status}: ${options.join(', ')}`);
        await combo.selectOption({ label });
      } else {
        const candidate = page.getByText(new RegExp(status, 'i')).first();
        await expect(candidate, 'Mollie test status control').toBeVisible();
        await candidate.click();
      }

      const cont = page.getByRole('button', { name: /continue|doorgaan|weiter/i }).first();
      if (await cont.count()) await cont.click();

      if (status === 'paid') {
        await page.waitForURL(new RegExp(new URL(base).hostname.replace(/\./g,'\\.'), 'i'), { timeout: 60_000 });
        await expect(page.locator('body')).toContainText(/thank|bedankt|danke|order|bestelling/i);
      }
    });
  }
}
