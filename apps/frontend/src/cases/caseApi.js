const casesUrl = '/api/v1/cases'

export class CaseApiError extends Error {
  constructor(status, code) {
    super(`Case API returned ${status}`)
    this.status = status
    this.code = code
  }
}

async function request(path, token, { method = 'GET', body, signal, binary, timeout = 5000 } = {}) {
  const headers = { Authorization: `Bearer ${token}` }
  if (body !== undefined && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(body)
  }
  const deadline = AbortSignal.timeout(timeout)
  const response = await fetch(`${casesUrl}${path}`, {
    method,
    headers,
    body,
    signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new CaseApiError(response.status, problem?.code)
  }
  return binary ? response.blob() : response.json()
}

export function createCase(token, title) {
  return request('', token, { method: 'POST', body: { title } })
}

export function readCase(token, id) {
  return request(`/${encodeURIComponent(id)}`, token)
}

export function updateCaseStatus(token, id, expectedVersion, status) {
  return request(`/${encodeURIComponent(id)}/status`, token, {
    method: 'PATCH', body: { expectedVersion, status },
  })
}

function evidencePath(caseId) {
  return `/${encodeURIComponent(caseId)}/evidence`
}

export function listEvidence(token, caseId, signal) {
  return request(evidencePath(caseId), token, { signal })
}

export function uploadEvidence(token, caseId, file, signal) {
  const body = new FormData()
  body.append('file', file)
  return request(evidencePath(caseId), token, { method: 'POST', body, signal, timeout: 60000 })
}

export function downloadEvidence(token, caseId, evidenceId, signal) {
  return request(`${evidencePath(caseId)}/${encodeURIComponent(evidenceId)}`, token, {
    signal, binary: true, timeout: 60000,
  })
}
