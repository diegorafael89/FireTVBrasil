package br.tv.fire

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
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

    // Praças preferidas para a Globo (a primeira encontrada é tocada)
    private val globoPreferidas = listOf("nordeste", "recife", "pernambuco", "salvador", "bahia", "fortaleza")

    private lateinit var panel: View
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private lateinit var adapter: ArrayAdapter<String>
    private var player: ExoPlayer? = null
    private val channels = mutableListOf<Channel>()
    private val names = mutableListOf<String>()
    private var autoStarted = false
    private var currentIsGlobo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        panel = findViewById(R.id.panel)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        playerView = findViewById(R.id.player)
        playerView.keepScreenOn = true

        adapter = ArrayAdapter(this, R.layout.item_channel, names)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(channels[pos]) }
        list.setOnItemLongClickListener { _, _, _, _ -> abrirGloboplay(); true }

        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (currentIsGlobo) {
                        // Sinal público caiu: troca para o Globoplay oficial (Nordeste)
                        abrirGloboplay()
                    } else {
                        Toast.makeText(this@MainActivity, "Sinal indisponível", Toast.LENGTH_SHORT).show()
                        showPanel()
                    }
                }
            })
        }
        loadChannels()
    }

    private fun loadChannels() {
        Thread {
            val all = try {
                parse(URL(playlistUrl).openStream().bufferedReader().readText())
            } catch (e: Exception) {
                runOnUiThread { status.text = "Erro ao baixar a lista: ${e.message}" }
                return@Thread
            }
            val total = all.size
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(16)
            all.forEach { ch ->
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
                    if (n == total) runOnUiThread { autoStart() }
                }
            }
            pool.shutdown()
        }.start()
    }

    // Ao terminar a verificação, toca automaticamente a melhor Globo validada (Nordeste primeiro)
    private fun autoStart() {
        if (autoStarted) return
        autoStarted = true
        val globo = channels.filter { it.name.contains("globo", ignoreCase = true) }
        val escolhido = globoPreferidas.firstNotNullOfOrNull { praca ->
            globo.firstOrNull { it.name.contains(praca, ignoreCase = true) }
        } ?: globo.firstOrNull()

        if (escolhido != null) {
            play(escolhido)
        } else {
            // Sem Globo pública no ar: abre o Globoplay oficial (Nordeste)
            abrirGloboplay()
        }
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

    private fun play(ch: Channel) {
        currentIsGlobo = ch.name.contains("globo", ignoreCase = true)
        player?.apply { setMediaItem(MediaItem.fromUri(ch.url)); prepare(); playWhenReady = true }
        panel.visibility = View.GONE
    }

    private fun abrirGloboplay() {
        player?.pause()
        panel.visibility = View.GONE
        startActivity(Intent(this, GloboActivity::class.java))
    }

    private fun showPanel() { panel.visibility = View.VISIBLE; list.requestFocus() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (panel.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
             keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            showPanel(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility != View.VISIBLE) showPanel() else super.onBackPressed()
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release(); player = null }
}
