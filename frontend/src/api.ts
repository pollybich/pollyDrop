import { t, apiErrorText } from './i18n'
export type Transfer = {
  kind: 'FILE' | 'TEXT'; name: string; bytes: number; createdAt: string; expiresAt: string;
  maxDownloads: number; downloads: number; remaining: number; passwordProtected: boolean;
  state: 'AVAILABLE' | 'IN_PROGRESS' | 'EXPIRED' | 'EXHAUSTED' | 'REVOKED'
}
export type Created = { readToken: string; manageToken: string; transfer: Transfer }
export class ApiError extends Error { constructor(public code: string, message: string) { super(message) } }

async function readError(response: Response): Promise<never> {
  const data = await response.json().catch(() => ({ code: 'NETWORK', message: t("Сервис не ответил. Попробуйте ещё раз.") }))
  throw new ApiError(data.code || "NETWORK", apiErrorText(data.code, data.message) || t("Не удалось выполнить запрос. Попробуйте ещё раз."))
}
const networkError = () => new ApiError('NETWORK', t("Не удалось связаться с сервисом. Проверьте соединение и попробуйте ещё раз."))
async function readJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await fetch(url, { cache: 'no-store', signal })
      if ([502, 503, 504].includes(response.status) && attempt < 2) throw networkError()
      if (!response.ok) return readError(response)
      return await response.json()
    } catch (error) {
      if (signal?.aborted) throw error
      const transient = error instanceof TypeError || error instanceof SyntaxError || error instanceof ApiError && error.code === 'NETWORK'
      if (!transient) throw error
      if (attempt >= 2) throw networkError()
      await new Promise<void>((resolve) => window.setTimeout(resolve, 400 * (attempt + 1)))
      if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError')
    }
  }
}
export function metadata(token: string, manage = false, signal?: AbortSignal): Promise<Transfer> {
  return readJson(`/api/${manage ? 'manage' : 'transfers'}/${encodeURIComponent(token)}`, signal)
}
export async function downloadFile(token: string, password: string, signal: AbortSignal): Promise<void> {
  const storageKey = `pollydrop.download.${token}`
  let ticket: { url: string; statusUrl: string; expiresAt?: string } | null = null
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || 'null')
    if (saved && /^\/api\/downloads\/[A-Za-z0-9_-]{43}$/.test(saved.url) && saved.statusUrl === `${saved.url}/status` && typeof saved.expiresAt === 'string') {
      const current = await readJson<{ state: string }>(saved.statusUrl, signal)
      if (current.state === 'SENT') { localStorage.removeItem(storageKey); return }
      if (current.state !== 'EXPIRED') ticket = saved
    }
  } catch (error) { if (signal.aborted) throw error }
  if (!ticket) {
    let response: Response
    try { response = await fetch(`/api/transfers/${encodeURIComponent(token)}/download-ticket`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }), signal }) }
    catch (error) { if (signal.aborted) throw error; throw networkError() }
    if (!response.ok) return readError(response)
    ticket = await response.json()
  }
  if (!ticket || !/^\/api\/downloads\/[A-Za-z0-9_-]{43}$/.test(ticket.url) || ticket.statusUrl !== `${ticket.url}/status`) throw new Error(t("Не удалось подготовить скачивание."))
  try { localStorage.setItem(storageKey, JSON.stringify(ticket)) } catch { /* Native downloads still work without persistent browser storage. */ }
  // The same capability is retained for HTTP Range retries in the native download manager.
  window.location.assign(ticket.url)
  for (let attempt = 0; attempt < 300; attempt++) {
    if (signal.aborted) throw new DOMException('Cancelled', 'AbortError')
    const { state } = await readJson<{ state: string }>(ticket.statusUrl, signal)
    if (state === 'SENT') { try { localStorage.removeItem(storageKey) } catch { /* Optional storage. */ } return }
    if (state === 'FAILED' || state === 'PARTIAL') throw new Error(t("Загрузка прервалась. Продолжите её в списке скачиваний браузера или повторите здесь. Лимит не потрачен."))
    if (state === 'EXPIRED') { try { localStorage.removeItem(storageKey) } catch { /* Optional storage. */ } throw new Error(t("Срок скачивания истёк. Проверьте условия передачи.")) }
    await new Promise<void>((resolve) => window.setTimeout(resolve, 1000))
  }
  throw new Error(t("Браузер ещё обрабатывает загрузку. Проверьте список скачиваний перед повтором."))
}

