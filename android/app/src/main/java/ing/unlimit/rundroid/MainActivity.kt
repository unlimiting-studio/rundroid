package ing.unlimit.rundroid

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
import ing.unlimit.rundroid.command.CommandRouter
import ing.unlimit.rundroid.databinding.ActivityMainBinding
import ing.unlimit.rundroid.service.RemoteControlAccessibilityService
import ing.unlimit.rundroid.service.ScreenCaptureService
import ing.unlimit.rundroid.websocket.MessageHandler
import ing.unlimit.rundroid.websocket.WebSocketManager
import okio.ByteString

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "remote_control_prefs"
        private const val SERVER_URL_KEY = "server_url"
        private const val DEVICE_TOKEN_KEY = "device_token"
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
        val savedToken = sharedPreferences.getString(DEVICE_TOKEN_KEY, "").orEmpty()
        binding.editAuthToken.setText(savedToken)
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
        val authToken = binding.editAuthToken.text?.toString()?.trim().orEmpty()
        sharedPreferences.edit().putString(DEVICE_TOKEN_KEY, authToken).apply()
        binding.tvStatus.text = "Connecting..."
        webSocketManager.connect(url, authToken)
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
