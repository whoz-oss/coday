import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  Output,
  ViewChild,
} from '@angular/core'
import { MatDivider } from '@angular/material/divider'
import { MatIcon } from '@angular/material/icon'
import { MatMenu, MatMenuModule } from '@angular/material/menu'
import { IconButtonComponent } from '../icon-button/icon-button.component'

export interface KebabMenuItem {
  /** Unique key identifying this action — emitted on selection. */
  key: string
  /** Display label shown in the menu. */
  label: string
  /** Optional Material icon name shown before the label. */
  icon?: string
  /** Visual variant — 'danger' renders the item in error color, 'separator' renders a divider (label and key are ignored). */
  variant?: 'default' | 'danger' | 'separator'
  /** When true the item is shown but cannot be clicked. */
  disabled?: boolean
}

/**
 * DsKebabMenu — a `more_vert` icon button that opens a Material overlay menu.
 *
 * Purely presentational: receives items as input, emits the selected item key.
 * No routing, no service injection, no business logic.
 *
 * CSS contract: relies on --color-error from the host theme.
 * The danger variant is styled via a global class injected through MatMenu panelClass.
 *
 * @example
 * <ds-kebab-menu [items]="menuItems" (itemSelected)="onAction($event)" />
 */
@Component({
  selector: 'ds-kebab-menu',
  standalone: true,
  imports: [IconButtonComponent, MatDivider, MatIcon, MatMenuModule],
  templateUrl: './kebab-menu.component.html',
  styleUrl: './kebab-menu.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KebabMenuComponent implements AfterViewInit {
  @Input({ required: true }) items!: KebabMenuItem[]

  /** Emits the `key` of the selected menu item. */
  @Output() itemSelected = new EventEmitter<string>()

  @ViewChild('menu') private menu!: MatMenu

  ngAfterViewInit(): void {
    this.menu.panelClass = 'ds-kebab-panel'
  }

  protected onItemClick(key: string): void {
    this.itemSelected.emit(key)
  }
}
