package com.steelsignage.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Steel Signage Android TV player.
 *  1. First run: enter server URL with the remote.
 *  2. The TV shows a 6-digit pairing code -> enter it in the admin panel.
 *  3. The assigned playlist is downloaded to the device and played in a loop (works offline).
 *  4. The server is polled every 15 s; playlist changes are picked up automatically.
 */
class MainActivity : Activity() {

    companion object {
        private const val POLL_MS = 15_000L
        private const val PREFS = "steelsignage"
    }

    data class Item(val id: String, val name: String, val type: String, val url: String,
                    val file: String, val size: Long, val duration: Int)

    private val scope = MainScope()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mediaDir by lazy { File(filesDir, "media").apply { mkdirs() } }

    private lateinit var stage: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var playerView: PlayerView
    private lateinit var messageView: LinearLayout
    private lateinit var messageTitle: TextView
    private lateinit var messageCode: TextView
    private lateinit var messageHint: TextView
    private lateinit var setupView: LinearLayout
    private lateinit var serverInput: EditText

    private var player: ExoPlayer? = null
    private var pollJob: Job? = null
    private var playJob: Job? = null

    private var items: List<Item> = emptyList()
    private var version: String? = null
    private var orientation = "landscape"
    private var index = -1
    private var nowPlaying = ""
    private val backPresses = ArrayList<Long>()

