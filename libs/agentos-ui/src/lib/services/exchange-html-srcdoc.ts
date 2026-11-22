import { DomSanitizer, SafeHtml, SafeResourceUrl } from '@angular/platform-browser'

/**
 * Lets an HTML file be used as the `srcdoc` of a sandboxed iframe.
 *
 * Angular's DOM security schema treats `iframe[srcdoc]` as an HTML context, so a plain string binding is
 * sanitized and every <script> of the file is stripped, which defeats the preview of a report.
 *
 * SECURITY INVARIANT: bypassing the sanitizer is acceptable ONLY because the receiving iframe carries the static
 * `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"` WITHOUT `allow-same-origin`: the document
 * then runs in an opaque origin, with no access to the app's cookies, storage or DOM. Never use this result
 * anywhere else (innerHTML, another iframe, ...) and never add `allow-same-origin` / `allow-top-navigation` to
 * the sandbox of a frame fed by it.
 *
 * No CSP <meta> is injected into the document on purpose: a srcdoc iframe inherits the CSP of the parent page,
 * and one more restrictive policy would block the very scripts the preview is meant to run.
 *
 * Known limitation (out of scope): relative resources (`<img src="img/a.png">`) cannot resolve from a srcdoc.
 */
export function trustSandboxedSrcdoc(sanitizer: DomSanitizer, raw: string): SafeHtml {
  return sanitizer.bypassSecurityTrustHtml(raw)
}

/** Message type posted by the selection bridge of the viewer's HTML preview. */
export const EXCHANGE_SELECTION_MESSAGE_TYPE = 'agentos-exchange-selection'

const SELECTION_BRIDGE_SCRIPT =
  '<script>(function(){try{var t;document.addEventListener("selectionchange",function(){' +
  'clearTimeout(t);t=setTimeout(function(){try{parent.postMessage({type:"' +
  EXCHANGE_SELECTION_MESSAGE_TYPE +
  '",text:String(getSelection()||"")},"*")}catch(e){}},150)})}catch(e){}})()</script>'

/**
 * Adds to an HTML document a tiny script that reports the user's text selection to the parent window, which
 * cannot read it itself (the preview iframe has an opaque origin). Used ONLY for the srcdoc of the viewer's
 * preview iframe: the new tab and the full view keep the original document.
 *
 * Why this is safe: the sandbox invariant is unchanged (no `allow-same-origin`). The file's own scripts could
 * post fake messages of this type, but the payload is plain text that only pre-fills a selection which the user
 * must still confirm (instruction + Run); it is never inserted in the DOM as HTML. The script is wrapped in
 * try/catch so that it can never break the scripts of the page.
 *
 * The script goes before the last `</body>` (case-insensitive), or at the end when there is none.
 */
export function withSelectionBridge(html: string): string {
  const closing = [...html.matchAll(/<\/body\s*>/gi)].pop()
  if (closing?.index === undefined) return html + SELECTION_BRIDGE_SCRIPT
  return html.slice(0, closing.index) + SELECTION_BRIDGE_SCRIPT + html.slice(closing.index)
}

/**
 * Lets the object URL of a PDF be the `src` of an iframe, which Angular otherwise refuses (resource URL context).
 *
 * This iframe carries NO `sandbox` attribute: browsers do not run their built-in PDF viewer inside a sandboxed frame
 * (it stays blank). That is acceptable because what loads is a `blob:` URL created by this app from the bytes of the
 * download endpoint, typed `application/pdf` (see `ensurePdfBlob`), and rendered by the browser's PDF viewer, never
 * parsed as HTML. Hence the guard: anything that is not a `blob:` URL gets no trusted URL (null, so the frame stays
 * empty), and this must never be used for HTML
 * (an HTML blob URL would run with the origin of the app: HTML goes through `trustSandboxedSrcdoc` only).
 */
export function trustPdfBlobUrl(sanitizer: DomSanitizer, url: string | null): SafeResourceUrl | null {
  if (!url?.startsWith('blob:')) return null
  return sanitizer.bypassSecurityTrustResourceUrl(url)
}
