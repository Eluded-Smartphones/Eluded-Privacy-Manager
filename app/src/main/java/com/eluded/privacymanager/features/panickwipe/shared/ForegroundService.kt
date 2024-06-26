package com.eluded.privacymanager.features.panickwipe.shared

import android.app.KeyguardManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

import com.eluded.privacymanager.Preferences
import com.eluded.privacymanager.R
import com.eluded.privacymanager.Trigger
import com.eluded.privacymanager.Utils
import com.eluded.privacymanager.features.panickwipe.trigger.lock.LockJobManager
import com.eluded.privacymanager.features.panickwipe.trigger.usb.UsbReceiver

class ForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1000
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    }

    private lateinit var prefs: Preferences
    private lateinit var lockReceiver: LockReceiver
    private val usbReceiver = UsbReceiver()

    override fun onCreate() {
        super.onCreate()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        deinit()
    }

    private fun init() {
        prefs = Preferences.new(this)
        lockReceiver = LockReceiver(getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val triggers = prefs.triggers
        Log.d("DEBUG", "Service Started");
        if (triggers.and(Trigger.LOCK.value) != 0)
            registerReceiver(lockReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        if (triggers.and(Trigger.USB.value) != 0)
            registerReceiver(usbReceiver, IntentFilter(ACTION_USB_STATE))
    }

    private fun deinit() {
        val unregister: (BroadcastReceiver) -> Unit = {
            try { unregisterReceiver(it) } catch (exc: IllegalArgumentException) {}
        }
        unregister(lockReceiver)
        unregister(usbReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationManager.CHANNEL_DEFAULT_ID)
                .setContentTitle(getString(R.string.foreground_service_notification_title))
                .setSmallIcon(android.R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    private class LockReceiver(private var locked: Boolean) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Preferences.new(context ?: return).triggers.and(Trigger.LOCK.value) == 0)
                return
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    locked = false
                    LockJobManager(context).cancel()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (locked) return
                    locked = true
                    Thread(Runner(context, goAsync())).start()
                }
            }
        }

        private class Runner(
            private val ctx: Context,
            private val pendingResult: PendingResult,
        ) : Runnable {
            override fun run() {
                val job = LockJobManager(ctx)
                var delay = 1000L
                while (job.schedule() != JobScheduler.RESULT_SUCCESS) {
                    Thread.sleep(delay)
                    delay = delay.shl(1)
                }
                pendingResult.finish()
            }
        }
    }
}