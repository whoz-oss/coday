import {ProjectLocalConfig} from "./project-local-config"

export type CodayConfig = {
  project: {
    [key: string]: ProjectLocalConfig
  }
  currentProject?: string
}
