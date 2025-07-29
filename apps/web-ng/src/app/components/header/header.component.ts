import { Component, OnInit, OnDestroy } from '@angular/core'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { CodayService } from '../../core/services/coday.service'
import { ThemeSelectorComponent } from '../theme-selector/theme-selector.component'

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [ThemeSelectorComponent],
  template: `
    <header class="header">
      <h1 id="header-title">{{ title }}</h1>
      <div class="header-actions">
        <app-theme-selector></app-theme-selector>
      </div>
    </header>
  `,
  styles: [`
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 1rem;
      background: var(--color-bg-secondary, #f8fafc);
      border-bottom: 1px solid var(--color-border, #e2e8f0);
    }
    
    h1 {
      margin: 0;
      font-size: 1.5rem;
      color: var(--color-text, #1e293b);
    }
    
    .header-actions {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
  `]
})
export class HeaderComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  title = 'Coday'

  constructor(private codayService: CodayService) {}

  ngOnInit(): void {
    this.codayService.projectTitle$
      .pipe(takeUntil(this.destroy$))
      .subscribe(title => {
        this.title = title
        document.title = title
      })
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
}