package dev.ujhhgtg.wekit.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

class StandardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val action = pendingAction ?: run { finish(); return }
        pendingAction = null
        action(this)
    }

    companion object {
        @Volatile
        private var pendingAction: (ComponentActivity.() -> Unit)? = null

        fun launch(context: Context, action: ComponentActivity.() -> Unit) {
            pendingAction = action
            context.startActivity(
                Intent(context, StandardActivity::class.java)
            )
        }
    }
}
