import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
const base = process.env.PUBLIC_BASE_URL || 'https://nenotv.com';
for (const path of ['/', '/language/nl/', '/language/de/', '/pricing/']) {
  test(`axe critical/serious: ${path}`, async ({ page }) => {
    await page.goto(base + path, { waitUntil: 'domcontentloaded' });
    const scan = await new AxeBuilder({ page }).analyze();
    const blocking = scan.violations.filter(v => ['critical','serious'].includes(v.impact));
    expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([]);
  });
}
