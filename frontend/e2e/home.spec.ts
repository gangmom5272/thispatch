import { test, expect } from '@playwright/test'

test('home page loads and shows the get started heading', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Get started' })).toBeVisible()
})

test('zustand counter increments on click', async ({ page }) => {
  await page.goto('/')
  const button = page.getByRole('button', { name: /zustand count is/ })
  await expect(button).toHaveText('zustand count is 0')
  await button.click()
  await expect(button).toHaveText('zustand count is 1')
})
