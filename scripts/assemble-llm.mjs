#!/usr/bin/env node
// Assembles the launcher's Windows-only llm component, model seats in the desktop app:
//   <stagingDir>/llm/{python/, llm/, bridge/mage-client-bridge.jar, llm.json}
// Usage: node scripts/assemble-llm.mjs [stagingDir]
//   (needs uv with a managed CPython 3.13, and node scripts/build.mjs bridge first)

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { XMAGE_VERSION, log, logError, repoRoot } from './lib.mjs'

const outDir = path.join(path.resolve(process.argv[2] ?? path.join(repoRoot, 'staging')), 'llm')
const PYTHON_SERIES = '3.13'
const PYTHON_UNUSED = [
  'include',
  'libs',
  'Scripts',
  'tcl',
  'DLLs/_tkinter.pyd',
  'DLLs/tcl86t.dll',
  'DLLs/tk86t.dll',
  'Lib/ensurepip',
  'Lib/idlelib',
  'Lib/test',
  'Lib/tkinter',
  'Lib/turtledemo',
]
const BRIDGE = {
  jar: 'bridge/mage-client-bridge.jar',
  mainClass: 'mage.client.bridge.BridgeClient',
  serverClasspath: ['classes/Mage', 'classes/Mage.Common', 'classes/Mage.Sets', 'lib/*'],
}

function fail(message) {
  logError(message)
  process.exit(1)
}

function uv(args) {
  return execFileSync('uv', args, { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] }).trim()
}

function copyPython(dest) {
  const found = uv(['python', 'find', '--managed-python', '--no-python-downloads', PYTHON_SERIES])
  const home = path.dirname(fs.realpathSync(found))
  fs.cpSync(home, dest, { recursive: true })
  for (const rel of PYTHON_UNUSED) fs.rmSync(path.join(dest, rel), { recursive: true, force: true })
  const sitePackages = path.join(dest, 'Lib', 'site-packages')
  for (const entry of fs.readdirSync(sitePackages)) {
    if (/^pip($|-)/.test(entry)) fs.rmSync(path.join(sitePackages, entry), { recursive: true, force: true })
  }
  log(`  python OK (${home})`)
  return sitePackages
}

function installDependencies(python, sitePackages) {
  const requirements = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'nexus-llm-')), 'requirements.txt')
  uv(['export', '--project', 'llm', '--frozen', '--no-dev', '--no-hashes', '--no-header', '--no-annotate',
    '--format', 'requirements-txt', '--output-file', requirements])
  uv(['pip', 'install', '--python', python, '--target', sitePackages, '--no-deps', '--only-binary', ':all:',
    '-r', requirements])
  const pins = fs.readFileSync(requirements, 'utf8').split('\n').filter((line) => line.includes('=='))
  fs.rmSync(path.dirname(requirements), { recursive: true, force: true })
  fs.rmSync(path.join(sitePackages, '.lock'), { force: true })
  log(`  dependencies OK (${pins.join(', ')})`)
}

function copyPackage(dest) {
  fs.mkdirSync(dest, { recursive: true })
  const modules = fs.readdirSync(path.join(repoRoot, 'llm')).filter((file) => file.endsWith('.py'))
  for (const file of modules) fs.copyFileSync(path.join(repoRoot, 'llm', file), path.join(dest, file))
  log(`  llm package OK (${modules.length} modules)`)
}

function copyBridge(dest) {
  const thin = path.join(repoRoot, 'Mage.Client.Bridge', 'target', `original-mage-client-bridge-${XMAGE_VERSION}.jar`)
  if (!fs.existsSync(thin)) fail(`missing ${thin}: run node scripts/build.mjs bridge`)
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  fs.copyFileSync(thin, dest)
  log(`  bridge OK (${path.basename(thin)}, on the server component's classes)`)
}

function smokeTest(python) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'nexus-llm-home-'))
  try {
    execFileSync(python, ['-B', '-c', 'import psutil, llm.catalog, llm.seat'], {
      cwd: outDir,
      env: { ...process.env, NEXUS_LLM_HOME: home },
      stdio: 'inherit',
    })
  } finally {
    fs.rmSync(home, { recursive: true, force: true })
  }
  log('  smoke OK (the bundled Python imports the supervisor)')
}

function main() {
  if (process.platform !== 'win32') fail('the llm component is Windows-only for now')
  log(`assembling the llm component in ${outDir}…`)
  fs.rmSync(outDir, { recursive: true, force: true })
  fs.mkdirSync(outDir, { recursive: true })
  const python = path.join(outDir, 'python', 'python.exe')
  const sitePackages = copyPython(path.dirname(python))
  installDependencies(python, sitePackages)
  copyPackage(path.join(outDir, 'llm'))
  copyBridge(path.join(outDir, BRIDGE.jar))
  const component = { schema: 1, python: 'python/python.exe', bridge: BRIDGE }
  fs.writeFileSync(path.join(outDir, 'llm.json'), JSON.stringify(component, null, 2) + '\n')
  smokeTest(python)
}

main()
