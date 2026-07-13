package io.tima.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.tima.app.session.initSessionDir

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSessionDir(applicationContext.filesDir)
        setContent { App() }
    }
}
