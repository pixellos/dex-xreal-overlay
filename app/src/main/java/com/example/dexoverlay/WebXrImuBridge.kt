package com.example.dexoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class WebXrImuBridge(private val context: Context) {

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    var onGlassesSingleTapListener: (() -> Unit)? = null
    var onDebugLogListener: ((log: String) -> Unit)? = null

    inner class WebXRInterface {
        @JavascriptInterface
        fun log(msg: String) {
            Log.d("WebXRBridge", msg)
            onDebugLogListener?.invoke(msg)
        }

        @JavascriptInterface
        fun onPoseUpdate(deltaX: Float, deltaY: Float) {
            mainHandler.post {
                onHeadMoveListener?.invoke(deltaX, deltaY)
            }
        }

        @JavascriptInterface
        fun onTapClick() {
            mainHandler.post {
                onGlassesSingleTapListener?.invoke()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startBridge() {
        mainHandler.post {
            try {
                val wv = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    
                    addJavascriptInterface(WebXRInterface(), "AndroidBridge")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            onDebugLogListener?.invoke("WebXR HTML5 Bridge Loaded Successfully!")
                        }
                    }

                    val htmlContent = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <script>
                          let lastAlpha = 0, lastBeta = 0;
                          let initialized = false;

                          function log(msg) {
                            if (window.AndroidBridge) AndroidBridge.log(msg);
                          }

                          async function initWebXR() {
                            log("Initializing WebXR Device API Engine...");
                            if (navigator.xr) {
                              try {
                                const supported = await navigator.xr.isSessionSupported('inline');
                                log("WebXR Inline Session Supported: " + supported);
                                initSensorFallback();
                              } catch(e) {
                                log("WebXR Session Error: " + e.message);
                                initSensorFallback();
                              }
                            } else {
                              log("WebXR Device API not exposed directly to WebView. Utilizing Sensor API engine.");
                              initSensorFallback();
                            }
                          }

                          function initSensorFallback() {
                            log("Binding High-Speed Motion Orientation Sensor...");
                            window.addEventListener('deviceorientation', function(e) {
                              let alpha = e.alpha || 0; // Yaw
                              let beta = e.beta || 0;   // Pitch

                              if (!initialized) {
                                lastAlpha = alpha;
                                lastBeta = beta;
                                initialized = true;
                                log("Motion Orientation Sensor ACTIVE!");
                                return;
                              }

                              let deltaYaw = (alpha - lastAlpha);
                              if (deltaYaw > 180) deltaYaw -= 360;
                              if (deltaYaw < -180) deltaYaw += 360;

                              let deltaPitch = (beta - lastBeta);

                              lastAlpha = alpha;
                              lastBeta = beta;

                              if (Math.abs(deltaYaw) > 0.02 || Math.abs(deltaPitch) > 0.02) {
                                if (window.AndroidBridge) {
                                  AndroidBridge.onPoseUpdate(deltaYaw * 0.4, deltaPitch * 0.4);
                                }
                              }
                            }, true);

                            window.addEventListener('click', function() {
                              if (window.AndroidBridge) AndroidBridge.onTapClick();
                            });
                          }

                          window.onload = initWebXR;
                        </script>
                        </head>
                        <body style="background:#03060B;color:#00E5FF;font-family:monospace;padding:10px;">
                        WebXR Device API Engine Running...
                        </body>
                        </html>
                    """.trimIndent()

                    loadDataWithBaseURL("https://localhost", htmlContent, "text/html", "UTF-8", null)
                }

                webView = wv
                onDebugLogListener?.invoke("Started WebXR Device API Bridge WebView")
            } catch (e: Exception) {
                Log.e("WebXRBridge", "Error starting WebXR bridge", e)
                onDebugLogListener?.invoke("Error starting WebXR bridge: ${e.message}")
            }
        }
    }

    fun stopBridge() {
        mainHandler.post {
            try {
                webView?.destroy()
                webView = null
                onDebugLogListener?.invoke("Stopped WebXR Device API Bridge")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
