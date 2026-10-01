import { test, expect } from '@playwright/test';
const base = process.env.PUBLIC_BASE_URL || 'https://nenotv.com';
const pages = [
  ['en-home','/'], ['nl-home','/language/nl/'], ['de-home','/language/de/'],
  ['en-pricing','/pricing/'], ['nl-pricing','/language/nl/prijzen/'], ['de-pricing','/language/de/preise/']
];
for (const [name,path] of pages) {
  test(`production readonly ${name}`, async ({ page }) => {
    const r = await page.goto(base + path, { waitUntil: 'domcontentloaded' });
    expect(r?.status() ?? 0).toBeLessThan(500);
    await expect(page.locator('body')).not.toContainText(/critical error|uncaught exception|database error/i);
    await expect(page.locator('h1').first()).toBeVisible();
    const canonical = page.locator('link[rel="canonical"]');
    if (await canonical.count()) await expect(canonical).toHaveAttribute('href', /^https:\/\//);
  });
}
