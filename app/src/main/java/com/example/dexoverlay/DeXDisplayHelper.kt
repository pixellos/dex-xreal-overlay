package com.example.dexoverlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

object DeXDisplayHelper {

    /**
     * Finds the Samsung DeX / XREAL secondary display if available.
     * Returns DEFAULT_DISPLAY if no external display is plugged in.
     */
    fun getTargetDisplay(context: Context): Display {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        // Find external presentation display (DeX / XREAL 1s)
        val secondaryDisplay = displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        return secondaryDisplay ?: displays.first { it.displayId == Display.DEFAULT_DISPLAY }
    }

    /**
     * Returns a WindowManager bound directly to the DeX/XREAL display surface context.
     */
    fun getDeXWindowManager(context: Context): WindowManager {
        val targetDisplay = getTargetDisplay(context)
        val displayContext = context.createDisplayContext(targetDisplay)
        return displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
}
