/**
 * Factory Cockpit — artifact governance admin view (`#view-admin`).
 *
 * Vanilla ESM, zero dependencies, zero build step. This is the human operator
 * surface for the three B5 admin commands (all `POST`, all admin-guarded):
 *
 *   - `POST /api/factory/admin/artifacts/gc`                (garbage collection)
 *   - `POST /api/factory/admin/artifacts/:id/purge`         `{ reason? }`
 *   - `POST /api/factory/admin/artifacts/:id/legal-hold`    `{ legalHold, reason? }`
 *
 * AUTHORIZATION IS SERVER-OWNED. The client never asserts admin from headers or
 * from anything it sends: it may only *reflect* a server decision. When the API
 * answers `403 FORBIDDEN_ADMIN_REQUIRED`, the view disables every admin action
 * and renders the escaped server message verbatim — never swallowed, never
 * masked. A known user profile (`trustContext` / `user` / `entitlements`) is
 * used purely as an optional UI gate; an unknown profile fails open and lets
 * the server decide.
 *
 * Destructive commands are confirmed through a native `<dialog>` modal
 * (`showModal` / `closeModal`), matching {@link createDialogConfirm} elsewhere.
 *
 * Lifecycle contract: `mountArtifactAdminView(container, options)` wires the
 * delegated listeners, registers its teardown and returns a handle whose
 * `unmount` is idempotent and leak-free (detaches listeners, empties the
 * container). The module is import-safe in Node: nothing touches `window` or
 * `document` until the view is mounted.
 */

import { esc } from '../components/facts.mjs'

/** Admin garbage-collection route. */
export const ADMIN_GC_PATH = '/api/factory/admin/artifacts/gc'

/** Admin purge route for an artifact id. */
export function buildPurgePath(artifactId) {
  return `/api/factory/admin/artifacts/${encodeURIComponent(artifactId)}/purge`
}

/** Admin legal-hold route for an artifact id. */
export function buildLegalHoldPath(artifactId) {
  return `/api/factory/admin/artifacts/${encodeURIComponent(artifactId)}/legal-hold`
}

/** Machine code rendered when the server refuses an admin command. */
export const FORBIDDEN_ADMIN_REQUIRED = 'FORBIDDEN_ADMIN_REQUIRED'

/**
 * Optional, non-authoritative UI entitlement probe. Returns `true` (unknown →
 * fail open, the server decides), or a strict boolean when the profile carries
 * an explicit admin signal. NEVER reads HTTP headers.
 */
export function isAdminEntitled(profile) {
  if (!profile || typeof profile !== 'object') return true
  if (profile.isAdmin === true || profile.admin === true) return true
  if (profile.isAdmin === false || profile.admin === false) return false
  const roles = Array.isArray(profile.roles) ? profile.roles : []
  const entitlements = Array.isArray(profile.entitlements) ? profile.entitlements : []
  const scopes = Array.isArray(profile.scopes) ? profile.scopes : []
  const values = [...roles, ...entitlements, ...scopes].map((value) => String(value).toLowerCase())
  if (values.length > 0) return values.includes('admin') || values.some((value) => value.startsWith('admin:'))
  return true
}