    private val serverUrl get() = prefs.getString("server", "")!!.trimEnd('/')
    private val deviceId: String
        get() = prefs.getString("deviceId", null) ?: ("tv-" + UUID.randomUUID().toString()).also {
            prefs.edit().putString("deviceId", it).apply()
        }

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        stage = findViewById(R.id.stage)
        imageView = findViewById(R.id.image)
        playerView = findViewById(R.id.video)
        messageView = findViewById(R.id.message)
        messageTitle = findViewById(R.id.messageTitle)
        messageCode = findViewById(R.id.messageCode)
        messageHint = findViewById(R.id.messageHint)
        setupView = findViewById(R.id.setup)
        serverInput = findViewById(R.id.serverUrl)

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSetup() }
        findViewById<Button>(R.id.bootButton).setOnClickListener { requestOverlayPermission() }
        serverInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveSetup(); true } else false
        }

        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) next()
                }
                override fun onPlayerError(error: PlaybackException) {
                    playJob?.cancel()
                    playJob = scope.launch { delay(1000); next() }
                }
            })
        }

        if (serverUrl.isEmpty()) showSetup() else start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ---------------------------------------------------------------- remote control keys
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (setupView.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            showSetup(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Kiosk behaviour: BACK does not exit the player")
    override fun onBackPressed() {
        if (setupView.visibility == View.VISIBLE && serverUrl.isNotEmpty()) { start(); return }
        val now = System.currentTimeMillis()
        backPresses.add(now)
        backPresses.removeAll { now - it > 3000 }
        if (backPresses.size >= 5) { backPresses.clear(); showSetup() }
    }

    // ---------------------------------------------------------------- setup screen
    private fun showSetup() {
        pollJob?.cancel(); playJob?.cancel(); player?.stop()
        setupView.visibility = View.VISIBLE
        serverInput.setText(serverUrl.ifEmpty { "http://" })
        serverInput.setSelection(serverInput.text.length)
        serverInput.requestFocus()
        findViewById<TextView>(R.id.setupInfo).text =
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ID ${deviceId.takeLast(8)}"
    }

    private fun saveSetup() {
        var url = serverInput.text.toString().trim().trimEnd('/')
        if (url.isEmpty() || url == "http:" || url == "http://") return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        prefs.edit().putString("server", url).apply()
        start()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                messageToast("Open Settings › Apps › Steel Signage › Display over other apps, and allow it.")
            }
        } else messageToast("Auto-start on boot is already allowed.")
    }

    private fun messageToast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- polling
    private fun start() {
        setupView.visibility = View.GONE
        hideSystemUi()
        version = null
        if (items.isEmpty()) showMessage(getString(R.string.connecting), null, serverUrl)

        pollJob?.cancel()
        pollJob = scope.launch {
            // Offline first: play the last known playlist immediately if we have it
            prefs.getString("lastContent", null)?.let { cached ->
                try { applyContent(JSONObject(cached), fromCache = true) } catch (e: Exception) { }
            }
            var registered = false
            while (isActive) {
                try {
                    var json = withContext(Dispatchers.IO) {
                        if (!registered) register() else {
                            val q = URLEncoder.encode(nowPlaying, "UTF-8")
                            httpGet("$serverUrl/api/player/$deviceId/content?playing=$q")
                        }
                    }
                    registered = true
                    if (json.optBoolean("reRegister")) json = withContext(Dispatchers.IO) { register() }
                    applyContent(json, fromCache = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (items.isEmpty()) showMessage("Cannot reach server — retrying…", null, "$serverUrl\n${e.message ?: ""}")
                }
                delay(POLL_MS)
            }
        }
    }

    private fun register(): JSONObject {
        val info = JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("app", "0.1.0")
            .put("platform", "android")
        val body = JSONObject().put("deviceId", deviceId).put("info", info)
        return httpPost("$serverUrl/api/player/register", body.toString())
    }

    private suspend fun applyContent(json: JSONObject, fromCache: Boolean) {
        if (!json.optBoolean("paired")) {
            if (fromCache) return
            prefs.edit().remove("lastContent").apply()
            stopPlayback(); items = emptyList(); version = null
            showMessage("Enter this code in the Steel Signage admin panel → Screens", json.optString("pairCode"), serverUrl)
            return
        }
        applyOrientation(json.optString("orientation", "landscape"))
        val newVersion = json.optString("version")
        if (newVersion == version) return

        val arr = json.optJSONArray("items")
        val list = (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            Item(o.getString("id"), o.optString("name"), o.getString("type"), o.getString("url"),
                 o.getString("file"), o.optLong("size"), o.optInt("duration", 10))
        }
        if (list.isEmpty()) {
            stopPlayback(); items = emptyList(); version = newVersion
            showMessage("${json.optString("screenName", "Screen")} is paired ✓",
                null, "Assign a playlist with files to this screen in the admin panel.")
            if (!fromCache) prefs.edit().putString("lastContent", json.toString()).apply()
            return
        }

        // Download everything new before switching (keeps current content playing meanwhile)
        if (!fromCache) {
            val firstTime = items.isEmpty()
            withContext(Dispatchers.IO) {
                list.forEachIndexed { i, it ->
                    val f = File(mediaDir, it.file)
                    if (!f.exists() || (it.size > 0 && f.length() != it.size)) {
                        if (firstTime) withContext(Dispatchers.Main) {
                            showMessage("Downloading ${i + 1} of ${list.size}…", null, it.name)
                        }
                        download("$serverUrl${it.url}", f)
                    }
                }
                // Clean up files no longer used
                val keep = list.map { it.file }.toSet()
                mediaDir.listFiles()?.forEach { if (it.name !in keep && !it.name.endsWith(".part")) it.delete() }
            }
            prefs.edit().putString("lastContent", json.toString()).apply()
        }

        val playable = list.filter { File(mediaDir, it.file).exists() }
        if (playable.isEmpty()) return
        items = playable
        // If some cached files are missing, leave version unset so the next poll downloads them
        version = if (playable.size == list.size) newVersion else null
        index = -1
        hideMessage()
        next()
    }

    // ---------------------------------------------------------------- playback
    private fun next() {
        playJob?.cancel()
        if (items.isEmpty()) return
        index = (index + 1) % items.size
        val item = items[index]
        val file = File(mediaDir, item.file)
        nowPlaying = item.name

        if (item.type == "video") {
            imageView.visibility = View.GONE
            imageView.setImageDrawable(null)
            playerView.visibility = View.VISIBLE
            player?.apply {
                repeatMode = if (items.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
        } else {
            player?.stop()
            playJob = scope.launch {
                val drawable = withContext(Dispatchers.IO) { loadImage(file) }
                if (drawable == null) { delay(1000); next(); return@launch }
                imageView.setImageDrawable(drawable)
                if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) drawable.start()
                imageView.visibility = View.VISIBLE
                playerView.visibility = View.GONE
                delay(item.duration.coerceAtLeast(1) * 1000L)
                next()
            }
        }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        player?.stop()
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        playerView.visibility = View.GONE
    }

    /** Decode images scaled to the screen size so large photos don't run the TV out of memory. */
    private fun loadImage(file: File): android.graphics.drawable.Drawable? = try {
        if (Build.VERSION.SDK_INT >= 28 && file.name.endsWith(".gif", true)) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
        } else {
            val dm = resources.displayMetrics
            val maxW = maxOf(dm.widthPixels, dm.heightPixels)
            val maxH = minOf(dm.widthPixels, dm.heightPixels).let { if (orientation.startsWith("portrait")) maxW else it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW || bounds.outHeight / (sample * 2) >= maxH) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        }
    } catch (e: Throwable) { null }

    private fun applyOrientation(o: String) {
        if (o == orientation && stage.tag == o) return
        orientation = o; stage.tag = o
        val root = findViewById<View>(R.id.root)
        root.post {
            val w = root.width; val h = root.height
            val portrait = o.startsWith("portrait")
            stage.layoutParams = FrameLayout.LayoutParams(if (portrait) h else w, if (portrait) w else h).apply {
                gravity = android.view.Gravity.CENTER
            }
            stage.rotation = when (o) { "portrait" -> 90f; "portrait-flipped" -> 270f; "landscape-flipped" -> 180f; else -> 0f }
        }
    }

    // ---------------------------------------------------------------- messages
    private fun showMessage(title: String, code: String?, hint: String?) {
        messageView.visibility = View.VISIBLE
        messageTitle.text = title
        messageCode.visibility = if (code.isNullOrEmpty()) View.GONE else View.VISIBLE
        messageCode.text = code ?: ""
        messageHint.text = hint ?: ""
    }

    private fun hideMessage() { messageView.visibility = View.GONE }

    // ---------------------------------------------------------------- networking
    private fun httpGet(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            if (c.responseCode == 404) return JSONObject().put("reRegister", true)
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun httpPost(url: String, body: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("Download failed: HTTP ${c.responseCode}")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (!tmp.renameTo(dest)) throw RuntimeException("Could not save ${dest.name}")
        } finally { c.disconnect(); tmp.delete() }
    }
}
