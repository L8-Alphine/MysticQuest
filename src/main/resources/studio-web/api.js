// The Studio API client. The session lives in an HttpOnly cookie; writes echo the CSRF token the
// server handed out at sign-in. The server authorizes every call; hiding buttons is only a courtesy.

export class ApiError extends Error {
  constructor(status, message, detail) {
    super(message);
    this.status = status;
    this.detail = detail;
  }
}

export const state = {
  me: null,          // { player, name, capabilities[], environment, csrf }
  documents: null,   // cached /api/documents
};

export function can(capability) {
  const granted = state.me?.capabilities || [];
  return granted.includes(capability) || granted.includes('ADMIN');
}

async function call(method, path, body) {
  const headers = { 'Accept': 'application/json' };
  const file = body instanceof Blob;
  if (method !== 'GET') {
    headers['Content-Type'] = file ? 'application/octet-stream' : 'application/json';
    if (state.me?.csrf) headers['X-CSRF-Token'] = state.me.csrf;
  }
  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : file ? body : JSON.stringify(body),
  });
  let payload = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login') {
      state.me = null;
      window.dispatchEvent(new Event('studio:signed-out'));
    }
    throw new ApiError(response.status, payload?.error || `Request failed (${response.status})`, payload?.detail);
  }
  return payload;
}

const query = params => new URLSearchParams(params).toString();

export const api = {
  login: code => call('POST', '/api/login', { code }),
  logout: () => call('POST', '/api/logout', {}),
  signInOptions: () => call('GET', '/api/sign-in-options'),
  me: () => call('GET', '/api/me'),
  files: () => call('GET', '/api/files'),
  file: path => call('GET', `/api/file?${query({ path })}`),
  saveFile: (path, text, version) => call('PUT', '/api/file', { path, text, version: version ?? null }),
  deleteFile: (path, version) => call('DELETE', `/api/file?${query({ path, version })}`),
  documents: async (fresh = false) => {
    if (fresh || !state.documents) state.documents = await call('GET', '/api/documents');
    return state.documents;
  },
  saveDocument: async (path, document, version) => {
    const result = await call('PUT', '/api/document', { path, document, version: version ?? null });
    state.documents = null;
    return result;
  },
  status: () => call('GET', '/api/status'),
  validate: () => call('POST', '/api/validate', {}),
  publish: (message, overwrite) => call('POST', '/api/publish', { message, overwrite }),
  reset: () => call('POST', '/api/reset', {}),
  releases: () => call('GET', '/api/releases'),
  compare: (from, to) => call('GET', `/api/releases/compare?${query({ from, to })}`),
  restore: number => call('POST', '/api/releases/restore', { number }),
  importBundle: file => call('PUT', '/api/releases/import', file),
  audit: (limit = 100) => call('GET', `/api/audit?${query({ limit })}`),
  audio: () => call('GET', '/api/audio'),
  uploadAudio: (name, kind, file) => call('PUT', `/api/audio?${query({ name, kind })}`, file),
  deleteAudio: name => call('DELETE', `/api/audio?${query({ name })}`),
  buildSoundPack: () => call('POST', '/api/audio/build', {}),
  live: () => call('GET', '/api/live'),
  livePlayer: player => call('GET', `/api/live/player?${query({ player })}`),
};

export function invalidate() {
  state.documents = null;
}
