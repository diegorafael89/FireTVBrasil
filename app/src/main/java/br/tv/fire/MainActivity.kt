package br.tv.fire

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Channel(val name: String, val group: String, val url: String)

class MainActivity : Activity() {

    // Lista pública do projeto iptv-org (GitHub) - canais do Brasil
    private val playlistUrl = "https://iptv-org.github.io/iptv/countries/br.m3u"

    // Endereço da CNN Brasil usado se a lista não puder ser baixada ou não trouxer a CNN
    private val cnnFallback = Channel(
        "CNN Brasil", "News", "https://d25usgadhphvwi.cloudfront.net/hls/main.m3u8"
    )

    private lateinit var panel: View
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private lateinit var adapter: ArrayAdapter<String>
    private var player: ExoPlayer? = null
    private val channels = mutableListOf<Channel>()
    private val names = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var current: Channel? = null
    private var cnnCandidates: List<Channel> = emptyList()
    private var retry = 0
    private var restartPending = false
    private var lastPos = -1L
    private var lastProgressAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        panel = findViewById(R.id.panel)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        playerView = findViewById(R.id.player)
        playerView.keepScreenOn = true

        adapter = ArrayAdapter(this, R.layout.item_channel, names)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(channels[pos]) }

        player = ExoPlayer.Builder(this).build().also { p ->
            p.setWakeMode(C.WAKE_MODE_NETWORK)
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = scheduleRestart()

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> retry = 0
                        Player.STATE_ENDED -> scheduleRestart()
                    }
                }

                // Qualquer tentativa de pausar é revertida na hora
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && current != null) p.playWhenReady = true
                }
            })
        }

        // Inicia imediatamente na CNN (sem esperar a verificação da lista inteira)
        play(cnnFallback)
        loadChannels()
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun isCnn(ch: Channel) = ch.name.contains("cnn", ignoreCase = true)

    private fun loadChannels() {
        Thread {
            val all = try {
                parse(URL(playlistUrl).openStream().bufferedReader().readText())
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Erro ao baixar a lista: ${e.message} (tocando CNN direto)"
                }
                return@Thread
            }

            // CNN entra na lista sem validação (o player já tem reconexão automática)
            val cnn = all.filter { isCnn(it) }.sortedByDescending { it.name.contains("brasil", true) }
            val rest = all.filterNot { isCnn(it) }
            runOnUiThread {
                cnnCandidates = cnn
                cnn.forEach { channels.add(it); names.add(it.name) }
                adapter.notifyDataSetChanged()
                cnn.firstOrNull()?.let { play(it) }
            }

            val total = rest.size
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(16)
            rest.forEach { ch ->
                pool.submit {
                    val ok = isAlive(ch.url)
                    val n = done.incrementAndGet()
                    runOnUiThread {
                        if (ok) {
                            channels.add(ch); names.add(ch.name); adapter.notifyDataSetChanged()
                        }
                        status.text = if (n == total) "${channels.size} canais ativos"
                        else "Verificando $n/$total - ${channels.size} ativos"
                    }
                }
            }
            pool.shutdown()
        }.start()
    }

    private fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name: String? = null
        var group = ""
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",").trim()
                group = Regex("group-title=\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: ""
            } else if (l.isNotEmpty() && !l.startsWith("#") && name != null) {
                out.add(Channel(name, group, l)); name = null
            }
        }
        return out.distinctBy { it.url }
    }

    // Só mantém streams que realmente respondem agora
    private fun isAlive(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; AFTMM) TVBrasil")
        if (c.responseCode !in 200..299) { c.disconnect(); false } else {
            val buf = ByteArray(512)
            val n = c.inputStream.read(buf)
            c.disconnect()
            if (n <= 0) false
            else if (url.contains(".m3u8")) String(buf, 0, n).contains("#EXTM3U")
            else true
        }
    } catch (e: Exception) { false }

    // ---------- Reprodução contínua ----------

    private fun play(ch: Channel) {
        current = ch
        retry = 0
        handler.removeCallbacks(restartRunnable)
        restartPending = false
        startStream(ch)
        panel.visibility = View.GONE
    }

    private fun startStream(ch: Channel) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(ch.url))
            prepare()
            playWhenReady = true
        }
        lastPos = -1L
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    private val restartRunnable = Runnable {
        restartPending = false
        // A cada 3 falhas seguidas tenta outra fonte da CNN, se existir
        if (retry > 0 && retry % 3 == 0 && cnnCandidates.size > 1 && current?.let { isCnn(it) } == true) {
            val next = cnnCandidates[(retry / 3) % cnnCandidates.size]
            current = next
        }
        current?.let { startStream(it) }
    }

    // Reconecta com espera crescente: 2s, 4s, 8s, 16s e no máximo 30s
    private fun scheduleRestart() {
        if (restartPending) return
        restartPending = true
        val delay = minOf(2000L shl minOf(retry, 4), 30000L)
        retry++
        handler.postDelayed(restartRunnable, delay)
    }

    // Vigia a cada 10s: despausa, reconecta se parou ou se a imagem congelou
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player
            val ch = current
            if (p != null && ch != null) {
                if (!p.playWhenReady) p.playWhenReady = true
                val st = p.playbackState
                val now = SystemClock.elapsedRealtime()
                if (st == Player.STATE_IDLE || st == Player.STATE_ENDED) {
                    scheduleRestart()
                } else {
                    val pos = p.currentPosition
                    if (pos != lastPos) { lastPos = pos; lastProgressAt = now }
                    else if (now - lastProgressAt > STALL_MS) { startStream(ch) }
                }
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun showPanel() { panel.visibility = View.VISIBLE; list.requestFocus() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Botões de mídia do controle nunca pausam o canal
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.playWhenReady = true
                return true
            }
        }
        if (panel.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
             keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            showPanel(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Voltar só alterna a lista; não fecha o app (a tecla Home sai, o canal continua)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility != View.VISIBLE) showPanel() else panel.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        player?.let {
            it.playWhenReady = true
            if (it.playbackState == Player.STATE_IDLE) current?.let { ch -> startStream(ch) }
        }
    }

    // Sem pause no onStop: o canal segue ativo mesmo em segundo plano
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        player?.release(); player = null
    }

    private companion object {
        const val WATCHDOG_MS = 10_000L
        const val STALL_MS = 25_000L
    }
}
