#!/usr/bin/env node
// Starts the dev stack with model seats on and opens the web client.
// Usage: node scripts/play-vs-llm.mjs [--no-open]
// A running XMage server and Vite are kept; the proxy is always restarted with --llm.

import { spawn, spawnSync } from 'node:child_process'
import path from 'node:path'
import { isAlive, log, PORTS, readPid } from './lib.mjs'

const options = process.argv.slice(2)
if (options.some((o) => o !== '--no-open')) {
  console.error('usage: node scripts/play-vs-llm.mjs [--no-open]')
  process.exit(1)
}

const devScript = path.join(import.meta.dirname, 'dev.mjs')
const running = (name) => isAlive(readPid(name))

function dev(...args) {
  const result = spawnSync(process.execPath, [devScript, ...args, '--llm'], { stdio: 'inherit', windowsHide: true })
  if (result.status !== 0) process.exit(result.status ?? 1)
}

if (running('server')) dev('restart', 'proxy')
else dev('start', 'server')
if (!running('vite')) dev('start', 'vite')

const url = `http://localhost:${PORTS.vite}`
log(`model seats on: open ${url}, log in, then Create Table → Multiplayer → pick a seat → Language models`)
log('to turn model seats off: node scripts/ctl.mjs restart proxy')
if (!options.includes('--no-open')) {
  const [cmd, args] = process.platform === 'win32'
    ? ['cmd', ['/c', 'start', '', url]]
    : [process.platform === 'darwin' ? 'open' : 'xdg-open', [url]]
  spawn(cmd, args, { detached: true, stdio: 'ignore', windowsHide: true }).unref()
}
