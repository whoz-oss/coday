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

/** Message type posted by the bridge of the viewer's HTML preview when the user selects text. */
export const EXCHANGE_SELECTION_MESSAGE_TYPE = 'agentos-exchange-selection'
/** Posted by the bridge once its document is loaded: the parent then (re)sends the edit and highlight state. */
export const EXCHANGE_READY_MESSAGE_TYPE = 'agentos-exchange-ready'
/** Posted by the bridge, debounced, with the serialized document after the user typed in it. */
export const EXCHANGE_HTML_MESSAGE_TYPE = 'agentos-exchange-html'
/** Posted by the bridge after a replace request: `ok` tells whether the passage was found. */
export const EXCHANGE_REPLACE_RESULT_MESSAGE_TYPE = 'agentos-exchange-replace-result'
/** Sent by the parent: `{ enabled }` turns the in-place editing of the document on or off. */
export const EXCHANGE_EDIT_MESSAGE_TYPE = 'agentos-exchange-edit'
/** Sent by the parent: `{ text }` highlights that passage (null removes the highlight). */
export const EXCHANGE_HIGHLIGHT_MESSAGE_TYPE = 'agentos-exchange-highlight'
/** Sent by the parent: `{ from, to }` replaces the passage `from` of the document with the text `to`. */
export const EXCHANGE_REPLACE_MESSAGE_TYPE = 'agentos-exchange-replace'
/** Posted by the bridge on Ctrl/Cmd+S inside the document, after the latest html: the parent then saves. */
export const EXCHANGE_SAVE_MESSAGE_TYPE = 'agentos-exchange-save'
/** Posted by the bridge after a highlight: `bottom` is where the highlighted passage ends, in the iframe viewport. */
export const EXCHANGE_ANCHOR_MESSAGE_TYPE = 'agentos-exchange-anchor'

/** Id of the injected script and of the injected highlight style: both are removed when the document is serialized. */
export const EXCHANGE_BRIDGE_SCRIPT_ID = 'agentos-exchange-bridge'
export const EXCHANGE_HIGHLIGHT_STYLE_ID = 'agentos-exchange-highlight-style'

/**
 * Body of `function(doc)`: the document as it is saved, without anything the bridge added to it (its script, its
 * highlight style, the `<mark data-agentos-selection>` wrapping the frozen selection). Kept as source text so that
 * the very same code runs in the iframe and in the unit test, instead of two copies that could drift apart.
 */
export const EXCHANGE_SERIALIZE_SOURCE =
  'var c=doc.documentElement.cloneNode(true);' +
  'var s=c.querySelectorAll("#' +
  EXCHANGE_BRIDGE_SCRIPT_ID +
  ',#' +
  EXCHANGE_HIGHLIGHT_STYLE_ID +
  '");' +
  'for(var i=0;i<s.length;i++)s[i].parentNode.removeChild(s[i]);' +
  'var m=c.querySelectorAll("mark[data-agentos-selection]");' +
  'for(var j=0;j<m.length;j++){var p=m[j].parentNode;while(m[j].firstChild)p.insertBefore(m[j].firstChild,m[j]);p.removeChild(m[j])}' +
  'return "<!DOCTYPE html>\\n"+c.outerHTML'