/** Human-readable byte size. */
export function formatBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return null
  if (bytes < 1024) return `${bytes} o`
  const units = ['Ko', 'Mo', 'Go', 'To']
  let size = bytes / 1024
  let unit = units[0]
  for (let index = 1; index < units.length && size >= 1024; index++) {
    size /= 1024
    unit = units[index]
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${unit}`
}

/** Normalize any thrown error into a structured, escapable descriptor. */
export function describeAdminError(error) {
  const status = Number(error?.status)
  const code = typeof error?.code === 'string' && error.code ? error.code : status ? `HTTP_${status}` : null
  const serverMessage =
    typeof error?.message === 'string' && error.message.trim() ? error.message.trim() : null
  const forbidden = status === 403 || code === FORBIDDEN_ADMIN_REQUIRED
  if (forbidden) {
    const detail = serverMessage ?? 'droits d’administration requis'
    return {
      forbidden: true,
      code: FORBIDDEN_ADMIN_REQUIRED,
      status: status || 403,
      message: `Accès refusé : ${detail} (${FORBIDDEN_ADMIN_REQUIRED})`,
    }
  }
  return {
    forbidden: false,
    code,
    status: Number.isFinite(status) ? status : null,
    message: serverMessage ?? 'Commande admin impossible (erreur réseau ou serveur).',
  }
}

/** Initial view state (exported for tests and renderers). */
export function createAdminState(overrides = {}) {
  return {
    mounted: true,
    adminEntitled: true,
    error: null,
    gc: { dryRun: false, running: false, report: null, error: null },
    purge: { artifactId: '', reason: '', running: false, result: null, error: null },
    legalHold: { artifactId: '', legalHold: true, reason: '', running: false, result: null, error: null },
    ...overrides,
  }
}

function statusChip(entitled) {
  return entitled
    ? '<span class="chip chip-success" data-admin-status="granted">Admin</span>'
    : '<span class="chip chip-fail" data-admin-status="denied">Accès refusé</span>'
}

function renderErrorBanner(error) {
  if (!error) return ''
  const code = error.code ? `<code class="cockpit-id">${esc(error.code)}</code>` : ''
  return (
    '<div class="panel" data-admin-error="true" role="alert">' +
    '<h2 class="panel-title">Erreur d’administration</h2>' +
    `<p class="placeholder">${esc(error.message)}</p>` +
    code +
    '</div>'
  )
}

function renderGcSection(state) {
  const disabled = !state.adminEntitled || state.gc.running ? ' disabled' : ''
  const checked = state.gc.dryRun ? ' checked' : ''
  let result = ''
  if (state.gc.report) {
    const report = state.gc.report
    const reclaimed = Array.isArray(report.reclaimedStagingKeys) ? report.reclaimedStagingKeys.length : 0
    const blobs = Array.isArray(report.scannedBlobKeys) ? report.scannedBlobKeys.length : 0
    const rows = Number.isFinite(report.scannedMetadataRows) ? report.scannedMetadataRows : 0
    const anomalies = Array.isArray(report.anomalies) ? report.anomalies.length : 0
    result =
      '<div class="panel" data-admin-gc-result="true">' +
      `<span class="chip" data-gc-reclaimed="${reclaimed}">staging recyclés : ${reclaimed}</span>` +
      `<span class="chip" data-gc-blobs="${blobs}">blobs scannés : ${blobs}</span>` +
      `<span class="chip" data-gc-rows="${rows}">lignes métadonnées : ${rows}</span>` +
      `<span class="chip ${anomalies ? 'chip-fail' : 'chip-success'}" data-gc-anomalies="${anomalies}">anomalies : ${anomalies}</span>` +
      (report.timestamp ? `<span class="cockpit-id">${esc(report.timestamp)}</span>` : '') +
      '</div>'
  } else if (state.gc.error) {
    result =
      '<div class="panel" data-admin-gc-error="true"><p class="placeholder">' +
      `${esc(state.gc.error.message)}</p></div>`
  }

  return (
    '<div class="panel" data-admin-gc="true">' +
    '<h2 class="panel-title">Garbage collection</h2>' +
    '<p class="placeholder">Réconcilie le stockage objet et les lignes de métadonnées ; recycle les uploads de staging orphelins.</p>' +
    `<label class="chip" data-admin-gc-dry-run-label="true"><input type="checkbox" name="dryRun" data-admin-gc-dry-run="true"${checked}${disabled}/> Dry-run</label>` +
    '<div class="form-actions">' +
    `<button type="button" class="btn btn-primary" data-admin-gc-run="true"${disabled}>${
      state.gc.running ? 'GC en cours…' : 'Lancer GC'
    }</button>` +
    '</div>' +
    result +
    '</div>'
  )
}

function renderPurgeSection(state) {
  const disabled = !state.adminEntitled || state.purge.running ? ' disabled' : ''
  let result = ''
  if (state.purge.result) {
    const purged = state.purge.result
    const freed = purged?.metadata ? formatBytes(purged.metadata.size) : null
    result =
      '<div class="panel" data-admin-purge-result="true">' +
      `<span class="chip chip-success" data-purge-status="${esc(purged.status ?? 'purged')}">${esc(
        purged.status ?? 'purged'
      )}</span>` +
      `<span class="cockpit-id">${esc(purged.artifactId ?? '')}</span>` +
      (freed ? `<span class="chip" data-purge-freed="${esc(String(purged.metadata.size))}">libéré : ${esc(freed)}</span>` : '') +
      (purged.reason ? `<span class="chip">motif : ${esc(purged.reason)}</span>` : '') +
      '</div>'
  } else if (state.purge.error) {
    result =
      '<div class="panel" data-admin-purge-error="true"><p class="placeholder">' +
      `${esc(state.purge.error.message)}</p></div>`
  }

  return (
    '<div class="panel" data-admin-purge="true">' +
    '<h2 class="panel-title">Purger un artefact</h2>' +
    '<div class="form-group">' +
    '<label for="admin-purge-id">Identifiant d’artefact</label>' +
    `<input id="admin-purge-id" class="mono" name="artifactId" data-admin-purge-id="true" value="${esc(
      state.purge.artifactId
    )}"${disabled}/>` +
    '</div>' +
    '<div class="form-group">' +
    '<label for="admin-purge-reason">Motif (optionnel)</label>' +
    `<input id="admin-purge-reason" class="mono" name="reason" data-admin-purge-reason="true" value="${esc(
      state.purge.reason
    )}"${disabled}/>` +
    '</div>' +
    '<div class="form-actions">' +
    `<button type="button" class="btn btn-danger" data-admin-purge-submit="true"${disabled}>${
      state.purge.running ? 'Purge…' : "Purger l’artefact"
    }</button>` +
    '</div>' +
    result +
    '</div>'
  )
}

