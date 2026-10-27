import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog'
import { RunEvent } from '../../../core/models'

export type CodeDiagnosticDialogData = NonNullable<RunEvent['codeDiagnostic']>

@Component({
  selector: 'sf-code-diagnostic-dialog',
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title id="code-diagnostic-title">{{ data.verification || 'Code verification' }}</h2>
    <mat-dialog-content aria-labelledby="code-diagnostic-title">
      <header class="summary">
        <span class="state" [class]="'state state--' + data.state">{{ data.state }}</span>
        @if (data.technical?.['durationSec'] !== undefined) {
          <span>{{ data.technical?.['durationSec'] }}s</span>
        }
        @if (data.exitCode !== undefined) {
          <span>exit {{ data.exitCode }}</span>
        }
      </header>

      @if (data.message || data.code || data.failedTasks?.length) {
        <section>
          <h3>Factory</h3>
          @if (data.message) {
            <p>{{ data.message }}</p>
          }
          @if (data.code) {
            <p class="mono">{{ data.code }}</p>
          }
          @if (data.failedTasks?.length) {
            <p><b>Failed Nx tasks/projects:</b> {{ data.failedTasks!.join(', ') }}</p>
          }
        </section>
      }
      @if (data.command) {
        <section>
          <h3>Command</h3>
          <pre>{{ data.command }}</pre>
        </section>
      }
      @if (data.stderr?.excerpt) {
        <section>
          <h3>
            stderr
            @if (data.stderr!.truncated) {
              <span class="truncated">truncated</span>
            }
          </h3>
          <pre>{{ data.stderr!.excerpt }}</pre>
        </section>
      }
      @if (data.stdout?.excerpt) {
        <section>
          <h3>
            stdout
            @if (data.stdout!.truncated) {
              <span class="truncated">truncated</span>
            }
          </h3>
          <pre>{{ data.stdout!.excerpt }}</pre>
        </section>
      }
      @if (technicalEntries.length) {
        <section>
          <h3>Technical details</h3>
          <dl>
            @for (entry of technicalEntries; track entry[0]) {
              <dt>{{ entry[0] }}</dt>
              <dd>{{ entry[1] }}</dd>
            }
          </dl>
        </section>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content {
      max-height: min(65vh, 680px);
      overflow-y: auto;
    }
    .summary {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 16px;
      color: var(--sf-text-muted);
      font-family: var(--sf-mono);
      font-size: 12px;
    }
    .state {
      padding: 3px 8px;
      border: 1px solid var(--sf-outline);
      border-radius: 999px;
      text-transform: uppercase;
      font-weight: 700;
    }
    .state--passed {
      color: var(--sf-success);
    }
    .state--failed,
    .state--timed_out,
    .state--refused {
      color: var(--sf-error);
    }
    h3 {
      margin: 18px 0 8px;
      font-size: 13px;
    }
    p {
      margin: 6px 0;
    }
    pre,
    .mono,
    dd {
      font-family: var(--sf-mono);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    pre {
      margin: 0;
      padding: 10px;
      border-radius: 6px;
      background: var(--sf-chrome);
      color: var(--sf-text);
      font-size: 12px;
      line-height: 1.5;
    }
    .truncated {
      margin-left: 6px;
      color: var(--sf-tertiary);
      font-size: 11px;
      font-weight: 500;
    }
    dl {
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr);
      gap: 6px 12px;
      margin: 0;
      font-size: 12px;
    }
    dt {
      color: var(--sf-text-muted);
    }
    dd {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CodeDiagnosticDialogComponent {
  protected readonly data = inject<CodeDiagnosticDialogData>(MAT_DIALOG_DATA)
  protected readonly technicalEntries = Object.entries(this.data.technical ?? {}).filter(
    ([key]) => key !== 'durationSec'
  )
}
