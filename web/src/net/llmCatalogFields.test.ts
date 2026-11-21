import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// The proxy copies only the fields it lists from the model service's catalog (LlmCatalog.sanitize), so a
// field the page reads but the proxy does not list never arrives: v0.9.5 shipped Claude's Sign in button
// with signInProgram missing from the proxy's list, and the button never showed.
const web = process.cwd()
const read = (...parts: string[]) => readFileSync(join(...parts), 'utf8')

function proxyList(name: string): string[] {
  const java = read(web, '..', 'Mage.Proxy', 'src', 'main', 'java', 'org', 'mage', 'proxy', 'LlmCatalog.java')
  const match = new RegExp(`${name}\\s*=\\s*Arrays\\.asList\\(([^)]*)\\)`).exec(java)
  if (!match) throw new Error(`${name} not found in LlmCatalog.java`)
  return [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1])
}

function interfaceFields(name: string): string[] {
  const ts = read(web, 'src', 'net', 'types.ts')
  const match = new RegExp(`export interface ${name} \\{([\\s\\S]*?)\\n\\}`).exec(ts)
  if (!match) throw new Error(`${name} not found in types.ts`)
  return [...match[1].matchAll(/^\s*(\w+)\??:/gm)].map((m) => m[1])
}

describe('the proxy passes every catalog field the page reads', () => {
  it('LBY-33: every LlmUnavailable field is in the proxy list, signInProgram included', () => {
    const fields = interfaceFields('LlmUnavailable')
    expect(fields).toContain('signInProgram')
    expect(proxyList('UNAVAILABLE_TEXT')).toEqual(expect.arrayContaining(fields))
  })
})
