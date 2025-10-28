import { Component, OnDestroy, OnInit } from '@angular/core'

@Component({
  selector: 'app-welcome-message',
  standalone: true,
  imports: [],
  templateUrl: './welcome-message.component.html',
  styleUrl: './welcome-message.component.scss',
})
export class WelcomeMessageComponent implements OnInit, OnDestroy {
  // Welcome message rotation
  welcomeMessages = [
    'Welcome to Coday', // English
    'Bienvenue sur Coday', // French
    'Bienvenido a Coday', // Spanish
    'Willkommen bei Coday', // German
    'Benvenuto su Coday', // Italian
    'Bem-vindo ao Coday', // Portuguese
    'Welkom bij Coday', // Dutch
    'Добро пожаловать в Coday', // Russian
    'Coday へようこそ', // Japanese
    '欢迎来到 Coday', // Chinese
    'Coday 에 오신 것을 환영합니다', // Korean
    'مرحبًا بك في Coday', // Arabic
  ]
  currentWelcomeIndex = 0
  currentWelcomeMessage = this.welcomeMessages[0]
  private welcomeRotationInterval?: number

  ngOnInit(): void {
    this.startWelcomeRotation()
  }

  ngOnDestroy(): void {
    this.stopWelcomeRotation()
  }

  private startWelcomeRotation(): void {
    this.welcomeRotationInterval = window.setInterval(() => {
      this.currentWelcomeIndex = (this.currentWelcomeIndex + 1) % this.welcomeMessages.length
      this.currentWelcomeMessage = this.welcomeMessages[this.currentWelcomeIndex]
    }, 3000)
  }

  private stopWelcomeRotation(): void {
    if (this.welcomeRotationInterval) {
      clearInterval(this.welcomeRotationInterval)
      this.welcomeRotationInterval = undefined
    }
  }
}
