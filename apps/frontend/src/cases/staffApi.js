const staffUrl = '/api/v1/staff'

async function request(path, options = {}, signal) {
  const timeout = AbortSignal.timeout(10000)
  const response = await fetch(staffUrl + path, {
    ...options,
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  if (!response.ok) {
    throw Object.assign(new Error('Staff request failed'), { status: response.status })
  }
  return response.status === 204 ? undefined : response.json()
}

export function staffCsrf(signal) {
  return request('/csrf', {}, signal)
}

export function currentStaff() {
  return request('/me')
}

async function changeSession(path, body) {
  const csrf = await staffCsrf()
  await request(path, { method: 'POST', body, headers: { [csrf.headerName]: csrf.token } })
}

export function loginStaff(username, password) {
  return changeSession('/login', new URLSearchParams({ username, password }))
}

export function logoutStaff() {
  return changeSession('/logout')
}
