package com.neartalk.app

import android.content.Context

object NearTalkRuntime {
    @Volatile
    private var managerInstance: NearbyVoiceManager? = null

    fun manager(context: Context): NearbyVoiceManager =
        managerInstance ?: synchronized(this) {
            managerInstance ?: NearbyVoiceManager(context.applicationContext).also {
                managerInstance = it
            }
        }
}
