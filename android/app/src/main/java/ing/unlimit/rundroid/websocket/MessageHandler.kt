package ing.unlimit.rundroid.websocket

import android.util.Log
import ing.unlimit.rundroid.command.CommandRouter
import ing.unlimit.rundroid.model.DeviceCommand
import com.google.gson.Gson

class MessageHandler(private val commandRouter: CommandRouter) {
    companion object {
        private const val TAG = "MessageHandler"
    }

    private val gson = Gson()

    fun handleMessage(text: String) {
        try {
            val command = gson.fromJson(text, DeviceCommand::class.java)
            commandRouter.route(command)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming message: $text", e)
        }
    }
}
