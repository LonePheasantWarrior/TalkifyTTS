package com.github.lonepheasantwarrior.talkify

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Process
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

object TalkifyExceptionHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "TalkifyException"

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        TtsLogger.i("Global exception handler initialized", tag = TAG)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        TtsLogger.e("Uncaught exception caught", throwable = throwable, tag = TAG)

        val context = TalkifyAppHolder.getContext()
        if (context != null) {
            showCrashDialog(context, throwable)
        }

        previousHandler?.uncaughtException(thread, throwable)
            ?: Process.killProcess(Process.myPid())
    }

    private fun showCrashDialog(context: Context, throwable: Throwable) {
        val errorMessage = buildErrorMessage(throwable)

        val title = context.getString(R.string.crash_dialog_title)
        val message = context.getString(R.string.crash_dialog_message, errorMessage)
        val positiveButton = context.getString(R.string.crash_dialog_restart)
        val negativeButton = context.getString(R.string.crash_dialog_report)

        try {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveButton) { _, _ ->
                    restartApp(context)
                }
                .setNegativeButton(negativeButton) { _, _ ->
                    TtsLogger.d("User chose to report crash", tag = TAG)
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            TtsLogger.e("Failed to show crash dialog", throwable = e, tag = TAG)
        }
    }

    private fun buildErrorMessage(throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine(throwable.javaClass.simpleName)
        sb.appendLine(throwable.message ?: "Unknown error")

        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 3) {
            sb.appendLine("Caused by: ${cause.javaClass.simpleName}")
            sb.appendLine(cause.message ?: "Unknown error")
            cause = cause.cause
            depth++
        }

        return sb.toString().take(500)
    }

    private fun restartApp(context: Context) {
        try {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Process.killProcess(Process.myPid())
        } catch (e: Exception) {
            TtsLogger.e("Failed to restart app", throwable = e, tag = TAG)
        }
    }
}

object TalkifyAppHolder {
    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    fun getContext(): Context? = appContext
}
