/**
 * Service de nettoyage automatique des threads expirés
 * Utilisé uniquement côté serveur
 */

import * as fs from 'fs/promises'
import * as path from 'path'
import * as yaml from 'yaml'
import { CodayLogger } from './coday-logger'

// Configuration du service
const INITIAL_DELAY_MINUTES = 5
const CLEANUP_INTERVAL_HOURS = 24
const TTL_DAYS = 90
const BATCH_SIZE = 100

export class ThreadCleanupService {
  private cleanupTimer: NodeJS.Timeout | null = null
  private isRunning = false

  constructor(
    private readonly projectsConfigPath: string,
    private readonly logger: CodayLogger
  ) {}

  /**
   * Démarre le service de nettoyage
   * Effectue un premier nettoyage après délai initial, puis périodiquement
   */
  async start(): Promise<void> {
    if (this.isRunning) {
      this.log('Thread cleanup service already running')
      return
    }

    this.isRunning = true
    this.log(`Starting thread cleanup service (TTL: ${TTL_DAYS} days)`)

    // Premier nettoyage après délai initial
    setTimeout(
      async () => {
        await this.performCleanup()

        // Puis nettoyage périodique
        this.cleanupTimer = setInterval(
          async () => {
            await this.performCleanup()
          },
          CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000
        )

        this.log(`Thread cleanup scheduled every ${CLEANUP_INTERVAL_HOURS} hours`)
      },
      INITIAL_DELAY_MINUTES * 60 * 1000
    )

    this.log(`Initial cleanup scheduled in ${INITIAL_DELAY_MINUTES} minutes`)
  }

  /**
   * Arrête le service de nettoyage
   */
  async stop(): Promise<void> {
    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer)
      this.cleanupTimer = null
    }
    this.isRunning = false
    this.log('Thread cleanup service stopped')
  }

  /**
   * Effectue le nettoyage des threads expirés
   */
  private async performCleanup(): Promise<void> {
    const startTime = Date.now()
    let totalScanned = 0
    let totalDeleted = 0
    let totalErrors = 0

    try {
      this.log('Starting thread cleanup...')

      // Lister tous les projets existants
      const projectDirs = await fs.readdir(this.projectsConfigPath)
      this.log(`Found ${projectDirs.length} projects to scan`)

      // Nettoyer les threads de chaque projet
      for (const projectName of projectDirs) {
        try {
          const projectPath = path.join(this.projectsConfigPath, projectName)
          const stat = await fs.lstat(projectPath)

          if (!stat.isDirectory()) continue

          const threadsDir = path.join(projectPath, 'threads')

          // Vérifier si le répertoire threads existe
          try {
            await fs.access(threadsDir)
          } catch {
            continue // Pas de répertoire threads pour ce projet
          }

          const { scanned, deleted, errors } = await this.cleanupProjectThreads(projectName, threadsDir)
          totalScanned += scanned
          totalDeleted += deleted
          totalErrors += errors
        } catch (error) {
          this.logError(`Error processing project ${projectName}: ${error}`)
          totalErrors++
        }
      }

      const duration = Date.now() - startTime
      this.log(
        `Cleanup completed: ${totalDeleted}/${totalScanned} threads deleted across all projects in ${duration}ms (${totalErrors} errors)`
      )
    } catch (error) {
      const duration = Date.now() - startTime
      this.logError(`Cleanup failed after ${duration}ms: ${error}`)
    }
  }

  /**
   * Nettoie les threads expirés d'un projet spécifique
   */
  private async cleanupProjectThreads(
    projectName: string,
    threadsDir: string
  ): Promise<{
    scanned: number
    deleted: number
    errors: number
  }> {
    let scanned = 0
    let deleted = 0
    let errors = 0

    try {
      const files = await fs.readdir(threadsDir)
      const threadFiles = files.filter((file) => file.endsWith('.yml'))
      scanned = threadFiles.length

      if (threadFiles.length === 0) {
        return { scanned, deleted, errors }
      }

      this.log(`Scanning ${threadFiles.length} threads in project ${projectName}`)

      // Traiter par lots pour éviter la surcharge
      for (let i = 0; i < threadFiles.length; i += BATCH_SIZE) {
        const batch = threadFiles.slice(i, i + BATCH_SIZE)
        const batchResult = await this.processBatch(batch, threadsDir, projectName)
        deleted += batchResult.deleted
        errors += batchResult.errors
      }

      if (deleted > 0) {
        this.log(`Project ${projectName}: deleted ${deleted}/${scanned} expired threads`)
      }
    } catch (error) {
      this.logError(`Error scanning project ${projectName}: ${error}`)
      errors++
    }

    return { scanned, deleted, errors }
  }

  /**
   * Traite un lot de fichiers de threads
   */
  private async processBatch(
    files: string[],
    threadsDir: string,
    projectName: string
  ): Promise<{
    deleted: number
    errors: number
  }> {
    let deleted = 0
    let errors = 0

    await Promise.all(
      files.map(async (file) => {
        const filePath = path.join(threadsDir, file)
        let threadData: any
        try {
          const content = await fs.readFile(filePath, 'utf-8')
          threadData = yaml.parse(content)
        } catch (error) {
          await fs.unlink(filePath)
          this.logError(`Error processing file ${file} in project ${projectName}: ${error}`)
        }

        if (!threadData || !threadData.modifiedDate) {
          return // Skip invalid files
        }

        // Vérifier si le thread est expiré
        if (this.isThreadExpired(threadData.modifiedDate)) {
          await fs.unlink(filePath)
          deleted++
          // Log de l'audit trail
          await this.logger.logAgentUsage('system', 'ThreadCleanup', 'cleanup', 0)
          console.log(`ThreadCleanup: Deleted expired thread: ${threadData.id || file} from project ${projectName}`)
        }
      })
    )

    return { deleted, errors }
  }

  /**
   * Vérifie si un thread est expiré
   */
  private isThreadExpired(modifiedDate: string): boolean {
    const expirationDate = new Date(modifiedDate)
    expirationDate.setDate(expirationDate.getDate() + TTL_DAYS)
    return new Date() > expirationDate
  }

  /**
   * Logging standard
   */
  private log(message: string): void {
    const timestamp = new Date().toISOString()
    console.log(`[${timestamp}] ThreadCleanup: ${message}`)
    // Note: CodayLogger ne log que les interactions agents, pas les messages généraux
  }

  /**
   * Logging d'erreur
   */
  private logError(message: string): void {
    const timestamp = new Date().toISOString()
    console.error(`[${timestamp}] ThreadCleanup ERROR: ${message}`)
    // Note: CodayLogger ne log que les interactions agents, pas les erreurs
  }

  /**
   * Méthode pour forcer un nettoyage manuel (utile pour tests/debug)
   */
  async forceCleanup(): Promise<void> {
    this.log('Manual cleanup triggered')
    await this.performCleanup()
  }
}
