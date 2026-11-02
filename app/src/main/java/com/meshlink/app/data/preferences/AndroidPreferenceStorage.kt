package com.meshlink.app.data.preferences

import android.content.Context

class AndroidPreferenceStorage(context: Context, name: String) : PreferenceStorage {
    private val preferences = context.getSharedPreferences(name,Context.MODE_PRIVATE)
    override fun get(key: String): String? = preferences.getString(key,null)
    override fun put(key: String, value: String) { preferences.edit().putString(key,value).apply() }
}
