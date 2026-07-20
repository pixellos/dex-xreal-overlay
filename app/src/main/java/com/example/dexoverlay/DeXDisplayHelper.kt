package com.example.dexoverlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

object DeXDisplayHelper {

    /**
     * Finds the Samsung DeX / XREAL secondary display if available.
     * Prioritizes Presentation displays (DeX desktop / XREAL glasses).
     * Safely falls back to DEFAULT_DISPLAY if no external display is plugged in.
     */
    fun getTargetDisplay(context: Context): Display {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        if (displays.isEmpty()) {
            return displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        }

        // Prioritize external presentation display (DeX / XREAL 1s)
        val secondaryDisplay = displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY && 
                    ((display.flags and Display.FLAG_PRESENTATION) != 0 ||
                     display.name.lowercase().contains("xreal") ||
                     display.name.lowercase().contains("dex"))
        } ?: displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

        return secondaryDisplay ?: (displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: displays[0])
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
