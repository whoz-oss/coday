export interface Killable {
  kill(): Promise<void>
}
