import { Component, OnInit, OnDestroy } from '@angular/core'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { CodayService } from '../../core/services/coday.service'

@Component({
  selector: 'app-header',
  standalone: true,
  template: `
    <header class="header">
      <h1 id="header-title">{{ title }}</h1>
    </header>
  `,
  styles: [`
    .header {
      padding: 1rem;
      background: #f8fafc;
      border-bottom: 1px solid #e2e8f0;
    }
    
    h1 {
      margin: 0;
      font-size: 1.5rem;
      color: #1e293b;
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