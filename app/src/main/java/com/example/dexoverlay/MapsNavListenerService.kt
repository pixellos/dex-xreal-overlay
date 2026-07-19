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
            val subText = extras.getCharSequence("android.subText")?.toString() ?: ""

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val intent = Intent(ACTION_NAV_UPDATE).apply {
                    putExtra(EXTRA_IS_NAV_ACTIVE, true)
                    putExtra(EXTRA_NAV_TITLE, title)
                    putExtra(EXTRA_NAV_TEXT, text)
                    putExtra(EXTRA_NAV_SUBTEXT, subText)
                }
                sendBroadcast(intent)
            }
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
        const val EXTRA_NAV_TITLE = "nav_title"
        const val EXTRA_NAV_TEXT = "nav_text"
        const val EXTRA_NAV_SUBTEXT = "nav_subtext"
    }
}
