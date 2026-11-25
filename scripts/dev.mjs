import { execFileSync, spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { parseEnv } from 'node:util'

const root = fileURLToPath(new URL('../', import.meta.url))
const frontend = join(root, 'apps/frontend')
const jar = 'apps/business-api/target/business-api-0.1.0-SNAPSHOT.jar'
const windows = process.platform === 'win32'
const children = []
const stopping = new AbortController()
const interrupted = new Promise((resolve) => {
  function interrupt() {
    stopping.abort()
    resolve(null)
  }
  process.once('SIGINT', interrupt)
  process.once('SIGTERM', interrupt)
  if (windows) process.once('SIGBREAK', interrupt)
})

function readConfig() {
  const path = join(root, '.env.dev')
  if (!existsSync(path)) {
    const contents = [
      'PROOFFLOW_DEV_JAVA_PORT=8082',
      'PROOFFLOW_DEV_WEB_PORT=5173',
      'PROOFFLOW_CASES_TENANT_ID=demo-tenant',
      'PROOFFLOW_CASES_ACTOR_ID=demo-operator',
      `PROOFFLOW_CASES_TOKEN=${randomBytes(32).toString('hex')}`,
      '',
    ].join('\n')
    try {
      writeFileSync(path, contents, { flag: 'wx', mode: 0o600 })
      console.log('Created local .env.dev. Use its token in the case workspace.')
    } catch (error) {
      if (error.code !== 'EEXIST') throw error
    }
  }
  return { ...parseEnv(readFileSync(path, 'utf8')), ...process.env }
}

async function checkPort(value, name) {
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535) {
    throw new Error(`${name} must be a TCP port between 1 and 65535.`)
  }
  const port = Number(value)
  await new Promise((resolve, reject) => {
    const probe = createServer()
    probe.once('error', () => reject(new Error(`Port ${port} is unavailable. Check ${name}.`)))
    probe.listen(port, '127.0.0.1', () => probe.close(resolve))
  })
  return port
}

function launch(label, command, args, options = {}) {
  const child = spawn(command, args, {
    cwd: root,
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    ...options,
  })
  child.stdout.pipe(process.stdout, { end: false })
  child.stderr.pipe(process.stderr, { end: false })
  const running = { label, child, result: null }
  running.done = new Promise((resolve) => {
    function finish(result) {
      running.result = result
      resolve(running)
    }
    child.once('error', (error) => finish(error.code || 'spawn failed'))
    child.once('exit', (code, signal) => finish(signal || String(code)))
  })
  children.push(running)
  return running
}

function buildJava(maven, env) {
  const args = ['-B', '-ntp', '-f', 'apps/business-api/pom.xml', '-DskipTests', 'package']
  if (!windows) return launch('Maven', maven, args, { env })
  return launch('Maven', 'powershell.exe', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
    `$ErrorActionPreference = 'Stop'; & $env:PROOFFLOW_MAVEN_EXECUTABLE ${args.join(' ')}; exit $LASTEXITCODE`,
  ], { detached: false, env: { ...env, PROOFFLOW_MAVEN_EXECUTABLE: maven } })
}

async function waitFor(url, services) {
  const limit = Date.now() + 30000
  while (Date.now() < limit && !stopping.signal.aborted) {
    const exited = services.find((service) => service.result !== null)
    if (exited) throw new Error(`${exited.label} exited (${exited.result}).`)
    try {
      const response = await fetch(url, {
        signal: AbortSignal.any([stopping.signal, AbortSignal.timeout(1000)]),
      })
      await response.body?.cancel()
      if (response.ok) return
    } catch {
      // The development server may still be binding its port.
    }
    await delay(250, undefined, { signal: stopping.signal })
  }
  throw new Error(`Timed out waiting for ${url}.`)
}

