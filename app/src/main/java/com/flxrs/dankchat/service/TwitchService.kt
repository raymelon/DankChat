package com.flxrs.dankchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.preference.PreferenceManager
import com.flxrs.dankchat.MainActivity
import com.flxrs.dankchat.R
import com.flxrs.dankchat.service.irc.IrcMessage
import com.flxrs.dankchat.service.twitch.connection.WebSocketConnection
import org.koin.core.KoinComponent
import org.koin.core.get
import org.koin.core.parameter.parametersOf

class TwitchService : Service(), KoinComponent {

    private val binder = LocalBinder()
    private val repository: TwitchRepository = get()
    private val connection: WebSocketConnection = get { parametersOf(::onDisconnect, ::onMessage) }
    private lateinit var manager: NotificationManager
    private lateinit var sharedPreferences: SharedPreferences

    private var changingConfiguration = false
    private var nick = ""
    private var isBound = false
    var startedConnection = false
        private set

    override fun onBind(p0: Intent?): IBinder? {
        stopForeground(true)
        changingConfiguration = false
        isBound = true
        return binder
    }

    override fun onRebind(intent: Intent?) {
        stopForeground(true)
        changingConfiguration = false
        isBound = true

        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!changingConfiguration) {
            val title = getString(R.string.notification_title)
            val message = getString(R.string.notification_message)

            val pendingStartActivityIntent = Intent(this, MainActivity::class.java).let {
                PendingIntent.getActivity(this, 0, it, 0)
            }

            val pendingStopIntent = Intent(this, TwitchService::class.java).let {
                it.action = STOP_COMMAND
                PendingIntent.getService(this, 0, it, 0)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID_LOW)
                .setSound(null)
                .setVibrate(null)
                .setContentTitle(title)
                .setContentText(message)
                .addAction(
                    R.drawable.ic_clear_24dp,
                    getString(R.string.notification_stop),
                    pendingStopIntent
                )
                .setStyle(MediaStyle().setShowActionsInCompactView(0))
                .setContentIntent(pendingStartActivityIntent)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .build()

            isBound = false
            startForeground(NOTIFICATION_ID, notification)
        }

        return true
    }

    inner class LocalBinder(val service: TwitchService = this@TwitchService) : Binder()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        changingConfiguration = true
    }

    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val name = getString(R.string.app_name)
            val channel = NotificationChannel(
                CHANNEL_ID_LOW,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }

            val mentionChannel = NotificationChannel(
                CHANNEL_ID_DEFAULT,
                "Mentions",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(mentionChannel)
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_COMMAND) {
            //manager.cancelAll()
            stopForeground(true)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @Synchronized
    fun connect(nick: String, oauth: String) {
        if (!startedConnection) {
            connection.connect(nick, oauth)
            startedConnection = true
            this.nick = nick
        }
    }

    fun joinChannel(channel: String) = connection.joinChannel(channel)

    fun partChannel(channel: String) = connection.partChannel(channel)

    fun sendMessage(channel: String, input: String) = repository.sendMessage(channel, input) {
        connection.sendMessage(it)
    }

    @Synchronized
    fun reconnect(onlyIfNecessary: Boolean) {
        connection.reconnect(onlyIfNecessary)
    }

    @Synchronized
    fun close(onClosed: () -> Unit) {
        startedConnection = false
        connection.close(onClosed)
    }

    private fun onMessage(message: IrcMessage) {
        val messages = repository.onMessage(message, connection.isJustinFan)
        if (!isBound) messages?.filter { it.message.isMention(nick) }?.map {
            if (sharedPreferences.getBoolean(
                    getString(R.string.preference_notification_key),
                    true
                )
            ) {
                createMentionNotification(it.message.channel, it.message.name, it.message.message)
            }
        }
    }

    private fun createMentionNotification(channel: String, user: String, message: String) {
        val pendingStartActivityIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, 0)
        }

        val summary = NotificationCompat.Builder(this, CHANNEL_ID_DEFAULT)
            .setContentTitle("You have new Mentions")
            .setContentText("")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setGroup(MENTION_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_DEFAULT)
            .setContentTitle("$user just mentioned you in #$channel")
            .setContentText(message)
            .setContentIntent(pendingStartActivityIntent)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setAutoCancel(true)
            .setGroup(MENTION_GROUP)
            .build()

        manager.notify(notificationId, notification)
        manager.notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    private fun onDisconnect() = repository.handleDisconnect()

    companion object {
        private const val CHANNEL_ID_LOW = "com.flxrs.dankchat.dank_id"
        private const val CHANNEL_ID_DEFAULT = "com.flxrs.dankchat.very_dank_id"
        private const val MENTION_GROUP = "dank_group"
        private const val NOTIFICATION_ID = 77777
        private const val SUMMARY_NOTIFICATION_ID = 12345
        private const val STOP_COMMAND = "STOP_DANKING"

        private var notificationId = 42
            get() = field++
    }
}