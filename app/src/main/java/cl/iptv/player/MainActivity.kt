package cl.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Executors

private data class Channel(
    var name: String,
    var url: String,
    var group: String = "TV EN VIVO",
    var logo: String = "",
    var favorite: Boolean = false,
    var tvgId: String = ""
)

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var header: LinearLayout
    private lateinit var toolsScroll: HorizontalScrollView
    private lateinit var searchView: EditText
    private lateinit var contentArea: LinearLayout
    private lateinit var channelScroll: ScrollView
    private var fullScreen = false
    private var selectedChannel: Channel? = null
    private var player: ExoPlayer? = null
    private val channels = mutableListOf<Channel>()
    private var selectedCategory = "TV EN VIVO"
    private var searchTerm = ""
    private var exportItems: List<Channel> = emptyList()
    private var backupMode = false
    private var playlistName = "Mi lista IPTV"
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val maxVisibleChannels = 300

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readPlaylist(uri)
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) writePlaylist(uri)
    }
    private val backupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        player = ExoPlayer.Builder(this).build()
        loadSaved()
        buildUi()
        renderChannels()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(0xFFF3F4F6.toInt()); setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setOnClickListener { action() }
        isFocusable = true; isFocusableInTouchMode = true
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080D18.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("MI IPTV", 23f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⛶ Pantalla completa") { toggleFullscreen() })
        header.addView(button("＋ Importar") { importFile.launch(arrayOf("*/*")) })
        header.addView(button("⋮") { managePlaylist() })
        root.addView(header)

        status = text("$playlistName · Importa una lista M3U para comenzar", 12f).apply {
            setTextColor(0xFF9AA9BD.toInt())
        }
        root.addView(status)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("TV EN VIVO", "VOD", "SERIES", "TELENOVELAS", "DEPORTES", "FAVORITOS", "TODAS").forEach { category ->
            tools.addView(button(category) { selectedCategory = category; renderChannels() })
        }
        toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        root.addView(toolsScroll)

        searchView = EditText(this).apply {
            hint = "Buscar canales…"
            setHintTextColor(0xFF8190A5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(true)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(0xFF151F30.toInt())
            setOnEditorActionListener { v, _, _ ->
                searchTerm = v.text.toString().trim()
                renderChannels()
                true
            }
        }
        root.addView(searchView, LinearLayout.LayoutParams(-1, dp(46)))

        contentArea = LinearLayout(this).apply {
            orientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setBackgroundColor(0xFF000000.toInt())
            controllerAutoShow = true
        }
        channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        channelScroll.addView(listBox)
        if (contentArea.orientation == LinearLayout.HORIZONTAL) {
            contentArea.addView(playerView, LinearLayout.LayoutParams(0, -1, 0.64f))
            contentArea.addView(channelScroll, LinearLayout.LayoutParams(0, -1, 0.36f))
        } else {
            contentArea.addView(playerView, LinearLayout.LayoutParams(-1, dp(220)))
            contentArea.addView(channelScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(contentArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun toggleFullscreen() {
        fullScreen = !fullScreen
        val immersive = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        window.decorView.systemUiVisibility = if (fullScreen) immersive else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        header.visibility = if (fullScreen) View.GONE else View.VISIBLE
        status.visibility = if (fullScreen) View.GONE else View.VISIBLE
        toolsScroll.visibility = if (fullScreen) View.GONE else View.VISIBLE
        searchView.visibility = if (fullScreen) View.GONE else View.VISIBLE
        channelScroll.visibility = if (fullScreen) View.GONE else View.VISIBLE
        contentArea.orientation = LinearLayout.VERTICAL
        playerView.layoutParams = if (fullScreen) {
            LinearLayout.LayoutParams(-1, -1)
        } else if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayout.LayoutParams(0, -1, 0.64f)
        } else {
            LinearLayout.LayoutParams(-1, dp(220))
        }
        if (!fullScreen && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            contentArea.orientation = LinearLayout.HORIZONTAL
            playerView.layoutParams = LinearLayout.LayoutParams(0, -1, 0.64f)
        }
        if (!fullScreen) {
            channelScroll.layoutParams = if (contentArea.orientation == LinearLayout.HORIZONTAL)
                LinearLayout.LayoutParams(0, -1, 0.36f) else LinearLayout.LayoutParams(-1, 0, 1f)
        }
        header.getChildAt(1)?.let { (it as? Button)?.text = if (fullScreen) "Salir ⛶" else "⛶ Pantalla completa" }
    }

    @Deprecated("Deprecated in Android API 33; retained for older device back navigation")
    override fun onBackPressed() {
        if (fullScreen) {
            toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }
    private fun renderChannels() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val filtered = channels.filter { channel ->
            val categoryMatch = when (selectedCategory) {
                "TODAS" -> true
                "FAVORITOS" -> channel.favorite
                else -> channel.group.equals(selectedCategory, true)
            }
            categoryMatch && (searchTerm.isBlank() ||
                channel.name.contains(searchTerm, true) ||
                channel.group.contains(searchTerm, true))
        }
        status.text = "$playlistName  ·  ${channels.size} elementos  ·  $selectedCategory"
        if (filtered.isEmpty()) {
            listBox.addView(text("Todavía no hay canales aquí.\nImporta una lista M3U para comenzar.", 14f))
            return
        }
        val visibleChannels = filtered.take(maxVisibleChannels)
        if (filtered.size > maxVisibleChannels) {
            listBox.addView(text("Mostrando ${maxVisibleChannels} de ${filtered.size}. Busca para encontrar otros.", 12f))
        }
        var lastEpisodeHeader = ""
        visibleChannels.forEach { channel ->
            if (channel.group.equals("SERIES", true) || channel.group.equals("TELENOVELAS", true)) {
                val ep = episodeInfo(channel.name)
                val heading = "${ep.series} · Temporada ${ep.season}"
                if (heading != lastEpisodeHeader) {
                    listBox.addView(text(heading, 13f).apply { setTextColor(0xFF79B8FF.toInt()) })
                    lastEpisodeHeader = heading
                }
            }
            val selected = selectedChannel?.url == channel.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(7), dp(8), dp(7))
                isFocusable = true
                isClickable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) 0xFF173B61.toInt() else 0xFF111B2A.toInt())
                    cornerRadius = dp(10).toFloat()
                    if (selected) setStroke(dp(2), 0xFF45A5FF.toInt())
                }
            }
            val accent = View(this).apply {
                setBackgroundColor(if (selected) 0xFF45A5FF.toInt() else 0xFF26364B.toInt())
            }
            row.addView(accent, LinearLayout.LayoutParams(dp(3), dp(42)).apply { rightMargin = dp(9) })
            if (channel.logo.isNotBlank()) {
                val poster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(42)).apply { rightMargin = dp(9) }
                    contentDescription = "Logo de ${channel.name}"
                }
                Glide.with(this@MainActivity).load(channel.logo)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play).into(poster)
                row.addView(poster)
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(channel.name, 14f).apply {
                setPadding(0, 0, 0, dp(2))
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE5EAF2.toInt())
            })
            labels.addView(text(channel.group + if (channel.favorite) "  ★" else "", 10f).apply {
                setPadding(0, 0, 0, 0)
                setTextColor(if (selected) 0xFF9ED0FF.toInt() else 0xFF91A0B5.toInt())
            })
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                selectedChannel = channel
                play(channel)
                renderChannels()
            }
            row.setOnLongClickListener {
                val actions = arrayOf(
                    if (channel.favorite) "Quitar de favoritos" else "Agregar a favoritos",
                    "Editar elemento",
                    "Eliminar elemento"
                )
                AlertDialog.Builder(this).setTitle(channel.name).setItems(actions) { _, which ->
                    when (which) {
                        0 -> { channel.favorite = !channel.favorite; save(); renderChannels() }
                        1 -> editChannel(channel)
                        2 -> confirmDelete(channel)
                    }
                }.show()
                true
            }
            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
        }
    }

