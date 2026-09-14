import SwiftUI

public struct BluetoothPermissionBanner: View {
    public init() {}

    public var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 20))
                .foregroundColor(.meshCyanPrimary)

            VStack(alignment: .leading, spacing: 2) {
                Text("Bluetooth Mesh Active")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.textPrimary)
                Text("Advertising & Scanning for nearby Android & iOS peers via BLE")
                    .font(.system(size: 10))
                    .foregroundColor(.textSecondary)
            }

            Spacer()
        }
        .padding(12)
        .background(Color.meshCyanContainer.opacity(0.3))
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.meshCyanPrimary.opacity(0.3), lineWidth: 1)
        )
    }
}
