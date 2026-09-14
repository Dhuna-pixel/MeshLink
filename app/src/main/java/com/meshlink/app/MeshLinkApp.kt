package com.meshlink.app

import android.app.Application
import com.meshlink.app.data.repository.MeshRepository

/**
 * Application class providing dependency container for repository.
 */
class MeshLinkApp : Application() {

    lateinit var meshRepository: MeshRepository
        private set

    override fun onCreate() {
        super.onCreate()
        meshRepository = MeshRepository(this)
    }
}
