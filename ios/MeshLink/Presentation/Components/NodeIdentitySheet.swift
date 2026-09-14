import SwiftUI

public struct NodeIdentitySheet: View {
    @Binding public var isPresented: Bool
    public let currentNode: Node
    public let onSave: (String, String) -> Void

    @State private var nodeId: String = ""
    @State private var nodeName: String = ""

    public init(
        isPresented: Binding<Bool>,
        currentNode: Node,
        onSave: @escaping (String, String) -> Void
    ) {
        self._isPresented = isPresented
        self.currentNode = currentNode
        self.onSave = onSave
    }

    public var body: some View {
        NavigationStack {
            ZStack {
                Color.meshDarkBackground.ignoresSafeArea()

                VStack(alignment: .leading, spacing: 20) {
                    Text("Configure Node Identity")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.textPrimary)

                    Text("Define the local device identifier and human-readable label in the mesh topology.")
                        .font(.system(size: 14))
                        .foregroundColor(.textSecondary)

                    VStack(alignment: .leading, spacing: 8) {
                        Text("NODE NAME")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.textSecondary)

                        TextField("e.g. Node A (iPhone)", text: $nodeName)
                            .padding(12)
                            .background(Color.meshDarkSurface)
                            .cornerRadius(8)
                            .foregroundColor(.textPrimary)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color.meshDarkSurfaceVariant, lineWidth: 1)
                            )
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("NODE ID")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.textSecondary)
                            Spacer()
                            Button("Generate Random") {
                                nodeId = IdGenerator.generateNodeId()
                            }
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.meshCyanPrimary)
                        }

                        TextField("e.g. NODE-A", text: $nodeId)
                            .padding(12)
                            .background(Color.meshDarkSurface)
                            .cornerRadius(8)
                            .foregroundColor(.meshCyanPrimary)
                            .font(.system(.body, design: .monospaced))
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color.meshDarkSurfaceVariant, lineWidth: 1)
                            )
                    }

                    Spacer()

                    HStack(spacing: 12) {
                        Button("Cancel") {
                            isPresented = false
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.meshDarkSurface)
                        .foregroundColor(.textSecondary)
                        .cornerRadius(10)

                        Button("Save Changes") {
                            onSave(nodeId, nodeName)
                            isPresented = false
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color.meshCyanPrimary)
                        .foregroundColor(.meshDarkBackground)
                        .font(.system(size: 15, weight: .bold))
                        .cornerRadius(10)
                    }
                }
                .padding(20)
            }
            .onAppear {
                nodeId = currentNode.nodeId
                nodeName = currentNode.nodeName
            }
        }
    }
}
