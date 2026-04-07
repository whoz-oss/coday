import SwiftUI

struct ChoiceView: View {
    let choice: ThreadViewModel.PendingChoice
    var onSelect: (String) -> Void

    @State private var freeText = ""
    @State private var isSubmitting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !choice.invite.isEmpty {
                Text(choice.invite)
                    .font(.subheadline.bold())
            }
            if let q = choice.optionalQuestion {
                Text(q).font(.subheadline).foregroundStyle(.secondary)
            }
            FlowLayout(spacing: 8) {
                ForEach(choice.options, id: \.self) { option in
                    Button(option) {
                        guard !isSubmitting else { return }
                        isSubmitting = true
                        onSelect(option)
                    }
                    .buttonStyle(.bordered)
    import SwiftUI

struct ChoiceView: View {
    let choice: ThreadViewModel.PendingChoice
    var onSelect: (String)   
struct Choicck     let choice: ThreadVixt    var onSelect: (String) -> Void

    @Stare
    @State private var freeText ext    @State private var isSubmitting  
    var body: some View {
        VStack(           VStack(alignmentBl            if !choice.invite.isEmpty {
                           Text(choice.invite)
                        .font(.subhead              }
            if let q = choice.od(            la                Text(q).font(.subheadline).fore              }
            FlowLayout(spacing: 8) {
                Foys            d)                ForEach(choice.optile                    Button(option) {
                        gng                        guard !isSu {                        isSubmitting = true
              sa                        onSelect(option)
 ac                    }
                 id                    ??    import SwiftUI

struct ChoiceView: VieCG
struct ChoiceVieght    let choice: ThreadVir     var onSelect: (String)   
struct Choicckewstruct Choicck     let choic  
    @Stare
    @State private var freeText ext    @State private var isSu ro    @Stat s    var body: some View {
        VStack(           VStack(alignmentBl ng        VStack(          m                           Text(choice.invite)
                        .font(.subh +                        .font(.subhead       bo            if let q = choice.od(            la     Su            FlowLayout(spacing: 8) {
                Foys            d)                ForEach(choice.optilevi                Foys            d) ze                        gng                        guard !isSu {                        isSubmitting = tru                sa                        onSelect(option)
 ac                    }
                 id     CG ac                    }
                 id                              id     +
struct ChoiceView: VieCG
struct ChoiceVieght    let choict)
struct ChoiceVieght    _Estrucat > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Views/Thread/InviteView.swift << 'SWIFT_EOF'
import SwiftUI

struct InviteView: View {
    let invite: ThreadViewModel.PendingInvite

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "questionmark.circle")
                    .foregroundStyle(.accentColor)
                Text(invite.invite)
                    .font(.subheadline)
            }
            if let def = invite.defaultValue, !def.isEmpty {
                Text("Default: \(def)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color.accentColor.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
