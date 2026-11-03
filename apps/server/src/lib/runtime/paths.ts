/**
 * Locations of everything the runtime writes under `<configDir>`.
 *
 *   <configDir>/runtime/        generated Nx workspace, state.json, secrets.json
 *   <configDir>/logs/           one log file per process (server, agentos, factory)
 *   <configDir>/web-setup.json  first-run answers (identity, setupDone) — never holds API keys
 *   <configDir>/agentos/        AgentOS JARs + data;  <configDir>/factory/  Factory JARs + data
 */

import * as path from 'path'

export const runtimeDir = (configDir: string): string => path.join(configDir, 'runtime')
export const logsDir = (configDir: string): string => path.join(configDir, 'logs')
export const logFilePath = (configDir: string, name: string): string => path.join(logsDir(configDir), `${name}.log`)
export const statePath = (configDir: string): string => path.join(runtimeDir(configDir), 'state.json')
export const secretsPath = (configDir: string): string => path.join(runtimeDir(configDir), 'secrets.json')
export const webSetupPath = (configDir: string): string => path.join(configDir, 'web-setup.json')

/** Data directories of the managed services (when the runtime pins them under configDir). */
export const agentosDataDir = (configDir: string): string => path.join(configDir, 'agentos', 'data', 'database')
export const agentosExchangeDir = (configDir: string): string => path.join(configDir, 'agentos', 'data', 'exchange')
export const factoryDataDir = (configDir: string): string => path.join(configDir, 'factory', 'data', 'database')
