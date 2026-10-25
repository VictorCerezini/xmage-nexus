#!/usr/bin/env node
// Full build chain for Mage.Proxy (Windows/macOS/Linux/WSL).
// Usage: node scripts/build.mjs [proxy|bridge]
//   (no arguments: builds server + plugins and packages the proxy)
//   (arg "proxy": only packages the proxy, assuming the rest is already built)
//   (arg "bridge": only packages Mage.Client.Bridge, the client behind model seats
//    (llm/); it needs engine artifacts built from the llm-seats-engine patch)

import { copyPluginJars, ensureMageArtifacts, stampMageArtifacts, forkDir, lastLines, log, logError, mvn, PLUGIN_MODULES, stopPid } from './lib.mjs'

function fail(step, res) {
  logError(`FAILED at: ${step}`)
  const errors = (res.stderr ?? '').split('\n').filter((l) => /ERROR/.test(l)).slice(0, 8)
  if (errors.length) logError(errors.join('\n'))
  // mvn -q writes its errors to stdout, not stderr
  const tail = lastLines(res.stdout, 60)
  if (tail.trim()) logError(`last lines of output:\n${tail}`)
  process.exit(1)
}

async function main() {
  const onlyProxy = process.argv[2] === 'proxy'

  if (process.argv[2] === 'bridge') {
    log('== package Mage.Client.Bridge (clean package) ==')
    ensureMageArtifacts()
    // without clean, shade wraps the previous shaded jar again; a running model seat holds the jar
    // open, which makes the clean fail on Windows, so stop seats first
    const res = mvn(['-q', '-f', 'Mage.Client.Bridge/pom.xml', 'clean', 'package', '-DskipTests'])
    if (res.code !== 0) fail('bridge packaging', res)
    log('  OK')
    return
  }

  if (!onlyProxy) {
    log('== step 1/4: build base modules (Mage.Common, Mage, Mage.Sets, Mage.Server) ==')
    let res = mvn(['-q', '-pl', 'Mage.Common,Mage,Mage.Sets,Mage.Server', '-am', 'install', '-DskipTests'], { cwd: forkDir() })
    if (res.code !== 0) fail('base module build', res)
    log('  OK')

    log('== step 2/4: build plugin modules ==')
    res = mvn(['-q', '-pl', PLUGIN_MODULES.join(','), 'install', '-DskipTests'], { cwd: forkDir() })
    if (res.code !== 0) fail('plugin build', res)
    // the stamp goes last: a failed plugin build must not leave a stamp that makes the next run skip the install
    stampMageArtifacts()
    log('  OK')

    log('== step 3/4: copy plugins to local-server/plugins/ ==')
    copyPluginJars()
  } else {
    log('== proxy mode: skipping server/plugin build ==')
  }

  log('== final step: package Mage.Proxy (clean package) ==')
  ensureMageArtifacts()
  // the jar held open by the running proxy blocks the clean on Windows
  await stopPid('proxy')
  await new Promise((r) => setTimeout(r, 1000))
  const res = mvn(['-q', '-f', 'Mage.Proxy/pom.xml', 'clean', 'package', '-DskipTests'])
  if (res.code !== 0) fail('proxy packaging', res)

  log('')
  log('Build completed. Start everything with: node scripts/dev.mjs start')
}

await main()
