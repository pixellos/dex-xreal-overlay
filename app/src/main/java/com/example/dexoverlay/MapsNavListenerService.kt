package com.example.dexoverlay

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MapsNavListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (sbn.packageName == "com.google.android.apps.maps") {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val arrow = parseVectorArrow("$title $text")
                val intent = Intent(ACTION_NAV_UPDATE).apply {
                    putExtra(EXTRA_IS_NAV_ACTIVE, true)
                    putExtra(EXTRA_NAV_ARROW, arrow)
                    putExtra(EXTRA_NAV_TITLE, title)
                    putExtra(EXTRA_NAV_TEXT, text)
                }
                sendBroadcast(intent)
            }
        }
    }

    private fun parseVectorArrow(rawText: String): String {
        val lower = rawText.lowercase()
        return when {
            lower.contains("slight right") || lower.contains("bear right") -> "↗"
            lower.contains("slight left") || lower.contains("bear left") -> "↖"
            lower.contains("right") -> "🠺"
            lower.contains("left") -> "🠸"
            lower.contains("u-turn") -> "⤺"
            lower.contains("straight") || lower.contains("continue") -> "🠹"
            else -> "🠹"
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == "com.google.android.apps.maps") {
            val intent = Intent(ACTION_NAV_UPDATE).apply {
                putExtra(EXTRA_IS_NAV_ACTIVE, false)
            }
            sendBroadcast(intent)
        }
    }

    companion object {
        const val ACTION_NAV_UPDATE = "com.example.dexoverlay.NAV_UPDATE"
        const val EXTRA_IS_NAV_ACTIVE = "is_nav_active"
        const val EXTRA_NAV_ARROW = "nav_arrow"
        const val EXTRA_NAV_TITLE = "nav_title"
        const val EXTRA_NAV_TEXT = "nav_text"
    }
}
