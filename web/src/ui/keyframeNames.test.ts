// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const SRC = fileURLToPath(new URL('..', import.meta.url))

function cssFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) return cssFiles(full)
    return name.endsWith('.css') ? [full] : []
  })
}

function keyframeDefinitions(): Map<string, string[]> {
  const defs = new Map<string, string[]>()
  for (const file of cssFiles(SRC).sort()) {
    const rel = relative(SRC, file).split(sep).join('/')
    const stripped = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
    for (const m of stripped.matchAll(/@keyframes\s+([\w-]+)/g)) {
      defs.set(m[1], [...(defs.get(m[1]) ?? []), rel])
    }
  }
  return defs
}

describe('@keyframes names', () => {
  it('each name is defined in exactly one place', () => {
    const duplicates = [...keyframeDefinitions()]
      .filter(([, files]) => files.length > 1)
      .map(([name, files]) => `${name}: ${files.join(', ')}`)
    expect(
      duplicates,
      '@keyframes names are global across the CSS bundle and the last definition wins; give each animation a component-specific name or share a single definition from styles.css',
    ).toEqual([])
  })
})
