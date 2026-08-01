import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = `${directory}/${entry.name}`
    return entry.isDirectory() ? sourceFiles(path) : [path]
  })
}

it('contains no retired theme tokens or colored left-bar decoration', () => {
  const sourceRoot = resolve(process.cwd(), 'src')
  const files = sourceFiles(sourceRoot)
    .filter((file) => /\.(vue|css)$/.test(file))
  const source = files.map((file) => readFileSync(file, 'utf8')).join('\n')
  const retired = ['--' + 'navy', '--' + 'cyan', '#' + '12233f', '#' + '35c2cb']

  for (const token of retired) expect(source).not.toContain(token)
  expect(source.replace(/\s/g, '')).not.toContain('border-' + 'left:3pxsolid')
})
