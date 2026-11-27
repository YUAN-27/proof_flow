import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const project = `proof-evidence-${randomBytes(6).toString('hex')}`
const token = randomBytes(32).toString('hex')
const env = {
  ...process.env,
  MYSQL_DATABASE: 'proofflow',
  MYSQL_USER: 'proofflow',
  MYSQL_PASSWORD: randomBytes(24).toString('hex'),
  MYSQL_ROOT_PASSWORD: randomBytes(24).toString('hex'),
  MINIO_ROOT_USER: 'proofflow-smoke',
  MINIO_ROOT_PASSWORD: randomBytes(24).toString('hex'),
  PROOFFLOW_WEB_PORT: '0',
  PROOFFLOW_CASES_TOKEN: token,
  PROOFFLOW_CASES_TENANT_ID: 'evidence-test-a',
  PROOFFLOW_CASES_ACTOR_ID: 'evidence-test-operator',
}
const composeArgs = [
  'compose', '-p', project, '--env-file', '.env.example',
  '-f', 'compose.yaml', '-f', 'compose.evidence.yaml',
]
const [override, ...extraArgs] = process.argv.slice(2)
assert.equal(extraArgs.length, 0, 'Usage: node tests/integration/evidence_persistence.mjs [prebuilt-compose-override.yaml]')
if (override) composeArgs.push('-f', resolve(override))

function compose(...args) {
  const result = spawnSync('docker', [...composeArgs, ...args], {
    cwd: root,
    env,
    stdio: ['ignore', 'pipe', 'inherit'],
    encoding: 'utf8',
  })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`docker compose ${args.join(' ')} exited ${result.status}`)
  return result.stdout.trim()
}

function frontendUrl() {
  const address = compose('port', 'frontend', '8080')
  assert.match(address, /^127\.0\.0\.1:\d+$/)
  return `http://${address}`
}

async function verifyAttachments() {
  let base = frontendUrl()
  async function request(path, expected, options = {}) {
    const headers = { Authorization: `Bearer ${env.PROOFFLOW_CASES_TOKEN}`, ...options.headers }
    const response = await fetch(`${base}${path}`, { ...options, headers, signal: AbortSignal.timeout(10000) })
    assert.equal(response.status, expected, `${options.method ?? 'GET'} ${path}`)
    return response
  }

  const casesPath = '/api/v1/cases'
  const created = await request(casesPath, 201, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'Evidence persistence smoke test' }),
  })
  const { id: caseId } = await created.json()
  assert.ok(caseId)

  const evidencePath = `${casesPath}/${caseId}/evidence`
  const filename = 'proof.pdf'
  const content = Buffer.from('%PDF-1.7\nProofFlow persistence smoke test\n')
  const upload = new FormData()
  upload.append('file', new Blob([content], { type: 'application/pdf' }), filename)
  const uploaded = await request(evidencePath, 201, { method: 'POST', body: upload })
  const { id } = await uploaded.json()
  assert.ok(id)

  compose('restart', 'business-api')
  compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '120')
  const listed = await request(evidencePath, 200)
  const attachments = await listed.json()
  assert.equal(attachments.length, 1)
  const [attachment] = attachments
  assert.equal(attachment.id, id)
  assert.equal(attachment.filename, filename)
  assert.equal(attachment.contentType, 'application/pdf')
  assert.equal(attachment.sizeBytes, content.length)

  const downloadPath = `${evidencePath}/${id}`
  const downloaded = await request(downloadPath, 200)
  assert.match(downloaded.headers.get('content-disposition') ?? '', /attachment/)
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), content)

  env.PROOFFLOW_CASES_TOKEN = randomBytes(32).toString('hex')
  env.PROOFFLOW_CASES_TENANT_ID = 'evidence-test-b'
  compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '120')
  compose('restart', 'frontend')
  base = frontendUrl()
  await request(evidencePath, 404)
  await request(downloadPath, 404)
}

try {
  if (!override) compose('build')
  compose('up', '-d', '--no-build', '--wait', '--wait-timeout', '180')
  await verifyAttachments()
  console.log('Evidence persisted after Java restart. Cross-tenant list and download returned 404.')
} finally {
  compose('down', '--volumes')
}
