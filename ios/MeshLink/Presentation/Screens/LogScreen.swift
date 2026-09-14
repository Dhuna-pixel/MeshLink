import SwiftUI

public struct LogScreen: View {
    @ObservedObject public var viewModel: MeshViewModel

    public init(viewModel: MeshViewModel) {
        self.viewModel = viewModel
    }

    private var filteredLogs: [NetworkLogEntry] {
        if let filter = viewModel.uiState.logFilterLevel {
            return viewModel.uiState.networkLogs.filter { $0.level == filter }
        }
        return viewModel.uiState.networkLogs
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Live Engineering Event Log")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.textPrimary)
                    Text("Chronological routing & multi-hop packet trace")
                        .font(.system(size: 12))
                        .foregroundColor(.textSecondary)
                }

                Spacer()

                Button(action: { viewModel.clearLogs() }) {
                    Image(systemName: "trash")
                        .font(.system(size: 14))
                        .foregroundColor(.textSecondary)
                        .padding(8)
                        .background(Color.meshDarkSurfaceVariant)
                        .clipShape(Circle())
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)

            // Filter Chips
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChipButton(
                        label: "ALL (\(viewModel.uiState.networkLogs.count))",
                        isSelected: viewModel.uiState.logFilterLevel == nil,
                        color: .meshCyanPrimary,
                        action: { viewModel.setLogFilter(nil) }
                    )

                    FilterChipButton(
                        label: "SUCCESS",
                        isSelected: viewModel.uiState.logFilterLevel == .success,
                        color: .meshGreenAccent,
                        action: { viewModel.setLogFilter(viewModel.uiState.logFilterLevel == .success ? nil : .success) }
                    )

                    FilterChipButton(
                        label: "INFO",
                        isSelected: viewModel.uiState.logFilterLevel == .info,
                        color: .meshCyanPrimary,
                        action: { viewModel.setLogFilter(viewModel.uiState.logFilterLevel == .info ? nil : .info) }
                    )

                    FilterChipButton(
                        label: "WARN",
                        isSelected: viewModel.uiState.logFilterLevel == .warning,
                        color: .meshAmberWarning,
                        action: { viewModel.setLogFilter(viewModel.uiState.logFilterLevel == .warning ? nil : .warning) }
                    )

                    FilterChipButton(
                        label: "ERROR",
                        isSelected: viewModel.uiState.logFilterLevel == .error,
                        color: .meshRedError,
                        action: { viewModel.setLogFilter(viewModel.uiState.logFilterLevel == .error ? nil : .error) }
                    )
                }
                .padding(.horizontal, 16)
            }

            // Terminal Log Console
            ZStack {
                Color.codeLogBackground.cornerRadius(12)

                if filteredLogs.isEmpty {
                    Text("No log events matching filter.")
                        .font(.system(size: 13))
                        .foregroundColor(.textSecondary)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(filteredLogs) { entry in
                                LogEntryCard(entry: entry)
                            }
                        }
                        .padding(10)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        .background(Color.meshDarkBackground.ignoresSafeArea())
    }
}

private struct FilterChipButton: View {
    let label: String
    let isSelected: Bool
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? color : Color.meshDarkSurfaceVariant)
                .foregroundColor(isSelected ? Color.meshDarkBackground : color)
                .cornerRadius(14)
        }
    }
}

private struct LogEntryCard: View {
    let entry: NetworkLogEntry

    private var messageColor: Color {
        switch entry.level {
        case .info: return .textPrimary
        case .success: return .meshGreenAccent
        case .warning: return .meshAmberWarning
        case .error: return .meshRedError
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                HStack(spacing: 6) {
                    LogLevelBadge(level: entry.level)
                    Text("[\(entry.tag)]")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.meshCyanPrimary)
                }

                Spacer()

                Text(entry.getFormattedTime())
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.textSecondary)
            }

            Text(entry.message)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(messageColor)
                .lineSpacing(2)

            if let summary = entry.packetSummary {
                Text(summary)
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundColor(.textTertiary)
            }
        }
        .padding(10)
        .background(Color.meshDarkSurface.opacity(0.6))
        .cornerRadius(8)
    }
}
