import { Coday } from './coday'
import { TerminalInteractor } from './terminal-interactor'
import { TerminalNonInteractiveInteractor } from './terminal-non-interactive-interactor'
import { parseCodayOptions } from './options'

const options = parseCodayOptions()

const interactor = options.oneshot ? new TerminalNonInteractiveInteractor() : new TerminalInteractor()

new Coday(interactor, options).run()
