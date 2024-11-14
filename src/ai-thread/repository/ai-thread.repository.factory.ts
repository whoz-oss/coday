/**
 * @fileoverview Factory for creating ThreadRepository instances based on configuration
 */

import {AiThreadRepository} from "../ai-thread.repository"
import {FileAiThreadRepository} from "./file-ai-thread.repository"
import path from "path"
import {BehaviorSubject, Observable} from "rxjs"
import {map} from "rxjs/operators"
import {ConfigService} from "../../service/config.service"

/**
 * Configuration for thread storage
 */
export interface ThreadStorageConfig {
  type: "file" | "database"  // Extensible for future storage types
  options: {
    // File storage options
    baseDir?: string
    // Future database options can be added here
  }
}

/**
 * Factory for creating and managing ThreadRepository instances based on configuration
 */
export class AiThreadRepositoryFactory {
  private readonly repository$: BehaviorSubject<AiThreadRepository>
  
  constructor(private configService: ConfigService) {
    // Initialize with default file repository
    const defaultRepo = this.createFileRepository(process.cwd())
    this.repository$ = new BehaviorSubject<AiThreadRepository>(defaultRepo)
    
    // Subscribe to config changes
    this.configService.selectedProject$.pipe(
      map(config => this.getStorageConfig(config)),
      map(storageConfig => this.createRepository(storageConfig))
    ).subscribe(repo => this.repository$.next(repo))
  }
  
  /**
   * Get the current thread repository instance
   */
  getCurrentRepository(): AiThreadRepository {
    return this.repository$.getValue()
  }
  
  /**
   * Observable of the current repository, updates when config changes
   */
  getRepository$(): Observable<AiThreadRepository> {
    return this.repository$.asObservable()
  }
  
  /**
   * Extract thread storage configuration from project config
   */
  private getStorageConfig(config: any): ThreadStorageConfig {
    // Default to file storage if not specified
    return {
      type: config?.thread?.storage?.type || "file",
      options: {
        baseDir: config?.thread?.storage?.baseDir || path.join(process.cwd(), ".coday", "threads")
      }
    }
  }
  
  /**
   * Create appropriate repository based on configuration
   */
  private createRepository(config: ThreadStorageConfig): AiThreadRepository {
    switch (config.type) {
      case "file":
        return this.createFileRepository(config.options.baseDir!)
      case "database":
        throw new Error("Database repository not implemented yet")
      default:
        throw new Error(`Unknown storage type: ${(config as any).type}`)
    }
  }
  
  /**
   * Create and initialize a file-based repository
   */
  private createFileRepository(baseDir: string): AiThreadRepository {
    const repo = new FileAiThreadRepository(baseDir)
    // Initialize async but don't wait
    repo.initialize().catch(error => {
      console.error("Failed to initialize file repository:", error)
    })
    return repo
  }
}