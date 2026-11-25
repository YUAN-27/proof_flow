const casesUrl = '/api/v1/cases'

export class CaseApiError extends Error {
  constructor(status, code) {
    super(`Case API returned ${status}`)
    this.status = status
    this.code = code
  }
}

async function request(path, token, method = 'GET', body) {
  const headers = { Authorization: `Bearer ${token}` }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${casesUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(5000),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new CaseApiError(response.status, problem?.code)
  }
  return response.json()
}

export function createCase(token, title) {
  return request('', token, 'POST', { title })
}

export function readCase(token, id) {
  return request(`/${encodeURIComponent(id)}`, token)
}

export function updateCaseStatus(token, id, expectedVersion, status) {
  return request(`/${encodeURIComponent(id)}/status`, token, 'PATCH', { expectedVersion, status })
}
