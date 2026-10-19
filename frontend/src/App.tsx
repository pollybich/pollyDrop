import { t, useLanguage, localeTag, apiErrorText } from './i18n'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent, DragEvent } from 'react'
import { ApiError, createTransfer, metadata, receive, revoke, downloadFile, pendingUpload, forgetUpload, hasPendingDownload } from './api'
import type { Created, Transfer, UploadPhase } from './api'
import { Icon } from './Icons'
import '@fontsource-variable/manrope'

const MAX_FILE = 25 * 1024 * 1024
const byteLabel = (bytes: number) => bytes < 1024 ? t("{v0} Б", { v0: bytes }) : bytes < 1024 * 1024 ? t("{v0} КБ", { v0: (bytes / 1024).toFixed(1) }) : t("{v0} МБ", { v0: (bytes / 1024 / 1024).toFixed(1) })
const dateLabel = (date: string) => new Intl.DateTimeFormat(localeTag(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(date))
const errorMessage = (error: unknown) => error instanceof ApiError ? apiErrorText(error.code, error.message) : error instanceof Error ? t(error.message) : t("Что-то пошло не так. Попробуйте ещё раз.")
const unavailable: Record<string, { title: string; text: string }> = {
  EXPIRED: { title: 'Время вышло.', text: 'Срок передачи закончился. Попросите отправителя создать новую ссылку.' },
  EXHAUSTED: { title: 'Уже получено.', text: 'Все получения использованы. Эта передача больше недоступна.' },
  REVOKED: { title: 'Ссылка отозвана.', text: 'Отправитель закрыл доступ к этой передаче.' },
}

function useRoute() {
  const [path, setPath] = useState(window.location.pathname)
  useEffect(() => { const onPop = () => setPath(window.location.pathname); window.addEventListener('popstate', onPop); return () => window.removeEventListener('popstate', onPop) }, [])
  const go = (next: string) => { window.history.pushState({}, '', next); setPath(next); window.scrollTo({ top: 0, behavior: 'instant' }) }
  return { path, go }
}

function App() {
  const { language, ready, setLanguage } = useLanguage()
  const { path, go } = useRoute()
  const [help, setHelp] = useState(false)
  const [toast, setToast] = useState('')
  const [online, setOnline] = useState<boolean | null>(null)
  useEffect(() => {
    let alive = true
    const controller = new AbortController()
    const check = () => { if (!navigator.onLine) { setOnline(false); return }; void fetch('/api/health', { cache: 'no-store', signal: controller.signal }).then((r) => { if (alive) setOnline(r.ok) }).catch(() => { if (alive) setOnline(false) }) }
    const offline = () => setOnline(false)
    check(); const timer = window.setInterval(check, 15000)
    window.addEventListener('online', check); window.addEventListener('offline', offline)
    return () => { alive = false; controller.abort(); window.clearInterval(timer); window.removeEventListener('online', check); window.removeEventListener('offline', offline) }
  }, [])
  useEffect(() => { if (!toast) return; const id = window.setTimeout(() => setToast(''), 4000); return () => clearTimeout(id) }, [toast])
  const copy = async (text: string, message = t("Ссылка скопирована")) => {
    try { await navigator.clipboard.writeText(text); setToast(message); return true }
    catch { setToast(t("Не удалось скопировать. Выделите и скопируйте вручную.")); return false }
  }
  const receiveMatch = path.match(/^\/s\/([A-Za-z0-9_-]{43})\/?$/)
  const manageMatch = path.match(/^\/manage\/([A-Za-z0-9_-]{43})\/?$/)
  if (!ready) return <div className="language-loading" role="status"><span className="brand-symbol">p</span><span>pollyDrop</span><span className="spinner" /></div>
  return <div className="app-shell">
    <div className="ambient" aria-hidden="true"><div className="ambient-light" /><div className="ambient-line" /></div>
    <header className="site-header">
      <a className="brand" href="/" onClick={(e) => { e.preventDefault(); go('/') }} aria-label={t("pollyDrop — главная")}><span className="brand-symbol" aria-hidden="true">p</span><span>pollyDrop<span className="brand-dot">.</span></span></a>
      <div className="header-actions"><div className="language-switch" role="group" aria-label="Language / Язык"><button type="button" aria-pressed={language === 'ru'} onClick={() => setLanguage('ru')}>RU</button><button type="button" aria-pressed={language === 'en'} onClick={() => setLanguage('en')}>EN</button></div><span className="beta-label">BETA / 04</span><button className="help-button" aria-label={t("Как это работает")} onClick={() => setHelp(true)}><span>{t("Как это работает")}</span><span className="help-question">?</span></button></div>
    </header>
    <main className="main-layout">
      <section className="intro">
        <p className="eyebrow"><span /> {t("НИЧЕГО ЛИШНЕГО")}</p>
        <h1>{receiveMatch ? <>{t("Вам")}<br /><em>{t("передали.")}</em></> : manageMatch ? <>{t("Ваши")}<br /><em>{t("правила.")}</em></> : <>{t("Передать.")}<br /><em>{t("И отпустить.")}</em></>}</h1>
        <p className="intro-description">{receiveMatch ? t("Заберите то, что предназначено вам. Пока ссылка ещё действует.") : manageMatch ? t("Вы решаете, когда закрыть доступ. Даже раньше срока.") : t("Файлы и слова. Только на нужное время. Без регистрации. Срок и доступ задаёте вы.")}</p>
        <div className="glass-object" aria-hidden="true"><div className="object-shadow" /><div className="glass-sheet back-sheet"><span className="sheet-line" /><span className="sheet-line short" /></div><div className="glass-sheet front-sheet"><div className="object-star">✳</div><span className="object-caption">FOR A MOMENT.</span><div className="object-corner" /></div><div className="orbit-dot" /></div>
        <p className="intro-note"><Icon name="shield" size={17} /><span>{t("Содержимое зашифровано")}<br />{t("на время хранения.")}</span></p>
      </section>
      <div className="workspace-column">
        <div className="workspace-heading"><span>{receiveMatch ? t("ВХОДЯЩАЯ ПЕРЕДАЧА") : manageMatch ? t("УПРАВЛЕНИЕ ПЕРЕДАЧЕЙ") : t("НОВАЯ ПЕРЕДАЧА")}</span><span className={`connection ${online === false ? 'offline' : ''}`}><i />{online === null ? t("Подключение") : online ? t("Готов к передаче") : t("Нет соединения")}</span></div>
        {receiveMatch ? <Recipient key={receiveMatch[1]} token={receiveMatch[1]} copy={copy} go={go} /> : manageMatch ? <Management key={manageMatch[1]} token={manageMatch[1]} go={go} /> : path === '/' ? <Composer copy={copy} go={go} /> : <div className="glass-panel state-card"><Icon name="link" size={36} /><h2>{t("Здесь пусто.")}</h2><p>{t("Проверьте адрес ссылки или создайте свою передачу.")}</p><button className="primary" onClick={() => go('/')}>{t("Новая передача")} <Icon name="arrow" /></button></div>}
        <p className="workspace-note"><Icon name="clock" size={14} /> {t("Временные ссылки. Контроль остаётся у вас.")}</p>
      </div>
    </main>
    <footer className="site-footer"><span>{t("Данные — не навсегда.")}</span><a className="geoip-credit" href="https://db-ip.com" target="_blank" rel="noopener noreferrer">IP Geolocation by DB-IP</a><span className="footer-right">{t("Сделано для спокойного обмена")} <a href="https://github.com/pollybich/pollyDrop" target="_blank" rel="noopener noreferrer" aria-label={t("Проект pollyDrop на GitHub")}><Icon name="arrow" size={16} /></a></span></footer>
    {toast && <div className="toast" role="status"><Icon name="check" size={17} />{toast}</div>}
    <Help open={help} close={() => setHelp(false)} />
  </div>
}

type Copy = (text: string, message?: string) => Promise<boolean>
function Composer({ copy, go }: { copy: Copy; go: (path: string) => void }) {
  const [pending, setPending] = useState(pendingUpload)
  const [phase, setPhase] = useState<UploadPhase>('checking')
  useEffect(() => { const update = () => setPending(pendingUpload()); window.addEventListener('pollydrop-upload', update); window.addEventListener('storage', update); return () => { window.removeEventListener('pollydrop-upload', update); window.removeEventListener('storage', update) } }, [])
  const [kind, setKind] = useState<'FILE' | 'TEXT'>('FILE')
  const [file, setFile] = useState<File | null>(null)
  const [text, setText] = useState('')
  const [ttl, setTtl] = useState(3600)
  const [limit, setLimit] = useState(1)
  const [protectedLink, setProtected] = useState(false)
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [drag, setDrag] = useState(false)
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState(0)
  const [created, setCreated] = useState<Created | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const abort = useRef<AbortController | null>(null)
  useEffect(() => () => abort.current?.abort(), [])
  const choose = (next: File | undefined) => {
    if (!next) return
    if (next.size > MAX_FILE) { setError(t("Этот файл больше 25 МБ. Выберите файл поменьше.")); return }
    if (!next.size) { setError(t("Файл пустой. Выберите другой.")); return }
    setFile(next); setError('')
  }
  const onDrop = (e: DragEvent) => { e.preventDefault(); setDrag(false); if (!uploading) choose(e.dataTransfer.files[0]) }
  const submit = async (e: FormEvent) => {
    e.preventDefault(); setError('')
    if (protectedLink && !password && !(kind === 'FILE' && pending)) { setError(t("Придумайте пароль или отключите защиту паролем.")); return }
    if (kind === 'TEXT' && new TextEncoder().encode(text).length > 65536) { setError(t("Текст должен быть не больше 64 КБ.")); return }
    const data = new FormData()
    data.append('options', new Blob([JSON.stringify({ kind, text: kind === 'TEXT' ? text : undefined, ttlSeconds: ttl, maxDownloads: limit, password: protectedLink ? password : undefined })], { type: 'application/json' }))
    if (kind === 'FILE' && file) data.append('file', file)
    abort.current = new AbortController(); setUploading(true); setProgress(0)
    try { setCreated(await createTransfer(data, setProgress, abort.current.signal, setPhase)); setPassword(''); setText(''); setFile(null) }
    catch (err) { setError(err instanceof DOMException && err.name === 'AbortError' ? (kind === 'FILE' ? t("Загрузка приостановлена. Прогресс сохранён.") : t("Загрузка отменена. Можно начать снова.")) : errorMessage(err)) }
    finally { setUploading(false) }
  }
  if (created) return <Result result={created} copy={copy} go={go} reset={() => setCreated(null)} />
  const resuming = kind === 'FILE' && pending !== null
  const effectiveProtected = resuming ? pending.passwordProtected : protectedLink
  const valid = kind === 'FILE' ? !!file || !!pending && pending.offset === pending.size : !!text.trim()
  return <form className="glass-panel composer" onSubmit={submit}>
    <div className="form-top"><div className="segmented" role="group" aria-label={t("Тип передачи")}><button type="button" className={kind === 'FILE' ? 'selected' : ''} aria-pressed={kind === 'FILE'} disabled={uploading} onClick={() => { setKind('FILE'); setError('') }}><Icon name="file" size={17} /> {t("Файл")}</button><button type="button" className={kind === 'TEXT' ? 'selected' : ''} aria-pressed={kind === 'TEXT'} disabled={uploading} onClick={() => { setKind('TEXT'); setError('') }}><Icon name="text" size={17} /> {t("Текст")}</button></div><span className="form-index">01 <span>/ 03</span></span></div>
    {resuming && !uploading && <aside className="resume-notice" role="status"><strong>{t("Прогресс сохранён")}</strong><span>{pending.name} · {Math.round(pending.offset / pending.size * 100)}%</span><p>{pending.offset < pending.size ? t("Выберите исходный файл — продолжим с сохранённого места. Срок и параметры остаются прежними.") : t("Все части сохранены. Завершите создание ссылки.")}</p><button type="button" className="text-button" onClick={() => { forgetUpload(); setError(''); setProgress(0) }}>{t("Начать новую загрузку")}</button></aside>}
    {kind === 'FILE' ? <div className={`drop-zone ${drag ? 'is-dragging' : ''} ${file ? 'has-file' : ''}`} onDragOver={(e) => { e.preventDefault(); setDrag(true) }} onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setDrag(false) }} onDrop={onDrop}>
      <input ref={input} type="file" className="visually-hidden" aria-label={t("Выберите файл")} disabled={uploading} onChange={(e) => { choose(e.target.files?.[0]); e.target.value = '' }} />
      {file ? <><div className="selected-file-icon"><Icon name="file" size={35} /><span>{file.name.split('.').at(-1)?.slice(0,5).toUpperCase() || 'FILE'}</span></div><p className="file-name">{file.name}</p><p className="drop-detail">{byteLabel(file.size)} <span>·</span> {t("Готов к передаче")}</p><button className="file-remove" type="button" disabled={uploading} onClick={() => setFile(null)} aria-label={t("Убрать файл")}><Icon name="close" size={16} /></button><button type="button" className="text-button" disabled={uploading} onClick={() => input.current?.click()}>{t("Выбрать другой")}</button></> : <><div className="drop-illustration" aria-hidden="true"><div className="drop-paper"><Icon name="file" size={30} /></div><span className="drop-plus"><Icon name="plus" size={16} /></span></div><p className="drop-title">{t("Перетащите файл сюда")}</p><button type="button" className="text-button" disabled={uploading} onClick={() => input.current?.click()}>{t("или выберите на устройстве")} <Icon name="arrow" size={14} /></button><p className="drop-limit">{t("Любой формат · до 25 МБ")}</p></>}
    </div> : <div className="text-zone"><label className="visually-hidden" htmlFor="transfer-text">{t("Текст передачи")}</label><textarea id="transfer-text" value={text} disabled={uploading} onChange={(e) => { setText(e.target.value); setError('') }} placeholder={t("Сообщение, ключ или просто пара слов…")} spellCheck={false} /><div className="text-count">{byteLabel(new TextEncoder().encode(text).length)} <span>{t("/ 64 КБ")}</span></div></div>}
    <div className="settings-grid"><label className="setting"><span className="setting-label"><Icon name="clock" size={15} /> {t("Удалить через")}</span><div className="select-wrap"><select aria-label={t("Удалить через")} value={resuming ? pending.ttlSeconds : ttl} disabled={uploading || resuming} onChange={(e) => setTtl(Number(e.target.value))}><option value={600}>{t("10 минут")}</option><option value={3600}>{t("1 час")}</option><option value={86400}>{t("24 часа")}</option><option value={172800}>{t("2 дня")}</option></select><Icon name="chevron" size={15} /></div></label><label className="setting"><span className="setting-label"><Icon name="download" size={15} /> {t("Получений")}</span><div className="select-wrap"><select aria-label={t("Количество получений")} value={resuming ? pending.maxDownloads : limit} disabled={uploading || resuming} onChange={(e) => setLimit(Number(e.target.value))}><option value={1}>{t("Один раз")}</option><option value={3}>{t("3 раза")}</option><option value={5}>{t("5 раз")}</option><option value={10}>{t("10 раз")}</option></select><Icon name="chevron" size={15} /></div></label></div>
    <div className="password-section"><div className="password-row"><label htmlFor="protect-toggle"><Icon name="lock" size={17} /><span>{t("Защитить паролем")}</span></label><button id="protect-toggle" type="button" role="switch" aria-checked={effectiveProtected} aria-label={t("Защитить паролем")} className={`switch ${effectiveProtected ? 'on' : ''}`} disabled={uploading || resuming} onClick={() => setProtected(!effectiveProtected)}><span /></button></div>{effectiveProtected && !resuming && <div className="password-input-wrap"><input type={visible ? 'text' : 'password'} aria-label={t("Пароль передачи")} placeholder={t("Придумайте пароль")} autoComplete="new-password" maxLength={128} value={password} disabled={uploading || resuming} onChange={(e) => setPassword(e.target.value)} /><button type="button" aria-label={visible ? t("Скрыть пароль") : t("Показать пароль")} aria-pressed={visible} onClick={() => setVisible(!visible)}><Icon name="eye" size={18} /></button></div>}</div>
    {resuming && pending.passwordProtected && <p className="form-footnote">{t("Используется пароль из сохранённой загрузки.")}</p>}
    {error && <p className="error-message" role="alert">{t(error)}</p>}
    <button type="submit" className="primary create-button" disabled={!valid || uploading}><span>{uploading ? phase === 'checking' ? t("Проверяем файл…") : phase === 'paused' ? t("Ждём соединения…") : phase === 'finishing' ? t("Обрабатываем передачу…") : t("Загружаем · {v0}%", { v0: progress }) : resuming ? pending.offset === pending.size ? t("Завершить загрузку") : t("Продолжить загрузку") : t("Создать ссылку")}</span><span className="button-disc">{uploading ? <span className="spinner" /> : <Icon name="arrow" size={20} />}</span></button>
    {uploading ? <div className="upload-status"><p className="upload-message" role="status">{phase === 'paused' ? t("Связь прервалась. Прогресс сохранён. Повторяем подключение…") : kind === 'FILE' ? t("Сохраняем прогресс после каждой части.") : ''}</p><div className="progress-track"><span style={{ width: `${progress}%` }} /></div><button type="button" className="text-button" onClick={() => abort.current?.abort()}>{kind === 'FILE' ? t("Приостановить") : t("Отменить загрузку")}</button></div> : <p className="form-footnote"><Icon name="shield" size={13} /> {t("Без аккаунта. Без постоянного хранения.")}</p>}
  </form>
}

