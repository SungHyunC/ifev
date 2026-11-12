package com.ifev.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ifev.app.ui.Actions
import com.ifev.app.ui.IfEvTheme
import com.ifev.app.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RecorderService.start(this)
        narrator.start()
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        if (savedInstanceState == null) handleDemoExtra(intent)
        val actions = Actions(
            onMode = viewModel::setMode,
            onTab = viewModel::selectTab,
            onModel = viewModel::selectModel,
            onHomeCharging = viewModel::answerHomeCharging,
            onMyCar = viewModel::selectMyCar,
            onMonths = viewModel::adjustMonths,
            onDemoDayZero = viewModel::demoDayZero,
            onDemoDrive = viewModel::demoDrive,
            onDemoFourWeeks = viewModel::demoFourWeeks,
            onVoice = viewModel::toggleVoice,
            onReal = viewModel::demoReal,
            onDemoRefuel = viewModel::demoRefuel,
            onWorkCharging = viewModel::answerWorkCharging,
        )
        setContent {
            IfEvTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                ui?.let { MainScreen(it, actions) }
            }
        }
    }

    /**
     * The service starts before this dialog is answered (recording must not wait on it), so a grant
     * made here is passed on: the recorder subscribes again to what the permission unlocks.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) recorder.onPermissionsChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemoExtra(intent)
    }

    /** `adb shell am start -n com.ifev.app/.MainActivity --es demo day0|drive|weeks` jumps to a demo stage for recordings. */
    private fun handleDemoExtra(intent: Intent) {
        when (intent.getStringExtra(EXTRA_DEMO)) {
            "day0" -> viewModel.demoDayZero()
            "drive" -> viewModel.demoDrive()
            "weeks" -> viewModel.demoFourWeeks()
            "refuel" -> viewModel.demoRefuel()
        }
    }

    companion object {
        const val EXTRA_DEMO = "demo"
        /** Vehicle speed fills in trip distance; the notification shows recording is on. */
        private val RUNTIME_PERMISSIONS = listOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY", "android.permission.POST_NOTIFICATIONS")
    }
}
