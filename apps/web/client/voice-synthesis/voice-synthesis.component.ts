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
  private currentOnEndCallback: (() => void) | null = null
  volume: number = 0.8
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

  public ding(): void {
    // Create a simple notification sound using Web Audio API
    try {
      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
      const oscillator = audioContext.createOscillator()
      const gainNode = audioContext.createGain()

      oscillator.connect(gainNode)
      gainNode.connect(audioContext.destination)

      oscillator.frequency.setValueAtTime(800, audioContext.currentTime) // 800Hz tone
      gainNode.gain.setValueAtTime(0.1, audioContext.currentTime) // Low volume
      gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.1)

      oscillator.start(audioContext.currentTime)
      oscillator.stop(audioContext.currentTime + 0.1)
    } catch (error) {
      console.error('Notification sound failed:', error)
    }
  }

  public extractPlainText(text: string): string {
    let processed = text
      // Remove emojis (Unicode ranges for emojis) + specific stars
      .replace(/[\u{1F600}-\u{1F64F}]|[\u{1F300}-\u{1F5FF}]|[\u{1F680}-\u{1F6FF}]|[\u{1F1E0}-\u{1F1FF}]|[\u{2600}-\u{26FF}]|[\u{2700}-\u{27BF}]|⭐/gu, '')
      
      // Remove code blocks
      .replace(/```[\s\S]*?```/g, 'code block')
      .replace(/`([^`]+)`/g, '$1') // Inline code
      
      // Remove markdown formatting
      .replace(/\*\*\*(.*?)\*\*\*/g, '$1') // Bold italic
      .replace(/\*\*(.*?)\*\*/g, '$1') // Bold
      .replace(/\*(.*?)\*/g, '$1') // Italic
      .replace(/~~(.*?)~~/g, '$1') // Strikethrough
      
      // Remove headers
      .replace(/#{1,6}\s*(.*)/g, '$1')
      
      // Replace links with just the text
      .replace(/\[([^\]]+)\]\([^\)]+\)/g, '$1')
      
      // Remove standalone URLs
      .replace(/https?:\/\/[^\s]+/g, 'link')
      
      // Remove HTML entities
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, 'and')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
    
    // Add punctuation for natural pauses BEFORE removing line breaks
    processed = this.addNaturalPunctuation(processed)
    
    return processed
      // Clean up multiple spaces and trim
      .replace(/\s+/g, ' ')
      .trim()
  }

  private addNaturalPunctuation(text: string): string {
    return text
      // Normalize multiple line breaks
      .replace(/\n+/g, '\n')
      .split('\n')
      .map(line => this.addPeriodIfNeeded(line.trim()))
      .filter(line => line.length > 0)
      .join(' ') // Join with spaces for speech synthesis
  }

  private addPeriodIfNeeded(line: string): string {
    if (!line) return ''
    
    // If line doesn't end with punctuation, add a period
    if (!/[.!?;:]$/.test(line)) {
      return line + '.'
    }
    return line
  }

  public speak(markdown: string, onEnd?: () => void) {
    if (!this.selectedVoice) {
      console.warn('no voice selected')
      return
    }

    this.stopSpeech()

    const text = this.extractPlainText(markdown)

    const utterance = new SpeechSynthesisUtterance(text)
    utterance.voice = this.selectedVoice
    utterance.lang = this.selectedVoice.lang
    utterance.volume = this.volume
    utterance.rate = this.rate

    this.currentOnEndCallback = onEnd || null

    if (onEnd) {
      utterance.onend = () => {
        if (this.currentOnEndCallback === onEnd) {
          this.currentOnEndCallback = null
          onEnd()
        }
      }
      utterance.onerror = () => {
        if (this.currentOnEndCallback === onEnd) {
          this.currentOnEndCallback = null
          onEnd()
        }
      }
    }

    this.currentUtterance = utterance
    speechSynthesis.speak(utterance)
    console.log('[VOICE-COMPONENT] Started speaking:', text.substring(0, 50) + '...')
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
      if (!testText) { 
        return 
      }

      this.speak(testText)
    }, 100)
  }

  public stopSpeech(): void {
    if (this.currentUtterance || speechSynthesis.speaking) {
      speechSynthesis.cancel()
      this.currentUtterance = null
      this.currentOnEndCallback = null
      console.log('[VOICE-COMPONENT] Speech stopped')
    }
  }

  public isSpeaking(): boolean {
    return speechSynthesis.speaking
  }
}
