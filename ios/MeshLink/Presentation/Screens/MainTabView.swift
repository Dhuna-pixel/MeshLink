import SwiftUI

public struct MainTabView: View {
    @StateObject private var viewModel: MeshViewModel

    public init(viewModel: MeshViewModel? = nil) {
        let vm = viewModel ?? MeshViewModel()
        self._viewModel = StateObject(wrappedValue: vm)
    }

    public var body: some View {
        TabView(selection: $viewModel.uiState.selectedTab) {
            StatusScreen(viewModel: viewModel)
                .tabItem {
                    Label(MeshScreenTab.status.title, systemImage: MeshScreenTab.status.iconName)
                }
                .tag(MeshScreenTab.status)

            NeighborsScreen(viewModel: viewModel)
                .tabItem {
                    Label(MeshScreenTab.neighbors.title, systemImage: MeshScreenTab.neighbors.iconName)
                }
                .tag(MeshScreenTab.neighbors)

            MessagingScreen(viewModel: viewModel)
                .tabItem {
                    Label(MeshScreenTab.messaging.title, systemImage: MeshScreenTab.messaging.iconName)
                }
                .tag(MeshScreenTab.messaging)

            LogScreen(viewModel: viewModel)
                .tabItem {
                    Label(MeshScreenTab.logs.title, systemImage: MeshScreenTab.logs.iconName)
                }
                .tag(MeshScreenTab.logs)

            SimulationScreen(viewModel: viewModel)
                .tabItem {
                    Label(MeshScreenTab.simulation.title, systemImage: MeshScreenTab.simulation.iconName)
                }
                .tag(MeshScreenTab.simulation)
        }
        .tint(.meshCyanPrimary)
        .onAppear {
            // Dark appearance for TabBar
            let appearance = UITabBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = UIColor(Color.meshDarkSurface)
            UITabBar.appearance().standardAppearance = appearance
            UITabBar.appearance().scrollEdgeAppearance = appearance
        }
    }
}