function renderLegalHoldSection(state) {
  const disabled = !state.adminEntitled || state.legalHold.running ? ' disabled' : ''
  const checked = state.legalHold.legalHold ? ' checked' : ''
  let result = ''
  if (state.legalHold.result) {
    const metadata = state.legalHold.result
    const active = metadata?.legalHold === true
    result =
      '<div class="panel" data-admin-legal-result="true">' +
      `<span class="chip ${active ? 'chip-fail' : 'chip-success'}" data-legal-hold="${active}">legal hold : ${
        active ? 'actif' : 'inactif'
      }</span>` +
      `<span class="cockpit-id">${esc(metadata?.id ?? state.legalHold.artifactId)}</span>` +
      (metadata?.legalHoldReason ? `<span class="chip">motif : ${esc(metadata.legalHoldReason)}</span>` : '') +
      '</div>'
  } else if (state.legalHold.error) {
    result =
      '<div class="panel" data-admin-legal-error="true"><p class="placeholder">' +
      `${esc(state.legalHold.error.message)}</p></div>`
  }

  return (
    '<div class="panel" data-admin-legal="true">' +
    '<h2 class="panel-title">Legal hold</h2>' +
    '<div class="form-group">' +
    '<label for="admin-legal-id">Identifiant d’artefact</label>' +
    `<input id="admin-legal-id" class="mono" name="artifactId" data-admin-legal-id="true" value="${esc(
      state.legalHold.artifactId
    )}"${disabled}/>` +
    '</div>' +
    `<label class="chip" data-admin-legal-state-label="true"><input type="checkbox" name="legalHold" data-admin-legal-state="true"${checked}${disabled}/> Placer le legal hold</label>` +
    '<div class="form-group">' +
    '<label for="admin-legal-reason">Motif (optionnel)</label>' +
    `<input id="admin-legal-reason" class="mono" name="reason" data-admin-legal-reason="true" value="${esc(
      state.legalHold.reason
    )}"${disabled}/>` +
    '</div>' +
    '<div class="form-actions">' +
    `<button type="button" class="btn btn-primary" data-admin-legal-submit="true"${disabled}>${
      state.legalHold.running ? 'Application…' : 'Appliquer le legal hold'
    }</button>` +
    '</div>' +
    result +
    '</div>'
  )
}

/** Render the full admin view as an escaped HTML string. */
export function renderArtifactAdmin(state = createAdminState()) {
  const header =
    '<div class="panel" data-admin-head="true">' +
    '<h2 class="panel-title">Gouvernance des artefacts</h2>' +
    `<div class="metrics" style="display:flex;gap:6px;flex-wrap:wrap">${statusChip(state.adminEntitled)}` +
    '<span class="chip">B5 · admin</span></div>' +
    '</div>'

  return (
    '<div class="artifact-admin" data-artifact-admin="true">' +
    header +
    renderErrorBanner(state.error) +
    renderGcSection(state) +
    renderPurgeSection(state) +
    renderLegalHoldSection(state) +
    '</div>'
  )
}

/** Read the admin profile from whichever option the host supplies. */
function resolveProfile(options) {
  return options.profile ?? options.user ?? options.trustContext ?? null
}

/**
 * Build the default native-`<dialog>` confirmation flow, mirroring the
 * projection controller's {@link createDialogConfirm} pattern. Resolves `false`
 * when no document/host is available, so a destructive command is never sent
 * without an explicit confirmation.
 */
