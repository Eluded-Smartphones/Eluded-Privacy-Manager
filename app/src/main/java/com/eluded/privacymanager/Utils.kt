package com.eluded.privacymanager

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.eluded.privacymanager.admin.DeviceAdminManager
import com.eluded.privacymanager.features.panickwipe.panic.PanicConnectionActivity
import com.eluded.privacymanager.features.panickwipe.panic.PanicResponderActivity
import com.eluded.privacymanager.features.panickwipe.shared.ForegroundService
import com.eluded.privacymanager.features.panickwipe.shared.RestartReceiver
import com.eluded.privacymanager.features.panickwipe.trigger.notification.NotificationListenerService
import com.eluded.privacymanager.features.panickwipe.trigger.shortcut.ShortcutActivity
import com.eluded.privacymanager.features.panickwipe.trigger.shortcut.ShortcutManager
import com.eluded.privacymanager.features.panickwipe.trigger.tile.TileService
import com.eluded.privacymanager.features.panickwipe.trigger.usb.UsbReceiver
import java.lang.reflect.InvocationTargetException


class Utils(private val ctx: Context) {
    companion object {
        fun setFlag(key: Int, value: Int, enabled: Boolean) =
            when(enabled) {
                true -> key.or(value)
                false -> key.and(value.inv())
            }
    }

    public val prefs by lazy { Preferences.new(ctx) }

    fun setEnabled(enabled: Boolean) {
        val triggers = prefs.triggers
        setPanicKitEnabled(enabled && triggers.and(Trigger.PANIC_KIT.value) != 0)
        setTileEnabled(enabled && triggers.and(Trigger.TILE.value) != 0)
        setShortcutEnabled(enabled && triggers.and(Trigger.SHORTCUT.value) != 0)
        setBroadcastEnabled(enabled && triggers.and(Trigger.BROADCAST.value) != 0)
        setNotificationEnabled(enabled && triggers.and(Trigger.NOTIFICATION.value) != 0)
        updateForegroundRequiredEnabled()
        updateApplicationEnabled()
    }

    fun setPanicKitEnabled(enabled: Boolean) {
        setComponentEnabled(PanicConnectionActivity::class.java, enabled)
        setComponentEnabled(PanicResponderActivity::class.java, enabled)
    }

