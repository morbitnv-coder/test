package com.morbit.womantracker

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val TRACKER_PREFS = "woman_tracker"
private const val SNAPSHOTS_KEY = "native_reply_snapshots_v2"
private const val MAX_SCAN = 12

private data class NativeReply(
    val messageId: String,
    val title: String,
    val date: String,
    val text: String,
    val jumpUrl: String,
    val topicReplies: Int?,
    val topicDelta: Int = 0,
    val likes: Int? = null,
    val dislikes: Int? = null,
    val likesDelta: Int = 0,
    val dislikesDelta: Int = 0
)

private data class NativeSnapshot(
    val topicReplies: Int? = null,
    val likes: Int? = null,
    val dislikes: Int? = null
)

@Composable
fun NativeMyRepliesScreen(profileUrl: String) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(TRACKER_PREFS, Context.MODE_PRIVATE)
    }

    var refreshKey by remember { mutableIntStateOf(0) }
    var replies by remember { mutableStateOf<List<NativeReply>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Загружаю профиль…") }
    var openedUrl by remember { mutableStateOf<String?>(null) }
    val previous = remember(refreshKey) { loadNativeSnapshots(prefs) }

    BackHandler(enabled = openedUrl != null) {
        openedUrl = null
    }

    if (openedUrl != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = { openedUrl = null }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("К моим ответам")
                }
            }
            TrackerVisibleWebView(
                url = openedUrl.orEmpty(),
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (loading) status else "Последние ответы: " + replies.size,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!loading && replies.any {
                        it.topicDelta > 0 || it.likesDelta != 0 || it.dislikesDelta != 0
                    }
                ) {
                    Text(
                        "Изменения с прошлой проверки показаны в скобках",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            IconButton(
                onClick = {
                    loading = true
                    status = "Обновляю профиль…"
                    refreshKey += 1
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }

        HorizontalDivider()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && replies.isEmpty() -> {
                    Column(modifier = Modifier.padding(20.dp)) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(status)
                    }
                }

                replies.isEmpty() -> {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Не нашёл ответы в профиле.")
                        Spacer(Modifier.height(8.dp))
                        Text("Нажми обновить. Если Woman.ru поменял страницу — подправим парсер.")
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(replies, key = { it.messageId }) { reply ->
                            NativeReplyCard(reply = reply) {
                                openedUrl = reply.jumpUrl
                            }
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }

            NativeReplyScraper(
                profileUrl = profileUrl,
                refreshKey = refreshKey,
                previous = previous,
                onProfile = { parsed ->
                    replies = parsed
                    if (parsed.isEmpty()) {
                        loading = false
                        status = "Ответы не найдены"
                    } else {
                        status = "Проверяю реакции 0/" + minOf(parsed.size, MAX_SCAN)
                    }
                },
                onReaction = { updated, done, total ->
                    replies = replies.map {
                        if (it.messageId == updated.messageId) updated else it
                    }
                    status = "Проверяю реакции " + done + "/" + total
                },
                onFinished = { finalList ->
                    replies = finalList
                    saveNativeSnapshots(prefs, finalList)
                    loading = false
                    status = "Готово"
                }
            )
        }
    }
}

@Composable
private fun NativeReplyCard(reply: NativeReply, onOpen: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                reply.title.ifBlank { "Тема" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (reply.date.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(reply.date, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(9.dp))
            Text(
                reply.text.ifBlank { "Текст ответа пока не удалось выделить." },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NativeMetric("👍", reply.likes, reply.likesDelta)
                NativeMetric("👎", reply.dislikes, reply.dislikesDelta)

                val count = reply.topicReplies?.toString() ?: "—"
                val delta = if (reply.topicDelta > 0) " (+" + reply.topicDelta + ")" else ""
                Text(
                    "💬 в теме " + count + delta,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onOpen) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("К сообщению")
                }
            }
        }
    }
}

