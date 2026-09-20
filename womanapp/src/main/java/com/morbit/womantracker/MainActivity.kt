package com.morbit.womantracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val PREFS = "woman_tracker"
private const val KEY_PROFILE_URL = "profile_url"
private const val NEW_TOPICS_URL = "https://www.woman.ru/relations/forum/?sort=new"
private const val WOMAN_HOME = "https://www.woman.ru/"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WomanTrackerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WomanTrackerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var profileUrl by remember { mutableStateOf(prefs.getString(KEY_PROFILE_URL, "").orEmpty()) }
    var profileDraft by remember { mutableStateOf(profileUrl) }
    var pageTitle by remember { mutableStateOf("Новые темы") }
    var currentUrl by remember { mutableStateOf(NEW_TOPICS_URL) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showProfileSetup by remember { mutableStateOf(false) }

    fun saveProfile(url: String) {
        val normalized = normalizeProfileUrl(url)
        if (normalized.isNotBlank()) {
            profileUrl = normalized
            profileDraft = normalized
            prefs.edit().putString(KEY_PROFILE_URL, normalized).apply()
        }
    }

    fun openNewTopics() {
        selectedTab = 0
        showProfileSetup = false
        pageTitle = "Новые темы"
        currentUrl = NEW_TOPICS_URL
        webView?.loadUrl(NEW_TOPICS_URL)
    }

    fun openMyReplies() {
        selectedTab = 1
        if (profileUrl.isBlank()) {
            showProfileSetup = true
            pageTitle = "Мои ответы"
        } else {
            showProfileSetup = false
            pageTitle = "Мои ответы"
        }
    }

    BackHandler(enabled = selectedTab == 0 && webView?.canGoBack() == true && !showProfileSetup) {
        webView?.goBack()
    }

    Scaffold(
        modifier = Modifier.systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    if (!showProfileSetup && selectedTab == 0 && webView?.canGoBack() == true) {
                        IconButton(onClick = { webView?.goBack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(onClick = {
                            showProfileSetup = false
                            pageTitle = "Woman.ru"
                            currentUrl = WOMAN_HOME
                            webView?.loadUrl(WOMAN_HOME)
                        }) {
                            Icon(Icons.Default.Home, contentDescription = "Woman.ru")
                        }
                        IconButton(onClick = { webView?.reload() }, enabled = !showProfileSetup) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                    IconButton(onClick = {
                        selectedTab = 1
                        showProfileSetup = true
                        pageTitle = "Настройка профиля"
                        profileDraft = profileUrl
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { openNewTopics() },
                    icon = { Icon(Icons.Default.Forum, contentDescription = null) },
                    label = { Text("Новые") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { openMyReplies() },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Мои") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showProfileSetup) {
                ProfileSetup(
                    value = profileDraft,
                    onValueChange = { profileDraft = it },
                    onSave = {
                        saveProfile(profileDraft)
                        if (profileUrl.isNotBlank()) openMyReplies()
                    },
                    onOpenWoman = {
                        showProfileSetup = false
                        pageTitle = "Вход / профиль"
                        currentUrl = WOMAN_HOME
                        webView?.loadUrl(WOMAN_HOME)
                    }
                )
            } else if (selectedTab == 1) {
                NativeMyRepliesScreen(profileUrl = profileUrl)
            } else {
                WomanWebView(
                    initialUrl = currentUrl,
                    onWebViewReady = { webView = it },
                    onTitleChanged = {
                        if (it.isNotBlank()) pageTitle = it.take(42)
                    },
                    onUrlChanged = { url ->
                        currentUrl = url
                        if (isProfileUrl(url)) {
                            saveProfile(url)
                            selectedTab = 1
                            pageTitle = "Мои ответы"
                        }
                    }
                )
            }
        }
    }

    LaunchedEffect(selectedTab, profileUrl) {
        if (!showProfileSetup) {
            if (selectedTab == 0 && currentUrl.isBlank()) openNewTopics()
            if (selectedTab == 1 && profileUrl.isBlank()) showProfileSetup = true
        }
    }
}

@Composable
private fun ProfileSetup(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpenWoman: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Профиль Woman.ru", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Открой свой профиль на Woman.ru и вставь сюда ссылку вида " +
                        "https://www.woman.ru/user/.../ . Если открыть профиль внутри приложения, ссылка сохранится автоматически."
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ссылка на профиль") }
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onSave) {
                        Text("Сохранить")
                    }
                    Button(onClick = onOpenWoman) {
                        Text("Открыть Woman.ru")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Версия 0.2: «Мои» теперь нативные карточки. Приложение сравнивает " +
                "состояние темы и реакций с предыдущей проверкой."
        )
    }
}

@Composable
private fun WomanWebView(
    initialUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onTitleChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit
) {
    val context = LocalContext.current

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = settings.userAgentString + " WomanTracker/0.2"
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.setSupportZoom(true)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        title?.let(onTitleChanged)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        if (url.startsWith("https://www.woman.ru/") || url.startsWith("https://woman.ru/")) {
                            return false
                        }
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val finalUrl = url.orEmpty()
                        onUrlChanged(finalUrl)
                        CookieManager.getInstance().flush()
                    }
                }

                onWebViewReady(this)
                loadUrl(initialUrl)
            }
        },
        update = { view ->
            if (view.url.isNullOrBlank() && initialUrl.isNotBlank()) {
                view.loadUrl(initialUrl)
            }
        }
    )
}

private fun isProfileUrl(url: String): Boolean {
    return Regex("""https?://(www\.)?woman\.ru/user/[^/?#]+/?""", RegexOption.IGNORE_CASE)
        .containsMatchIn(url)
}

private fun normalizeProfileUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val candidate = when {
        trimmed.startsWith("https://", true) -> trimmed
        trimmed.startsWith("http://", true) -> "https://" + trimmed.substringAfter("://")
        trimmed.startsWith("www.woman.ru/", true) -> "https://$trimmed"
        trimmed.startsWith("woman.ru/", true) -> "https://www.$trimmed"
        else -> trimmed
    }
    return if (isProfileUrl(candidate)) candidate.substringBefore("?") else ""
}
