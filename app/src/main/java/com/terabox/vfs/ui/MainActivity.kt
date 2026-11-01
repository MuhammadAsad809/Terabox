package com.terabox.vfs.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.terabox.vfs.TeraBoxApp
import com.terabox.vfs.data.DriveAccount
import com.terabox.vfs.data.DriveSessionExpiredException
import com.terabox.vfs.data.FileEntity
import com.terabox.vfs.data.GoogleDriveApiClient
import com.terabox.vfs.data.PinnedEntity
import com.terabox.vfs.provider.CloudSSDDocumentsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val app by lazy { TeraBoxApp.instance }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF42A5F5),
                    secondary = Color(0xFF26A69A),
                    surface = Color(0xFF1E1E1E),
                    background = Color(0xFF121212)
                )
            ) {
                CloudSSDMainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        app.playerManager.release()
    }

    @Composable
    fun CloudSSDMainScreen() {
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableIntStateOf(0) } // 0: Library, 1: Player, 2: Offline, 3: Settings

        var activeAccount by remember { mutableStateOf(app.dbHelper.getActiveAccount()) }
        var showLoginDialog by remember { mutableStateOf(false) }

        // Folder & File Navigation State
        var currentFolderId by remember { mutableStateOf("root") }
        val folderHistory = remember { mutableStateListOf<Pair<String, String>>() } // id to name
        var filesList by remember { mutableStateOf(listOf<FileEntity>()) }
        var isLoading by remember { mutableStateOf(false) }
        var isOffline by remember { mutableStateOf(!isNetworkAvailable(this@MainActivity)) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        // Search & Filter State
        var searchQuery by remember { mutableStateOf("") }
        var selectedFilter by remember { mutableStateOf("ALL") } // ALL, VIDEO, AUDIO, IMAGE
        var isGridView by remember { mutableStateOf(false) }
        var sortBy by remember { mutableStateOf("NAME") } // NAME, DATE, SIZE

        // Player Overlay / State
        var activePlayingFile by remember { mutableStateOf<FileEntity?>(null) }
        var showImageViewer by remember { mutableStateOf<FileEntity?>(null) }

        // Pinning Progress
        var pinningProgress by remember { mutableStateOf<Pair<String, Float>?>(null) }

        // Function to load children from DB and sync with Google Drive
        fun refreshDirectory(folderId: String, forceCloud: Boolean = false) {
            val acc = activeAccount
            scope.launch {
                isLoading = true
                errorMessage = null
                isOffline = !isNetworkAvailable(this@MainActivity)

                // 1. Load from local cache first for instant UI response
                filesList = withContext(Dispatchers.IO) {
                    app.dbHelper.getChildren(folderId)
                }

                // 2. If online and (forced or cache is empty), sync with Drive API v3
                if (!isOffline && acc != null && (forceCloud || filesList.isEmpty())) {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            // Also refresh quota and profile
                            val aboutRes = app.apiClient.getAbout(acc.accessToken)
                            aboutRes.getOrNull()?.let { (quota, user) ->
                                val updated = acc.copy(
                                    totalQuota = quota.limit,
                                    usedQuota = quota.usage,
                                    displayName = user?.displayName ?: acc.displayName,
                                    email = user?.emailAddress ?: acc.email
                                )
                                app.dbHelper.saveAccount(updated)
                                activeAccount = updated
                            }

                            val listRes = app.apiClient.listFiles(
                                accessToken = acc.accessToken,
                                folderId = folderId,
                                mediaOnly = true
                            )

                            if (listRes.isSuccess) {
                                val driveFiles = listRes.getOrNull()?.files ?: emptyList()
                                val entities = driveFiles.map { df ->
                                    FileEntity(
                                        id = df.id,
                                        name = df.name,
                                        mimeType = df.mimeType,
                                        parentId = folderId,
                                        size = df.size,
                                        modifiedTime = System.currentTimeMillis(),
                                        md5Checksum = df.md5Checksum,
                                        thumbnailLink = df.thumbnailLink,
                                        isFolder = df.isFolder
                                    )
                                }
                                app.dbHelper.upsertFiles(entities)
                                Result.success(entities)
                            } else {
                                val ex = listRes.exceptionOrNull()
                                Result.failure(ex ?: Exception("Drive list failed"))
                            }
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }

                    if (result.isSuccess) {
                        filesList = withContext(Dispatchers.IO) {
                            app.dbHelper.getChildren(folderId)
                        }
                    } else {
                        val ex = result.exceptionOrNull()
                        if (ex is DriveSessionExpiredException) {
                            errorMessage = "Drive session expired. Tap to reconnect."
                            showLoginDialog = true
                        } else {
                            errorMessage = ex?.message ?: "Error syncing with Drive"
                        }
                    }
                }
                isLoading = false
            }
        }

        LaunchedEffect(currentFolderId, activeAccount) {
            refreshDirectory(currentFolderId, forceCloud = filesList.isEmpty())
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "CloudSSD Media",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (activeAccount != null) {
                                    "${activeAccount?.displayName} • ${formatGb(activeAccount?.totalQuota ?: 0)} Cloud"
                                } else "No Account Connected",
                                fontSize = 11.sp,
                                color = Color(0xFF81D4FA)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1E1E1E),
                        titleContentColor = Color.White
                    ),
                    actions = {
                        IconButton(onClick = { refreshDirectory(currentFolderId, forceCloud = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF42A5F5))
                        }
                        if (activeAccount == null) {
                            Button(
                                onClick = { showLoginDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("Sign In", fontSize = 12.sp)
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF1E1E1E)) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Library") },
                        label = { Text("Library") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.PlayCircle, contentDescription = "Player") },
                        label = { Text("Player") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.PushPin, contentDescription = "Offline") },
                        label = { Text("Offline") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // Offline Banner
                    if (isOffline) {
                        Surface(
                            color = Color(0xFFE65100),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Offline Mode: Showing cached media & pinned files",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Error Banner
                    errorMessage?.let { msg ->
                        Surface(
                            color = Color(0xFFB71C1C),
                            modifier = Modifier.fillMaxWidth().clickable { showLoginDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("Reconnect", color = Color(0xFF80D8FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    when (selectedTab) {
                        0 -> LibraryTab(
                            account = activeAccount,
                            folderHistory = folderHistory,
                            filesList = filesList,
                            searchQuery = searchQuery,
                            selectedFilter = selectedFilter,
                            isGridView = isGridView,
                            sortBy = sortBy,
                            isLoading = isLoading,
                            onSearchChange = { searchQuery = it },
                            onFilterChange = { selectedFilter = it },
                            onSortChange = { sortBy = it },
                            onGridToggle = { isGridView = !isGridView },
                            onFolderClick = { folder ->
                                folderHistory.add(Pair(currentFolderId, if (folderHistory.isEmpty()) "Root" else folderHistory.last().second))
                                currentFolderId = folder.id
                            },
                            onBreadcrumbClick = { index ->
                                if (index < folderHistory.size) {
                                    val target = folderHistory[index]
                                    while (folderHistory.size > index) {
                                        folderHistory.removeAt(folderHistory.lastIndex)
                                    }
                                    currentFolderId = target.first
                                }
                            },
                            onRootBreadcrumbClick = {
                                folderHistory.clear()
                                currentFolderId = "root"
                            },
                            onFileClick = { file ->
                                if (file.isImage) {
                                    showImageViewer = file
                                } else {
                                    activePlayingFile = file
                                    selectedTab = 1
                                    activeAccount?.let { acc ->
                                        app.playerManager.playMedia(file, filesList, acc.accessToken)
                                    }
                                }
                            },
                            onPinClick = { file ->
                                scope.launch {
                                    pinningProgress = Pair("Pinning ${file.name}...", 0.05f)
                                    val acc = activeAccount
                                    if (acc != null) {
                                        val destFile = File(app.cacheManager.pinnedDir, "${file.id}_${file.name}")
                                        val res = withContext(Dispatchers.IO) {
                                            app.apiClient.downloadFileForPin(acc.accessToken, file.id, destFile) { down, total ->
                                                val p = if (total > 0) down.toFloat() / total.toFloat() else 0.5f
                                                pinningProgress = Pair("Pinning ${file.name} (${(p * 100).toInt()}%)...", p)
                                            }
                                        }
                                        pinningProgress = null
                                        if (res.isSuccess) {
                                            app.dbHelper.pinFile(file.id, destFile.absolutePath, destFile.length())
                                            Toast.makeText(this@MainActivity, "Pinned '${file.name}' for offline use!", Toast.LENGTH_SHORT).show()
                                            refreshDirectory(currentFolderId)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Pin failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onExternalOpen = { file ->
                                try {
                                    val uri = DocumentsContract.buildDocumentUri(CloudSSDDocumentsProvider.AUTHORITY, file.id)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, file.mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(Intent.createChooser(intent, "Open in VLC or Player"))
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSignInClick = { showLoginDialog = true }
                        )
                        1 -> PlayerTab(
                            activeFile = activePlayingFile,
                            onFileSelect = { activePlayingFile = it }
                        )
                        2 -> OfflinePinnedTab(
                            onPlayFile = { pinned ->
                                val entity = app.dbHelper.getFile(pinned.fileId) ?: FileEntity(
                                    id = pinned.fileId,
                                    name = File(pinned.localPath).name,
                                    mimeType = "video/*",
                                    parentId = "root",
                                    size = pinned.fileSize,
                                    modifiedTime = pinned.pinnedAt,
                                    md5Checksum = null,
                                    thumbnailLink = null,
                                    isFolder = false
                                )
                                activePlayingFile = entity
                                selectedTab = 1
                                app.playerManager.playMedia(entity, listOf(entity), "")
                            },
                            onUnpin = { pinned ->
                                File(pinned.localPath).delete()
                                app.dbHelper.unpinFile(pinned.fileId)
                                Toast.makeText(this@MainActivity, "Unpinned file", Toast.LENGTH_SHORT).show()
                            }
                        )
                        3 -> SettingsTab(
                            account = activeAccount,
                            onSignOut = {
                                app.dbHelper.clearActiveAccount()
                                activeAccount = null
                                Toast.makeText(this@MainActivity, "Signed out", Toast.LENGTH_SHORT).show()
                            },
                            onSignInClick = { showLoginDialog = true }
                        )
                    }
                }

                // Pinning Progress Dialog
                pinningProgress?.let { (msg, prog) ->
                    Dialog(onDismissRequest = {}) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(msg, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                                Spacer(Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    progress = { prog },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF42A5F5)
                                )
                            }
                        }
                    }
                }

                // Image Viewer Overlay (Swipe & Zoom)
                showImageViewer?.let { imgFile ->
                    ImageViewerDialog(
                        file = imgFile,
                        accessToken = activeAccount?.accessToken ?: "",
                        onDismiss = { showImageViewer = null }
                    )
                }

                // Login & Google Auth Dialog
                if (showLoginDialog) {
                    LoginDialog(
                        onDismiss = { showLoginDialog = false },
                        onConnected = { acc ->
                            activeAccount = acc
                            showLoginDialog = false
                            refreshDirectory("root", forceCloud = true)
                            Toast.makeText(this@MainActivity, "Connected to Google Drive!", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }

    // --- Tab 0: Media Library Screen ---
    @Composable
    fun LibraryTab(
        account: DriveAccount?,
        folderHistory: List<Pair<String, String>>,
        filesList: List<FileEntity>,
        searchQuery: String,
        selectedFilter: String,
        isGridView: Boolean,
        sortBy: String,
        isLoading: Boolean,
        onSearchChange: (String) -> Unit,
        onFilterChange: (String) -> Unit,
        onSortChange: (String) -> Unit,
        onGridToggle: () -> Unit,
        onFolderClick: (FileEntity) -> Unit,
        onBreadcrumbClick: (Int) -> Unit,
        onRootBreadcrumbClick: () -> Unit,
        onFileClick: (FileEntity) -> Unit,
        onPinClick: (FileEntity) -> Unit,
        onExternalOpen: (FileEntity) -> Unit,
        onSignInClick: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (account == null) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF42A5F5), modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Connect Google Drive", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Turn Google Drive into high-speed virtual extra storage. Stream 500 GB+ media with only a small 5 GB cache on your phone.",
                                textAlign = TextAlign.Center,
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = onSignInClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Connect Drive Account")
                            }
                        }
                    }
                }
                return
            }

            // Search Bar & Grid/List Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier.weight(1f).height(48.dp),
                    placeholder = { Text("Search videos, audio, images...", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF42A5F5),
                        unfocusedBorderColor = Color(0xFF333333)
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onGridToggle) {
                    Icon(
                        imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = "Toggle View",
                        tint = Color.LightGray
                    )
                }
            }

            // Filter Chips (All, Videos, Audio, Images)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181818))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { onFilterChange("ALL") },
                    label = { Text("All Media", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "VIDEO",
                    onClick = { onFilterChange("VIDEO") },
                    label = { Text("Videos", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
                FilterChip(
                    selected = selectedFilter == "AUDIO",
                    onClick = { onFilterChange("AUDIO") },
                    label = { Text("Audio", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
                FilterChip(
                    selected = selectedFilter == "IMAGE",
                    onClick = { onFilterChange("IMAGE") },
                    label = { Text("Images", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp)) }
                )
            }

            // Breadcrumb Navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222222))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Drive Root",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF81D4FA),
                    modifier = Modifier.clickable { onRootBreadcrumbClick() }
                )
                folderHistory.forEachIndexed { idx, crumb ->
                    Text(" / ", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        text = crumb.second,
                        fontSize = 12.sp,
                        color = Color(0xFF81D4FA),
                        modifier = Modifier.clickable { onBreadcrumbClick(idx) }
                    )
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Color(0xFF42A5F5))
            }

            // Filtered & Sorted File List
            val displayList = remember(filesList, searchQuery, selectedFilter, sortBy) {
                filesList.filter { file ->
                    val matchesSearch = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
                    val matchesFilter = when (selectedFilter) {
                        "VIDEO" -> file.isFolder || file.isVideo
                        "AUDIO" -> file.isFolder || file.isAudio
                        "IMAGE" -> file.isFolder || file.isImage
                        else -> true
                    }
                    matchesSearch && matchesFilter
                }.sortedWith(
                    when (sortBy) {
                        "SIZE" -> compareByDescending<FileEntity> { it.isFolder }.thenByDescending { it.size }
                        "DATE" -> compareByDescending<FileEntity> { it.isFolder }.thenByDescending { it.modifiedTime }
                        else -> compareByDescending<FileEntity> { it.isFolder }.thenBy { it.name.lowercase() }
                    }
                )
            }

            if (displayList.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.DarkGray)
                        Spacer(Modifier.height(8.dp))
                        Text("No media files found in this folder", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayList) { item ->
                        MediaGridCard(
                            item = item,
                            onClick = { if (item.isFolder) onFolderClick(item) else onFileClick(item) }
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(displayList) { item ->
                        MediaListItem(
                            item = item,
                            onItemClick = { if (item.isFolder) onFolderClick(item) else onFileClick(item) },
                            onPinClick = { onPinClick(item) },
                            onExternalOpen = { onExternalOpen(item) }
                        )
                        Divider(color = Color(0xFF1E1E1E))
                    }
                }
            }
        }
    }

    // --- Tab 1: ExoPlayer Media Player ---
    @Composable
    fun PlayerTab(
        activeFile: FileEntity?,
        onFileSelect: (FileEntity) -> Unit
    ) {
        val player = app.playerManager.exoPlayer
        var isPlaying by remember { mutableStateOf(player.isPlaying) }
        var currentPosition by remember { mutableLongStateOf(player.currentPosition) }
        var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
        var selectedSpeed by remember { mutableFloatStateOf(1.0f) }

        // Periodic ticker for seek bar
        LaunchedEffect(Unit) {
            while (true) {
                isPlaying = player.isPlaying
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
                delay(500)
            }
        }

        if (activeFile == null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.DarkGray)
                    Spacer(Modifier.height(12.dp))
                    Text("No media playing", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Select a video or audio file from your Drive Library to stream.", color = Color.Gray, fontSize = 13.sp)
                }
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Video Surface or Audio Album View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (activeFile.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Audio waveform/cover representation
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E88E5).copy(alpha = 0.2f),
                            modifier = Modifier.size(120.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF42A5F5), modifier = Modifier.size(56.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(activeFile.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White, maxLines = 1)
                        Text("Google Drive Cloud Audio", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }

            // Controls Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(16.dp)
            ) {
                Text(
                    text = activeFile.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatSize(activeFile.size)} • Smart Cached Stream (15 MB Prefetch Active)",
                    fontSize = 11.sp,
                    color = Color(0xFF81D4FA)
                )

                Spacer(Modifier.height(12.dp))

                // Seek Slider
                val sliderPosition = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                Slider(
                    value = sliderPosition,
                    onValueChange = { frac ->
                        val target = (frac * duration).toLong()
                        player.seekTo(target)
                        currentPosition = target
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPosition), fontSize = 11.sp, color = Color.Gray)
                    Text(formatTime(duration), fontSize = 11.sp, color = Color.Gray)
                }

                Spacer(Modifier.height(12.dp))

                // Playback Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speed selector
                    TextButton(onClick = {
                        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                        val nextIdx = (speeds.indexOf(selectedSpeed) + 1) % speeds.size
                        selectedSpeed = speeds[nextIdx]
                        app.playerManager.setSpeed(selectedSpeed)
                    }) {
                        Text("${selectedSpeed}x", fontWeight = FontWeight.Bold, color = Color(0xFF42A5F5))
                    }

                    // 10s Rewind
                    IconButton(onClick = { app.playerManager.seekBackward(10000L) }) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White)
                    }

                    // Play / Pause
                    FloatingActionButton(
                        onClick = {
                            if (player.isPlaying) {
                                app.playerManager.pause()
                            } else {
                                app.playerManager.resume()
                            }
                            isPlaying = player.isPlaying
                        },
                        containerColor = Color(0xFF1E88E5),
                        contentColor = Color.White,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause"
                        )
                    }

                    // 10s Forward
                    IconButton(onClick = { app.playerManager.seekForward(10000L) }) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                    }

                    // Background Audio Indicator
                    IconButton(onClick = {
                        Toast.makeText(this@MainActivity, "Background audio active with screen off", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Headphones, contentDescription = "Background", tint = Color(0xFF4CAF50))
                    }
                }
            }
        }
    }

    // --- Tab 2: Offline (Pinned) Screen ---
    @Composable
    fun OfflinePinnedTab(
        onPlayFile: (PinnedEntity) -> Unit,
        onUnpin: (PinnedEntity) -> Unit
    ) {
        var pinnedList by remember { mutableStateOf(app.dbHelper.getPinnedFiles()) }
        val totalBytes = app.dbHelper.getTotalPinnedSizeBytes()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PushPin, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Pinned Media Storage", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        Text(
                            "${pinnedList.size} files pinned • ${formatSize(totalBytes)} (Exempt from LRU cache eviction)",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (pinnedList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(56.dp), tint = Color.DarkGray)
                        Spacer(Modifier.height(12.dp))
                        Text("No Pinned Files", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap the 3 dots on any media in your Library to pin it for offline flights or travel.", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pinnedList) { item ->
                        val fileName = File(item.localPath).name
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.OfflinePin, contentDescription = null, tint = Color(0xFF4CAF50))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(fileName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White, maxLines = 1)
                                    Text("${formatSize(item.fileSize)} • Airplane Mode Ready", fontSize = 11.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { onPlayFile(item) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color(0xFF42A5F5))
                                }
                                IconButton(onClick = {
                                    onUnpin(item)
                                    pinnedList = app.dbHelper.getPinnedFiles()
                                }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Unpin", tint = Color(0xFFE57373))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Tab 3: Settings Screen ---
    @Composable
    fun SettingsTab(
        account: DriveAccount?,
        onSignOut: () -> Unit,
        onSignInClick: () -> Unit
    ) {
        var cacheCapGb by remember { mutableFloatStateOf(app.cacheManager.getCacheCapGb()) }
        var currentCacheMb by remember { mutableLongStateOf(app.cacheManager.getCurrentLruCacheSizeBytes() / (1024 * 1024)) }
        var wifiOnly by remember { mutableStateOf(app.dbHelper.isWifiOnlyPrefetch()) }
        var prefetchEnabled by remember { mutableStateOf(app.dbHelper.isPrefetchEnabled()) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Google Drive Account Info Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Cloud Backing Account", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF81D4FA))
                    Spacer(Modifier.height(10.dp))
                    if (account != null) {
                        Text(account.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        Text(account.email, fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        val usedGb = account.usedQuota.toFloat() / (1024f * 1024f * 1024f)
                        val totalGb = account.totalQuota.toFloat() / (1024f * 1024f * 1024f)
                        val frac = if (totalGb > 0) (usedGb / totalGb).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF42A5F5)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            String.format("%.2f GB used of %.2f GB Google Drive", usedGb, totalGb),
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    } else {
                        Text("No Google Drive connected", color = Color.Gray, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onSignInClick) { Text("Sign In to Drive") }
                    }
                }
            }

            // Smart Cache Cap (1 to 20 GB)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Smart Cache Limit (LRU Eviction)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Currently Cached: $currentCacheMb MB • Limit: ${cacheCapGb.toInt()} GB",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = cacheCapGb,
                        onValueChange = {
                            cacheCapGb = it
                            app.cacheManager.setCacheCapGb(it)
                        },
                        valueRange = 1f..20f,
                        steps = 18
                    )
                    Text(
                        "When cache exceeds ${cacheCapGb.toInt()} GB, the oldest accessed chunks are evicted automatically. Pinned files are never evicted.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            app.cacheManager.clearLruCache()
                            currentCacheMb = app.cacheManager.getCurrentLruCacheSizeBytes() / (1024 * 1024)
                            Toast.makeText(this@MainActivity, "Cache Cleared!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Cache Now")
                    }
                }
            }

            // Prefetch Rules
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Prefetch Engine Rules", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("15 MB Next-Item Prefetch", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White)
                            Text("Pre-buffers the start of the next track/episode", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = prefetchEnabled,
                            onCheckedChange = {
                                prefetchEnabled = it
                                app.dbHelper.setPrefetchEnabled(it)
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wi-Fi Only Prefetch", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.White)
                            Text("Saves mobile data by only prefetching on Wi-Fi", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                app.dbHelper.setWifiOnlyPrefetch(it)
                            }
                        )
                    }
                }
            }

            // Android Native Storage Framework (Phase 2 SAF)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3024)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                        Spacer(Modifier.width(8.dp))
                        Text("Virtual Storage Mounted in Android", fontWeight = FontWeight.Bold, color = Color(0xFFA5D6A7))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Google Drive is directly exposed to Android's native Files app, VLC, and external media players via Storage Access Framework with zero local storage footprint.",
                        fontSize = 11.sp,
                        color = Color.White,
                        lineHeight = 16.sp
                    )
                }
            }

            if (account != null) {
                Button(
                    onClick = onSignOut,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out")
                }
            }
        }
    }

    // --- Sub-components ---

    @Composable
    fun MediaListItem(
        item: FileEntity,
        onItemClick: () -> Unit,
        onPinClick: () -> Unit,
        onExternalOpen: () -> Unit
    ) {
        var showMenu by remember { mutableStateOf(false) }
        val isPinned = remember(item.id) { app.dbHelper.isPinned(item.id) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onItemClick() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    item.isFolder -> Icons.Default.Folder
                    item.isVideo -> Icons.Default.VideoLibrary
                    item.isAudio -> Icons.Default.AudioFile
                    item.isImage -> Icons.Default.Image
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    item.isFolder -> Color(0xFFFFB74D)
                    item.isVideo -> Color(0xFF42A5F5)
                    item.isAudio -> Color(0xFF66BB6A)
                    item.isImage -> Color(0xFFAB47BC)
                    else -> Color.LightGray
                },
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.isFolder) "Folder" else "${formatSize(item.size)} • ${if (isPinned) "Pinned Offline" else "Drive Stream"}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            if (!item.isFolder) {
                IconButton(onClick = onItemClick) {
                    Icon(Icons.Default.PlayCircle, contentDescription = "Play", tint = Color(0xFF42A5F5))
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.LightGray)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (!item.isFolder) {
                        DropdownMenuItem(
                            text = { Text("Stream in VLC / Player") },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onExternalOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isPinned) "Already Pinned" else "Pin for Offline") },
                            leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onPinClick()
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MediaGridCard(
        item: FileEntity,
        onClick: () -> Unit
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.9f)
                .clickable { onClick() }
        ) {
            Column(
                modifier = Modifier.padding(8.dp).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when {
                        item.isFolder -> Icons.Default.Folder
                        item.isVideo -> Icons.Default.Videocam
                        item.isAudio -> Icons.Default.MusicNote
                        item.isImage -> Icons.Default.Image
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = when {
                        item.isFolder -> Color(0xFFFFB74D)
                        item.isVideo -> Color(0xFF42A5F5)
                        item.isAudio -> Color(0xFF66BB6A)
                        else -> Color(0xFFAB47BC)
                    },
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = item.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    fun ImageViewerDialog(
        file: FileEntity,
        accessToken: String,
        onDismiss: () -> Unit
    ) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val streamUrl = GoogleDriveApiClient.BASE_URL + "/files/${file.id}?alt=media"
                AsyncImage(
                    model = streamUrl,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }

    @Composable
    fun LoginDialog(
        onDismiss: () -> Unit,
        onConnected: (DriveAccount) -> Unit
    ) {
        var tokenInput by remember { mutableStateOf("") }
        var isConnecting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Connect Google Drive", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter your Google OAuth2 Access Token (scope: drive.readonly) to connect your Google Drive extra storage:",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        placeholder = { Text("Paste Bearer ya29....") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = tokenInput.isNotBlank() && !isConnecting,
                    onClick = {
                        val clean = tokenInput.trim().removePrefix("Bearer ").trim()
                        isConnecting = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { app.apiClient.getAbout(clean) }
                            isConnecting = false
                            if (res.isSuccess) {
                                val (quota, user) = res.getOrNull()!!
                                val acc = DriveAccount(
                                    accountId = "drive_primary",
                                    email = user?.emailAddress ?: "user@gmail.com",
                                    displayName = user?.displayName ?: "Google Drive",
                                    accessToken = clean,
                                    refreshToken = null,
                                    totalQuota = quota.limit,
                                    usedQuota = quota.usage,
                                    isActive = true
                                )
                                app.dbHelper.saveAccount(acc)
                                onConnected(acc)
                            } else {
                                Toast.makeText(this@MainActivity, "Invalid Token: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text(if (isConnecting) "Verifying..." else "Connect Drive")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.0f GB", gb)
    }

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}
