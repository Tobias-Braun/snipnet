import { expect, test } from '@playwright/test';

test.describe('landing page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => document.fonts.ready);
  });

  test('renders the hero and primary sections', async ({ page }) => {
    await expect(page.getByRole('link', { name: 'Snipnet home' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.locator('#how-it-works')).toBeAttached();
    await expect(page.locator('#features')).toBeAttached();
    await expect(page.locator('#download')).toBeAttached();
  });

  test('has no horizontal overflow', async ({ page }) => {
    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(scrollWidth).toBe(clientWidth);
  });

  test('matches the full-page snapshot', async ({ page }) => {
    // A small pixel ratio absorbs sub-pixel anti-aliasing differences between otherwise identical hosts.
    await expect(page).toHaveScreenshot('landing.png', {
      fullPage: true,
      animations: 'disabled',
      maxDiffPixelRatio: 0.01,
    });
  });
});
