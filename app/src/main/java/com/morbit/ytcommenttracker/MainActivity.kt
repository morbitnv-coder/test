package com.morbit.ytcommenttracker

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS = "ytct"
        private const val KEY_API = "api"
        private const val KEY_ITEMS = "items"
        private const val AUTO_REFRESH_MS = 5 * 60 * 1000L
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val trackedItems: SnapshotStateList<TrackedComment> = mutableStateListOf()

    private lateinit var prefs: SharedPreferences

    private var apiKey by mutableStateOf("")
    private var refreshing by mutableStateOf(false)
    private var statusMessage by mutableStateOf("")
    private var addDialogVisible by mutableStateOf(false)
    private var addDialogPrefill by mutableStateOf("")
    private var settingsDialogVisible by mutableStateOf(false)
    private var errorDialogMessage by mutableStateOf<String?>(null)

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                refreshAll(false)
                handler.postDelayed(this, AUTO_REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        apiKey = prefs.getString(KEY_API, "").orEmpty()
        loadItems()

        setContent {
            TrackerTheme {
                TrackerScreen(
                    items = trackedItems,
                    apiKeyConfigured = apiKey.isNotBlank(),
                    refreshing = refreshing,
                    statusMessage = statusMessage,
                    onAdd = {
                        addDialogPrefill = ""
                        addDialogVisible = true
                    },
                    onRefresh = { refreshAll(true) },
                    onSettings = { settingsDialogVisible = true },
                    onOpen = { item ->
                        markRead(item)
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                        } catch (_: Exception) {
                            toast("Не удалось открыть YouTube")
                        }
                    },
                    onMarkRead = { item -> markRead(item) },
                    onDelete = { item -> deleteItem(item) }
                )

                if (addDialogVisible) {
                    AddCommentDialog(
                        initialValue = addDialogPrefill,
                        onDismiss = { addDialogVisible = false },
                        onConfirm = { raw ->
                            val url = extractUrl(raw)
                            val commentId = extractCommentId(url)
                            when {
                                commentId == null -> "В ссылке не найден параметр lc=…"
                                apiKey.isBlank() -> {
                                    addDialogVisible = false
                                    settingsDialogVisible = true
                                    toast("Сначала укажи YouTube API key")
                                    null
                                }
                                else -> {
                                    addDialogVisible = false
                                    addComment(url, commentId)
                                    null
                                }
                            }
                        }
                    )
                }

                if (settingsDialogVisible) {
                    ApiKeyDialog(
                        initialValue = apiKey,
                        onDismiss = { settingsDialogVisible = false },
                        onSave = { value ->
                            apiKey = value.trim()
                            prefs.edit().putString(KEY_API, apiKey).apply()
                            settingsDialogVisible = false
                            statusMessage = if (apiKey.isBlank()) "API key удалён" else "API key сохранён"
                            if (apiKey.isNotBlank() && trackedItems.isNotEmpty()) refreshAll(false)
                        }
                    )
                }

                errorDialogMessage?.let { message ->
                    ErrorDialog(
                        message = message,
                        onDismiss = { errorDialogMessage = null }
                    )
                }
            }
        }

        handleIncomingShare(intent)
        if (trackedItems.isNotEmpty() && apiKey.isNotBlank()) refreshAll(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(autoRefresh)
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(autoRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (sharedText.isNotBlank()) {
            addDialogPrefill = extractUrl(sharedText)
            addDialogVisible = true
        }
    }

    private fun addComment(url: String, commentId: String) {
        if (trackedItems.any { it.id == commentId }) {
            toast("Этот комментарий уже отслеживается")
            return
        }
        if (refreshing) return

        refreshing = true
        statusMessage = "Добавляю комментарий…"

        executor.execute {
            try {
                val item = fetchComment(commentId, url, true)
                runOnUiThread {
                    if (trackedItems.any { it.id == item.id }) {
                        refreshing = false
                        statusMessage = ""
                        toast("Эта ветка уже отслеживается")
                        return@runOnUiThread
                    }
                    trackedItems.add(0, item)
                    saveItems()
                    refreshing = false
                    statusMessage = "Комментарий добавлен"
                    toast("Добавлено")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    refreshing = false
                    statusMessage = "Ошибка запроса"
                    errorDialogMessage = friendlyError(e)
                }
            }
        }
    }

    private fun refreshAll(userInitiated: Boolean) {
        if (refreshing) return
        if (trackedItems.isEmpty()) {
            if (userInitiated) toast("Сначала добавь комментарий")
            return
        }
        if (apiKey.isBlank()) {
            if (userInitiated) settingsDialogVisible = true
            return
        }

        refreshing = true
        statusMessage = "Обновляю…"
        val snapshot = trackedItems.toList()

        executor.execute {
            var ok = 0
            val updated = snapshot.map { old ->
                try {
                    val fresh = fetchComment(old.id, old.url, false)
                    ok++
                    fresh.copy(
                        seenLikes = old.seenLikes,
                        seenReplyIds = old.seenReplyIds
                    )
                } catch (e: Exception) {
                    old.copy(error = friendlyError(e))
                }
            }

            runOnUiThread {
                trackedItems.clear()
                trackedItems.addAll(updated)
                saveItems()
                refreshing = false
                statusMessage = "Обновлено " + ok + " из " + updated.size
                if (userInitiated) toast(statusMessage)
            }
        }
    }

    private fun markRead(item: TrackedComment) {
        val index = trackedItems.indexOfFirst { it.id == item.id }
        if (index < 0) return

        trackedItems[index] = item.copy(
            seenLikes = item.likes,
            seenReplyIds = item.replies.map { it.id }.toSet()
        )
        saveItems()
    }

    private fun deleteItem(item: TrackedComment) {
        trackedItems.removeAll { it.id == item.id }
        saveItems()
        statusMessage = "Удалено из отслеживания"
    }

    private fun fetchComment(commentId: String, originalUrl: String, initial: Boolean): TrackedComment {
        if (apiKey.isBlank()) throw Exception("Не указан YouTube API key")

        val commentJson = getJson(
            "https://www.googleapis.com/youtube/v3/comments?part=snippet&id=" +
                enc(commentId) + "&textFormat=plainText&key=" + enc(apiKey)
        )
        val items = commentJson.optJSONArray("items")
        if (items == null || items.length() == 0) {
            throw Exception("Комментарий не найден или недоступен через YouTube API")
        }

        val snippet = items.getJSONObject(0).getJSONObject("snippet")
        val parentId = snippet.optString("parentId", "")
        val rootId = if (parentId.isNotBlank()) parentId else commentId

        var rootSnippet = snippet
        if (parentId.isNotBlank()) {
            val rootJson = getJson(
                "https://www.googleapis.com/youtube/v3/comments?part=snippet&id=" +
                    enc(parentId) + "&textFormat=plainText&key=" + enc(apiKey)
            )
            val roots = rootJson.optJSONArray("items")
            if (roots != null && roots.length() > 0) {
                rootSnippet = roots.getJSONObject(0).getJSONObject("snippet")
            }
        }

        val videoId = rootSnippet.optString("videoId", snippet.optString("videoId", ""))
        val replies = fetchReplies(rootId)
        val likes = rootSnippet.optInt("likeCount", 0)

        return TrackedComment(
            id = rootId,
            url = normalizeUrl(originalUrl, rootId),
            videoId = videoId,
            videoTitle = fetchVideoTitle(videoId),
            author = rootSnippet.optString("authorDisplayName", ""),
            text = rootSnippet.optString("textDisplay", ""),
            likes = likes,
            seenLikes = if (initial) likes else 0,
            replies = replies,
            seenReplyIds = if (initial) replies.map { it.id }.toSet() else emptySet(),
            error = if (parentId.isNotBlank()) {
                "Ссылка вела на ответ — отслеживается вся ветка комментария."
            } else {
                ""
            }
        )
    }

    private fun fetchVideoTitle(videoId: String): String {
        if (videoId.isBlank()) return "YouTube"
        return try {
            val json = getJson(
                "https://www.googleapis.com/youtube/v3/videos?part=snippet&id=" +
                    enc(videoId) + "&key=" + enc(apiKey)
            )
            val items = json.optJSONArray("items")
            if (items != null && items.length() > 0) {
                items.getJSONObject(0).getJSONObject("snippet").optString("title", "YouTube")
            } else {
                "YouTube"
            }
        } catch (_: Exception) {
            "YouTube"
        }
    }

    private fun fetchReplies(parentId: String): List<Reply> {
        val result = mutableListOf<Reply>()
        var pageToken = ""
        var pages = 0

        do {
            var url =
                "https://www.googleapis.com/youtube/v3/comments?part=snippet&parentId=" +
                    enc(parentId) + "&maxResults=100&textFormat=plainText&key=" + enc(apiKey)

            if (pageToken.isNotBlank()) {
                url += "&pageToken=" + enc(pageToken)
            }

            val json = getJson(url)
            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val obj = items.getJSONObject(i)
                    val snippet = obj.getJSONObject("snippet")
                    result += Reply(
                        id = obj.optString("id", ""),
                        author = snippet.optString("authorDisplayName", ""),
                        text = snippet.optString("textDisplay", ""),
                        published = snippet.optString("publishedAt", "")
                    )
                }
            }

            pageToken = json.optString("nextPageToken", "")
            pages++
        } while (pageToken.isNotBlank() && pages < 5)

        return result.sortedBy { it.published }
    }

    private fun getJson(urlString: String): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = readAll(stream)

            if (code !in 200..299) {
                var message = "YouTube API: HTTP " + code
                try {
                    val error = JSONObject(body).optJSONObject("error")
                    if (error != null && error.optString("message").isNotBlank()) {
                        message = error.optString("message")
                    }
                } catch (_: JSONException) {
                }
                throw Exception(message)
            }

            return JSONObject(body)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readAll(input: InputStream?): String {
        if (input == null) return ""
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            val builder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                builder.append(line)
            }
            return builder.toString()
        }
    }

    private fun loadItems() {
        trackedItems.clear()
        try {
            val array = JSONArray(prefs.getString(KEY_ITEMS, "[]"))
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { trackedItems += TrackedComment.fromJson(it) }
            }
        } catch (_: Exception) {
            prefs.edit().remove(KEY_ITEMS).apply()
        }
    }

    private fun saveItems() {
        val array = JSONArray()
        trackedItems.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun extractUrl(raw: String): String {
        val matcher = Pattern.compile("https?://\\S+").matcher(raw)
        if (matcher.find()) return trimUrl(matcher.group().orEmpty())
        return trimUrl(raw.trim())
    }

    private fun trimUrl(value: String): String {
        var result = value
        while (
            result.endsWith(")") ||
            result.endsWith("]") ||
            result.endsWith(".") ||
            result.endsWith(",")
        ) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun extractCommentId(url: String): String? {
        return try {
            Uri.parse(url).getQueryParameter("lc")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrl(original: String, commentId: String): String {
        return try {
            val uri = Uri.parse(original)
            if (uri.getQueryParameter("lc") != null) {
                original
            } else {
                original + if (original.contains("?")) "&lc=" + Uri.encode(commentId)
                else "?lc=" + Uri.encode(commentId)
            }
        } catch (_: Exception) {
            original
        }
    }

    private fun enc(value: String): String = Uri.encode(value)

    private fun friendlyError(error: Exception): String {
        return error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private val AppBackground = Color(0xFF0B0F14)
private val AppSurface = Color(0xFF111820)
private val AppSurfaceRaised = Color(0xFF18212B)
private val AppPrimary = Color(0xFFFF4D5A)
private val AppPrimarySoft = Color(0xFF3A2025)
private val AppTextMuted = Color(0xFF9AA6B2)

@Composable
private fun TrackerTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = AppPrimary,
        onPrimary = Color.White,
        primaryContainer = AppPrimarySoft,
        onPrimaryContainer = Color(0xFFFFDADD),
        background = AppBackground,
        onBackground = Color(0xFFF3F6F8),
        surface = AppSurface,
        onSurface = Color(0xFFF3F6F8),
        surfaceVariant = AppSurfaceRaised,
        onSurfaceVariant = Color(0xFFC7D0D8),
        outline = Color(0xFF3B4651),
        error = Color(0xFFFF6B6B)
    )

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerScreen(
    items: List<TrackedComment>,
    apiKeyConfigured: Boolean,
    refreshing: Boolean,
    statusMessage: String,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (TrackedComment) -> Unit,
    onMarkRead: (TrackedComment) -> Unit,
    onDelete: (TrackedComment) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "YT Comment Tracker",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color(0xFF0F151C),
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Button(
                    onClick = onAdd,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddComment,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить", maxLines = 1)
                }

                Spacer(Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text("Обновить", maxLines = 1, fontSize = 13.sp)
                }

                Spacer(Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("Настройки", maxLines = 1, fontSize = 13.sp)
                }
            }
        }
    ) { innerPadding ->
        if (items.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                apiKeyConfigured = apiKeyConfigured,
                refreshing = refreshing,
                statusMessage = statusMessage,
                onAdd = onAdd
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 10.dp,
                    bottom = 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    StatusPanel(
                        count = items.size,
                        apiKeyConfigured = apiKeyConfigured,
                        refreshing = refreshing,
                        statusMessage = statusMessage
                    )
                }

                items(items, key = { it.id }) { item ->
                    CommentCard(
                        item = item,
                        onOpen = { onOpen(item) },
                        onMarkRead = { onMarkRead(item) },
                        onDelete = { onDelete(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    apiKeyConfigured: Boolean,
    refreshing: Boolean,
    statusMessage: String,
    onAdd: () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusPanel(
            count = 0,
            apiKeyConfigured = apiKeyConfigured,
            refreshing = refreshing,
            statusMessage = statusMessage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        Spacer(Modifier.weight(1f))

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(28.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(20.dp)
                    .size(44.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Пока пусто",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Добавь ссылку на свой комментарий YouTube. Дальше приложение будет показывать новые лайки и ответы.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextMuted
        )

        Spacer(Modifier.height(22.dp))

        Button(
            onClick = onAdd,
            modifier = Modifier.height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AddComment,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text("Добавить первый комментарий")
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Ещё удобнее: YouTube → Поделиться → YT Comment Tracker",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextMuted
        )

        Spacer(Modifier.weight(1.4f))
    }
}

@Composable
private fun StatusPanel(
    count: Int,
    apiKeyConfigured: Boolean,
    refreshing: Boolean,
    statusMessage: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = AppSurface
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubbleOutline,
                        contentDescription = null,
                        tint = AppTextMuted,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = count.toString() + " отслеживается",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.weight(1f))

                Icon(
                    imageVector = if (apiKeyConfigured) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (apiKeyConfigured) Color(0xFF7ED7A1) else Color(0xFFFFB36B),
                    modifier = Modifier.size(18.dp)
                )

                Spacer(Modifier.width(5.dp))

                Text(
                    text = if (apiKeyConfigured) "API готов" else "API не задан",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (apiKeyConfigured) Color(0xFF9DE5B9) else Color(0xFFFFC58D)
                )
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextMuted
                )
            }
        }
    }
}

@Composable
private fun CommentCard(
    item: TrackedComment,
    onOpen: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit
) {
    val newLikes = (item.likes - item.seenLikes).coerceAtLeast(0)
    val newReplies = item.replies.count { it.id !in item.seenReplyIds }
    val hasNew = newLikes > 0 || newReplies > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasNew) Color(0xFF171D25) else AppSurface
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppPrimarySoft
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SmartDisplay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.videoTitle.ifBlank { "YouTube" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.author.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (hasNew) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AppPrimarySoft
                    ) {
                        Text(
                            text = "НОВОЕ",
                            color = Color(0xFFFFB8BE),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(13.dp))

            Text(
                text = item.text.ifBlank { item.url },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill(
                    icon = Icons.Rounded.ThumbUp,
                    value = item.likes.toString(),
                    delta = newLikes
                )
                StatPill(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    value = item.replies.size.toString(),
                    delta = newReplies
                )
            }

            if (item.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF34252A)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF9AA3),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = item.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB6BC)
                        )
                    }
                }
            }

            val shownReplies = item.replies.takeLast(5).reversed()
            if (shownReplies.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                shownReplies.forEach { reply ->
                    val unread = reply.id !in item.seenReplyIds
                    ReplyRow(reply = reply, unread = unread)
                    Spacer(Modifier.height(7.dp))
                }
                if (item.replies.size > shownReplies.size) {
                    Text(
                        text = "Ещё " + (item.replies.size - shownReplies.size) + " ответов",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("YouTube")
                }

                if (hasNew) {
                    TextButton(onClick = onMarkRead) {
                        Icon(
                            imageVector = Icons.Rounded.DoneAll,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Прочитано")
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Удалить из отслеживания",
                        tint = AppTextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    delta: Int
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (delta > 0) AppPrimarySoft else AppSurfaceRaised
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (delta > 0) Color(0xFFFFA8AF) else AppTextMuted
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge
            )
            if (delta > 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "+" + delta,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFB8BE),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ReplyRow(reply: Reply, unread: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (unread) Color(0xFF27212A) else Color(0xFF131A22)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = reply.author.ifBlank { "Ответ" },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) Color(0xFFFFC3C8) else AppTextMuted,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (unread) {
                    Text(
                        text = "НОВЫЙ",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB8BE)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = reply.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (unread) MaterialTheme.colorScheme.onSurface else Color(0xFF8F9AA5)
            )
        }
    }
}

@Composable
private fun AddCommentDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> String?
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var error by remember(initialValue) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Rounded.AddComment, contentDescription = null)
        },
        title = {
            Text("Добавить комментарий")
        },
        text = {
            Column {
                Text(
                    text = "Вставь прямую ссылку на комментарий YouTube. В ней должен быть параметр lc=…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextMuted
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text("Ссылка на комментарий") },
                    placeholder = { Text("https://youtube.com/watch?v=…&lc=…") },
                    isError = error != null,
                    supportingText = {
                        error?.let { Text(it) }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    error = onConfirm(value.trim())
                },
                enabled = value.isNotBlank()
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun ApiKeyDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Rounded.Settings, contentDescription = null)
        },
        title = {
            Text("Настройки")
        },
        text = {
            Column {
                Text(
                    text = "Для чтения лайков и ответов нужен YouTube Data API v3 key. Ключ хранится только на телефоне.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextMuted
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("YouTube API key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text("Не удалось выполнить запрос")
        },
        text = {
            Text(message)
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

data class Reply(
    val id: String = "",
    val author: String = "",
    val text: String = "",
    val published: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("author", author)
            put("text", text)
            put("published", published)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Reply {
            return Reply(
                id = json.optString("id", ""),
                author = json.optString("author", ""),
                text = json.optString("text", ""),
                published = json.optString("published", "")
            )
        }
    }
}

data class TrackedComment(
    val id: String = "",
    val url: String = "",
    val videoId: String = "",
    val videoTitle: String = "",
    val author: String = "",
    val text: String = "",
    val error: String = "",
    val likes: Int = 0,
    val seenLikes: Int = 0,
    val replies: List<Reply> = emptyList(),
    val seenReplyIds: Set<String> = emptySet()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("url", url)
            put("videoId", videoId)
            put("videoTitle", videoTitle)
            put("author", author)
            put("text", text)
            put("error", error)
            put("likes", likes)
            put("seenLikes", seenLikes)

            val repliesArray = JSONArray()
            replies.forEach { repliesArray.put(it.toJson()) }
            put("replies", repliesArray)

            val seenArray = JSONArray()
            seenReplyIds.forEach { seenArray.put(it) }
            put("seen", seenArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TrackedComment {
            val replies = mutableListOf<Reply>()
            json.optJSONArray("replies")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { replies += Reply.fromJson(it) }
                }
            }

            val seen = mutableSetOf<String>()
            json.optJSONArray("seen")?.let { array ->
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) seen += value
                }
            }

            return TrackedComment(
                id = json.optString("id", ""),
                url = json.optString("url", ""),
                videoId = json.optString("videoId", ""),
                videoTitle = json.optString("videoTitle", ""),
                author = json.optString("author", ""),
                text = json.optString("text", ""),
                error = json.optString("error", ""),
                likes = json.optInt("likes", 0),
                seenLikes = json.optInt("seenLikes", 0),
                replies = replies,
                seenReplyIds = seen
            )
        }
    }
}
