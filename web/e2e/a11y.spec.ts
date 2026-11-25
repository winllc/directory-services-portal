import { writeFileSync } from 'node:fs';
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * WCAG 2.1 A and AA checks (axe-core) of the portal's main pages, as an administrator of the demo
 * directory. A serious or critical violation fails the build; the full axe results of every page
 * are attached to the report (playwright-report/).
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const ALICE = 'uid=alice,ou=people,dc=example,dc=com';
const ENGINEERING = 'cn=engineering,ou=groups,dc=example,dc=com';
const SOCIAL = 'cn=social-club,ou=groups,dc=example,dc=com';
const SALES = 'cn=sales,ou=groups,dc=example,dc=com';

async function check(page: Page, name: string) {
  // Settle: no loading spinners left.
  await expect(page.locator('.loading-block')).toHaveCount(0, { timeout: 20_000 });
  // And no opening animation still running: a dialog fading in would be measured half transparent.
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getComputedTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => undefined)),
    ),
  );
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  // Kept with the test's output (test-results/) and attached to the HTML report.
  const file = test.info().outputPath(`axe-${name}.json`);
  writeFileSync(file, JSON.stringify(results, null, 2));
  await test.info().attach(`axe-${name}.json`, { path: file, contentType: 'application/json' });
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  const summary = blocking.map(
    (v) =>
      `${v.impact} ${v.id}: ${v.help}\n  ${v.nodes
        .slice(0, 8)
        .map((n) => `${n.target.join(' ')} -- ${[...n.any, ...n.all, ...n.none].map((c) => c.message).join('; ')}`)
        .join('\n  ')}`,
  );
  expect(summary, `${name}: serious or critical WCAG violations`).toEqual([]);
}

async function signIn(page: Page, username = 'admin') {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill('password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('navigation')).toBeVisible();
}

test('sign-in page', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByLabel('Username')).toBeVisible();
  await check(page, 'login');
  // With an error shown.
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('wrong');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await check(page, 'login-error');
});

const pages: [string, string][] = [
  ['home', '/'],
  ['directory', '/d/staff'],
  ['directory-search', '/d/staff?q=a'],
  ['entry', `/d/staff/entry?dn=${encodeURIComponent(ALICE)}`],
  ['entry-edit', `/d/staff/entry?dn=${encodeURIComponent(ALICE)}&edit=1`],
  ['entry-new', '/d/staff/new'],
  ['group', `/d/groups/entry?dn=${encodeURIComponent(ENGINEERING)}`],
  ['white-pages', '/d/white-pages'],
  ['my-profile', '/me'],
  ['groups', '/groups'],
  ['groups-review', '/groups?tab=review'],
  ['group-access', `/groups/def-groups/group?dn=${encodeURIComponent(ENGINEERING)}`],
  ['group-access-open', `/groups/def-groups/group?dn=${encodeURIComponent(SOCIAL)}`],
  ['metrics', '/metrics'],
  ['admin-definitions', '/admin/definitions'],
  ['admin-definition', '/admin/definitions/def-staff'],
  ['admin-definition-groups', '/admin/definitions/def-groups'],
  ['admin-forms', '/admin/forms'],
  ['admin-form', '/admin/forms/form-person'],
  ['admin-schema', '/admin/schema'],
  ['admin-permissions', '/admin/permissions'],
  ['admin-audit', '/admin/audit'],
  ['admin-integrations', '/admin/integrations'],
  ['admin-ldap-directories', '/admin/ldap-directories'],
  ['admin-analysis', '/admin/analysis'],
  ['admin-license', '/admin/license'],
];

for (const [name, path] of pages) {
  test(`page: ${name}`, async ({ page }) => {
    await signIn(page);
    await page.goto(path);
    await expect(page.locator('main h1').first()).toBeVisible({ timeout: 20_000 });
    await check(page, name);
  });
}

test('dialogs: import, new webhook, bulk members', async ({ page }) => {
  await signIn(page);
  await page.goto('/d/staff');
  await page.getByRole('button', { name: 'Import' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-import');
  await page.keyboard.press('Escape');

  await page.goto('/admin/integrations');
  await page.getByRole('tab', { name: 'Webhooks' }).click();
  await page.getByRole('button', { name: /New webhook/ }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-webhook');
  await page.keyboard.press('Escape');

  await page.goto(`/d/groups/entry?dn=${encodeURIComponent(ENGINEERING)}`);
  await page.getByRole('button', { name: /Change members in bulk/ }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-members');
});

test('group access dialogs: a member\'s end date, a pasted list, a request', async ({ page }) => {
  await signIn(page);
  await page.goto(`/groups/def-groups/group?dn=${encodeURIComponent(ENGINEERING)}`);
  await page.getByRole('button', { name: /^End date for / }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-member-end-date');
  await page.keyboard.press('Escape');

  // A pasted list of people: the list, then who was found, who was not and what is ambiguous.
  await page.getByRole('button', { name: 'Add from a list' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-add-from-list');
  await page.getByLabel('People to add').fill('george\nalice\nnobody\nbob@example.com');
  await page.getByRole('button', { name: 'Check the list' }).click();
  await expect(page.getByRole('region', { name: 'Not found' })).toBeVisible();
  await check(page, 'dialog-add-from-list-checked');
  await page.keyboard.press('Escape');

  // The administrator isn't in sales: the request dialog, with its optional end date.
  await page.goto(`/groups/def-groups/group?dn=${encodeURIComponent(SALES)}`);
  await page.getByRole('button', { name: 'Request access' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-request-access');
});

test('group access: several requests ticked to decide together', async ({ page }) => {
  // Two people ask to join sales (again in the second colour scheme's run: then they have asked already).
  for (const who of ['alice', 'dave']) {
    await signIn(page, who);
    const asked = await page.request.post(`/api/groups/def-groups/requests?dn=${encodeURIComponent(SALES)}`, {
      headers: { 'X-DSP-Request': '1' },
      data: { reason: 'Quarter end' },
    });
    expect([201, 409]).toContain(asked.status());
    await page.context().clearCookies();
  }
  await signIn(page);
  await page.goto('/groups?tab=review');
  await page.getByRole('checkbox', { name: 'Select all shown' }).check();
  await expect(page.getByRole('button', { name: 'Approve selected' })).toBeVisible();
  await check(page, 'groups-review-selected');
  await page.getByRole('button', { name: 'Approve selected' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await check(page, 'dialog-decide-selected');
});

test('as a user without administration: home and profile', async ({ page }) => {
  await signIn(page, 'alice');
  await check(page, 'user-home');
  await page.goto('/me');
  await expect(page.locator('main h1').first()).toBeVisible();
  await check(page, 'user-profile');
});

test('keyboard: skip link, page titles, dialogs close with Escape and return focus', async ({ page }) => {
  await signIn(page);
  await page.goto('/d/staff');
  await expect(page.locator('main h1')).toHaveText('Staff Directory');
  await expect(page).toHaveTitle('Staff Directory · Directory Services Portal');
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to main content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.locator('main')).toBeFocused();

  const open = page.getByRole('button', { name: 'Import' });
  await open.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toBeVisible();
  // Focus moves into the dialog, Escape closes it and focus returns to the button that opened it.
  await expect(page.getByRole('dialog').locator(':focus')).toHaveCount(1);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(open).toBeFocused();
});
