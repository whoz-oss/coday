import { Injectable } from '@angular/core'
import { OverlayContainer } from '@angular/cdk/overlay'

/** The element that carries the AgentOS design tokens (see index.html and styles.scss). */
const SHELL_SELECTOR = 'agentos-shell'

/**
 * Puts the CDK overlay container inside `<agentos-shell>`.
 *
 * By default the CDK appends `.cdk-overlay-container` to `<body>`, so everything it renders (MatDialog, mat-select and
 * mat-autocomplete panels, mat-menu, tooltips, snack bars) lives OUTSIDE `agentos-shell`. The design tokens
 * (`--color-*`, `--radius-*`, `--font-*`, the whole skin selected by `<html data-theme>`) are custom properties scoped
 * under `agentos-shell`: they only flow down to descendants, so an overlay under `<body>` would fall back to the
 * Material defaults. Hosting the container in the shell makes the overlays inherit the tokens, for the three skins, in
 * light and dark, without copying a single value (the alternative, re-applying the tokens on every overlay panel class,
 * is what apps/client does for the kebab menu).
 *
 * Without an `agentos-shell` in the document (unit tests, another host page), it falls back to `<body>`, like the CDK.
 */
@Injectable()
export class ShellOverlayContainer extends OverlayContainer {
  protected override _createContainer(): void {
    super._createContainer()
    const shell = this._document.querySelector(SHELL_SELECTOR)
    // `super` created the container and appended it to <body>: move it, it is the same element (class, platform marker).
    if (shell && this._containerElement) shell.appendChild(this._containerElement)
  }
}
