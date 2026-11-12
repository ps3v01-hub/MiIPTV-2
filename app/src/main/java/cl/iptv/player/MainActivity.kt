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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF090D16.toInt()); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("MI IPTV", 24f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("＋ Importar M3U") { importFile.launch(arrayOf("*/*")) })
        header.addView(button("Administrar lista") { managePlaylist() })
        header.addView(button("Exportar") { chooseExport() })
        header.addView(button("Respaldo") { backupFile.launch("mi_iptv_respaldo.json") })
        root.addView(header)

        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setBackgroundColor(0xFF000000.toInt())
        }
        root.addView(playerView, LinearLayout.LayoutParams(-1, dp(220)))

        status = text("$playlistName · Importa una lista M3U para comenzar", 13f).apply { setTextColor(0xFF9CA3AF.toInt()) }
        root.addView(status)

        val search = EditText(this).apply {
            hint = "Buscar canales, películas o series"; setHintTextColor(0xFF9CA3AF.toInt()); setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(true); setPadding(dp(12), dp(8), dp(12), dp(8));
            setOnEditorActionListener { v, _, _ -> searchTerm = v.text.toString().trim(); renderChannels(); true }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button("＋ Agregar") { showEditor(null) { channels.add(it); save(); renderChannels() } })
        tools.addView(button("Categorías") { showCategories() })
        tools.addView(button("Películas") { selectedCategory = "VOD"; renderChannels() })
        tools.addView(button("Series") { selectedCategory = "SERIES"; renderChannels() })
        tools.addView(button("Telenovelas") { selectedCategory = "TELENOVELAS"; renderChannels() })
        tools.addView(button("Favoritos") { selectedCategory = "FAVORITOS"; renderChannels() })
        tools.addView(button("Todas") { selectedCategory = "TODAS"; renderChannels() })
        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        root.addView(toolsScroll)

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun managePlaylist() {
        val options = arrayOf("Cambiar nombre de la lista", "Exportar lista completa M3U", "Exportar categoría actual M3U", "Vaciar lista actual")
        AlertDialog.Builder(this).setTitle("Administrar: $playlistName").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val input = EditText(this).apply { setSingleLine(true); setText(playlistName); hint = "Nombre de la lista" }
                    AlertDialog.Builder(this).setTitle("Nombre de la lista").setView(input)
                        .setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                            val value = input.text.toString().trim()
                            if (value.isNotBlank()) { playlistName = value; getSharedPreferences("iptv", MODE_PRIVATE).edit().putString("playlistName", playlistName).apply(); save(); renderChannels() }
                        }.show()
                }
                1 -> { exportItems = channels.toList(); if (exportItems.isEmpty()) toast("La lista está vacía") else exportFile.launch("${safeFileName(playlistName)}.m3u") }
                2 -> chooseExportCategory()
                3 -> AlertDialog.Builder(this).setMessage("¿Vaciar todos los elementos de esta lista? Esta acción no se puede deshacer.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Vaciar") { _, _ -> channels.clear(); save(); renderChannels(); toast("Lista vaciada") }.show()
            }
        }.show()
    }

    private fun safeFileName(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifBlank { "mi_lista" }

    private fun chooseExportCategory() {
        if (channels.isEmpty()) { toast("No hay elementos para exportar"); return }
        val categories = channels.map { it.group }.distinct().sorted()
        AlertDialog.Builder(this).setTitle("Exportar categoría")
            .setItems(categories.toTypedArray()) { _, which ->
                val category = categories[which]
                exportItems = channels.filter { it.group.equals(category, true) }
                if (exportItems.isNotEmpty()) exportFile.launch("${safeFileName(category)}.m3u")
            }.show()
    }

    private fun chooseExport() {
        if (channels.isEmpty()) { toast("No hay listas para exportar"); return }
        val options = arrayOf("Exportar lista completa", "Exportar categoría actual")
        AlertDialog.Builder(this).setTitle("Exportar M3U").setItems(options) { _, which ->
            exportItems = if (which == 1 && selectedCategory != "TODAS" && selectedCategory != "FAVORITOS")
                channels.filter { it.group.equals(selectedCategory, true) }
            else if (which == 1 && selectedCategory == "FAVORITOS") channels.filter { it.favorite }
            else channels.toList()
            if (exportItems.isEmpty()) { toast("Esta categoría no tiene elementos"); return@setItems }
            val fileName = if (which == 1) "${selectedCategory.lowercase(Locale.ROOT).replace(' ', '_')}.m3u" else "mi_lista.m3u"
            exportFile.launch(fileName)
        }.show()
    }

    private fun showCategories() {
        val categories = (listOf("TV EN VIVO", "VOD", "SERIES", "TELENOVELAS", "PAÍSES", "DEPORTES", "FAVORITOS", "TODAS") + channels.map { it.group })
            .distinct().sortedBy { it.lowercase(Locale.ROOT) }
        AlertDialog.Builder(this).setTitle("Seleccionar categoría").setItems(categories.toTypedArray()) { _, which ->
            selectedCategory = categories[which]; renderChannels()
        }.show()
    }

    private fun renderChannels() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val filtered = channels.filter { c ->
            val categoryMatch = when (selectedCategory) {
                "TODAS" -> true
                "FAVORITOS" -> c.favorite
                else -> c.group.equals(selectedCategory, true)
            }
            categoryMatch && (searchTerm.isBlank() || c.name.contains(searchTerm, true) || c.group.contains(searchTerm, true))
        }
        status.text = "$playlistName · ${channels.size} elementos · ${selectedCategory} · ${filtered.size} encontrados"
        if (filtered.isEmpty()) {
            listBox.addView(text("No hay elementos en esta categoría. Importa una lista M3U o elige otra categoría.", 15f))
            return
        }
        val visibleChannels = filtered.take(maxVisibleChannels)
        if (filtered.size > maxVisibleChannels) {
            listBox.addView(text("Mostrando los primeros $maxVisibleChannels de ${filtered.size}. Usa Buscar para encontrar otros canales.", 13f))
        }
        var lastEpisodeHeader = ""
        visibleChannels.forEach { channel ->
            if (channel.group.equals("SERIES", true) || channel.group.equals("TELENOVELAS", true)) {
                val ep = episodeInfo(channel.name)
                val header = "${ep.series} · Temporada ${ep.season}"
                if (header != lastEpisodeHeader) {
                    listBox.addView(text(header, 14f).apply { setTextColor(0xFF80B9FF.toInt()) })
                    lastEpisodeHeader = header
                }
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(3), dp(4), dp(3)) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (channel.logo.isNotBlank()) {
                val poster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(58)).apply { rightMargin = dp(10) }
                    contentDescription = "Imagen de ${channel.name}"
                }
                Glide.with(this@MainActivity).load(channel.logo).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(android.R.drawable.ic_media_play).error(android.R.drawable.ic_media_play).into(poster)
                info.addView(poster)
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(channel.name, 16f))
            labels.addView(text(channel.group, 11f).apply { setTextColor(0xFF9CA3AF.toInt()) })
            info.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button(if (channel.favorite) "★" else "☆") { channel.favorite = !channel.favorite; save(); renderChannels() })
            row.addView(button("▶") { play(channel) })
            row.addView(button("Editar") { editChannel(channel) })
            row.addView(button("✕") { confirmDelete(channel) })
            row.setOnClickListener { play(channel) }
            row.isFocusable = true
            listBox.addView(row)
            val line = View(this).apply { setBackgroundColor(0xFF263244.toInt()) }
            listBox.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private data class EpisodeInfo(val series: String, val season: Int, val episode: Int)
    private fun episodeInfo(raw: String): EpisodeInfo {
        val patterns = listOf(
            Regex("^(.*?)[ ._-]*[Ss](\\d{1,2})[ ._-]*[Ee](\\d{1,3}).*$", RegexOption.IGNORE_CASE),
            Regex("^(.*?)\\bTemporada[ ._-]*(\\d{1,2})[ ._-]*(?:Cap[ií]tulo|Episodio)[ ._-]*(\\d{1,3}).*$", RegexOption.IGNORE_CASE),
            Regex("^(.*?)\\b(\\d{1,2})x(\\d{1,3}).*$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val m = pattern.find(raw.trim()) ?: continue
            return EpisodeInfo(m.groupValues[1].replace(Regex("[._-]+$"), "").trim().ifBlank { raw }, m.groupValues[2].toIntOrNull() ?: 1, m.groupValues[3].toIntOrNull() ?: 0)
        }
        return EpisodeInfo(raw, 1, 0)
    }

    private fun play(channel: Channel) {
        try {
            player?.setMediaItem(MediaItem.fromUri(channel.url)); player?.prepare(); player?.play()
            status.text = "Reproduciendo: ${channel.name}"
        } catch (e: Exception) { toast("No se pudo reproducir esta dirección") }
    }

    private fun showEditor(channel: Channel?, onSave: (Channel) -> Unit) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hintText: String, value: String): EditText = EditText(this).apply { hint = hintText; setText(value); setSingleLine(true) }
        val name = field("Nombre", channel?.name ?: "")
        val url = field("URL", channel?.url ?: "")
        val group = field("Categoría", channel?.group ?: "TV EN VIVO")
        val logo = field("URL del logo (opcional)", channel?.logo ?: "")
        panel.addView(name); panel.addView(url); panel.addView(group); panel.addView(logo)
        AlertDialog.Builder(this).setTitle(if (channel == null) "Agregar elemento" else "Editar elemento")
            .setView(panel).setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                if (name.text.isBlank() || url.text.isBlank()) { toast("Nombre y URL son obligatorios"); return@setPositiveButton }
                val result = channel ?: Channel("", "")
                result.name = name.text.toString().trim(); result.url = url.text.toString().trim()
                result.group = group.text.toString().trim().ifBlank { "TV EN VIVO" }; result.logo = logo.text.toString().trim()
                onSave(result)
            }.show()
    }

    private fun editChannel(channel: Channel) = showEditor(channel) { save(); renderChannels() }
    private fun confirmDelete(channel: Channel) {
        AlertDialog.Builder(this).setMessage("¿Eliminar ${channel.name} de la lista?")
            .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ -> channels.remove(channel); save(); renderChannels() }.show()
    }

    private fun readPlaylist(uri: Uri) {
        status.text = "Preparando importación de la lista M3U…"

        val progressText = TextView(this).apply {
            text = "Abriendo archivo…"
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            setPadding(dp(20), 0, dp(20), dp(12))
        }
        val progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressText)
            addView(progressBar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Importando lista M3U")
            .setView(progressPanel)
            .setCancelable(false)
            .create()
        dialog.show()

        importExecutor.execute {
            try {
                val parsed = contentResolver.openInputStream(uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024)
                    parseM3u(reader) { count ->
                        if (count % 1000 == 0) {
                            runOnUiThread {
                                if (!isFinishing && dialog.isShowing) {
                                    progressText.text = "Procesando… $count canales encontrados"
                                }
                            }
                        }
                    }
                } ?: emptyList()

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dialog.dismiss()

                    if (parsed.isEmpty()) {
                        status.text = "No se encontraron canales válidos"
                        toast("No encontré canales válidos. Comprueba que sea una lista M3U.")
                        return@runOnUiThread
                    }

                    channels.clear()
                    channels.addAll(parsed)
                    selectedCategory = "TODAS"
                    searchTerm = ""
                    renderChannels()
                    save()
                    status.text = "$playlistName · ${parsed.size} canales importados · TODAS"
                    toast("Lista importada: ${parsed.size} canales")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        dialog.dismiss()
                        status.text = "Error al importar la lista M3U"
                        toast("No se pudo leer el archivo: ${e.localizedMessage ?: "error desconocido"}")
                    }
                }
            }
        }
    }

    private fun parseM3u(
        reader: BufferedReader,
        onProgress: (Int) -> Unit
    ): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var group = "TV EN VIVO"
        var logo = ""
        var tvgId = ""
        var lineCount = 0

        while (true) {
            val raw = reader.readLine() ?: break
            lineCount++
            val line = raw.trim().removePrefix("\uFEFF")

            if (line.startsWith("#EXTINF", true)) {
                // En M3U, la primera coma separa los atributos del nombre.
                val commaIndex = line.indexOf(',')
                name = if (commaIndex >= 0) {
                    line.substring(commaIndex + 1).trim().ifBlank { "Sin nombre" }
                } else {
                    "Sin nombre"
                }

                group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1) ?: "TV EN VIVO"
                logo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1) ?: ""
                tvgId = Regex("tvg-id=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1) ?: ""
            } else if (line.isNotBlank() && !line.startsWith("#") && name.isNotBlank()) {
                result.add(
                    Channel(
                        name = name,
                        url = line,
                        group = normalizeGroup(group, name, line),
                        logo = logo,
                        favorite = false,
                        tvgId = tvgId
                    )
                )
                name = ""
                group = "TV EN VIVO"
                logo = ""
                tvgId = ""
                if (result.size % 1000 == 0) onProgress(result.size)
            }
        }

        onProgress(result.size)
        return result
    }

    private fun normalizeGroup(group: String, name: String = "", url: String = ""): String {
        val g = group.trim()
        return when {
            // Telenovelas siempre tienen prioridad sobre la clasificación genérica de series.
            g.contains("telenovela", true) || g.contains("novela", true) ||
                name.contains("telenovela", true) || name.contains("novela", true) -> "TELENOVELAS"
            g.contains("serie", true) || Regex("(?i)\\bS\\d{1,2}[ ._-]*E\\d{1,3}\\b").containsMatchIn(name) ||
                Regex("(?i)\\b\\d{1,2}x\\d{1,3}\\b").containsMatchIn(name) -> "SERIES"
            g.contains("vod", true) || g.contains("película", true) || g.contains("pelicula", true) ||
                g.contains("movie", true) || g.contains("film", true) || g.contains("cine", true) -> "VOD"
            g.contains("deporte", true) || g.contains("sport", true) -> "DEPORTES"
            g.isBlank() -> "TV EN VIVO"
            else -> g
        }
    }

    private fun writePlaylist(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { out ->
                    out.write("#EXTM3U\n")
                    exportItems.forEach { c ->
                        val group = c.group.replace("\"", "")
                        val logo = c.logo.replace("\"", "")
                        val id = c.tvgId.replace("\"", "")
                        out.write("#EXTINF:-1 tvg-id=\"$id\" tvg-logo=\"$logo\" group-title=\"$group\",${c.name}\n${c.url}\n")
                    }
                }
            }
            toast("Lista M3U exportada: ${exportItems.size} elementos")
        } catch (e: Exception) { toast("No se pudo exportar la lista") }
    }

    private fun writeBackup(uri: Uri) {
        try {
            val arr = JSONArray()
            channels.forEach { c -> arr.put(JSONObject().put("name", c.name).put("url", c.url).put("group", c.group).put("logo", c.logo).put("favorite", c.favorite).put("tvgId", c.tvgId)) }
            contentResolver.openOutputStream(uri)?.use { it.write(JSONObject().put("format", "MiIPTV-backup-v1").put("channels", arr).toString(2).toByteArray(Charsets.UTF_8)) }
            toast("Respaldo guardado: ${channels.size} elementos")
        } catch (_: Exception) { toast("No se pudo crear el respaldo") }
    }

    private fun save() {
        // La conversión de decenas de miles de canales a JSON se hace fuera del hilo visual.
        val snapshot = channels.toList()
        try {
            importExecutor.execute {
                try {
                    val arr = JSONArray()
                    snapshot.forEach { c ->
                        arr.put(
                            JSONObject()
                                .put("name", c.name)
                                .put("url", c.url)
                                .put("group", c.group)
                                .put("logo", c.logo)
                                .put("favorite", c.favorite)
                                .put("tvgId", c.tvgId)
                        )
                    }
                    getSharedPreferences("iptv", MODE_PRIVATE)
                        .edit()
                        .putString("channels", arr.toString())
                        .apply()
                } catch (_: Exception) {
                    runOnUiThread { toast("No se pudo guardar la lista en el dispositivo") }
                }
            }
        } catch (_: Exception) {
            toast("No se pudo iniciar el guardado de la lista")
        }
    }
    private fun loadSaved() {
        playlistName = getSharedPreferences("iptv", MODE_PRIVATE).getString("playlistName", "Mi lista IPTV") ?: "Mi lista IPTV"
        val raw = getSharedPreferences("iptv", MODE_PRIVATE).getString("channels", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); channels.add(Channel(o.optString("name"), o.optString("url"), o.optString("group", "TV EN VIVO"), o.optString("logo"), o.optBoolean("favorite"), o.optString("tvgId"))) }
        } catch (_: Exception) { }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        importExecutor.shutdownNow()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
