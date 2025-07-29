import { Component, OnInit, OnDestroy } from '@angular/core'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { CodayService } from '../../core/services/coday.service'
import { ThemeSelectorComponent } from '../theme-selector/theme-selector.component'
import { OptionsPanelComponent } from '../options-panel/options-panel.component'

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [ThemeSelectorComponent, OptionsPanelComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss'
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