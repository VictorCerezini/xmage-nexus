#!/usr/bin/env node
// Full Mage.Proxy build chain (Windows/macOS/Linux/WSL).
// Usage: node scripts/build.mjs [proxy|bridge]
//   (no argument: compile server + plugins and package the proxy)
//   (arg "proxy": only package the proxy, assuming the rest is already compiled)
//   (arg "bridge": only package Mage.Client.Bridge, the client behind model seats
//    (llm/); it needs engine artifacts built from the llm-seats-engine patch)

import { copyPluginJars, ensureMageArtifacts, stampMageArtifacts, forkDir, log, logError, mvn, PLUGIN_MODULES, stopPid } from './lib.mjs'

function fail(step, res) {
  logError(`FAILED at: ${step}`)
  if (res.stderr) logError(res.stderr.split('\n').filter((l) => /ERROR/.test(l)).slice(0, 8).join('\n'))
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
    log('== step 1/4: compile base modules (Mage.Common, Mage, Mage.Sets, Mage.Server) ==')
    let res = mvn(['-q', '-pl', 'Mage.Common,Mage,Mage.Sets,Mage.Server', '-am', 'install', '-DskipTests'], { cwd: forkDir() })
    if (res.code !== 0) fail('base module compilation', res)
    stampMageArtifacts()
    log('  OK')

    log('== step 2/4: compile plugin modules ==')
    res = mvn(['-q', '-pl', PLUGIN_MODULES.join(','), 'install', '-DskipTests'], { cwd: forkDir() })
    if (res.code !== 0) fail('plugin compilation', res)
    log('  OK')

    log('== step 3/4: copy plugins to local-server/plugins/ ==')
    copyPluginJars()
  } else {
    log('== proxy mode: skipping server/plugin compilation ==')
  }

  log('== final step: package Mage.Proxy (clean package) ==')
  ensureMageArtifacts()
  // the jar held open by the running proxy blocks the clean on Windows
  stopPid('proxy')
  await new Promise((r) => setTimeout(r, 1000))
  const res = mvn(['-q', '-f', 'Mage.Proxy/pom.xml', 'clean', 'package', '-DskipTests'])
  if (res.code !== 0) fail('proxy packaging', res)

  log('')
  log('Build complete. Start everything with: node scripts/dev.mjs start')
}

await main()
