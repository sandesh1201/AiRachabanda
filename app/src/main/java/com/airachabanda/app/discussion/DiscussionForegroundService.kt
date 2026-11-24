package com.airachabanda.app.discussion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.airachabanda.app.AIRachabandaApplication
import com.airachabanda.app.MainActivity
import com.airachabanda.app.orchestration.CouncilWorkflow
import com.airachabanda.app.orchestration.DiscussionNotificationCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object DiscussionRuntime {
    fun allowed(context: Context): Boolean {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun show(context: Context, conversationId: String) {
        if (!allowed(context)) return
        val intent = Intent(context, DiscussionForegroundService::class.java)
            .setAction(DiscussionForegroundService.ACTION_SHOW)
            .putExtra(DiscussionForegroundService.EXTRA_CONVERSATION, conversationId)
        ContextCompat.startForegroundService(context, intent)
    }

    fun dismiss(context: Context, conversationId: String) {
        if (!allowed(context)) return
        val intent = Intent(context, DiscussionForegroundService::class.java)
            .setAction(DiscussionForegroundService.ACTION_STOP)
            .putExtra(DiscussionForegroundService.EXTRA_CONVERSATION, conversationId)
        context.startService(intent)
    }
}

class DiscussionForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Council discussion", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = "Controls for a council discussion you started. The question stays off the lock screen."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        val action = intent?.action ?: ACTION_SHOW
        enterForeground(buildNotification("Council discussion", DiscussionNotificationCopy.publicText(), conversationId, paused = false, ongoing = true))
        scope.launch {
            runCatching {
                val app = application as AIRachabandaApplication
                val workflow = CouncilWorkflow(app.container.orchestration, app.container.councilDao, app.container.discussionDao)
                when (action) {
                    ACTION_PAUSE -> workflow.pause(conversationId)
                    ACTION_RESUME -> workflow.resume(conversationId)
                    ACTION_STOP -> workflow.stop(conversationId)
                }
                val stage = workflow.inspect(conversationId)
                val title = app.container.councilDao.conversation(conversationId)?.title ?: "Council discussion"
                val finished = action == ACTION_STOP || stage.sessionId == null || stage.stage in FINISHED
                if (finished) {
                    val notificationsPermitted = Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(this@DiscussionForegroundService, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (notificationsPermitted && NotificationManagerCompat.from(this@DiscussionForegroundService).areNotificationsEnabled()) {
                        NotificationManagerCompat.from(this@DiscussionForegroundService).notify(
                            DONE_ID,
                            buildNotification(
                                "Council discussion",
                                "Discussion ${stage.stage}. Completed ${stage.completedTasks}. Pending ${stage.pendingTasks}.",
                                conversationId,
                                paused = false,
                                ongoing = false,
                            ),
                        )
                    }
                    stopInForeground()
                    stopSelf()
                } else {
                    val text = DiscussionNotificationCopy.privateText(
                        title = title,
                        stage = stage.stage,
                        completed = stage.completedTasks,
                        pending = stage.pendingTasks,
                        active = stage.activeParticipantCount,
                        waiting = stage.waitingForUser,
                    )
                    enterForeground(buildNotification(title, text, conversationId, paused = stage.stage == "Paused", ongoing = true))
                }
            }.onFailure {
                stopInForeground()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun stopInForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(title: String, text: String, conversationId: String, paused: Boolean, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_CONVERSATION, conversationId).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        val hold = PendingIntent.getService(
            this,
            2,
            Intent(this, DiscussionForegroundService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE)
                .putExtra(EXTRA_CONVERSATION, conversationId),
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, DiscussionForegroundService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_CONVERSATION, conversationId),
            pendingFlags(),
        )
        val publicVersion = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Council discussion")
            .setContentText(DiscussionNotificationCopy.publicText())
            .build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(ongoing)
            .addAction(0, if (paused) "Resume" else "Pause", hold)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val CHANNEL = "airachabanda.discussion"
        const val EXTRA_CONVERSATION = "discussionConversationId"
        const val ACTION_SHOW = "com.airachabanda.app.discussion.SHOW"
        const val ACTION_PAUSE = "com.airachabanda.app.discussion.PAUSE"
        const val ACTION_RESUME = "com.airachabanda.app.discussion.RESUME"
        const val ACTION_STOP = "com.airachabanda.app.discussion.STOP"
        private const val ONGOING_ID = 11011
        private const val DONE_ID = 11012
        private val FINISHED = setOf("Stopped", "Completed", "Failed")
    }
}
