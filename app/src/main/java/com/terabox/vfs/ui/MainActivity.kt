package com.terabox.vfs.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.terabox.vfs.provider.TeraBoxDocumentsProvider
import com.terabox.vfs.sync.AccountSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
                MainApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        val app = TeraBoxApp.instance
        val scope = rememberCoroutineScope()
        val apiClient = remember { TeraBoxApiClient() }

        var selectedTab by remember { mutableStateOf(1) } // Default to Files tab so user sees files immediately!
        var accounts by remember { mutableStateOf(app.dbHelper.getAccounts()) }
        var currentDir by remember { mutableStateOf("/") }
        var fileList by remember { mutableStateOf<List<FileNodeModel>>(emptyList()) }
        var searchQuery by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }

        // Dialog states
        var showCookieInput by remember { mutableStateOf(false) }
        var showWebViewLogin by remember { mutableStateOf(false) }
        var showNewFolderDialog by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf<FileNodeModel?>(null) }
        var showDeleteDialog by remember { mutableStateOf<FileNodeModel?>(null) }

        // Progress Dialog states for real Upload & Download
        var transferProgress by remember { mutableStateOf<Pair<String, Float>?>(null) }

        val activeAccount = accounts.firstOrNull { it.active }

        // Refresh files from database or cloud
        fun refreshFileList(forceCloud: Boolean = false) {
            val acc = activeAccount ?: return
            scope.launch {
                isLoading = true
                if (forceCloud) {
                    withContext(Dispatchers.IO) {
                        val listRes = apiClient.listFiles(acc.ndus, currentDir)
                        if (listRes.isSuccess) {
                            val items = listRes.getOrDefault(emptyList())
                            val nodes = items.map { item ->
                                FileNodeModel(
                                    fsId = item.fsId,
                                    accountId = acc.id,
                                    path = item.path,
                                    parentPath = currentDir,
                                    filename = item.filename,
                                    isDir = item.isdir == 1,
                                    size = item.size,
                                    mtime = item.mtime,
                                    dlink = item.dlink,
                                    dlinkUpdatedAt = if (item.dlink != null) System.currentTimeMillis() else 0L,
                                    isPinned = false
                                )
                            }
                            app.dbHelper.insertOrUpdateNodes(nodes)
                        }
                    }
                }

                fileList = withContext(Dispatchers.IO) {
                    if (searchQuery.isNotBlank()) {
                        app.dbHelper.searchNodes(acc.id, searchQuery)
                    } else {
                        app.dbHelper.getChildren(acc.id, currentDir)
                    }
                }
                isLoading = false
            }
        }

        LaunchedEffect(activeAccount, currentDir, searchQuery) {
            refreshFileList(forceCloud = fileList.isEmpty())
        }

        // Native File Picker for Cloud Upload
        val uploadPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null && activeAccount != null) {
                val (fileName, fileSize) = getFileInfoFromUri(this@MainActivity, uri)
                val targetRemotePath = if (currentDir == "/") "/$fileName" else "$currentDir/$fileName"

                scope.launch {
                    transferProgress = Pair("Uploading $fileName...", 0f)
                    val success = withContext(Dispatchers.IO) {
                        try {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                val res = apiClient.uploadFile(
                                    ndus = activeAccount.ndus,
                                    remotePath = targetRemotePath,
                                    filename = fileName,
                                    inputStream = stream,
                                    fileSize = fileSize
                                ) { uploaded, total ->
                                    val prog = if (total > 0) uploaded.toFloat() / total.toFloat() else 0.5f
                                    transferProgress = Pair("Uploading $fileName (${(prog * 100).toInt()}%)...", prog)
                                }
                                res.isSuccess
                            } ?: false
                        } catch (e: Exception) {
                            false
                        }
                    }
                    transferProgress = null
                    if (success) {
                        Toast.makeText(this@MainActivity, "Uploaded $fileName to TeraBox!", Toast.LENGTH_SHORT).show()
                        refreshFileList(forceCloud = true)
                    } else {
                        Toast.makeText(this@MainActivity, "Upload failed. Check connection.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "TeraBox 1TB Virtual Storage",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            if (activeAccount != null) {
                                Text(
                                    "${activeAccount.name} • 1,024 GB Cloud",
                                    fontSize = 11.sp,
                                    color = Color(0xFF81D4FA)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1A1A1A),
                        titleContentColor = Color.White
                    ),
                    actions = {
                        IconButton(onClick = { refreshFileList(forceCloud = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF64B5F6))
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = "1 TB Drive") },
                        label = { Text("1 TB Drive") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                        label = { Text("Cloud Files") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Storage, contentDescription = "Cache") },
                        label = { Text("Cache") }
                    )
                }
            },
            floatingActionButton = {
                if (selectedTab == 1 && activeAccount != null) {
                    ExtendedFloatingActionButton(
                        onClick = { uploadPickerLauncher.launch("*/*") },
                        containerColor = Color(0xFF1E88E5),
                        contentColor = Color.White,
                        icon = { Icon(Icons.Default.Upload, contentDescription = null) },
                        text = { Text("Upload File") }
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
                    0 -> DriveDashboardTab(
                        activeAccount = activeAccount,
                        onConnectClick = { showCookieInput = true },
                        onBrowserLoginClick = { showWebViewLogin = true }
                    )
                    1 -> NativeFileExplorerTab(
                        activeAccount = activeAccount,
                        currentDir = currentDir,
                        fileList = fileList,
                        searchQuery = searchQuery,
                        isLoading = isLoading,
                        onSearchChange = { searchQuery = it },
                        onDirClick = { newDir -> currentDir = newDir },
                        onUpClick = {
                            if (currentDir != "/") {
                                val clean = if (currentDir.endsWith("/")) currentDir.dropLast(1) else currentDir
                                val lastSlash = clean.lastIndexOf('/')
                                currentDir = if (lastSlash <= 0) "/" else clean.substring(0, lastSlash)
                            }
                        },
                        onUploadClick = { uploadPickerLauncher.launch("*/*") },
                        onNewFolderClick = { showNewFolderDialog = true },
                        onDownloadClick = { node ->
                            scope.launch {
                                transferProgress = Pair("Preparing download: ${node.filename}...", 0f)
                                withContext(Dispatchers.IO) {
                                    val dlinkRes = apiClient.getDownloadLink(activeAccount!!.ndus, node.fsId)
                                    val dlink = dlinkRes.getOrNull()
                                    if (dlink != null) {
                                        val downloadDir = File(
                                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                            "TeraBox"
                                        ).apply { mkdirs() }
                                        val destFile = File(downloadDir, node.filename)

                                        apiClient.downloadFile(dlink, activeAccount.ndus, destFile) { down, tot ->
                                            val p = if (tot > 0) down.toFloat() / tot.toFloat() else 0.5f
                                            transferProgress = Pair("Downloading ${node.filename} (${(p * 100).toInt()}%)...", p)
                                        }
                                    }
                                }
                                transferProgress = null
                                Toast.makeText(this@MainActivity, "Saved to Downloads/TeraBox/${node.filename}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onPlayClick = { node ->
                            // Open in external media player (VLC, MX Player) using SAF URI
                            try {
                                val docId = TeraBoxDocumentsProvider.buildDocId(activeAccount!!.id, node.fsId)
                                val uri = DocumentsContract.buildDocumentUri(TeraBoxDocumentsProvider.AUTHORITY, docId)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, getMimeType(node.filename))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(Intent.createChooser(intent, "Open with..."))
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRenameClick = { node -> showRenameDialog = node },
                        onDeleteClick = { node -> showDeleteDialog = node }
                    )
                    2 -> CacheTab()
                }

                // Transfer progress overlay
                transferProgress?.let { (msg, prog) ->
                    Dialog(onDismissRequest = {}) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(msg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    progress = { prog },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF1E88E5)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Direct Cookie Setup Dialog (100% Native, No WebViews!)
        if (showCookieInput) {
            var cookieText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCookieInput = false },
                title = { Text("Connect TeraBox 1TB Storage") },
                text = {
                    Column {
                        Text(
                            "Enter your TeraBox 'ndus' cookie to grant 1,024 GB virtual storage to this phone:",
                            fontSize = 13.sp,
                            color = Color.LightGray
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cookieText,
                            onValueChange = { cookieText = it },
                            placeholder = { Text("Paste ndus=...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clean = cookieText.trim().removePrefix("ndus=").trim()
                            if (clean.isNotBlank()) {
                                showCookieInput = false
                                scope.launch {
                                    val quotaRes = withContext(Dispatchers.IO) { apiClient.getQuota(clean) }
                                    val quota = quotaRes.getOrNull()
                                    val acc = AccountModel(
                                        id = "terabox_main",
                                        name = "TeraBox 1TB Drive",
                                        ndus = clean,
                                        totalQuota = quota?.total ?: (1024L * 1024L * 1024L * 1024L),
                                        usedQuota = quota?.used ?: 0L,
                                        active = true
                                    )
                                    app.dbHelper.upsertAccount(acc)
                                    accounts = app.dbHelper.getAccounts()
                                    selectedTab = 1
                                    refreshFileList(forceCloud = true)
                                    Toast.makeText(this@MainActivity, "1 TB Virtual Storage Connected!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Text("Connect 1TB Drive")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCookieInput = false }) { Text("Cancel") }
                }
            )
        }

        // Optional Quick Web Login (auto-closes once cookie is captured)
        if (showWebViewLogin) {
            Dialog(onDismissRequest = { showWebViewLogin = false }) {
                Card(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
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
                                        val cookieStr = CookieManager.getInstance().getCookie(url)
                                        if (cookieStr != null && cookieStr.contains("ndus=")) {
                                            val ndus = cookieStr.split(";")
                                                .map { it.trim() }
                                                .firstOrNull { it.startsWith("ndus=") }
                                                ?.removePrefix("ndus=")
                                            if (!ndus.isNullOrEmpty()) {
                                                showWebViewLogin = false
                                                scope.launch {
                                                    val quotaRes = withContext(Dispatchers.IO) { apiClient.getQuota(ndus) }
                                                    val quota = quotaRes.getOrNull()
                                                    val acc = AccountModel(
                                                        id = "terabox_main",
                                                        name = "TeraBox 1TB Drive",
                                                        ndus = ndus,
                                                        totalQuota = quota?.total ?: (1024L * 1024L * 1024L * 1024L),
                                                        usedQuota = quota?.used ?: 0L,
                                                        active = true
                                                    )
                                                    app.dbHelper.upsertAccount(acc)
                                                    accounts = app.dbHelper.getAccounts()
                                                    selectedTab = 1
                                                    refreshFileList(forceCloud = true)
                                                    Toast.makeText(this@MainActivity, "TeraBox Connected Successfully!", Toast.LENGTH_LONG).show()
                                                }
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

        // New Folder Dialog
        if (showNewFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewFolderDialog = false },
                title = { Text("Create New Folder") },
                text = {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        placeholder = { Text("Folder Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val trimmed = folderName.trim()
                        if (trimmed.isNotBlank() && activeAccount != null) {
                            showNewFolderDialog = false
                            scope.launch {
                                val target = if (currentDir == "/") "/$trimmed" else "$currentDir/$trimmed"
                                val res = withContext(Dispatchers.IO) { apiClient.createFolder(activeAccount.ndus, target) }
                                if (res.isSuccess) {
                                    Toast.makeText(this@MainActivity, "Folder created!", Toast.LENGTH_SHORT).show()
                                    refreshFileList(forceCloud = true)
                                } else {
                                    Toast.makeText(this@MainActivity, "Failed to create folder", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Rename Dialog
        showRenameDialog?.let { node ->
            var newName by remember { mutableStateOf(node.filename) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                title = { Text("Rename ${if (node.isDir) "Folder" else "File"}") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotBlank() && activeAccount != null) {
                            showRenameDialog = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    apiClient.renameItem(activeAccount.ndus, node.path, trimmed)
                                }
                                if (res.isSuccess) {
                                    val newPath = node.path.substringBeforeLast('/') + "/$trimmed"
                                    app.dbHelper.renameNode(node.fsId, trimmed, newPath)
                                    refreshFileList(forceCloud = true)
                                    Toast.makeText(this@MainActivity, "Renamed successfully", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Rename failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = null }) { Text("Cancel") }
                }
            )
        }

        // Delete Dialog
        showDeleteDialog?.let { node ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text("Delete ${if (node.isDir) "Folder" else "File"}?") },
                text = { Text("Are you sure you want to delete '${node.filename}' from your TeraBox cloud?") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        onClick = {
                            showDeleteDialog = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    apiClient.deleteItem(activeAccount!!.ndus, node.path)
                                }
                                if (res.isSuccess) {
                                    app.dbHelper.deleteNode(node.fsId)
                                    refreshFileList(forceCloud = true)
                                    Toast.makeText(this@MainActivity, "Deleted '${node.filename}'", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
                }
            )
        }
    }

    @Composable
    fun DriveDashboardTab(
        activeAccount: AccountModel?,
        onConnectClick: () -> Unit,
        onBrowserLoginClick: () -> Unit
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
                val freeGb = (totalGb - usedGb).coerceAtLeast(0f)
                val fraction = if (totalGb > 0) (usedGb / totalGb).coerceIn(0f, 1f) else 0f

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudCircle, contentDescription = null, tint = Color(0xFF42A5F5), modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("1,024 GB Cloud Storage", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(activeAccount.name, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                            color = Color(0xFF42A5F5),
                            trackColor = Color(0xFF333333)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(String.format("%.2f GB used", usedGb), fontSize = 13.sp, color = Color.LightGray)
                            Text(String.format("%.2f GB FREE", freeGb), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF81D4FA))
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3024)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                            Spacer(Modifier.width(8.dp))
                            Text("1 TB Virtual Storage Granted to Phone", fontWeight = FontWeight.Bold, color = Color(0xFFA5D6A7))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "TeraBox is directly mounted into Android's native Files app and Storage Access Framework. GameHub, VLC, and emulators can access your game ISOs, ROMs, and movies with zero local storage footprint.",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(56.dp), tint = Color(0xFF42A5F5))
                        Spacer(Modifier.height(12.dp))
                        Text("Connect Your 1 TB Storage", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Grant 1,024 GB of virtual cloud storage directly to your Android phone without eating your internal memory.",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onConnectClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connect via Session Cookie (Instant)")
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onBrowserLoginClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Quick Browser Login")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NativeFileExplorerTab(
        activeAccount: AccountModel?,
        currentDir: String,
        fileList: List<FileNodeModel>,
        searchQuery: String,
        isLoading: Boolean,
        onSearchChange: (String) -> Unit,
        onDirClick: (String) -> Unit,
        onUpClick: () -> Unit,
        onUploadClick: () -> Unit,
        onNewFolderClick: () -> Unit,
        onDownloadClick: (FileNodeModel) -> Unit,
        onPlayClick: (FileNodeModel) -> Unit,
        onRenameClick: (FileNodeModel) -> Unit,
        onDeleteClick: (FileNodeModel) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (activeAccount == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Connect your TeraBox account in '1 TB Drive' tab first.", color = Color.Gray)
                }
                return
            }

            // Action Toolbar (Upload File & New Folder directly in app!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onUploadClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onNewFolderClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Folder", fontSize = 12.sp)
                }

                Spacer(Modifier.weight(1f))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.width(160.dp),
                    placeholder = { Text("Search...", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E88E5),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }

            // Breadcrumb path navigation bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF242424))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentDir != "/") {
                    IconButton(onClick = onUpClick, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF42A5F5))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (currentDir == "/") "TeraBox Root (/)" else currentDir,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.LightGray,
                    maxLines = 1
                )
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Color(0xFF1E88E5))
            }

            if (fileList.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.DarkGray)
                        Spacer(Modifier.height(8.dp))
                        Text("No files in this folder", color = Color.Gray, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onUploadClick) {
                            Text("Upload File to this Folder")
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(fileList) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (node.isDir) {
                                        onDirClick(node.path)
                                    } else {
                                        onPlayClick(node)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Specialized Type Icon
                            Icon(
                                imageVector = when {
                                    node.isDir -> Icons.Default.Folder
                                    isGameFile(node.filename) -> Icons.Default.SportsEsports
                                    isVideoFile(node.filename) -> Icons.Default.VideoLibrary
                                    isAudioFile(node.filename) -> Icons.Default.AudioFile
                                    isImageFile(node.filename) -> Icons.Default.Image
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = when {
                                    node.isDir -> Color(0xFFFFB74D)
                                    isGameFile(node.filename) -> Color(0xFFE91E63)
                                    isVideoFile(node.filename) -> Color(0xFF42A5F5)
                                    isAudioFile(node.filename) -> Color(0xFF66BB6A)
                                    else -> Color(0xFF90CAF9)
                                },
                                modifier = Modifier.size(32.dp)
                            )

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    node.filename,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (node.isDir) "Folder" else "${formatSize(node.size)} • ${formatDate(node.mtime)}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }

                            // File Context Actions (Download, Play, Rename, Delete)
                            if (!node.isDir) {
                                IconButton(onClick = { onPlayClick(node) }) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = "Play/Open", tint = Color(0xFF42A5F5))
                                }
                                IconButton(onClick = { onDownloadClick(node) }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download to Phone", tint = Color(0xFF81C784))
                                }
                            }

                            var showItemMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showItemMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.LightGray)
                                }
                                DropdownMenu(
                                    expanded = showItemMenu,
                                    onDismissRequest = { showItemMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            showItemMenu = false
                                            onRenameClick(node)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = Color(0xFFE57373)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFE57373)) },
                                        onClick = {
                                            showItemMenu = false
                                            onDeleteClick(node)
                                        }
                                    )
                                }
                            }
                        }
                        Divider(color = Color(0xFF1E1E1E))
                    }
                }
            }
        }
    }

    @Composable
    fun CacheTab() {
        val app = TeraBoxApp.instance
        var cacheSizeMb by remember { mutableStateOf(app.cacheManager.getTotalCacheSizeBytes() / (1024 * 1024)) }
        var maxCacheGb by remember { mutableStateOf(app.cacheManager.getMaxCacheSizeBytes() / (1024f * 1024f * 1024f)) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Local Smart Cache", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Cached Blocks on Phone: $cacheSizeMb MB", fontSize = 13.sp, color = Color.LightGray)
                    Spacer(Modifier.height(16.dp))
                    Text("Max Cache Budget: ${maxCacheGb.toInt()} GB", fontSize = 13.sp)
                    Slider(
                        value = maxCacheGb,
                        onValueChange = {
                            maxCacheGb = it
                            app.cacheManager.setMaxCacheSizeGb(it.toLong())
                        },
                        valueRange = 2f..20f,
                        steps = 18
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            app.cacheManager.clearAllUnpinnedCache(emptySet())
                            cacheSizeMb = app.cacheManager.getTotalCacheSizeBytes() / (1024 * 1024)
                            Toast.makeText(this@MainActivity, "Cache Cleared", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear All Cached Blocks")
                    }
                }
            }
        }
    }

    private fun isGameFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".iso") || lower.endsWith(".cso") || lower.endsWith(".bin") ||
                lower.endsWith(".chd") || lower.endsWith(".rom") || lower.endsWith(".nsp") ||
                lower.endsWith(".xci") || lower.endsWith(".apk")
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".flv")
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") ||
                lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".m4a")
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun formatDate(timestampSeconds: Long): String {
        if (timestampSeconds <= 0) return ""
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(timestampSeconds * 1000L))
    }

    private fun getMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "avi", "mov", "webm" -> "video/*"
            "mp3", "flac", "wav", "aac", "ogg" -> "audio/*"
            "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
            "iso", "bin", "cso", "zip", "7z", "rar" -> "application/octet-stream"
            "pdf" -> "application/pdf"
            else -> "*/*"
        }
    }

    private fun getFileInfoFromUri(context: Context, uri: Uri): Pair<String, Long> {
        var name = "uploaded_file"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return Pair(name, size)
    }
}
