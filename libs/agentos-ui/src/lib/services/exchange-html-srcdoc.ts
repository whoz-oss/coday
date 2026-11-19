import { DomSanitizer, SafeHtml } from '@angular/platform-browser'

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
