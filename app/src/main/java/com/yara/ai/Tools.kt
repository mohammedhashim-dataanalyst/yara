package com.yara.ai

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminders", "Yara reminders", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(context, "reminders")
            .setContentTitle("Yara ⏰")
            .setContentText(intent.getStringExtra("text") ?: "Reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true).build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}

object Tools {
    private val http = OkHttpClient()

    /** Reminder via AlarmManager (inexact, no special permission). Lost if the phone reboots. */
    fun remind(ctx: Context, minutes: Int, text: String): String {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(ctx, (at % 1_000_000_000L).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return "⏰ Reminder set for " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(at)) + ": " + text
    }

    fun openApp(ctx: Context, name: String): Boolean {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, 0).firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) } ?: return false
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    /** Free job boards: Remotive + Arbeitnow. Blocking - call from a background thread. */
    fun searchJobs(query: String): List<Link> {
        val out = mutableListOf<Link>()
        try {
            val url = "https://remotive.com/api/remote-jobs".toHttpUrl().newBuilder()
                .addQueryParameter("search", query).addQueryParameter("limit", "8").build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val jobs = JSONObject(r.body?.string().orEmpty()).optJSONArray("jobs")
                if (jobs != null) for (i in 0 until minOf(jobs.length(), 8)) {
                    val j = jobs.getJSONObject(i)
                    out.add(Link("${j.optString("title")} - ${j.optString("company_name")} (${j.optString("candidate_required_location")})", j.optString("url")))
                }
            }
        } catch (e: Exception) { }
        try {
            val words = Regex("\\w+").findAll(query.lowercase()).map { it.value }.filter { it.length > 2 }.toList()
            http.newCall(Request.Builder().url("https://www.arbeitnow.com/api/job-board-api").build()).execute().use { r ->
                val data = JSONObject(r.body?.string().orEmpty()).optJSONArray("data")
                var n = 0
                if (data != null) for (i in 0 until data.length()) {
                    val j = data.getJSONObject(i)
                    val title = j.optString("title")
                    if (words.any { title.lowercase().contains(it) }) {
                        out.add(Link("$title - ${j.optString("company_name")} (${j.optString("location")})", j.optString("url")))
                        if (++n >= 5) break
                    }
                }
            }
        } catch (e: Exception) { }
        return out
    }
}
