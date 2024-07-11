import {ProjectDescription} from "./project-description"

export type Project = ProjectDescription & {
  root: string
  name: string
}