const BRIDGE_SOURCE = [
  'var M="agentos-exchange-",HL="agentos-selection",st,ht,range=null,last=null,editing=false;',
  'function post(m){try{parent.postMessage(m,"*")}catch(e){}}',
  'function serialize(doc){' + EXCHANGE_SERIALIZE_SOURCE + '}',
  // Selection reporting, debounced. The last non-empty range is kept: the selection may collapse once the user
  // moves to the composer of the parent, and the highlight must still find it.
  'document.addEventListener("selectionchange",function(){clearTimeout(st);st=setTimeout(function(){' +
    'var s=getSelection();if(s&&s.rangeCount&&String(s)){last=s.getRangeAt(0).cloneRange()}' +
    'post({type:"' +
    EXCHANGE_SELECTION_MESSAGE_TYPE +
    '",text:String(getSelection()||"")})},150)});',
  // Ctrl/Cmd+S saves, as in the other editors.
  'document.addEventListener("keydown",function(e){if(editing&&(e.ctrlKey||e.metaKey)&&String(e.key).toLowerCase()==="s"){' +
    'e.preventDefault();clearTimeout(ht);sendHtml();post({type:"' +
    EXCHANGE_SAVE_MESSAGE_TYPE +
    '"})}});',
  // Edited document, debounced.
  'document.addEventListener("input",function(){if(!editing)return;clearTimeout(ht);ht=setTimeout(sendHtml,300)});',
  'function sendHtml(){post({type:"' + EXCHANGE_HTML_MESSAGE_TYPE + '",html:serialize(document)})}',
  // Highlight of the frozen selection.
  'function clearHl(){try{if(window.CSS&&CSS.highlights)CSS.highlights.delete(HL)}catch(e){}' +
    'var m=document.querySelectorAll("mark[data-agentos-selection]");' +
    'for(var i=0;i<m.length;i++){var p=m[i].parentNode;while(m[i].firstChild)p.insertBefore(m[i].firstChild,m[i]);p.removeChild(m[i])}' +
    'var s=document.getElementById("' +
    EXCHANGE_HIGHLIGHT_STYLE_ID +
    '");if(s&&s.parentNode)s.parentNode.removeChild(s);range=null}',
  'function find(text){var t0=last?String(last):"";if(last&&(t0===text||t0.trim()===text))return last.cloneRange();' +
    'var sel=getSelection();if(sel&&sel.rangeCount){var r=sel.getRangeAt(0);var t=String(r);' +
    'if(t===text||t.trim()===text)return r.cloneRange()}' +
    'var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_TEXT),nodes=[],all="",n;' +
    'while(n=w.nextNode()){nodes.push({n:n,s:all.length});all+=n.nodeValue}' +
    'var at=all.indexOf(text);if(at<0)return null;' +
    'function loc(p){for(var i=nodes.length-1;i>=0;i--)if(nodes[i].s<=p)return {n:nodes[i].n,o:p-nodes[i].s};return null}' +
    'var a=loc(at),b=loc(at+text.length-1);if(!a||!b)return null;' +
    'var r2=document.createRange();r2.setStart(a.n,a.o);r2.setEnd(b.n,b.o+1);return r2}',
  'function highlight(text){clearHl();if(!text)return;var r=find(text);if(!r)return;range=r;' +
    'var rc=r.getBoundingClientRect();post({type:"' +
    EXCHANGE_ANCHOR_MESSAGE_TYPE +
    '",bottom:rc.bottom});' +
    'if(window.CSS&&CSS.highlights&&window.Highlight){var s=document.createElement("style");s.id="' +
    EXCHANGE_HIGHLIGHT_STYLE_ID +
    '";s.textContent="::highlight("+HL+"){background:#ffe14d;color:#000}";(document.head||document.documentElement).appendChild(s);' +
    'CSS.highlights.set(HL,new Highlight(r))}' +
    'else{try{var k=document.createElement("mark");k.setAttribute("data-agentos-selection","");' +
    'k.style.background="#ffe14d";k.style.color="#000";r.surroundContents(k);range=document.createRange();range.selectNodeContents(k)}catch(e){}}}',
  'function replace(from,to){var r=range;if(!r||String(r).trim()!==from)r=find(from);' +
    'if(!r){post({type:"' +
    EXCHANGE_REPLACE_RESULT_MESSAGE_TYPE +
    '",ok:false});return}' +
    'r.deleteContents();r.insertNode(document.createTextNode(to));clearHl();sendHtml();post({type:"' +
    EXCHANGE_REPLACE_RESULT_MESSAGE_TYPE +
    '",ok:true})}',
  // Messages from the parent only.
  'addEventListener("message",function(e){if(e.source!==parent)return;var d=e.data;if(!d||typeof d.type!=="string")return;' +
    'try{if(d.type==="' +
    EXCHANGE_EDIT_MESSAGE_TYPE +
    '"){editing=d.enabled===true;document.designMode=editing?"on":"off"}' +
    'else if(d.type==="' +
    EXCHANGE_HIGHLIGHT_MESSAGE_TYPE +
    '"){highlight(typeof d.text==="string"?d.text:null)}' +
    'else if(d.type==="' +
    EXCHANGE_REPLACE_MESSAGE_TYPE +
    '"&&typeof d.from==="string"&&typeof d.to==="string"){replace(d.from,d.to)}}catch(x){}});',
  'post({type:"' + EXCHANGE_READY_MESSAGE_TYPE + '"});',
].join('')

const SELECTION_BRIDGE_SCRIPT =
  '<script id="' + EXCHANGE_BRIDGE_SCRIPT_ID + '">(function(){try{' + BRIDGE_SOURCE + '}catch(e){}})()</script>'

/**
 * Adds to an HTML document a tiny script that reports the user's text selection to the parent window, which
 * cannot read it itself (the preview iframe has an opaque origin), and that edits the document in place on request.
 * Used ONLY for the srcdoc of the viewer's preview iframe: the new tab and the full view keep the original document.
 *
 * What the bridge does, on the parent's messages (accepted from `parent` only):
 * - `agentos-exchange-edit {enabled}`: `document.designMode` on/off, so the user types in the rendered page. On
 *   `input`, debounced, it posts `agentos-exchange-html {html}`: the serialized document with the bridge removed.
 * - `agentos-exchange-highlight {text}`: highlights the frozen selection (CSS Custom Highlight, `<mark>` fallback).
 * - `agentos-exchange-replace {from, to}`: replaces the passage `from` with the text `to` (an accepted proposal).
 *
 * Why this is safe: the sandbox invariant is unchanged (no `allow-same-origin`). The file's own scripts could post
 * fake messages of these types, but the payload is the user's own file content; it is only SAVED on an explicit user
 * action (Save), never applied on its own, and nothing from it is injected into the DOM of the app (the parent
 * keeps it as a string, and the iframe stays sandboxed). The selection text only pre-fills a selection the user must
 * still confirm. The script is wrapped in try/catch so that it can never break the scripts of the page.
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
