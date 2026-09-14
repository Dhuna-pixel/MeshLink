import SwiftUI

public struct MetricCard: View {
    public let title: String
    public let value: String
    public let subtitle: String
    public let iconName: String
    public let color: Color
    public let action: () -> Void

    public init(
        title: String,
        value: String,
        subtitle: String,
        iconName: String,
        color: Color,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.value = value
        self.subtitle = subtitle
        self.iconName = iconName
        self.color = color
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(title)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.textSecondary)
                    Spacer()
                    Image(systemName: iconName)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(color)
                }

                Text(value)
                    .font(.system(size: 24, weight: .bold, design: .rounded))
                    .foregroundColor(color)

                Text(subtitle)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(.textTertiary)
                    .lineLimit(1)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.meshDarkSurface)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.meshDarkSurfaceVariant.opacity(0.4), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
