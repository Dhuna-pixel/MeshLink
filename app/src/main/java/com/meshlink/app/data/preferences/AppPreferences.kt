package com.meshlink.app.data.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Existing SharedPreferences architecture, abstracted for deterministic persistence tests. */
interface PreferenceStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

enum class ThemeMode(val label: String) {
    SYSTEM("System Default"), LIGHT("Light"), DARK("Dark");
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM }
}
data class AppPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notificationsEnabled: Boolean = true,
    val hideLockScreenPreview: Boolean = true,
    val notificationPermissionAsked: Boolean = false
)
class AppPreferencesStore(private val storage: PreferenceStorage) {
    private val _state = MutableStateFlow(AppPreferences(
        theme = ThemeMode.parse(storage.get("theme")),
        notificationsEnabled = storage.get("notifications") != "false",
        hideLockScreenPreview = storage.get("private_preview") != "false",
        notificationPermissionAsked = storage.get("notification_asked") == "true"
    ))
    val state = _state.asStateFlow()
    @Synchronized fun setTheme(mode: ThemeMode) { storage.put("theme",mode.name); _state.value = _state.value.copy(theme = mode) }
    @Synchronized fun setNotifications(enabled: Boolean) { storage.put("notifications",enabled.toString()); _state.value = _state.value.copy(notificationsEnabled = enabled) }
    @Synchronized fun setPrivatePreview(hide: Boolean) { storage.put("private_preview",hide.toString()); _state.value = _state.value.copy(hideLockScreenPreview = hide) }
    @Synchronized fun markPermissionAsked() { storage.put("notification_asked","true"); _state.value = _state.value.copy(notificationPermissionAsked = true) }
}