export function createArtifactConfirm({ doc, showModal, closeModal } = {}) {
  return ({ label, warning, detail }) =>
    new Promise((resolve) => {
      if (!doc) {
        resolve(false)
        return
      }
      const host = doc.getElementById?.('cockpit-dialog-content')
      const dialog = doc.getElementById?.('cockpit-dialog')
      if (!host || !dialog) {
        resolve(false)
        return
      }
      const content =
        `<div class="dialog-body"><h3>${esc(label)}</h3>` +
        `<p>${esc(warning)}</p>` +
        (detail ? `<code class="cockpit-id">${esc(detail)}</code>` : '') +
        '</div>' +
        '<div class="dialog-actions">' +
        '<button type="button" data-dialog-action="cancel">Annuler</button>' +
        '<button type="button" class="btn-danger" data-dialog-action="confirm">Confirmer</button>' +
        '</div>'

      let settled = false
      const finish = (value) => {
        if (settled) return
        settled = true
        host.removeEventListener?.('click', onHostClick)
        try {
          if (typeof closeModal === 'function') closeModal()
          else dialog.close?.()
        } catch {
          // Closing an already-closed dialog is a no-op.
        }
        resolve(value)
      }
      const onHostClick = (event) => {
        const button = event?.target?.closest?.('[data-dialog-action]')
        if (!button) return
        finish(button.dataset?.dialogAction === 'confirm')
      }

      if (typeof showModal === 'function') showModal(content)
      else {
        host.innerHTML = content
        dialog.showModal?.()
      }
      host.addEventListener('click', onHostClick)
    })
}

function readInputValue(event) {
  const target = event?.target
  if (!target) return null
  const dataset = target.dataset ?? {}
  if (dataset.adminPurgeId !== undefined) return { field: 'purge:artifactId', value: target.value ?? '' }
  if (dataset.adminPurgeReason !== undefined) return { field: 'purge:reason', value: target.value ?? '' }
  if (dataset.adminLegalId !== undefined) return { field: 'legalHold:artifactId', value: target.value ?? '' }
  if (dataset.adminLegalReason !== undefined) return { field: 'legalHold:reason', value: target.value ?? '' }
  if (dataset.adminGcDryRun !== undefined) return { field: 'gc:dryRun', value: target.checked === true }
  if (dataset.adminLegalState !== undefined) return { field: 'legalHold:legalHold', value: target.checked === true }
  return null
}

/**
 * Mount the artifact admin view.
 *
 * @param {any} container element-like target (typically `#view-admin`)
 * @param {{
 *   apiClient: { post: Function },
 *   profile?: object, user?: object, trustContext?: object,
 *   confirm?: (request: object) => Promise<boolean>,
 *   document?: Document, showModal?: Function, closeModal?: Function,
 *   registerTeardown?: (fn: Function) => void,
 * }} [options]
 * @returns {{ unmount: Function, render: Function, getState: Function, isMounted: Function,
 *   runGc: Function, purgeArtifact: Function, setLegalHold: Function }}
 */
