package com.thatspot.app

import android.app.Application

class ThatSpotApp : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this).also { it.start() }
    }
}
