import SwiftUI

public struct NodeStatusBadge: View {
    public let status: NodeStatus

    public init(status: NodeStatus) {
        self.status = status
    }

    private var statusColor: Color {
        switch status {
        case .connected: return .meshGreenAccent
        case .connecting: return .meshAmberWarning
        case .discovered: return .meshCyanPrimary
        case .disconnected: return .textTertiary
        }
    }

    private var statusText: String {
        switch status {
        case .connected: return "CONNECTED"
        case .connecting: return "CONNECTING"
        case .discovered: return "DISCOVERED"
        case .disconnected: return "DISCONNECTED"
        }
    }

    public var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(statusColor)
                .frame(width: 7, height: 7)

            Text(statusText)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(statusColor)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(statusColor.opacity(0.15))
        .cornerRadius(12)
    }
}