@Composable
private fun NativeMetric(label: String, value: Int?, delta: Int) {
    val base = value?.toString() ?: "—"
    val suffix = when {
        delta > 0 -> " (+" + delta + ")"
        delta < 0 -> " (" + delta + ")"
        else -> ""
    }
    Text(label + " " + base + suffix, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun NativeReplyScraper(
    profileUrl: String,
    refreshKey: Int,
    previous: Map<String, NativeSnapshot>,
    onProfile: (List<NativeReply>) -> Unit,
    onReaction: (NativeReply, Int, Int) -> Unit,
    onFinished: (List<NativeReply>) -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var working by remember { mutableStateOf<List<NativeReply>>(emptyList()) }
    var queue by remember { mutableStateOf<List<NativeReply>>(emptyList()) }
    var target by remember { mutableStateOf<NativeReply?>(null) }
    var done by remember { mutableIntStateOf(0) }

    fun finish() {
        target = null
        queue = emptyList()
        onFinished(working)
    }

    fun next(view: WebView) {
        val item = queue.firstOrNull()
        if (item == null) {
            finish()
            return
        }
        queue = queue.drop(1)
        target = item
        view.loadUrl(item.jumpUrl)
    }

    fun readReaction(view: WebView, item: NativeReply) {
        view.evaluateJavascript(nativeReactionScript(item.messageId)) { encoded ->
            val objectText = decodeNativeJs(encoded)
            val obj = runCatching { JSONObject(objectText) }.getOrNull()
            val likes = obj?.nativeNullableInt("likes")
            val dislikes = obj?.nativeNullableInt("dislikes")
            val old = previous[item.messageId]

            val updated = item.copy(
                likes = likes,
                dislikes = dislikes,
                likesDelta = if (likes != null && old?.likes != null) likes - old.likes else 0,
                dislikesDelta = if (dislikes != null && old?.dislikes != null) {
                    dislikes - old.dislikes
                } else {
                    0
                }
            )

            working = working.map {
                if (it.messageId == item.messageId) updated else it
            }
            done += 1
            onReaction(updated, done, minOf(working.size, MAX_SCAN))
            target = null
            view.postDelayed({ next(view) }, 160)
        }
    }

    AndroidView(
        modifier = Modifier
            .size(1.dp)
            .alpha(0.01f),
        factory = {
            WebView(context).apply {
                configureNativeTrackerWebView(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val active = view ?: return
                        CookieManager.getInstance().flush()

                        val current = target
                        if (current == null && isNativeProfileUrl(url.orEmpty())) {
                            active.postDelayed({
                                active.evaluateJavascript(nativeProfileScript()) { encoded ->
                                    val json = decodeNativeJs(encoded)
                                    val array = runCatching { JSONArray(json) }.getOrNull() ?: JSONArray()
                                    val parsed = ArrayList<NativeReply>()
                                    val seen = HashSet<String>()

                                    for (i in 0 until minOf(array.length(), 30)) {
                                        val o = array.optJSONObject(i) ?: continue
                                        val id = o.optString("messageId")
                                        if (id.isBlank() || !seen.add(id)) continue

                                        val topicReplies = o.nativeNullableInt("topicReplies")
                                        val old = previous[id]
                                        val jump = nativeAbsoluteUrl(o.optString("jumpUrl"))
                                        if (jump.isBlank()) continue

                                        parsed += NativeReply(
                                            messageId = id,
                                            title = o.optString("title").nativeClean(180),
                                            date = o.optString("date").nativeClean(80),
                                            text = o.optString("text").nativeClean(1400),
                                            jumpUrl = jump,
                                            topicReplies = topicReplies,
                                            topicDelta = if (
                                                topicReplies != null && old?.topicReplies != null
                                            ) {
                                                topicReplies - old.topicReplies
                                            } else {
                                                0
                                            }
                                        )
                                    }

                                    working = parsed
                                    onProfile(parsed)
                                    done = 0
                                    queue = parsed.take(MAX_SCAN)

                                    if (queue.isEmpty()) {
                                        finish()
                                    } else {
                                        next(active)
                                    }
                                }
                            }, 350)
                        } else if (current != null) {
                            active.postDelayed({
                                if (target?.messageId == current.messageId) {
                                    readReaction(active, current)
                                }
                            }, 450)
                        }
                    }
                }
                webView = this
            }
        }
    )

    LaunchedEffect(profileUrl, refreshKey, webView) {
        if (profileUrl.isNotBlank() && webView != null) {
            working = emptyList()
            queue = emptyList()
            target = null
            done = 0
            webView?.loadUrl(profileUrl)
        }
    }
}

@Composable
private fun TrackerVisibleWebView(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = {
            WebView(context).apply {
                configureNativeTrackerWebView(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                    }
                }
                loadUrl(url)
            }
        }
    )
}

private fun configureNativeTrackerWebView(view: WebView) {
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.databaseEnabled = true
    view.settings.cacheMode = WebSettings.LOAD_DEFAULT
    view.settings.userAgentString = view.settings.userAgentString + " WomanTracker/0.2"
    view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
}