function LinkField({ label, value, copy }: { label: string; value: string; copy: Copy }) {
  const [copied, setCopied] = useState(false)
  useEffect(() => { if (!copied) return; const t = window.setTimeout(() => setCopied(false), 2000); return () => clearTimeout(t) }, [copied])
  return <div className="link-field"><input aria-label={label} value={value} readOnly onFocus={(e) => e.currentTarget.select()} /><button type="button" aria-label={t("Скопировать {v0}", { v0: label.toLowerCase() })} onClick={async () => setCopied(await copy(value))}><Icon name={copied ? 'check' : 'copy'} size={18} /></button></div>
}
function Result({ result, copy, reset, go }: { result: Created; copy: Copy; reset: () => void; go: (path: string) => void }) {
  const read = `${window.location.origin}/s/${result.readToken}`
  const manage = `${window.location.origin}/manage/${result.manageToken}`
  return <div className="glass-panel result-panel">
    <div className="result-heading"><span className="success-mark"><Icon name="check" size={24} /></span><span className="form-index">03 / 03</span></div>
    <h2>{t("Можно передавать.")}</h2><p className="panel-description">{t("Всё готово. Отправьте эту ссылку тому,")}<br className="desktop-break" /> {t("кому предназначены данные.")}</p>
    <div className="transfer-summary"><span className="summary-icon"><Icon name={result.transfer.kind === 'TEXT' ? 'text' : 'file'} size={25} /></span><div><strong>{result.transfer.kind === 'TEXT' ? t('Текст') : result.transfer.name}</strong><span>{byteLabel(result.transfer.bytes)} · {result.transfer.maxDownloads === 1 ? t("Одно получение") : t("{v0} получений", { v0: result.transfer.maxDownloads })}</span></div>{result.transfer.passwordProtected && <Icon name="lock" size={17} />}</div>
    <label className="field-caption">{t("ССЫЛКА ДЛЯ ПОЛУЧАТЕЛЯ")}</label><LinkField label={t("Ссылка для получателя")} value={read} copy={copy} />
    <p className="expiry-line"><Icon name="clock" size={15} /> {t("До")} {dateLabel(result.transfer.expiresAt)}</p>
    <details className="management-details"><summary><span><Icon name="shield" size={16} /> {t("Отзыв и управление")}</span><Icon name="chevron" size={14} /></summary><p>{t("Эта ссылка только для вас. Сохраните её, чтобы отозвать передачу. Не отправляйте получателю.")}</p><LinkField label={t("Ссылка управления")} value={manage} copy={copy} /><button className="text-button" onClick={() => go(`/manage/${result.manageToken}`)}>{t("Открыть управление")} <Icon name="arrow" size={15} /></button></details>
    <button type="button" className="secondary wide" onClick={reset}><Icon name="plus" size={18} /> {t("Ещё одна передача")}</button>
  </div>
}