function signalService(service, signal) {
  if (!service.child.pid || service.result !== null) return
  try {
    if (windows) {
      execFileSync('taskkill.exe', ['/PID', String(service.child.pid), '/T', '/F'], {
        stdio: 'ignore', windowsHide: true, timeout: 5000,
      })
    } else {
      process.kill(-service.child.pid, signal)
    }
  } catch {
    if (service.result === null) service.child.kill(signal)
  }
}

try {
  const config = readConfig()
  const vite = join(frontend, 'node_modules/vite/bin/vite.js')
  if (!existsSync(vite)) throw new Error('Install the frontend dependencies with npm --prefix apps/frontend install.')
  if (!/^[A-Za-z0-9._~+/-]{32,}={0,2}$/.test(config.PROOFFLOW_CASES_TOKEN || '')) {
    throw new Error('Set a valid local PROOFFLOW_CASES_TOKEN in .env.dev.')
  }
  const javaPort = await checkPort(config.PROOFFLOW_DEV_JAVA_PORT || '8082', 'PROOFFLOW_DEV_JAVA_PORT')
  const webPort = await checkPort(config.PROOFFLOW_DEV_WEB_PORT || '5173', 'PROOFFLOW_DEV_WEB_PORT')
  if (javaPort === webPort) throw new Error('The Java and Vite ports must be different.')
  if (stopping.signal.aborted) throw new Error('Development startup interrupted.')

  const toolsEnv = { ...process.env }
  delete toolsEnv.PROOFFLOW_CASES_TOKEN
  delete toolsEnv.PROOFFLOW_CASES_TENANT_ID
  delete toolsEnv.PROOFFLOW_CASES_ACTOR_ID
  if (config.JAVA_HOME) toolsEnv.JAVA_HOME = config.JAVA_HOME
  const maven = buildJava(config.MVN || 'mvn', toolsEnv)
  const built = await Promise.race([maven.done, interrupted])
  if (!built || stopping.signal.aborted) throw new Error('Development startup interrupted.')
  if (built.result !== '0') throw new Error(`Maven failed (${built.result}). Check JDK 21 and MVN in .env.dev.`)

  const java = config.JAVA_HOME
    ? join(config.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java')
    : 'java'
  const javaUrl = `http://127.0.0.1:${javaPort}`
  const webUrl = `http://127.0.0.1:${webPort}/`
  const javaEnv = {
    ...toolsEnv,
    PROOFFLOW_CASES_TOKEN: config.PROOFFLOW_CASES_TOKEN,
    PROOFFLOW_CASES_TENANT_ID: config.PROOFFLOW_CASES_TENANT_ID || 'demo-tenant',
    PROOFFLOW_CASES_ACTOR_ID: config.PROOFFLOW_CASES_ACTOR_ID || 'demo-operator',
  }
  const backend = launch('Java', java, [
    '-jar', jar, '--server.address=127.0.0.1', `--server.port=${javaPort}`,
  ], { env: javaEnv })
  await waitFor(`${javaUrl}/api/v1/health`, [backend])

  const webEnv = { ...toolsEnv, PROOFFLOW_API_TARGET: javaUrl }
  const web = launch('Vite', process.execPath, [
    vite, '--host', '127.0.0.1', '--port', String(webPort), '--strictPort',
  ], { cwd: frontend, env: webEnv })
  await waitFor(webUrl, [backend, web])
  console.log(`ProofFlow development is ready at ${webUrl}. Press Ctrl+C to stop Java and Vite.`)
  const exited = await Promise.race([backend.done, web.done, interrupted])
  if (exited) throw new Error(`${exited.label} exited (${exited.result}).`)
} catch (error) {
  if (!stopping.signal.aborted) {
    console.error(error.message)
    process.exitCode = 1
  }
} finally {
  for (const service of children) signalService(service, 'SIGTERM')
  await Promise.race([Promise.all(children.map((service) => service.done)), delay(4000, undefined, { ref: false })])
  for (const service of children) signalService(service, 'SIGKILL')
}