export function mountArtifactAdminView(container, options = {}) {
  if (!container || typeof container !== 'object') throw new TypeError('artifact-admin.mount requires a container')
  const apiClient = options.apiClient
  if (!apiClient || typeof apiClient.post !== 'function') {
    throw new TypeError('artifact-admin.mount requires an apiClient with a post() method')
  }

  const doc = options.document ?? globalThis.document ?? null
  const confirm =
    typeof options.confirm === 'function'
      ? options.confirm
      : createArtifactConfirm({ doc, showModal: options.showModal, closeModal: options.closeModal })

  const state = createAdminState({ adminEntitled: isAdminEntitled(resolveProfile(options)) })

  const render = () => {
    if (!state.mounted) return
    container.innerHTML = renderArtifactAdmin(state)
  }

  const applyError = (error, section) => {
    const described = describeAdminError(error)
    if (described.forbidden) state.adminEntitled = false
    if (section && state[section]) state[section].error = described
    else state.error = described
    if (described.forbidden) state.error = described
    return described
  }

  const runGc = async (overrides = {}) => {
    if (!state.mounted || state.gc.running) return null
    const dryRun = overrides.dryRun ?? state.gc.dryRun
    state.gc.running = true
    state.gc.error = null
    state.error = null
    render()
    try {
      const body = dryRun ? { dryRun: true } : {}
      const report = await apiClient.post(ADMIN_GC_PATH, body)
      if (!state.mounted) return null
      state.gc.report = report ?? {}
      return report
    } catch (error) {
      if (!state.mounted) return null
      applyError(error, 'gc')
      return null
    } finally {
      if (state.mounted) {
        state.gc.running = false
        render()
      }
    }
  }

  const purgeArtifact = async (overrides = {}) => {
    if (!state.mounted || state.purge.running) return null
    const artifactId = String(overrides.artifactId ?? state.purge.artifactId ?? '').trim()
    const reason = String(overrides.reason ?? state.purge.reason ?? '').trim()
    if (!artifactId) {
      state.purge.error = { forbidden: false, code: null, status: null, message: 'Identifiant d’artefact requis.' }
      render()
      return null
    }
    state.purge.running = true
    state.purge.error = null
    state.purge.result = null
    state.error = null
    render()
    try {
      const approved = await confirm({
        action: 'purge',
        label: 'Purger l’artefact',
        warning: 'Suppression définitive et irréversible. Le legal hold et la rétention doivent avoir expiré.',
        detail: artifactId,
      })
      if (!approved) {
        state.purge.running = false
        render()
        return null
      }
      const body = reason ? { reason } : {}
      const result = await apiClient.post(buildPurgePath(artifactId), body)
      if (!state.mounted) return null
      state.purge.result = result ?? { status: 'purged', artifactId, reason }
      return state.purge.result
    } catch (error) {
      if (!state.mounted) return null
      applyError(error, 'purge')
      return null
    } finally {
      if (state.mounted) {
        state.purge.running = false
        render()
      }
    }
  }

  const setLegalHold = async (overrides = {}) => {
    if (!state.mounted || state.legalHold.running) return null
    const artifactId = String(overrides.artifactId ?? state.legalHold.artifactId ?? '').trim()
    const legalHold = overrides.legalHold ?? state.legalHold.legalHold
    const reason = String(overrides.reason ?? state.legalHold.reason ?? '').trim()
    if (!artifactId) {
      state.legalHold.error = { forbidden: false, code: null, status: null, message: 'Identifiant d’artefact requis.' }
      render()
      return null
    }
    state.legalHold.running = true
    state.legalHold.error = null
    state.legalHold.result = null
    state.error = null
    render()
    try {
      const body = { legalHold: legalHold === true }
      if (reason) body.reason = reason
      const metadata = await apiClient.post(buildLegalHoldPath(artifactId), body)
      if (!state.mounted) return null
      state.legalHold.result = metadata ?? { id: artifactId, legalHold: legalHold === true }
      return state.legalHold.result
    } catch (error) {
      if (!state.mounted) return null
      applyError(error, 'legalHold')
      return null
    } finally {
      if (state.mounted) {
        state.legalHold.running = false
        render()
      }
    }
  }

  const onClick = (event) => {
    const target = event?.target
    const closest = typeof target?.closest === 'function' ? (selector) => target.closest(selector) : () => null
    if (closest('[data-admin-gc-run]')) {
      void runGc()
      return
    }
    if (closest('[data-admin-purge-submit]')) {
      void purgeArtifact()
      return
    }
    if (closest('[data-admin-legal-submit]')) {
      void setLegalHold()
    }
  }

  const onInput = (event) => {
    const entry = readInputValue(event)
    if (!entry) return
    const [section, field] = entry.field.split(':')
    if (state[section]) state[section][field] = entry.value
  }

  container.addEventListener?.('click', onClick)
  container.addEventListener?.('input', onInput)
  container.addEventListener?.('change', onInput)

  let unmounted = false
  const unmount = () => {
    if (unmounted) return
    unmounted = true
    state.mounted = false
    container.removeEventListener?.('click', onClick)
    container.removeEventListener?.('input', onInput)
    container.removeEventListener?.('change', onInput)
    container.innerHTML = ''
    state.error = null
    state.gc = { dryRun: false, running: false, report: null, error: null }
    state.purge = { artifactId: '', reason: '', running: false, result: null, error: null }
    state.legalHold = { artifactId: '', legalHold: true, reason: '', running: false, result: null, error: null }
  }

  if (typeof options.registerTeardown === 'function') options.registerTeardown(unmount)

  render()

  return {
    unmount,
    render,
    runGc,
    purgeArtifact,
    setLegalHold,
    getState: () => state,
    isMounted: () => state.mounted,
  }
}

/** Alias matching the cockpit view naming convention. */
export const mount = mountArtifactAdminView

export default mountArtifactAdminView
