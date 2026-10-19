import { useEffect, useSyncExternalStore } from 'react'
import english from './messages.en.json'
export type Language = 'ru' | 'en'
const dictionary: Record<string, string> = english
const reverse = Object.fromEntries(Object.entries(dictionary).map(([key, value]) => [value, key]))
const listeners = new Set<() => void>()
const preferenceKey = 'pollydrop.language'
let saved: string | null = null
try { saved = localStorage.getItem(preferenceKey) } catch { /* Storage may be unavailable. */ }
let state = { language: (saved === 'ru' ? 'ru' : 'en') as Language, ready: saved === 'ru' || saved === 'en' }
let detecting: Promise<void> | null = null
function update(language: Language, ready = true) {
  state = { language, ready }
  document.documentElement.lang = language
  document.title = language === 'ru' ? 'pollyDrop — передать и отпустить' : 'pollyDrop — send it, let it go'
  document.querySelector('meta[name="description"]')?.setAttribute('content', language === 'ru' ? 'Файлы и слова. Только на нужное время. Временная передача с pollyDrop.' : 'Files and words. Only for as long as needed. Temporary sharing with pollyDrop.')
  listeners.forEach((listener) => listener())
}
export function setLanguage(language: Language) {
  try { localStorage.setItem(preferenceKey, language) } catch { /* Manual selection still works. */ }
  update(language)
}
async function detectLanguage() {
  if (state.ready) { update(state.language); return }
  if (detecting) return detecting
  detecting = (async () => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 3000)
    let language: Language = 'en'
    try {
      const response = await fetch('/api/locale', { cache: 'no-store', signal: controller.signal })
      if (response.ok) { const result = await response.json(); if (result.language === 'ru') language = 'ru' }
    } catch { /* Unknown country defaults to English; manual selection stays available. */ }
    finally { window.clearTimeout(timeout) }
    if (!state.ready) update(language)
  })()
  return detecting
}
export function useLanguage() {
  const current = useSyncExternalStore((listener) => { listeners.add(listener); return () => { listeners.delete(listener) } }, () => state)
  useEffect(() => { void detectLanguage() }, [])
  return { ...current, setLanguage }
}
export const localeTag = () => state.language === 'ru' ? 'ru-RU' : 'en-GB'
export function t(input: string, variables?: Record<string, unknown>): string {
  const key = dictionary[input] ? input : reverse[input] || input
  const value = state.language === 'ru' ? key : dictionary[key] || input
  return variables ? value.replace(/\{([^}]+)\}/g, (match, name: string) => String(variables[name] ?? match)) : value
}
const apiErrors: Record<string, string> = {
  UPLOAD_PAUSED: 'Связь прервалась. Прогресс сохранён. Продолжите загрузку, когда соединение восстановится.',
  UPLOAD_MISMATCH: 'Выберите исходный файл или начните новую загрузку.', UPLOAD_OFFSET: 'Прогресс загрузки изменился. Повторите попытку.',
  UPLOAD_INCOMPLETE: 'Дождитесь окончания загрузки.', CHECKSUM_MISMATCH: 'Проверка файла не прошла. Начните новую загрузку.',
  UPLOAD_EXPIRED: 'Срок загрузки истёк. Начните новую передачу.', UPLOAD_NOT_FOUND: 'Загрузка не найдена. Начните новую передачу.',
  WRONG_PASSWORD: 'Пароль не подошёл.', PASSWORD_REQUIRED: 'Введите пароль от отправителя.',
  IN_PROGRESS: 'Передача уже открыта другим получателем. Попробуйте позже.', SERVER_BUSY: 'Сервис занят. Попробуйте через минуту.',
  PASSWORD_LOCKED: 'Слишком много попыток. Попробуйте позже.', NOT_READY: 'Сервис запускается. Попробуйте через несколько секунд.',
  EXPIRED: 'Время вышло.', EXHAUSTED: 'Уже получено.', REVOKED: 'Ссылка отозвана.',
  NOT_FOUND: 'Нет доступа.', STORAGE_FULL: 'Недостаточно места для новой передачи.', NETWORK: 'Не удалось связаться с сервисом. Проверьте соединение и попробуйте ещё раз.'
}
export const apiErrorText = (code: string, message: string) => t(apiErrors[code] || (dictionary[message] ? message : 'Не удалось выполнить запрос. Попробуйте ещё раз.'))
