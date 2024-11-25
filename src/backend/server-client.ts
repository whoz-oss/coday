import {Response} from "express"
import {ServerInteractor} from "../model/server-interactor"
import {Coday} from "../coday"
import {HeartBeatEvent} from "../shared"
import {Subscription} from "rxjs"

export class ServerClient {
  private readonly heartbeatInterval: NodeJS.Timeout
  private terminationTimeout?: NodeJS.Timeout
  private lastConnected: number
  private coday?: Coday
  
  static readonly SESSION_TIMEOUT = 60 * 60 * 1000 // 1 hour in milliseconds
  static readonly HEARTBEAT_INTERVAL = 10_000 // 10 seconds
  
  constructor(
    private readonly clientId: string,
    private response: Response,
    private readonly interactor: ServerInteractor,
  ) {
    // Subscribe to interactor events
    this.subscription = this.interactor.events.subscribe(event => {
      const data = `data: ${JSON.stringify(event)}\n\n`
      this.response.write(data)
    })
    this.lastConnected = Date.now()
    this.heartbeatInterval = setInterval(() => this.sendHeartbeat(), ServerClient.HEARTBEAT_INTERVAL)
  }
  
  /**
   * Update client connection with new response object.
   * Called when client reconnects with same clientId.
   */
  private subscription?: Subscription
  
  reconnect(response: Response): void {
    this.response = response
    this.lastConnected = Date.now()
    
    if (this.terminationTimeout) {
      clearTimeout(this.terminationTimeout)
      delete this.terminationTimeout
      console.log(`${new Date().toISOString()} Client ${this.clientId} reconnected, cleared termination`)
    }

    // Replay thread messages if we have an active Coday instance
    if (this.coday) {
      console.log(`${new Date().toISOString()} Replaying messages for client ${this.clientId}`)
      this.coday.replay()
    }
  }
  
  /**
   * Start or resume Coday instance.
   * Returns true if new instance was created, false if existing instance was used.
   */
  startCoday(): boolean {
    if (this.coday) {
      return false // Already running
    }
    
    this.coday = new Coday(this.interactor, {oneshot: false})
    this.coday.run().finally(() => this.terminate(true))
    return true
  }
  
  /**
   * Terminate the client connection.
   * If immediate is true, cleanup everything now.
   * Otherwise schedule cleanup after SESSION_TIMEOUT.
   */
  terminate(immediate: boolean = false): void {
    // Clear heartbeat interval
    clearInterval(this.heartbeatInterval)
    this.response.end()
    
    if (immediate) {
      this.cleanup()
      console.log(`${new Date().toISOString()} Client ${this.clientId} terminated immediately`)
      return
    }
    
    // Stop Coday but keep it alive
    this.coday?.stop()
    
    // Clear any existing termination timeout
    if (this.terminationTimeout) {
      clearTimeout(this.terminationTimeout)
    }
    
    // Set new termination timeout
    this.terminationTimeout = setTimeout(() => {
      const idleTime = Date.now() - this.lastConnected
      if (idleTime >= ServerClient.SESSION_TIMEOUT) {
        this.cleanup()
        console.log(`${new Date().toISOString()} Client ${this.clientId} session expired after ${Math.round(idleTime / 1000)}s of inactivity`)
      }
    }, ServerClient.SESSION_TIMEOUT)
    
    console.log(`${new Date().toISOString()} Client ${this.clientId} disconnected, termination scheduled in ${ServerClient.SESSION_TIMEOUT / 1000}s`)
  }
  
  /**
   * Stop the current Coday run if any
   */
  stop(): void {
    this.coday?.stop()
  }
  
  /**
   * Check if client has been inactive longer than SESSION_TIMEOUT
   */
  isExpired(): boolean {
    return Date.now() - this.lastConnected >= ServerClient.SESSION_TIMEOUT
  }
  
  private sendHeartbeat(): void {
    try {
      const heartBeatEvent = new HeartBeatEvent({})
      this.interactor.sendEvent(heartBeatEvent)
    } catch (error) {
      console.error("Error sending heartbeat:", error)
      this.terminate()
    }
  }
  
  /**
   * Get the interactor instance for this client
   */
  getInteractor(): ServerInteractor {
    return this.interactor
  }
  
  private cleanup(): void {
    this.subscription?.unsubscribe()
    this.coday?.kill()
    delete this.coday
    if (this.terminationTimeout) {
      clearTimeout(this.terminationTimeout)
      delete this.terminationTimeout
    }
  }
}

/**
 * Manages all active server clients
 */
export class ServerClientManager {
  private readonly clients: Map<string, ServerClient> = new Map()
  
  /**
   * Get or create a client for the given clientId
   */
  getOrCreate(clientId: string, response: Response): ServerClient {
    const existingClient = this.clients.get(clientId)
    if (existingClient) {
      existingClient.reconnect(response)
      return existingClient
    }
    
    const interactor = new ServerInteractor(clientId)
    const client = new ServerClient(clientId, response, interactor)
    this.clients.set(clientId, client)
    return client
  }
  
  /**
   * Get a client by id if it exists
   */
  get(clientId: string): ServerClient | undefined {
    return this.clients.get(clientId)
  }
  
  /**
   * Remove a client from the manager
   */
  remove(clientId: string): void {
    this.clients.delete(clientId)
  }
  
  /**
   * Clean up expired clients
   */
  cleanupExpired(): void {
    for (const [clientId, client] of this.clients.entries()) {
      if (client.isExpired()) {
        client.terminate(true)
        this.clients.delete(clientId)
      }
    }
  }
}