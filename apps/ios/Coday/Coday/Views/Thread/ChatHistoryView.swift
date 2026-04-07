import SwiftUI

struct ChatHistoryView: View {
    let messages: [ChatMessage]
    let streamingText: String
    let isThinking: Bool
    var onDeleteMessage: ((String) -> Void)?

    @State private var isTrackingBottom = true
    @State private var showScrollToBottom = false
    @Namespace private var bottomAnchor

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(messages) { message in
                        ChatMessageView(
                            message: message,
                            onDelete: { onDeleteMessage?(message.id) }
                        )
                        .padding(.horizontal, 12)
                    }

                    // Streaming text (live chunks)
                    if !streamingText.isEmpty {
                        Stimport SwiftUI

struct ChatHistoryView: View {
    let messages: [ChatMessage]
    let streamingText: String
    let isT  
struct ChatH/ T    let messages: [ChatMessag      let streamingText: String
in    let isThinking: Bool
         var onDeleteMessage)

    @State private var isTrackingBottom = ont    @State private var showScrollToBottom = f      @Namespace private var bottomAnchor

    var  
    var body: some View {
        Scr           ScrollViewReaderht            ScrollView {
         bo                LazyVSt}
                    ForEach(messages) { message in
                                    ChatMessageView(
                                      message: me                              onDelete: { onDe                          )
                        .padding(.horizonta                          ",                    }

                    // St  
                   }
                     if !streamingText.isEmpty {
                            Stimport SwiftUI

stru  
struct ChatHistoryView: View {
    letm)
    let messages: [ChatMessag }    let streamingText: String
hi    let isT  
struct ChatH/ isstruct ChatHisin    let isThinking: Bool
         var onDeleteMessage)

    @State privam"         var onDeleteMess  
    @State private var isTr   
    var  
    var body: some View {
        Scr           ScrollViewReaderht            ScrollView {
         bo                L       var Tr        Scr           Sc           bo                LazyVSt}
                    ForEach                      ForEach(messag("                                    ChatMessageVi l                                      message: me  ar                        .padding(.horizonta                          ",                    }

                    // St    
                    // St  
                   }
                     if !streamingText.ise(C                   }
                                                        Stimport SwiftUI

sca
stru  
struct ChatHistoryView: View {
      }struc      letm)
    let messages: [ck    let olhi    let isT  
struct ChatH/ isstruct ChatHisin cat > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Views/Thread/ChatMessageView.swift << 'SWIFT_EOF'
import SwiftUI

struct ChatMessageView: View {
    let message: ChatMessage
    var onDelete: (() -> Void)?

    @State private var showDeleteConfirm = false
    @State private var expandedImage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Speaker + timestamp header
            HStack(spacing: 6) {
                Image(systemName: roleIcon)
                    .font(.caption)
                    .foregroundStyle(roleColor)
                Text(message.speaker)
                    .font(.caption.bold())
                    .foregroundStyle(roleColor)
                Spacer()
                Text(message.timestamp.chatTimestamp())
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            // Content bubble
            Group {
                switch message.type {
                caseimport SwiftUI

struct ChatMessageView: View {
    let message: ChatMessage
    var onDelete: (() -> Void)?

    @State   
struct ChatM       let message: ChatMessage
      var onDelete: (() -> Vo: 
    @State private var showDe me    @State private var expandedImage: String?

  
    var body: some View {
        VStack(al           VStack(alignmenten            // Speaker + timestamp header
                    HStack(spacing: 6) {
       d(                Image(systemNam                      .font(.caption)
                            .foregroundSty(l                Text(message.speaker)
        do                    .font(.caption.b                      .foregroundStyle(rolein                Spacer()
                Text(                  Text(meas                    .font(.caption2)
                                       .foregroundStyle(            }

            // Content bubble
   
           uiI            Group {
                          sw                  caseimport SwiftUI

  
struct ChatMessageView: View {
 )
     let message: ChatMessage
      var onDelete: (() -> Vo00
    @State   
struct ChatM      struct ChatMip      var onDelete: (() -> Vo: 
    @State      @State private var showDe   
  
    var body: some View {
        VStack(al           VStack(alignmenten              VStack(al       so                    HStack(spacing: 6) {
       d(                Image(systemNam              d(                Image(systemNa}
                            .foregroundSty(l                Text(message.spe)
        do                    .font(.caption.b                      .foregroundS                  Text(                  Text(meas                    .font(.caption2)
                             t
                                       .foregroundStyle(            }

              
            // Content bubble
   
           uiI            Group {      
           uiI                                          sw    tt
  
struct ChatMessageView: View {
 )
     let message: ChatMes= tsue )
     let message: ChatMess           var onDelete: (() -> Vfr    @State   
struct ChatM    )
struct ChatM      @State      @State private var showDe   
  
    var body:     
    var body: some View {
        VStack?" 
         VStack(al       $s       d(                Image(systemNam              d(                Image(systemNa}
                            .{                             .foregroundSty(l                Text(message.spe)
        it        do                    .font(.caption.b                      .foregro$0                             t
                                       .foregroundStyle(            }

              
            // Content bubble
   
           uiIge                               
              
            // Content bubble
   
           uiI    .ig            /()   
           uiI               .           uiI                        e)  
struct ChatMessageView: View {
 )
     let message: ChatMes=insex )
     let message: ChatMes=ol Ic     let message: ChatMess      agstruct ChatM    )
struct ChatM      @State      @State private var showDentstruct ChatM        
    var body:     
    var body: some View {
        V    p    var body: somlo        VStack?" 
      h          VStack(                              .{                             .foregroundSty(l                Text(message.spe)
              it        do                    .font(.caption.b                      .foregro$0                   us                                       .foregroundStyle(            }

              
            // Content bubble
   
              
            // Content bubble
   
           uiIge    s            /.t   
           uiIge        et  n               
            // Content bubble
 es            /     
           uiI    .ig    .  ss           uiI               .         rstruct ChatMessageView: View {
 )
     let message: ChatMes=insex )
   .r )
     let message: ChatMes=lo .o     let message: ChatMes=ol Ic isstruct ChatM      @State      @State private var showDentstruct ChatM        
         var body:     
    var body: some View {
        V    p    var body: som      var body: somat        V    p    var boe       h          VStack(                          =              it        do                  cat > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Views/Thread/StreamingTextView.swift << 'SWIFT_EOF'
import SwiftUI

/// Shows live-streaming text with a pulsing cursor indicator.
struct StreamingTextView: View {
    let text: String
    @State private var cursorVisible = true

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            MarkdownText(content: text)
                .frame(maxWidth: .infinity, alignment: .leading)
            // Blinking cursor
            Rectangle()
                .fill(Color.accentColor)
                .frame(width: 2, height: 16)
                .opacity(cursorVisible ? 1 : 0)
                .animation(.easeInOut(duration: 0.5).repeatForever(), value: cursorVisible)
                .onAppear { cursorVisible.toggle() }
        }
        .padding(10)
        .background(Color.messageBubbleAssistant)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