private fun nativeProfileScript(): String = """
(function() {
  try {
    const links = Array.from(document.querySelectorAll('a[href*="/forum/GoToMessage/"]'));
    const result = [];
    const seen = new Set();

    function clean(s) {
      return (s || '')
        .replace(/\u00a0/g, ' ')
        .replace(/[ \t]+/g, ' ')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
    }

    function cardFor(a) {
      let node = a.parentElement;
      let best = node;
      for (let i = 0; i < 9 && node; i++, node = node.parentElement) {
        const one = node.querySelectorAll ?
          node.querySelectorAll('a[href*="/forum/GoToMessage/"]').length : 0;
        const heading = node.querySelector ? node.querySelector('h2, h3') : null;
        const len = (node.innerText || '').length;
        if (heading && one === 1 && len > 40 && len < 7000) best = node;
      }
      return best || a.parentElement;
    }

    for (const a of links) {
      const href = a.getAttribute('href') || '';
      const match = href.match(/[?&]id=(\d+)/);
      if (!match || seen.has(match[1])) continue;
      seen.add(match[1]);

      const card = cardFor(a);
      const heading = card && card.querySelector ? card.querySelector('h2, h3') : null;
      const title = clean(heading ? heading.innerText : '');
      const raw = clean(card ? card.innerText : '');

      const dates = raw.match(/\d{1,2}\s+[а-яё]+\s+\d{4},\s*\d{1,2}:\d{2}/gi) || [];
      const date = dates.length ? dates[dates.length - 1] : '';

      const countMatch = raw.match(/([\d\s]+)\s+ответ(?:ов|а)?\b/i);
      const count = countMatch ? parseInt(countMatch[1].replace(/\s/g, ''), 10) : null;

      let text = raw;
      if (title) text = text.replace(title, '');
      for (const d of dates) text = text.replace(d, '');
      text = text
        .replace(/[\d\s]+\s+ответ(?:ов|а)?\b/gi, '')
        .replace(/Показать полностью/gi, '')
        .replace(/Перейти/gi, '')
        .replace(/Всего\s+\d+\s+ответ[^\n]*/gi, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();

      const lines = text.split('\n').map(x => x.trim()).filter(Boolean);
      if (lines.length > 1 && lines[0].length < 70 && lines.slice(1).join(' ').length > 40) {
        lines.shift();
      }

      result.push({
        messageId: match[1],
        title: title,
        date: date,
        text: lines.join('\n').slice(0, 1800),
        jumpUrl: new URL(href, location.origin).href,
        topicReplies: Number.isFinite(count) ? count : null
      });
    }

    return JSON.stringify(result);
  } catch (e) {
    return JSON.stringify([]);
  }
})()
""".trimIndent()

private fun nativeReactionScript(messageId: String): String {
    val template = """
(function() {
  try {
    const id = __MESSAGE_ID__;
    let node = null;
    const selectors = [
      '#message-' + id,
      '#message' + id,
      '#' + id,
      '[data-id="' + id + '"]',
      '[data-message-id="' + id + '"]',
      '[data-message="' + id + '"]',
      '[id*="' + id + '"]'
    ];

    for (const s of selectors) {
      try {
        node = document.querySelector(s);
        if (node) break;
      } catch (_) {}
    }

    if (!node) {
      try { node = document.querySelector(':target'); } catch (_) {}
    }

    if (!node && location.hash) {
      node = document.getElementById(location.hash.replace(/^#/, ''));
    }

    function numbers(el) {
      if (!el || !el.querySelectorAll) return [];
      return Array.from(el.querySelectorAll('button'))
        .map(b => (b.innerText || b.textContent || '').trim())
        .filter(t => /^-?\d+$/.test(t))
        .map(t => parseInt(t, 10));
    }

    let current = node;
    let values = [];
    for (let i = 0; i < 10 && current; i++, current = current.parentElement) {
      values = numbers(current);
      const text = current.innerText || '';
      if (values.length >= 2 && text.length > 10 && text.length < 12000) break;
    }

    return JSON.stringify({
      likes: values.length >= 1 ? values[0] : null,
      dislikes: values.length >= 2 ? values[1] : null
    });
  } catch (e) {
    return JSON.stringify({likes:null, dislikes:null});
  }
})()
""".trimIndent()

    return template.replace("__MESSAGE_ID__", JSONObject.quote(messageId))
}

private fun loadNativeSnapshots(prefs: SharedPreferences): Map<String, NativeSnapshot> {
    val raw = prefs.getString(SNAPSHOTS_KEY, null) ?: return emptyMap()

    return runCatching {
        val root = JSONObject(raw)
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = root.optJSONObject(id) ?: continue
                put(
                    id,
                    NativeSnapshot(
                        topicReplies = o.nativeNullableInt("topicReplies"),
                        likes = o.nativeNullableInt("likes"),
                        dislikes = o.nativeNullableInt("dislikes")
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())
}

private fun saveNativeSnapshots(prefs: SharedPreferences, replies: List<NativeReply>) {
    val root = JSONObject()

    for (reply in replies) {
        val o = JSONObject()
        reply.topicReplies?.let { o.put("topicReplies", it) }
        reply.likes?.let { o.put("likes", it) }
        reply.dislikes?.let { o.put("dislikes", it) }
        root.put(reply.messageId, o)
    }

    prefs.edit().putString(SNAPSHOTS_KEY, root.toString()).apply()
}

private fun JSONObject.nativeNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getInt(key) }.getOrNull()
}

private fun decodeNativeJs(encoded: String?): String {
    if (encoded.isNullOrBlank() || encoded == "null") return "[]"

    return runCatching {
        val value = JSONTokener(encoded).nextValue()
        if (value is String) value else encoded
    }.getOrDefault(encoded)
}

private fun String.nativeClean(max: Int): String =
    replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .take(max)

private fun nativeAbsoluteUrl(url: String): String {
    if (url.isBlank()) return ""

    return when {
        url.startsWith("https://", true) -> url
        url.startsWith("http://", true) -> "https://" + url.substringAfter("://")
        url.startsWith("/") -> "https://www.woman.ru" + url
        else -> "https://www.woman.ru/" + url
    }
}

private fun isNativeProfileUrl(url: String): Boolean =
    Regex("""https?://(www\.)?woman\.ru/user/[^/?#]+/?""", RegexOption.IGNORE_CASE)
        .containsMatchIn(url)
