import SwiftUI

struct ProjectListView: View {
    @State private var vm = ProjectListViewModel()
    @State private var showNewProject = false
    @State private var searchText = ""

    var body: some View {
        Group {
            if vm.isLoading && vm.projects.isEmpty {
                ProgressView("Loading projects…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if vm.projects.isEmpty {
                ContentUnavailableView(
                    "No Projects",
                    systemImage: "folder",
                    description: Text("Create a new project to get started.")
                )
            } else {
                List {
                    ForEach(vm.groupedProjects, id: \.title) { group in
                        Section(group.title) {
                            ForEach(filteredProjects(group.items)) { project in
                                                                                                                        ect: project)
                                 
                                                      }
                    }
                }
                                       d)
                        bl              hT                        bl              hT            }
        .navigationTitle("Coday")
        .naviga        .naviga        .naviga        .naviga        .naviga        AppView(projectName: project.name)
        }
                                       te                                                                          te s                                       te                                   st                                                            li                                       te                             te                                       te    ie                                       te                                      p                                       te                                                                          te s             m.                                 i                                       te             l                     {                 r                                        tn(                                       te                                                                                         _                                        te                                                                          tn         .fcat > /Users/m1/Desktop/coday__feat-ios-native-app/apps/ios/Coday/Coday/Views/ProjectNewView.swift << 'SWIFT_EOF'
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
                                                                                                                                      "New Project")
                               Di                               Di                                                       la                                                                Di                                        Di   pl                               Di                                        ea                                           me                               Di                                           Di                        ng                               Di               sV                               Di                              isCreating = true
        Task {
            do {
                try await                 try await                 try aw                  try await                 try await                          Cr                try await    }
}