function createLegacyTransfer(data: FormData, progress: (value: number) => void, signal: AbortSignal): Promise<Created> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/transfers')
    const abort = () => xhr.abort()
    signal.addEventListener('abort', abort, { once: true })
    const cleanup = () => signal.removeEventListener('abort', abort)
    xhr.upload.onprogress = (event) => { if (event.lengthComputable) progress(Math.min(95, Math.round(event.loaded / event.total * 95))) }
    xhr.onload = () => {
      cleanup()
      let response
      try { response = JSON.parse(xhr.responseText) } catch { reject(new Error(t("Сервис не ответил. Попробуйте ещё раз."))); return }
      if (xhr.status >= 200 && xhr.status < 300) { progress(100); resolve(response) }
      else reject(new ApiError(response.code, apiErrorText(response.code, response.message) || t("Не удалось создать передачу.")))
    }
    xhr.onerror = () => { cleanup(); reject(new Error(t("Нет соединения с сервером. Попробуйте ещё раз."))) }
    xhr.onabort = () => { cleanup(); reject(new DOMException(t("Передача отменена."), 'AbortError')) }
    xhr.send(data)
  })
}
export async function receive(token: string, password: string): Promise<{ blob: Blob; name: string }> {
  const response = await fetch(`/api/transfers/${encodeURIComponent(token)}/download`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }) })
  if (!response.ok) return readError(response)
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="([^"]+)"/)?.[1]
  const name = encoded ? decodeURIComponent(encoded) : plain || 'pollydrop-file'
  return { blob: await response.blob(), name }
}
export async function revoke(token: string): Promise<void> {
  const response = await fetch(`/api/manage/${encodeURIComponent(token)}`, { method: 'DELETE' })
  if (!response.ok) return readError(response)
}


