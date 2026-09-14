import SwiftUI

public struct LogLevelBadge: View {
    public let level: LogLevel

    public init(level: LogLevel) {
        self.level = level
    }

    private var badgeColor: Color {
        switch level {
        case .info: return .meshCyanPrimary
        case .success: return .meshGreenAccent
        case .warning: return .meshAmberWarning
        case .error: return .meshRedError
        }
    }

    public var body: some View {
        Text(level.rawValue)
            .font(.system(size: 9, weight: .black, design: .monospaced))
            .foregroundColor(badgeColor)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(badgeColor.opacity(0.15))
            .cornerRadius(4)
    }
}
