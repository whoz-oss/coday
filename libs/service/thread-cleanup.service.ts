/**
 * Service de nettoyage automatique des threads expirés
 * Utilisé uniquement côté serveur pour la conformité RGPD
 */

import * as fs from 'fs/promises'
import * as path from 'path'
import * as yaml from 'yaml'
import { AiThreadRepositoryFactory } from '../ai-thread/repository/ai-thread.repository.factory'

// Configuration du service
const INITIAL_DELAY_MINUTES = 5
const CLEANUP_INTERVAL_HOURS = 24
const TTL_DAYS = 90
const BATCH_SIZE = 100

interface CleanupResult {
  scannedFiles: number
  deletedFiles: number
  errors: string[]
  duration: number
  deletedThreads: string[] // IDs des threads supprimés pour audit
}

export class ThreadCleanupService {
  private cleanupTimer: NodeJS.Timeout | null = null
  private isRunning = false
  private logger?: any // TODO: Typer avec le service de log serveur

  constructor(
    private readonly repositoryFactory: AiThreadRepositoryFactory,
    logger?: any
  ) {
    this.logger = logger
  }

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
    setTimeout(async () => {
      await this.performCleanup()

      // Puis nettoyage périodique
      this.cleanupTimer = setInterval(async () => {
        await this.performCleanup()
      }, CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000)

      this.log(`Thread cleanup scheduled every ${CLEANUP_INTERVAL_HOURS} hours`)
    }, INITIAL_DELAY_MINUTES * 60 * 1000)

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
  private async performCleanup(): Promise<CleanupResult> {
    const startTime = Date.now()
    const result: CleanupResult = {
      scannedFiles: 0,
      deletedFiles: 0,
      errors: [],
      duration: 0,
      deletedThreads: []
    }

    try {
      this.log('Starting thread cleanup...')

      // Obtenir le repository pour accéder aux fichiers
      const repository = await this.repositoryFactory.repository.pipe().toPromise()
      if (!repository) {
        throw new Error('Repository not available')
      }

      // Accéder au répertoire des threads (assuming FileAiThreadRepository)
      const threadsDir = (repository as any).threadsDir
      if (!threadsDir) {
        throw new Error('Cannot access threads directory')
      }

      // Scanner tous les fichiers de threads
      const files = await fs.readdir(threadsDir)
      const threadFiles = files.filter(file => file.endsWith('.yml'))
      
      result.scannedFiles = threadFiles.length

      // Traiter par lots pour éviter la surcharge
      for (let i = 0; i < threadFiles.length; i += BATCH_SIZE) {
        const batch = threadFiles.slice(i, i + BATCH_SIZE)
        await this.processBatch(batch, threadsDir, result)
      }

      result.duration = Date.now() - startTime

      this.log(`Cleanup completed: ${result.deletedFiles}/${result.scannedFiles} threads deleted in ${result.duration}ms`)
      
      if (result.errors.length > 0) {
        this.logError(`Cleanup errors: ${result.errors.length}`, result.errors)
      }

      return result

    } catch (error) {
      result.duration = Date.now() - startTime
      const errorMsg = `Cleanup failed: ${error}`
      result.errors.push(errorMsg)
      this.logError(errorMsg)
      return result
    }
  }

  /**
   * Traite un lot de fichiers de threads
   */
  private async processBatch(
    files: string[], 
    threadsDir: string, 
    result: CleanupResult
  ): Promise<void> {
    await Promise.all(
      files.map(async (file) => {
        try {
          const filePath = path.join(threadsDir, file)
          const content = await fs.readFile(filePath, 'utf-8')
          const threadData = yaml.parse(content)

          if (!threadData || !threadData.modifiedDate) {
            return // Skip invalid files
          }

          // Vérifier si le thread est expiré
          if (this.isThreadExpired(threadData.modifiedDate)) {
            await fs.unlink(filePath)
            result.deletedFiles++
            result.deletedThreads.push(threadData.id || file)
          }

        } catch (error) {
          const errorMsg = `Error processing file ${file}: ${error}`
          result.errors.push(errorMsg)
          this.logError(errorMsg)
        }
      })
    )
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
    
    if (this.logger) {
      this.logger.info('ThreadCleanup', message)
    }
  }

  /**
   * Logging d'erreur
   */
  private logError(message: string, details?: any): void {
    const timestamp = new Date().toISOString()
    console.error(`[${timestamp}] ThreadCleanup ERROR: ${message}`)
    
    if (details) {
      console.error(details)
    }
    
    if (this.logger) {
      this.logger.error('ThreadCleanup', message, details)
    }
  }

  /**
   * Méthode pour forcer un nettoyage manuel (utile pour tests/debug)
   */
  async forceCleanup(): Promise<CleanupResult> {
    this.log('Manual cleanup triggered')
    return await this.performCleanup()
  }
}