export type PendingUpload = {
  secret: string; name: string; size: number; fingerprint: string; offset: number;
  expiresAt: string; ttlSeconds: number; maxDownloads: number; passwordProtected: boolean
}
export type UploadPhase = 'checking' | 'sending' | 'paused' | 'finishing'
type UploadStatus = { name: string; bytes: number; fingerprint: string; offset: number; chunkSize: number; expiresAt: string; maxDownloads: number; passwordProtected: boolean; completed: boolean }
const pendingKey = 'pollydrop.upload'
let volatilePending: PendingUpload | null = null
export function pendingUpload(): PendingUpload | null {
  try {
    const entry = JSON.parse(localStorage.getItem(pendingKey) || 'null')
    if (entry && /^[A-Za-z0-9_-]{43}$/.test(entry.secret) && /^[a-f0-9]{64}$/.test(entry.fingerprint) && typeof entry.name === 'string' && Number.isSafeInteger(entry.size) && entry.size > 0 && entry.size <= 25 * 1024 * 1024 && typeof entry.expiresAt === 'string') return entry
  } catch { /* The active tab can still resume if browser storage is unavailable. */ }
  return volatilePending
}
function savePending(entry: PendingUpload | null) {
  volatilePending = entry
  try { if (entry) localStorage.setItem(pendingKey, JSON.stringify(entry)); else localStorage.removeItem(pendingKey) } catch { /* Optional storage. */ }
  window.dispatchEvent(new Event('pollydrop-upload'))
}
export function forgetUpload() {
  const entry = pendingUpload(); savePending(null)
  if (entry) void fetch(`/api/uploads/${entry.secret}`, { method: 'DELETE' }).catch(() => { /* Server expiry still cleans up encrypted staging parts. */ })
}
function checkAborted(signal: AbortSignal) { if (signal.aborted) throw new DOMException('Cancelled', 'AbortError') }
async function delay(ms: number, signal: AbortSignal) {
  checkAborted(signal)
  await new Promise<void>((resolve, reject) => {
    const abort = () => { window.clearTimeout(timer); signal.removeEventListener('abort', abort); reject(new DOMException('Cancelled', 'AbortError')) }
    const timer = window.setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, ms)
    signal.addEventListener('abort', abort, { once: true })
  })
}
async function uploadRequest<T>(url: string, init: RequestInit, signal: AbortSignal, phase: (phase: UploadPhase) => void): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    checkAborted(signal)
    const controller = new AbortController(), abort = () => controller.abort()
    signal.addEventListener('abort', abort, { once: true })
    const timeout = window.setTimeout(abort, 90000)
    try {
      const response = await fetch(url, { ...init, cache: 'no-store', signal: controller.signal })
      if ([429, 502, 503, 504].includes(response.status)) throw networkError()
      if (!response.ok) return await readError(response)
      const data = await response.json(); phase('sending'); return data
    } catch (error) {
      checkAborted(signal)
      const transient = error instanceof TypeError || error instanceof SyntaxError || error instanceof DOMException && error.name === 'AbortError' || error instanceof ApiError && error.code === 'NETWORK'
      if (!transient) throw error
      phase('paused')
      if (attempt >= 5) throw new ApiError('UPLOAD_PAUSED', t('Связь прервалась. Прогресс сохранён. Продолжите загрузку, когда соединение восстановится.'))
      await delay(Math.min(5000, 750 * 2 ** attempt), signal)
    } finally { window.clearTimeout(timeout); signal.removeEventListener('abort', abort) }
  }
}
export async function createTransfer(data: FormData, progress: (value: number) => void, signal: AbortSignal, phase: (phase: UploadPhase) => void = () => {}): Promise<Created> {
  const part = data.get('options')
  if (!(part instanceof Blob)) throw new Error(t('Не удалось прочитать параметры передачи.'))
  const options = JSON.parse(await part.text())
  if (options.kind !== 'FILE') { phase('sending'); return createLegacyTransfer(data, progress, signal) }
  const file = data.get('file'), existing = pendingUpload()
  phase('checking');checkAborted(signal)
  let fingerprint = existing?.fingerprint
  if (file instanceof File) {
    if (file.size < 1 || file.size > 25 * 1024 * 1024) throw new Error(t('Файл должен быть не больше 25 МБ.'))
    const hash = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());checkAborted(signal)
    fingerprint = Array.from(new Uint8Array(hash), (v) => v.toString(16).padStart(2, '0')).join('')
    if (existing && (file.size !== existing.size || fingerprint !== existing.fingerprint)) throw new ApiError('UPLOAD_MISMATCH', t('Выберите исходный файл или начните новую загрузку.'))
  } else if (!existing || existing.offset < existing.size) throw new Error(t('Выберите исходный файл, чтобы продолжить загрузку.'))
  let entry: PendingUpload
  if (existing) entry = existing
  else {
    if (!(file instanceof File) || !fingerprint) throw new Error(t('Добавьте файл или текст.'))
    const random = crypto.getRandomValues(new Uint8Array(32))
    const secret = btoa(String.fromCharCode(...random)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    entry = { secret, fingerprint, name: file.name, size: file.size, offset: 0, expiresAt: new Date(Date.now() + options.ttlSeconds * 1000).toISOString(), ttlSeconds: options.ttlSeconds, maxDownloads: options.maxDownloads, passwordProtected: !!options.password }
    savePending(entry)
    const status = await uploadRequest<UploadStatus>(`/api/uploads/${secret}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: file.name.slice(0,240), bytes: file.size, fingerprint, ttlSeconds: options.ttlSeconds, maxDownloads: options.maxDownloads, password: options.password }) }, signal, phase)
    entry = { ...entry, expiresAt: status.expiresAt };savePending(entry)
  }
  const url = `/api/uploads/${entry.secret}`
  let status = await uploadRequest<UploadStatus>(url, {}, signal, phase)
  while (true) {
    checkAborted(signal)
    entry = { ...entry, offset: status.offset, expiresAt: status.expiresAt };savePending(entry)
    progress(Math.round(status.offset / entry.size * 100))
    if (status.completed || status.offset === entry.size) break
    if (!(file instanceof File)) throw new Error(t('Выберите исходный файл, чтобы продолжить загрузку.'))
    try {
      status = await uploadRequest<UploadStatus>(url, { method: 'PATCH', headers: { 'Content-Type': 'application/octet-stream', 'Upload-Offset': String(status.offset) }, body: file.slice(status.offset, status.offset + status.chunkSize) }, signal, phase)
    } catch (error) {
      if (!(error instanceof ApiError && error.code === 'UPLOAD_OFFSET')) throw error
      status = await uploadRequest<UploadStatus>(url, {}, signal, phase)
    }
  }
  phase('finishing')
  const created = await uploadRequest<Created>(`${url}/complete`, { method: 'POST' }, signal, () => phase('finishing'))
  savePending(null);progress(100);return created
}

export function hasPendingDownload(token: string): boolean {
  try { const saved = JSON.parse(localStorage.getItem(`pollydrop.download.${token}`) || 'null'); return !!saved && /^\/api\/downloads\/[A-Za-z0-9_-]{43}$/.test(saved.url) && saved.statusUrl === `${saved.url}/status` } catch { return false }
}
