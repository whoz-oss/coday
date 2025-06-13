export interface VoiceInfo {
  name: string
  lang: string
  localService: boolean
  default: boolean
  displayName: string
}

const TestTexts: Record<string, string> = {
  fr: 'Bonjour, ceci est un test de la voix sélectionnée.',
  en: 'Hello, this is a test of the selected voice.',
  es: 'Hola, esta es una prueba de la voz seleccionada.',
  de: 'Hallo, dies ist ein Test der ausgewählten Stimme.',
  it: 'Ciao, questo è un test della voce selezionata.',
  pt: 'Olá, este é um teste da voz selecionada.',
  ja: 'こんにちは、これは選択された音声のテストです。',
  ko: '안녕하세요, 이것은 선택된 음성의 테스트입니다.',
  zh: '你好，这是所选语音的测试。',
  ru: 'Привет, это тест выбранного голоса.',
  ar: 'مرحبا، هذا اختبار للصوت المحدد.',
}

export class VoiceSynthesisComponent {
  private voices: Promise<SpeechSynthesisVoice[]> | undefined
  private currentUtterance: SpeechSynthesisUtterance | null = null
  volume: number = 1.0
  rate: number = 1.2
  language: string = 'en'
  public selectedVoice: SpeechSynthesisVoice | undefined

  constructor() {
    if (!('speechSynthesis' in window)) {
      console.warn('Speech synthesis not available')
      this.voices = new Promise((resolve) => resolve([]))
      return
    }

    this.voices = new Promise((resolve, reject) => {
      const immediateVoices = speechSynthesis.getVoices()
      if (immediateVoices.length) {
        resolve(immediateVoices)
      } else {
        const handle = setTimeout(() => {
          reject('no voices found')
        }, 2000)
        speechSynthesis.addEventListener('voiceschanged', () => {
          const delayedVoices = speechSynthesis.getVoices()
          if (delayedVoices.length) {
            clearTimeout(handle)
            resolve(delayedVoices)
          }
        })
      }
    })
  }

  public speak(text: string) {
    if (!this.selectedVoice) {
      console.warn('no voice selected')
      return
    }

    const utterance = new SpeechSynthesisUtterance(text)
    utterance.voice = this.selectedVoice
    utterance.lang = this.selectedVoice.lang
    utterance.volume = this.volume
    utterance.rate = this.rate

    this.currentUtterance = utterance
    speechSynthesis.speak(utterance)
  }

  public async getVoices(): Promise<VoiceInfo[]> {
    const langCode = this.language.slice(0, 2)

    const matchingVoices =
      (await this.voices)
        ?.filter((voice) => voice.lang.toLowerCase().startsWith(langCode))
        ?.sort((a, b) => {
          // Local voices first
          if (a.localService && !b.localService) return -1
          if (!a.localService && b.localService) return 1
          return a.name.localeCompare(b.name)
        }) ?? []
    console.log('matching voices', matchingVoices, langCode)
    return matchingVoices.map((voice) => ({
      name: voice.name,
      lang: voice.lang,
      localService: voice.localService,
      default: voice.default,
      displayName: this.formatVoiceDisplayName(voice),
    }))
  }

  private formatVoiceDisplayName(voice: SpeechSynthesisVoice): string {
    const localMarker = voice.localService ? '🏠' : '☁️'
    const defaultMarker = voice.default ? ' ⭐' : ''
    return `${localMarker} ${voice.name} (${voice.lang})${defaultMarker}`
  }

  public async setSelectedVoice(voice: string | undefined): Promise<void> {
    if (!voice) {
      this.selectedVoice = undefined
      return
    }

    const [voiceName, voiceLang] = voice.split('|')

    if (!voiceName || !voiceLang) {
      this.selectedVoice = undefined
      return
    }

    const foundVoice = (await this.voices)?.find((v) => v.name === voiceName && v.lang === voiceLang)

    if (foundVoice) {
      this.selectedVoice = foundVoice
    } else {
      this.selectedVoice = undefined
    }
  }

  public testSelectedVoice(): void {
    setTimeout(() => {
      if (!this.selectedVoice) {
        console.log(`[VOICE] no voice selected`)
        return
      }
      this.stopSpeech()
      // Get test text
      const langCode = this.selectedVoice.lang.slice(0, 2)
      const testText = TestTexts[langCode] || TestTexts['en']

      this.speak(testText)
    }, 100)
  }

  public stopSpeech(): void {
    if (this.currentUtterance) {
      speechSynthesis.cancel()
      this.currentUtterance = null
      console.log('[VOICE-COMPONENT] Speech stopped')
    }
  }
}
