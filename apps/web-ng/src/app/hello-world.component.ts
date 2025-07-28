import { Component } from '@angular/core'
import { HeaderComponent } from './components/header/header.component'
import { ChatHistoryComponent } from './components/chat-history/chat-history.component'
import { ChatMessage } from './components/chat-message/chat-message.component'
import { ChatTextareaComponent } from './components/chat-textarea/chat-textarea.component'
import { ChoiceSelectComponent, ChoiceOption } from './components/choice-select/choice-select.component'

@Component({
  selector: 'app-hello-world',
  standalone: true,
  imports: [HeaderComponent, ChatHistoryComponent, ChatTextareaComponent, ChoiceSelectComponent],
  template: `
    <div class="hello-container">
      <div class="migration-info">
        <h1>🎯 Coday Angular Migration - Phase 2</h1>
        <p>Component Migration Demo 🚀</p>
        <p><strong>Issue #147 - Component Structure Created ✅</strong></p>
      </div>
      
      <!-- Demo des composants migrés -->
      <div class="components-demo">
        <h2>Migrated Components Demo</h2>
        
        <!-- Header Component -->
        <div class="component-section">
          <h3>HeaderComponent</h3>
          <app-header></app-header>
        </div>
        
        <!-- Chat Interface -->
        <div class="component-section chat-demo">
          <h3>Chat Interface (ChatHistory + ChatTextarea)</h3>
          <div class="chat-container">
            <app-chat-history 
              [messages]="demoMessages"
              [isThinking]="isThinking"
              (playRequested)="onPlayMessage($event)"
              (copyRequested)="onCopyMessage($event)"
              (stopRequested)="onStopRequested()"
            ></app-chat-history>
            
            <app-chat-textarea 
              [isDisabled]="false"
              (messageSubmitted)="onMessageSubmitted($event)"
              (voiceRecordingToggled)="onVoiceToggled($event)"
            ></app-chat-textarea>
          </div>
        </div>
        
        <!-- Choice Select Component -->
        <div class="component-section">
          <h3>ChoiceSelectComponent</h3>
          <app-choice-select
            [options]="demoChoices"
            [labelHtml]="'Choose an option to test the component:'"
            [isVisible]="showChoices"
            (choiceSelected)="onChoiceSelected($event)"
          ></app-choice-select>
          
          <button (click)="toggleChoices()" class="toggle-btn">
            {{ showChoices ? 'Hide' : 'Show' }} Choice Demo
          </button>
        </div>
      </div>
      
      <div class="status">
        <h3>Migration Progress:</h3>
        <ul>
          <li>✅ Angular app structure in apps/web-ng</li>
          <li>✅ 4 core components created (Header, ChatHistory, ChatTextarea, ChoiceSelect)</li>
          <li>✅ Basic UI and interactions working</li>
          <li>🔄 Next: Connect to Coday events and services</li>
        </ul>
      </div>
    </div>
  `,
  styles: [`
    .hello-container {
      padding: 2rem;
      max-width: 1200px;
      margin: 0 auto;
      font-family: system-ui, -apple-system, sans-serif;
      line-height: 1.6;
    }

    .migration-info {
      text-align: center;
      margin-bottom: 3rem;
    }

    .migration-info h1 {
      color: #2563eb;
      margin-bottom: 1rem;
    }

    .migration-info p {
      font-size: 1.1rem;
      margin-bottom: 1rem;
    }

    .components-demo {
      margin-bottom: 3rem;
    }

    .components-demo h2 {
      color: #1e293b;
      border-bottom: 2px solid #e5e7eb;
      padding-bottom: 0.5rem;
      margin-bottom: 2rem;
    }

    .component-section {
      margin: 2rem 0;
      padding: 1.5rem;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fafafa;
    }

    .component-section h3 {
      margin-top: 0;
      margin-bottom: 1rem;
      color: #374151;
      font-size: 1.2rem;
    }

    .chat-demo .chat-container {
      border: 1px solid #d1d5db;
      border-radius: 8px;
      height: 400px;
      display: flex;
      flex-direction: column;
      background: white;
    }

    .toggle-btn {
      margin-top: 1rem;
      padding: 0.5rem 1rem;
      background: #3b82f6;
      color: white;
      border: none;
      border-radius: 4px;
      cursor: pointer;
    }

    .toggle-btn:hover {
      background: #2563eb;
    }

    .status {
      margin: 2rem 0;
      padding: 1.5rem;
      border-radius: 8px;
      background: #f0f9ff;
      border: 1px solid #0ea5e9;
    }

    .status h3 {
      margin-top: 0;
      margin-bottom: 1rem;
      color: #1e293b;
    }

    .status ul {
      list-style: none;
      padding: 0;
    }

    .status li {
      padding: 0.5rem 0;
      border-bottom: 1px solid #e2e8f0;
    }

    .status li:last-child {
      border-bottom: none;
    }
  `]
})
export class HelloWorldComponent {
  // Demo data
  demoMessages: ChatMessage[] = [
    {
      id: '1',
      role: 'user',
      speaker: 'User',
      content: 'Hello, can you help me with Angular migration?',
      timestamp: new Date(),
      type: 'text'
    },
    {
      id: '2', 
      role: 'assistant',
      speaker: 'Sway',
      content: 'Of course! I can help you migrate your components to Angular. What specific component would you like to work on?',
      timestamp: new Date(),
      type: 'text'
    },
    {
      id: '3',
      role: 'system',
      speaker: 'System', 
      content: 'Tool: analyze_components()',
      timestamp: new Date(),
      type: 'technical'
    }
  ]
  
  demoChoices: ChoiceOption[] = [
    { value: 'header', label: 'Work on Header Component' },
    { value: 'chat', label: 'Work on Chat Components' },
    { value: 'choice', label: 'Work on Choice Component' },
    { value: 'services', label: 'Work on Services' }
  ]
  
  isThinking: boolean = false
  showChoices: boolean = false
  
  onMessageSubmitted(message: string) {
    // Add user message
    const userMessage: ChatMessage = {
      id: Date.now().toString(),
      role: 'user',
      speaker: 'User',
      content: message,
      timestamp: new Date(),
      type: 'text'
    }
    
    this.demoMessages = [...this.demoMessages, userMessage]
    
    // Simulate thinking
    this.isThinking = true
    
    // Simulate response after 2 seconds
    setTimeout(() => {
      const assistantMessage: ChatMessage = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        speaker: 'Sway',
        content: `I received your message: "${message}". This is a demo response from the Angular component!`,
        timestamp: new Date(),
        type: 'text'
      }
      
      this.demoMessages = [...this.demoMessages, assistantMessage]
      this.isThinking = false
    }, 2000)
  }
  
  onVoiceToggled(isRecording: boolean) {
    console.log('Voice recording:', isRecording)
  }
  
  onChoiceSelected(choice: string) {
    console.log('Choice selected:', choice)
    
    const choiceMessage: ChatMessage = {
      id: Date.now().toString(),
      role: 'user',
      speaker: 'User',
      content: `Selected option: ${choice}`,
      timestamp: new Date(),
      type: 'text'
    }
    
    this.demoMessages = [...this.demoMessages, choiceMessage]
  }
  
  onPlayMessage(message: ChatMessage) {
    console.log('Play requested for message:', message.content)
  }
  
  onCopyMessage(message: ChatMessage) {
    console.log('Copy requested for message:', message.content)
  }
  
  onStopRequested() {
    console.log('Stop requested')
    this.isThinking = false
  }
  
  toggleChoices() {
    this.showChoices = !this.showChoices
  }
}