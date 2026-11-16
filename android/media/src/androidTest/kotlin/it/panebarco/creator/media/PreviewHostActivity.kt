package it.panebarco.creator.media

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred

/** A visible test host makes the real player's audio-focus request eligible on target 35+. */
class PreviewHostActivity : Activity() {
    val windowFocused = CompletableDeferred<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(FrameLayout(this))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) windowFocused.complete(Unit)
    }
}
