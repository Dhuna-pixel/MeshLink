import SwiftUI

/**
 * Modern Engineering Dark Theme color palette for MeshLink iOS.
 * Exact 1-to-1 match with Android MeshLink theme colors.
 */
public extension Color {
    static let meshDarkBackground = Color(red: 15/255, green: 23/255, blue: 42/255)       // #0F172A Deep Slate
    static let meshDarkSurface = Color(red: 30/255, green: 41/255, blue: 59/255)          // #1E293B Slate Card Surface
    static let meshDarkSurfaceVariant = Color(red: 51/255, green: 65/255, blue: 85/255)   // #334155

    static let meshCyanPrimary = Color(red: 0/255, green: 229/255, blue: 255/255)        // #00E5FF Vibrant Cyan
    static let meshCyanContainer = Color(red: 0/255, green: 77/255, blue: 97/255)        // #004D61
    static let meshGreenAccent = Color(red: 0/255, green: 230/255, blue: 118/255)        // #00E676 Success Green
    static let meshGreenContainer = Color(red: 0/255, green: 77/255, blue: 37/255)       // #004D25
    static let meshPurpleAccent = Color(red: 179/255, green: 136/255, blue: 255/255)     // #B388FF Destination Purple
    static let meshAmberWarning = Color(red: 255/255, green: 171/255, blue: 0/255)       // #FFAB00 Warning Amber
    static let meshRedError = Color(red: 255/255, green: 82/255, blue: 82/255)           // #FF5252 Error Red

    static let textPrimary = Color(red: 248/255, green: 250/255, blue: 252/255)          // #F8FAFC
    static let textSecondary = Color(red: 148/255, green: 163/255, blue: 184/255)       // #94A3B8
    static let textTertiary = Color(red: 100/255, green: 116/255, blue: 139/255)        // #64748B

    static let codeLogBackground = Color(red: 2/255, green: 6/255, blue: 23/255)         // #020617 Terminal Deep Black
}