function useTransfer(token: string, owner = false) {
  const [data, setData] = useState<Transfer | null>(null)
  const [error, setError] = useState('')
  const refresh = async () => { setError(''); try { setData(await metadata(token, owner)) } catch (e) { setError(errorMessage(e)) } }
  useEffect(() => { let live = true; const abort = new AbortController(); metadata(token, owner, abort.signal).then((d) => { if (live) setData(d) }).catch((e) => { if (live) setError(errorMessage(e)) }); return () => { live = false; abort.abort() } }, [token, owner])
  useEffect(() => { if (data?.state !== 'IN_PROGRESS') return; const t = window.setInterval(refresh, 1500); return () => clearInterval(t) }, [data?.state, token])
  return { data, error, refresh }
}
function saveFile(blob: Blob, name: string) { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name; document.body.append(link); link.click(); link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 30000) }
function Recipient({ token, copy, go }: { token: string; copy: Copy; go: (path: string) => void }) {
  const { data, error: loadingError, refresh } = useTransfer(token)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [received, setReceived] = useState(false)
  const pendingDownload = useRef<AbortController | null>(null)
  useEffect(() => () => pendingDownload.current?.abort(), [])
  const [content, setContent] = useState<{ text: string; blob: Blob; name: string } | null>(null)
  const download = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError('')
    try {
      if (data?.kind === 'TEXT') {
        const result = await receive(token, password)
        setContent({ text: await result.blob.text(), ...result })
      } else {
        pendingDownload.current = new AbortController()
        await downloadFile(token, password, pendingDownload.current.signal)
      }
      setReceived(true); setPassword(''); await refresh()
    } catch (e) { setError(errorMessage(e)); if (!(e instanceof ApiError && (e.code === 'WRONG_PASSWORD' || e.code === 'PASSWORD_REQUIRED'))) await refresh() }
    finally { setBusy(false) }
  }
  if (loadingError && !data) return <StateCard title={t("Не удалось открыть.")} text={loadingError} go={go} retry={refresh} />
  if (!data) return <div className="glass-panel loading-panel" role="status"><span className="spinner" /> {t("Открываем передачу…")}</div>
  if (!received && unavailable[data.state]) return <StateCard {...unavailable[data.state]} go={go} />
  return <form className="glass-panel receive-panel" onSubmit={download}>
    <span className={`large-file-icon ${received ? 'done' : ''}`}><Icon name={received ? 'check' : data.passwordProtected ? 'lock' : data.kind === 'TEXT' ? 'text' : 'file'} size={36} /></span>
    <p className="eyebrow small">{received ? t("ПЕРЕДАЧА ПОЛУЧЕНА") : data.kind === 'TEXT' ? t("ДЛЯ ВАС ОСТАВИЛИ ТЕКСТ") : t("ДЛЯ ВАС ОСТАВИЛИ ФАЙЛ")}</p>
    <h2>{received ? t("Теперь у вас.") : data.passwordProtected && data.name === 'Защищённая передача' ? t(data.name) : data.kind === 'TEXT' ? t('Текст') : data.name}</h2>
    <p className="panel-description">{received ? data.kind === 'TEXT' ? t("Сохраните то, что нужно. Ссылка живёт по правилам отправителя.") : t("Файл передан браузеру. Проверьте список загрузок. Во встроенном браузере Telegram при необходимости откройте ссылку в Safari или Chrome.") : t("Получите данные, пока действуют условия передачи.")}</p>
    {content ? <><div className="received-text" tabIndex={0} aria-label={t("Полученный текст")}>{content.text}</div><div className="text-result-actions"><button type="button" className="secondary" onClick={() => copy(content.text, t("Текст скопирован"))}><Icon name="copy" size={17} /> {t("Скопировать")}</button><button type="button" className="secondary" onClick={() => saveFile(content.blob, `${content.name}.txt`)}><Icon name="download" size={17} /> {t("Сохранить")}</button></div></> : <div className="receive-facts"><span><Icon name="file" size={15} /> {byteLabel(data.bytes)}</span><span><Icon name="clock" size={15} /> {t("До")} {dateLabel(data.expiresAt)}</span><span><Icon name="download" size={15} /> {t("Осталось:")} {data.remaining}</span></div>}
    {!received && data.passwordProtected && <label className="receive-password"><span>{t("Пароль от отправителя")}</span><input type="password" aria-label={t("Пароль от отправителя")} autoComplete="off" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("Введите пароль")} maxLength={128} required /></label>}
    {loadingError && <p className="error-message" role="alert">{t(loadingError)} <button type="button" className="text-button" onClick={refresh}>{t("Обновить состояние")}</button></p>}
    {error && <p className="error-message" role="alert">{t(error)}</p>}
    {!received ? <button className="primary" disabled={busy || data.state === 'IN_PROGRESS' && !hasPendingDownload(token)}><span>{busy ? data.kind === 'FILE' ? t("Передаём браузеру…") : t("Получаем…") : data.state === 'IN_PROGRESS' && !hasPendingDownload(token) ? t("Передача уже открыта") : data.kind === 'FILE' && hasPendingDownload(token) ? t("Продолжить скачивание") : data.kind === 'TEXT' ? t("Показать текст") : t("Получить файл")}</span>{busy ? <span className="spinner" /> : <Icon name="download" />}</button> : <button type="button" className="secondary wide" onClick={() => go('/')}><Icon name="plus" size={18} /> {t("Создать свою передачу")}</button>}
    {!received && <p className="form-footnote">{data.kind === 'FILE' ? t("При обрыве продолжите скачивание в менеджере браузера. Повтор не расходует дополнительное получение.") : t("При обрыве можно попробовать снова.")}</p>}
  </form>
}
function StateCard({ title, text, go, retry }: { title: string; text: string; go: (path: string) => void; retry?: () => Promise<void> }) {
  return <div className="glass-panel state-card"><span className="large-file-icon"><Icon name="clock" size={34} /></span><h2>{t(title)}</h2><p className="panel-description">{t(text)}</p>{retry && <button className="primary" onClick={retry}>{t("Попробовать снова")} <Icon name="arrow" size={18} /></button>}<button className="secondary wide" onClick={() => go('/')}>{t("Создать передачу")} <Icon name="arrow" size={18} /></button></div>
}
function Management({ token, go }: { token: string; go: (path: string) => void }) {
  const { data, error: loadError, refresh } = useTransfer(token, true)
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const remove = async () => { setBusy(true); try { await revoke(token); await refresh(); setConfirm(false) } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) } }
  if (loadError && !data) return <StateCard title={t("Нет доступа.")} text={loadError} go={go} retry={refresh} />
  if (!data) return <div className="glass-panel loading-panel" role="status"><span className="spinner" /> {t("Открываем управление…")}</div>
  const active = data.state === 'AVAILABLE' || data.state === 'IN_PROGRESS'
  return <div className="glass-panel manage-panel"><span className="large-file-icon"><Icon name="shield" size={34} /></span><p className="eyebrow small">{t("ТОЛЬКО ДЛЯ ОТПРАВИТЕЛЯ")}</p><h2>{data.kind === 'TEXT' ? t('Текст') : data.name}</h2><p className="panel-description">{active ? t("Передача доступна по вашим условиям.") : t(unavailable[data.state]?.text || '')}</p><dl className="manage-facts"><div><dt>{t("Состояние")}</dt><dd><i className={`status-dot ${active ? '' : 'ended'}`} />{active ? data.state === 'IN_PROGRESS' ? t("Получают сейчас") : t("Доступна") : data.state === 'REVOKED' ? t("Отозвана") : data.state === 'EXPIRED' ? t("Срок истёк") : t("Получена")}</dd></div><div><dt>{t("Получений")}</dt><dd>{data.downloads} {t("из")} {data.maxDownloads}</dd></div><div><dt>{t("Действует до")}</dt><dd>{dateLabel(data.expiresAt)}</dd></div><div><dt>{t("Размер")}</dt><dd>{byteLabel(data.bytes)}</dd></div><div><dt>{t("Пароль")}</dt><dd>{data.passwordProtected ? t("Установлен") : t("Без пароля")}</dd></div></dl>{error && <p className="error-message" role="alert">{t(error)}</p>}{active && (confirm ? <div className="revoke-confirm"><p>{t("Закрыть доступ? Вернуть эту ссылку не получится.")}</p><button className="danger" disabled={busy} onClick={remove}>{busy ? t("Закрываем…") : t("Да, отозвать передачу")}</button><button className="text-button" onClick={() => setConfirm(false)}>{t("Оставить доступной")}</button></div> : <button className="secondary wide" onClick={() => setConfirm(true)}><Icon name="close" size={18} /> {t("Отозвать передачу")}</button>)}<button className="text-button manage-new" onClick={() => go('/')}>{t("Новая передача")} <Icon name="arrow" size={16} /></button></div>
}
function Help({ open, close }: { open: boolean; close: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => { if (open) ref.current?.showModal(); else ref.current?.close() }, [open])
  return <dialog ref={ref} className="help-dialog" onClose={close} onClick={(e) => { const rect = e.currentTarget.getBoundingClientRect(); if (e.target === e.currentTarget && (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom)) close() }}><button className="dialog-close" aria-label={t("Закрыть подсказку")} onClick={close}><Icon name="close" /></button><p className="eyebrow small">{t("ПРОСТОЙ ОБМЕН")}</p><h2>{t("На нужное время.")}</h2><div className="help-steps"><div><span>01</span><p><strong>{t("Добавьте файл или текст.")}</strong> {t("Аккаунт не нужен. Выберите срок, число получений и, если нужно, пароль.")}</p></div><div><span>02</span><p><strong>{t("Передайте ссылку.")}</strong> {t("Пароль лучше сообщить отдельно. Ссылку управления оставьте себе.")}</p></div><div><span>03</span><p><strong>{t("Отпустите данные.")}</strong> {t("После срока, лимита или отзыва доступ прекращается. Содержимое удаляется автоматически.")}</p></div></div><p className="help-privacy"><Icon name="shield" size={18} /><span>{t("Содержимое шифруется на сервере. Это защита хранения, а не сквозное шифрование. Получатель может сохранить свою копию.")}</span></p><button className="primary" onClick={close}>{t("Понятно")} <Icon name="check" size={18} /></button></dialog>
}

export default App
