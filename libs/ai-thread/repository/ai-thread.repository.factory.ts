/**
 * @fileoverview Factory for creating ThreadRepository instances based on configuration
 */

import { AiThreadRepository } from '../ai-thread.repository'
import { FileAiThreadRepository } from './file-ai-thread.repository'
import * as path from 'node:path'
import { BehaviorSubject, filter, Observable } from 'rxjs'
import { map } from 'rxjs/operators'
// eslint-disable-next-line @nx/enforce-module-boundaries
import { SelectedProject } from '../../model'
// eslint-disable-next-line @nx/enforce-module-boundaries
import { ProjectService } from '../../service/project.service'

/**
 * Factory for creating and managing ThreadRepository instances based on configuration
 */
export class AiThreadRepositoryFactory {
  private readonly repository$: BehaviorSubject<AiThreadRepository | null>
  repository: Observable<AiThreadRepository | null>

  constructor(private projectService: ProjectService) {
    // Initialize with default file repository
    this.repository$ = new BehaviorSubject<AiThreadRepository | null>(null)
    this.repository = this.repository$.asObservable()

    // Subscribe to config changes
    this.projectService.selectedProject$
      .pipe(
        filter((value) => !!value),
        map((selectedProject) => this.createRepository(selectedProject))
      )
      .subscribe((repo) => {
        this.repository$.next(repo)
      })
  }

  /**
   * Get the current thread repository instance
   */
  getCurrentRepository(): AiThreadRepository | null {
    return this.repository$.getValue()
  }

  /**
   * Create appropriate repository based on configuration
   */
  private createRepository(selectedProject: SelectedProject): AiThreadRepository {
    const storage = selectedProject?.config?.storage
    switch (storage?.type) {
      case 'mongo':
        throw new Error('Mongo database repository not implemented yet')
      case 'postgres':
        throw new Error('Postgres database repository not implemented yet')
      case 'file':
      default:
        if (!selectedProject?.configPath) {
          throw new Error('No selected project with configPath')
        }
        const dir = path.join(selectedProject.configPath, 'threads')
        const repo = new FileAiThreadRepository(dir)
        console.log(`Thread repository created from '${dir}'`)
        return repo
      // throw new Error(`Unknown storage type: ${(selectedProject as any).type}`)
    }
  }
}
