package com.example.remotecontrol

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.remotecontrol.command.CommandRouter
import com.example.remotecontrol.databinding.ActivityMainBinding
import com.example.remotecontrol.service.RemoteControlAccessibilityService
import com.example.remotecontrol.service.ScreenCaptureService
import com.example.remotecontrol.websocket.MessageHandler
import com.example.remotecontrol.websocket.WebSocketManager
import okio.ByteString

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "remote_control_prefs"
        private const val SERVER_URL_KEY = "server_url"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var messageHandler: MessageHandler
    private lateinit var commandRouter: CommandRouter

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        initializeScreenCaptureLauncher()
        initializeNetworkLayer()
        initializeUi()
        restoreServerUrl()
        updateAccessibilityStatus()
        updateMediaProjectionStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateMediaProjectionStatus()
    }

    override fun onDestroy() {
        webSocketManager.disconnect()
        super.onDestroy()
    }

    private fun initializeNetworkLayer() {
        webSocketManager = WebSocketManager(object : WebSocketManager.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    isConnected = true
                    binding.btnConnect.text = "Disconnect"
                    binding.tvStatus.text = "Connected"
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    isConnected = false
                    binding.btnConnect.text = "Connect"
                    binding.tvStatus.text = "Disconnected"
                }
            }

            override fun onMessage(text: String) {
                messageHandler.handleMessage(text)
            }

            override fun onBinaryMessage(bytes: ByteString) {
                Log.d(TAG, "Received binary message (${bytes.size} bytes)")
            }
        })

        commandRouter = CommandRouter(this, webSocketManager)
        messageHandler = MessageHandler(commandRouter)
    }

    private fun initializeUi() {
        binding.btnConnect.setOnClickListener { toggleConnection() }

        binding.btnA11ySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnMediaProjection.setOnClickListener {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun initializeScreenCaptureLauncher() {
        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                binding.tvMediaProjectionStatus.text = "Screen Capture: Granted"
            } else {
                binding.tvMediaProjectionStatus.text = "Screen Capture: Not Granted"
            }
        }
    }

    private fun restoreServerUrl() {
        val savedUrl = sharedPreferences.getString(SERVER_URL_KEY, "").orEmpty()
        binding.editServerUrl.setText(savedUrl)
    }

    private fun toggleConnection() {
        if (isConnected) {
            webSocketManager.disconnect()
            return
        }

        val url = binding.editServerUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            binding.tvStatus.text = "Server URL is required"
            return
        }

        sharedPreferences.edit().putString(SERVER_URL_KEY, url).apply()
        binding.tvStatus.text = "Connecting..."
        webSocketManager.connect(url)
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        binding.tvA11yStatus.text = if (isEnabled) {
            "Accessibility Service: Enabled"
        } else {
            "Accessibility Service: Disabled"
        }
    }

    private fun updateMediaProjectionStatus() {
        binding.tvMediaProjectionStatus.text = if (ScreenCaptureService.mediaProjection != null) {
            "Screen Capture: Granted"
        } else {
            "Screen Capture: Not Granted"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val componentName = ComponentName(this, RemoteControlAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        val targetFullName = componentName.flattenToString()
        val targetShortName = componentName.flattenToShortString()
        return enabledServices.split(':').any { enabled ->
            enabled.equals(targetFullName, ignoreCase = true) ||
                enabled.equals(targetShortName, ignoreCase = true)
        }
    }
}
