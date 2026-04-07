import SwiftUI

struct ProjectNewView: View {
    var onCreate: (String, String) async throws -> Void

    @State private var name = ""
    @State private var path = ""
    @State private var isCreating = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Project Details") {
                    TextField("Project name", text: $name)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Path (e.g. /Users/me/myproject)", text: $path)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                if let error {
                    Section {
                        Text(error)
                      import SwiftUI

struct ProjectNewView: View {
    var onCreate: (String, String) async throws -> Void

    @Stat"N
struct Proje       var onCreate: (String, SDi
    @State private var name = ""
    @State private v       @State private var path = "la    @State private var isCreati      @State private var error: String?
        @Environment(\.dismiss) private pl
    var body: some View {
        NavigationS           NavigationStack ea            Form {
                       Sme                    TextField("Project name                          .autocorrectionDisabled()
       ng                        .textInputAutocapitalizasV                    TextField("Path (e.g. /Users/me/myproje                          .autocorrectionDisabled()
                        .tt                         .textInputAutocapitaliza                  }
                if let error {
                            Cr                    Section {}
             cat << 'SWIFT_EOF' > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Views/MainAppView.swift
import SwiftUI

struct MainAppView: View {
    let projectName: String

    @State private var threadListVM = ThreadListViewModel()
    @State private var selectedThreadId: String?
    @State private var showTokenUsage = false
    @State private var columnVisibility = NavigationSplitViewVisibility.automatic

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            // Sidebar: thread list
            ThreadListView(
                vm: threadListVM,
                projectName: projectName,
                selectedThreadId: $selectedThreadId
            )
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showTokenUsage = true
                    } label: {
                        Image(systemName: "chart.bar")
                    }
                    .accessibilityLabel(import SwiftUI

struct MainAppView: View {
    let projectName: String

    @State private var threadListVM =  
struct MainAet     let projectName: StriId
    @State private var thiew    @State private var selectedThreadId: String?
    @Stat      @State private var showTokenUsage = false
  o    @State private var columnVisibility = Nale
    var body: some View {
        NavigationSplitView(columnVisibility: $columnt:         NavigationSplitV              // Sidebar: thread list
            ThreadListView(
 ce            ThreadListView(
                        vm: thread                  projectName: proec                selectedThreadId: $selec              )
            .toolbar {
             Li            hr                Toolb                      Button {
                        sho                          sho
                     } label: {
                                       Imageme                    }
                    .accessibil                      ()
struct MainAppView: View {
    let projectName: StristV    let projectName: Strime
    @State private var tht Wstruct MainAet     let projectName: St:     @State private var thiew    @State pr      @Stat      @State private var showTokenUsage = false
  o    @State privem  o    @State private var columnVisibility = Nale
    vfo    var body: some View {
        NavigationSplidS        NavigationSplitV              ThreadListView(
 ce            ThreadListView(
                        vm: thread                  projert ce            ThreadListV                          vm: tnm            .toolbar {
             Li            hr                Toolb                      Button {
                    ew             Li      :                         sho                          sho
                     }g(                     } label: {
                       ,                               .b                    .accessibil                      ()
struct Mai  struct MainAppView: View {
    let projectName: Strist}
    let projecat << 'SWIFT_EOF' > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Views/ThreadListView.swift
import SwiftUI

struct ThreadListView: View {
    @Bindable var vm: ThreadListViewModel
    let projectName: String
    @Binding var selectedThreadId: String?

    @State private var showRenameAlert = false
    @State private var renameTarget: ThreadSummary?
    @State private var renameText = ""
    @State private var isCreating = false

    var body: some View {
        Group {
            if vm.isLoading && vm.threads.isEmpty {
                ProgressView("Loading threads…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(selection: $selectedThreadId) {
                    if !vm.starredThreads.isEmpty {
                        Section("Starred") {
                            ForEach(vm.starredThreads) { thread in
                                ThreadRowView(
                                    thread: thread,
                 import SwiftUI

struct ThreadListView: View {
    @Bindable var vm: ThreadListViewModel
    let projectName: Str {
struct Threagle    @Bindable var vm: Thread      let projectName: String
    @Binding      @Binding var selectedT  
    @State private var showRenameAlert =       @State private var renameTarget: ThreadSuam    @State private var renameText = ""
    @State me    @State private var isCreating = f  
    var body: some View {
        Group           Group {
         a            ifTh                ProgressView("Loading threads…")                      .frame(maxWidth: .infinity, m
             } else {
                List(selection: $selectedThread                  Lis                      if !vm.starredThreads.isEmpty {Fo                        Section("Starred") {
                                   ForEach(vm.star                                  ThreadRowView(
                  is                                    thread: t                   import SwiftUI

struct ThreadListm.
struct ThreadListView: View {       @Bindable var vm: Threadam    let projectName: Str {
struct Threag rstruct Threagle    @Binda      @Binding      @Binding var selectedT  
    @State private var showR      @State private var showRenameAlert =       @State me    @State private var isCreating = f  
    var body: some View {
        Group           Group {
         a            var body: some View {
        Group           Gta        Group                      a            ifTh                   } else {
                List(selection: $selectedThread                  Lis                      if !vm.starredle                List                                     ForEach(vm.star                                  ThreadRowView(
                  is                                    thread: t  id                  is                                    thread: t                   import SwiftUTo
struct ThreadListm.
struct ThreadListView: View {       @Bindable var vm: Threadam    let projec()
struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @Binding      @Binding var selectedT  
        @State private var showR      @State private var showRenameAlert =       @State)
    var body: some View {
        Group           Group {
         a            var body: some View {
        Group         d"        Group           }
         a            var bodym.        Group           Gta        Group  t(                List(selection: $selectedThread                  Lis                      if !vm.starredlld                  is                                    thread: t  id                  is                                    thread: t                   import SwiftUTo
struct ThreadListm.
struct ThreadListView: View {       { struct ThreadListm.
struct ThreadListView: View {       @Bindable var vm: Threadam    let projec()
struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @B  struct ThreadListV= struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @Binding             @State private var showR      @State private var showRenameAlert =       @State)
    var body: hr    var body: some View {
        Group           Group {
         a            var bodVo        Group           ->         a            var body {        Group         d"        Group     ng         a            var bodym.        Group   (tstruct ThreadListm.
struct ThreadListView: View {       { struct ThreadListm.
struct ThreadListView: View {       @Bindable var vm: Threadam    let projec()
struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @B  struct ThreadListV= struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @Binding             @State private v.sstruct ThreadListVsEstruct ThreadListView: View {       @Bindable var vm: Th .struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @B  struct       var body: hr    var body: some View {
        Group           Group {
         a            var bodVo        Group           ->         a            var body {        Group         d"        Group     ng         a            var bodym.        Group   (n(        Group           Group {
        De         a            var bodVAcstruct ThreadListView: View {       { struct ThreadListm.
struct ThreadListView: View {       @Bindable var vm: Threadam    let projec()
struct ThreadListVlastruct Threag rstruct Threagle    @Binda      struct ThreadListView: View {       @Bindable var vm: Th  struct ThreadListVlastruct Threag rstruct Threagle    @Binda      @B  struct }
        Group           Group {
         a            var bodVo        Group           ->         a            var body {        Group      find /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday -name '*.swift' | sort
