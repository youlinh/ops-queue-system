import { test as setup } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import {
  authDirectory,
  login,
  passwords,
  storageStatePath,
  type E2eUser,
} from './helpers'
import { seedEnvironment } from './seed'

setup('authenticate deterministic acceptance users', async ({ browser }) => {
  await seedEnvironment()
  await mkdir(authDirectory, { recursive: true })
  for (const username of Object.keys(passwords) as E2eUser[]) {
    const context = await browser.newContext()
    const page = await context.newPage()
    await login(page, username)
    await context.storageState({ path: storageStatePath(username) })
    await context.close()
  }
})
