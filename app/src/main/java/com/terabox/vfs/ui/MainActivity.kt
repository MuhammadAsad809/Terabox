package com.terabox.vfs.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.terabox.vfs.TeraBoxApp
import com.terabox.vfs.data.AccountModel
import com.terabox.vfs.data.FileNodeModel
import com.terabox.vfs.data.TeraBoxApiClient
import com.terabox.vfs.sync.AccountSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1E88E5),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val app = TeraBoxApp.instance
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(0) }
        var accounts by remember { mutableStateOf(app.dbHelper.getAccounts()) }
        var showWebViewLogin by remember { mutableStateOf(false) }
        var showManualCookieDialog by remember { mutableStateOf(false) }
        var currentDir by remember { mutableStateOf("/") }
        var fileList by remember { mutableStateOf<List<FileNodeModel>>(emptyList()) }
        var searchQuery by remember { mutableStateOf("") }
        var cacheSizeMb by remember { mutableStateOf(app.cacheManager.getTotalCacheSizeBytes() / (1024 * 1024)) }

        val activeAccount = accounts.firstOrNull { it.active }

        // Refresh files when account or directory changes
        fun loadFiles() {
            if (activeAccount != null) {
                fileList = if (searchQuery.isNotBlank()) {
                    app.dbHelper.searchNodes(activeAccount.id, searchQuery)
                } else {
                    app.dbHelper.getChildren(activeAccount.id, currentDir)
                }
            } else {
                fileList = emptyList()
            }
        }

        LaunchedEffect(activeAccount, currentDir, searchQuery) {
            loadFiles()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "TeraBox Virtual Storage",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1A1A1A),
                        titleContentColor = Color.White
                    ),
                    actions = {
                        IconButton(onClick = {
                            // Enqueue background sync
                            WorkManager.getInstance(this@MainActivity)
                                .enqueue(OneTimeWorkRequestBuilder<AccountSyncWorker>().build())
                            Toast.makeText(this@MainActivity, "Cloud Sync Started...", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Sync, contentDescription = "Sync", tint = Color(0xFF64B5F6))
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = "Dashboard") },
                        label = { Text("Drive") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Explorer") },
                        label = { Text("Files") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Storage, contentDescription = "Settings") },
                        label = { Text("Cache") }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF121212))
            ) {
                when (selectedTab) {
                    0 -> DashboardTab(
                        activeAccount = activeAccount,
                        onLoginClick = { showWebViewLogin = true },
                        onManualCookieClick = { showManualCookieDialog = true }
                    )
                    1 -> FileExplorerTab(
                        activeAccount = activeAccount,
                        currentDir = currentDir,
                        fileList = fileList,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onDirClick = { newDir -> currentDir = newDir },
                        onUpClick = {
                            if (currentDir != "/") {
                                currentDir = currentDir.substringBeforeLast('/', "/")
                                if (currentDir.isEmpty()) currentDir = "/"
                            }
                        },
                        onPinToggle = { node ->
                            app.dbHelper.setPinned(node.fsId, !node.isPinned)
                            loadFiles()
                        }
                    )
                    2 -> CacheSettingsTab(
                        cacheSizeMb = cacheSizeMb,
                        onClearCache = {
                            app.cacheManager.clearAllUnpinnedCache(emptySet())
                            cacheSizeMb = app.cacheManager.getTotalCacheSizeBytes() / (1024 * 1024)
                            Toast.makeText(this@MainActivity, "Cache Cleared", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        // WebView Login Dialog
        if (showWebViewLogin) {
            WebViewLoginDialog(
                onDismiss = { showWebViewLogin = false },
                onCookieCaptured = { ndusCookie ->
                    showWebViewLogin = false
                    scope.launch {
                        val client = TeraBoxApiClient()
                        val quotaRes = withContext(Dispatchers.IO) { client.getQuota(ndusCookie) }
                        val quota = quotaRes.getOrNull()
                        val acc = AccountModel(
                            id = "terabox_1",
                            name = "My TeraBox",
                            ndus = ndusCookie,
                            totalQuota = quota?.total ?: (1024L * 1024L * 1024L * 1024L),
                            usedQuota = quota?.used ?: 0L,
                            active = true
                        )
                        app.dbHelper.upsertAccount(acc)
                        accounts = app.dbHelper.getAccounts()
                        WorkManager.getInstance(this@MainActivity)
                            .enqueue(OneTimeWorkRequestBuilder<AccountSyncWorker>().build())
                        Toast.makeText(this@MainActivity, "TeraBox Connected Successfully!", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // Manual Cookie Dialog
        if (showManualCookieDialog) {
            ManualCookieDialog(
                onDismiss = { showManualCookieDialog = false },
                onSubmit = { cookie ->
                    showManualCookieDialog = false
                    scope.launch {
                        val client = TeraBoxApiClient()
                        val quotaRes = withContext(Dispatchers.IO) { client.getQuota(cookie) }
                        val quota = quotaRes.getOrNull()
                        val acc = AccountModel(
                            id = "terabox_1",
                            name = "My TeraBox",
                            ndus = cookie,
                            totalQuota = quota?.total ?: (1024L * 1024L * 1024L * 1024L),
                            usedQuota = quota?.used ?: 0L,
                            active = true
                        )
                        app.dbHelper.upsertAccount(acc)
                        accounts = app.dbHelper.getAccounts()
                        WorkManager.getInstance(this@MainActivity)
                            .enqueue(OneTimeWorkRequestBuilder<AccountSyncWorker>().build())
                        Toast.makeText(this@MainActivity, "Cookie Saved & Sync Scheduled", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    @Composable
    fun DashboardTab(
        activeAccount: AccountModel?,
        onLoginClick: () -> Unit,
        onManualCookieClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (activeAccount != null) {
                val totalGb = activeAccount.totalQuota.toFloat() / (1024f * 1024f * 1024f)
                val usedGb = activeAccount.usedQuota.toFloat() / (1024f * 1024f * 1024f)
                val fraction = if (totalGb > 0) (usedGb / totalGb).coerceIn(0f, 1f) else 0f

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudQueue, contentDescription = null, tint = Color(0xFF42A5F5))
                            Spacer(Modifier.width(8.dp))
                            Text(activeAccount.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF42A5F5),
                            trackColor = Color(0xFF333333)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = String.format("%.2f GB used of %.2f GB", usedGb, totalGb),
                            fontSize = 13.sp,
                            color = Color.LightGray
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2E3C)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Android Virtual Drive Active",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81D4FA)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "TeraBox is mounted into Android's system Files app. GameHub, VLC, and emulators can open games and videos directly through the Storage Access Framework (SAF).",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text("No TeraBox Account Connected", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Connect your TeraBox account to mount your 1TB cloud drive virtually.", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onLoginClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) {
                            Text("Login with TeraBox WebView")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onManualCookieClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enter NDUS Cookie Manually")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FileExplorerTab(
        activeAccount: AccountModel?,
        currentDir: String,
        fileList: List<FileNodeModel>,
        searchQuery: String,
        onSearchChange: (String) -> Unit,
        onDirClick: (String) -> Unit,
        onUpClick: () -> Unit,
        onPinToggle: (FileNodeModel) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search files & games in TeraBox...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1E88E5),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            // Current Path Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentDir != "/") {
                    IconButton(onClick = onUpClick, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Up", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(currentDir, fontSize = 13.sp, color = Color.LightGray)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(fileList) { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (node.isDir) {
                                    onDirClick(node.path)
                                } else {
                                    Toast.makeText(this@MainActivity, "Virtual File: ${node.filename}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (node.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (node.isDir) Color(0xFFFFB74D) else Color(0xFF81D4FA)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.filename, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (!node.isDir) {
                                val sizeMb = node.size.toFloat() / (1024f * 1024f)
                                Text(String.format("%.2f MB", sizeMb), fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                        if (!node.isDir) {
                            IconButton(onClick = { onPinToggle(node) }) {
                                Icon(
                                    imageVector = if (node.isPinned) Icons.Default.PushPin else Icons.Default.CloudDownload,
                                    contentDescription = "Pin Offline",
                                    tint = if (node.isPinned) Color(0xFF4CAF50) else Color.Gray
                                )
                            }
                        }
                    }
                    Divider(color = Color(0xFF222222))
                }
            }
        }
    }

    @Composable
    fun CacheSettingsTab(
        cacheSizeMb: Long,
        onClearCache: () -> Unit
    ) {
        val app = TeraBoxApp.instance
        var sliderValue by remember { mutableStateOf(app.cacheManager.getMaxCacheSizeBytes() / (1024f * 1024f * 1024f)) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LRU Block Cache", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Currently Cached on Device: $cacheSizeMb MB", fontSize = 13.sp, color = Color.LightGray)
                    Spacer(Modifier.height(16.dp))
                    Text("Max Cache Budget: ${sliderValue.toInt()} GB", fontSize = 13.sp)
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it
                            app.cacheManager.setMaxCacheSizeGb(it.toLong())
                        },
                        valueRange = 2f..20f,
                        steps = 18
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onClearCache,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Cached Blocks")
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun WebViewLoginDialog(
        onDismiss: () -> Unit,
        onCookieCaptured: (String) -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(16.dp)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = TeraBoxApiClient.USER_AGENT

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val cookieStr = CookieManager.getInstance().getCookie(url)
                                    if (cookieStr != null && cookieStr.contains("ndus=")) {
                                        val ndus = cookieStr.split(";")
                                            .map { it.trim() }
                                            .firstOrNull { it.startsWith("ndus=") }
                                            ?.removePrefix("ndus=")
                                        if (!ndus.isNullOrEmpty()) {
                                            onCookieCaptured(ndus)
                                        }
                                    }
                                }
                            }
                            loadUrl("https://www.terabox.com/main")
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun ManualCookieDialog(
        onDismiss: () -> Unit,
        onSubmit: (String) -> Unit
    ) {
        var cookieInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Enter NDUS Cookie") },
            text = {
                Column {
                    Text("Paste your TeraBox 'ndus' session cookie:", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        placeholder = { Text("ndus=...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = cookieInput.trim().removePrefix("ndus=")
                        if (clean.isNotBlank()) onSubmit(clean)
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }
}