    fun setTileEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            setComponentEnabled(TileService::class.java, enabled)
    }

    fun setShortcutEnabled(enabled: Boolean) {
        val shortcut = ShortcutManager(ctx)
        if (!enabled) shortcut.remove()
        setComponentEnabled(ShortcutActivity::class.java, enabled)
        if (enabled) shortcut.push()
    }

    fun setBroadcastEnabled(enabled: Boolean) =
        setComponentEnabled(TriggerReceiver::class.java, enabled)

    fun setNotificationEnabled(enabled: Boolean) =
        setComponentEnabled(NotificationListenerService::class.java, enabled)

    fun updateApplicationEnabled() {
        val prefix = "${ctx.packageName}.trigger.application"
        val options = prefs.triggerApplicationOptions
        val enabled = prefs.isEnabled && prefs.triggers.and(Trigger.APPLICATION.value) != 0
        setComponentEnabled(
            "$prefix.SignalActivity",
            enabled && options.and(ApplicationOption.SIGNAL.value) != 0,
        )
        setComponentEnabled(
            "$prefix.TelegramActivity",
            enabled && options.and(ApplicationOption.TELEGRAM.value) != 0,
        )
        setComponentEnabled(
            "$prefix.ThreemaActivity",
            enabled && options.and(ApplicationOption.THREEMA.value) != 0,
        )
        setComponentEnabled(
            "$prefix.SessionActivity",
            enabled && options.and(ApplicationOption.SESSION.value) != 0,
        )
    }

    fun updateForegroundRequiredEnabled() {
        val enabled = prefs.isEnabled
        val triggers = prefs.triggers
        val isUSB = triggers.and(Trigger.USB.value) != 0
        val foregroundEnabled = enabled && (triggers.and(Trigger.LOCK.value) != 0 || isUSB)
        setForegroundEnabled(foregroundEnabled)
        setComponentEnabled(RestartReceiver::class.java, foregroundEnabled)
        setComponentEnabled(UsbReceiver::class.java, enabled && isUSB)
    }

    private fun setForegroundEnabled(enabled: Boolean) =
        Intent(ctx.applicationContext, ForegroundService::class.java).also {
            if (enabled) ContextCompat.startForegroundService(ctx.applicationContext, it)
            else ctx.stopService(it)
        }

    private fun setComponentEnabled(cls: Class<*>, enabled: Boolean) =
        setComponentEnabled(ComponentName(ctx, cls), enabled)

    private fun setComponentEnabled(cls: String, enabled: Boolean) =
        setComponentEnabled(ComponentName(ctx, cls), enabled)

    private fun setComponentEnabled(componentName: ComponentName, enabled: Boolean) =
        ctx.packageManager.setComponentEnabledSetting(
            componentName,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

    fun fire(trigger: Trigger, safe: Boolean = true) {
        if (!prefs.isEnabled || prefs.triggers.and(trigger.value) == 0) return

        val admin = DeviceAdminManager(ctx)
        try {
            admin.lockNow()
            if (prefs.isWipeData && safe) admin.wipeData() else Toast.makeText(ctx, "Panic Wipe Triggered", Toast.LENGTH_SHORT).show();
        } catch (exc: SecurityException) {}
        if (prefs.isRecastEnabled && safe) recast()
    }

    fun isDeviceLocked() = ctx.getSystemService(KeyguardManager::class.java).isDeviceLocked

    fun isPackageInstalled(
        packageName: String,
        packageManager: PackageManager
    ): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    @Throws(
        ClassNotFoundException::class,
        NoSuchMethodException::class,
        InvocationTargetException::class,
        IllegalAccessException::class
    )
    public fun getSystemProperty(key: String, defValue: String): String {
        // Types of parameters
        val paramTypes = arrayOf<Class<*>>(
            String::class.java,
            String::class.java
        )
        // Parameters
        val params = arrayOf<Any>(key, defValue)
        // Target class
        val c = Class.forName("android.os.SystemProperties")
        // Target method
        val m = c.getDeclaredMethod("get", *paramTypes)
        // Invoke
        return m.invoke(c, *params) as String
    }
    private fun recast() {
        val action = prefs.recastAction
        if (action.isEmpty()) return
        ctx.sendBroadcast(Intent(action).apply {
            val cls = prefs.recastReceiver.split('/')
            val packageName = cls.firstOrNull() ?: ""
            if (packageName.isNotEmpty()) {
                setPackage(packageName)
                if (cls.size == 2)
                    setClassName(
                        packageName,
                        "$packageName.${cls[1].trimStart('.')}",
                    )
            }
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val extraKey = prefs.recastExtraKey
            if (extraKey.isNotEmpty()) putExtra(extraKey, prefs.recastExtraValue)
        })
    }

    fun updateWipeOnUSB(isChecked: Boolean) {
        prefs.wipeOnUSB = isChecked
        prefs.triggers = Utils.setFlag(prefs.triggers, Trigger.USB.value, isChecked)
        this.updateForegroundRequiredEnabled()
    }

    fun getWipeOnUSB(): Boolean {
        return prefs.triggers.and(Trigger.USB.value) != 0;
    }

    fun updateWipeOnNotification(isChecked: Boolean) {
        prefs.triggers =
            Utils.setFlag(prefs.triggers, Trigger.NOTIFICATION.value, isChecked)
        this.setNotificationEnabled(isChecked && prefs.isEnabled)
    }
    fun getWipeOnNotification(): Boolean {
        return  prefs.triggers.and(Trigger.NOTIFICATION.value) != 0
    }

}