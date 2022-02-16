package me.lucky.wasted

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        val prefs = Preferences(context)
        if (!prefs.isServiceEnabled || prefs.maxFailedPasswordAttempts <= 0) return
        val admin = DeviceAdminManager(context)
        if (admin.getCurrentFailedPasswordAttempts() >= prefs.maxFailedPasswordAttempts)
            admin.wipeData()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        if (Preferences(context).isServiceEnabled)
            Toast.makeText(context, R.string.service_unavailable_popup, Toast.LENGTH_SHORT).show()
    }
}
