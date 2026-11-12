package com.retroplayer.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.retroplayer.android.data.Game
import com.retroplayer.android.data.GameDatabase
import com.retroplayer.android.data.GameRepository
import com.retroplayer.android.data.RetroCloud
import com.retroplayer.android.data.RetroSystem
import com.retroplayer.android.data.SettingsRepository
import com.retroplayer.android.sync.RetroSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = GameDatabase(this)
        val repository = GameRepository(this, database)
        val cloud = RetroCloud(this)
        val settings = SettingsRepository(this)
        setContent {
            var darkTheme by remember { mutableStateOf(settings.darkTheme) }
            RetroPlayerTheme(darkTheme) {
                RetroPlayerApp(
                    database = database,
                    repository = repository,
                    cloud = cloud,
                    settings = settings,
                    darkTheme = darkTheme,
                    onThemeChange = {
                        settings.darkTheme = it
                        darkTheme = it
                    }
                )
            }
        }
    }
}

private enum class AppTab(val label: String, val glyph: String) {
    LIBRARY("Library", "▦"),
    SYNC("Sync", "☁"),
    ACCOUNT("Account", "◉"),
    SETTINGS("Settings", "⚙")
}

@Composable
private fun RetroPlayerApp(
    database: GameDatabase,
    repository: GameRepository,
    cloud: RetroCloud,
    settings: SettingsRepository,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AppTab.LIBRARY) }
    var games by remember { mutableStateOf(database.allGames()) }
    var backups by remember { mutableStateOf(database.saves(context)) }
    var search by remember { mutableStateOf("") }
    var systemFilter by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var recentOnly by remember { mutableStateOf(false) }
    var sortByRecent by remember { mutableStateOf(true) }
    var selectedGame by remember { mutableStateOf<Game?>(null) }
    var pendingUris by remember { mutableStateOf<List<Uri>?>(null) }
    var showBinDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        games = database.allGames()
        backups = database.saves(context)
    }

    fun importSelected(uris: List<Uri>, binSystem: RetroSystem? = null) {
        scope.launch {
            busy = true
            status = "Importing game files…"
            val outcome = withContext(Dispatchers.IO) {
                runCatching { repository.importUris(uris, binSystem) }
            }
            outcome.onSuccess { imported ->
                refresh()
                status = "Imported " + imported.size + " game file(s)."
            }.onFailure { error ->
                status = error.message ?: "The selected files could not be imported."
            }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val names = uris.map { displayName(context, it) }
            val hasBin = names.any { it.substringAfterLast('.', "").equals("bin", true) }
            val hasCue = names.any { it.substringAfterLast('.', "").equals("cue", true) }
            if (hasBin && !hasCue) {
                pendingUris = uris
                showBinDialog = true
            } else {
                importSelected(uris)
            }
        }
    }

    val biosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching { repository.importBios(uri); "PlayStation BIOS imported." }
                        .getOrElse { it.message ?: "Could not import BIOS." }
                }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repository.importSave(uri) }
                }
                result.onSuccess {
                    refresh()
                    status = "Save backup imported."
                    if (cloud.currentSession() != null) {
                        runCatching { cloud.syncAll(database) }
                            .onSuccess { status = "Save backup imported and cloud sync completed." }
                            .onFailure { status = "Backup imported locally. Cloud sync: " + (it.message ?: "failed") }
                    }
                }.onFailure { status = it.message ?: "Could not import save backup." }
            }
        }
    }

    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refresh()
        status = "Library updated."
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val path = exportPath
        exportPath = null
        if (uri != null && path != null) {
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching { repository.copySaveToUri(path, uri); "Save backup exported." }
                        .getOrElse { it.message ?: "Could not export save backup." }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        RetroSyncScheduler.setEnabled(context, settings.autoSync)
    }

    val visibleGames = games
        .filter { game ->
            val matchesQuery = search.isBlank() ||
                game.title.contains(search, true) ||
                game.fileName.contains(search, true) ||
                (game.system?.title?.contains(search, true) == true)
            val matchesSystem = systemFilter == null || game.systemId == systemFilter
            matchesQuery && matchesSystem &&
                (!favoritesOnly || game.favorite) &&
                (!recentOnly || game.lastPlayedAt > 0L)
        }
        .let { rows ->
            if (sortByRecent) rows.sortedByDescending { it.lastPlayedAt } else rows.sortedBy { it.title.lowercase(Locale.ROOT) }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        icon = { Text(destination.glyph, fontSize = 19.sp) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("RETRO PLAYER", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                    Text("ANDROID EDITION", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                if (tab == AppTab.LIBRARY) {
                    Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                        Text("＋ Import")
                    }
                }
            }

            when (tab) {
                AppTab.LIBRARY -> {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search your library") },
                        placeholder = { Text("Game title or console") }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = systemFilter == null && !favoritesOnly && !recentOnly,
                            onClick = { systemFilter = null; favoritesOnly = false; recentOnly = false },
                            label = { Text("All") }
                        )
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { favoritesOnly = !favoritesOnly; recentOnly = false; systemFilter = null },
                            label = { Text("★ Favorites") }
                        )
                        FilterChip(
                            selected = recentOnly,
                            onClick = { recentOnly = !recentOnly; favoritesOnly = false; systemFilter = null },
                            label = { Text("Recently played") }
                        )
                        RetroSystem.entries.forEach { system ->
                            FilterChip(
                                selected = systemFilter == system.id,
                                onClick = { systemFilter = if (systemFilter == system.id) null else system.id; favoritesOnly = false; recentOnly = false },
                                label = { Text(system.shortTitle) }
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(visibleGames.size.toString() + " games", color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = { sortByRecent = !sortByRecent }) {
                            Text(if (sortByRecent) "Sort: Recently played" else "Sort: Title A–Z")
                        }
                    }
                    if (games.isEmpty()) {
                        EmptyLibrary(onImport = { importLauncher.launch(arrayOf("*/*")) })
                    } else if (visibleGames.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No games match those filters.", color = MaterialTheme.colorScheme.secondary)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(148.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleGames, key = { it.id }) { game ->
                                GameCard(
                                    game = game,
                                    onClick = { selectedGame = game },
                                    onFavorite = {
                                        database.setFavorite(game.id, !game.favorite)
                                        refresh()
                                    }
                                )
                            }
                        }
                    }
                }
                AppTab.SYNC -> {
                    SyncScreen(
                        email = cloud.currentSession()?.email,
                        backups = backups,
                        status = status,
                        busy = busy,
                        onSync = {
                            scope.launch {
                                busy = true
                                status = "Syncing your library and save backups…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { cloud.syncAll(database) }
                                }
                                result.onSuccess {
                                    refresh()
                                    status = "Synced " + it.gamesSynced + " library entries, " +
                                        it.savesUploaded + " save backup(s) up, " +
                                        it.savesDownloaded + " down."
                                    if (it.skippedLargeSaves > 0) status += " " + it.skippedLargeSaves + " backup(s) exceeded the 5 MB limit."
                                }.onFailure {
                                    status = it.message ?: "Cloud sync failed."
                                }
                                busy = false
                            }
                        },
                        onImportSave = { saveLauncher.launch(arrayOf("*/*")) },
                        onExportSave = { file ->
                            exportPath = file.path
                            exportLauncher.launch(file.name)
                        }
                    )
                }
                AppTab.ACCOUNT -> {
                    AccountScreen(
                        cloud = cloud,
                        busy = busy,
                        status = status,
                        onStatus = { status = it },
                        onBusy = { busy = it },
                        onAuthChanged = {
                            RetroSyncScheduler.setEnabled(context, settings.autoSync)
                            refresh()
                        }
                    )
                }
                AppTab.SETTINGS -> {
                    SettingsScreen(
                        settings = settings,
                        repository = repository,
                        darkTheme = darkTheme,
                        onThemeChange = onThemeChange,
                        onChooseBios = { biosLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 5.dp)
                )
            }
        }
    }

    if (showBinDialog) {
        AlertDialog(
            onDismissRequest = { showBinDialog = false; pendingUris = null },
            title = { Text("Choose the system for .bin") },
            text = { Text("The .bin extension is used by both PlayStation disc images and some Genesis dumps. Select the correct system for these files.") },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        showBinDialog = false
                        pendingUris?.let { importSelected(it, RetroSystem.PS1) }
                        pendingUris = null
                    }) { Text("PlayStation 1") }
                    TextButton(onClick = {
                        showBinDialog = false
                        pendingUris?.let { importSelected(it, RetroSystem.GENESIS) }
                        pendingUris = null
                    }) { Text("Sega Genesis") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBinDialog = false; pendingUris = null }) { Text("Cancel") }
            }
        )
    }

    selectedGame?.let { game ->
        AlertDialog(
            onDismissRequest = { selectedGame = null },
            title = { Text(game.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(game.system?.title ?: "Unknown system")
                    Text(if (game.isAvailableOnDevice) game.fileName else "Not imported on this device")
                    Text(formatBytes(game.sizeBytes) + " · " + game.playCount + " plays")
                    Text("Play time: " + formatDuration(game.playtimeSeconds))
                    if (game.lastPlayedAt > 0) Text("Last played " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(game.lastPlayedAt)))
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        selectedGame = null
                        if (!game.isAvailableOnDevice) {
                            status = "Import this game file on the current device before launching it."
                        } else {
                            database.recordPlayed(game.id)
                            refresh()
                            val intent = Intent(context, PlayerActivity::class.java).apply {
                                putExtra(PlayerActivity.EXTRA_GAME_PATH, game.filePath)
                                putExtra(PlayerActivity.EXTRA_SYSTEM_ID, game.systemId)
                                putExtra(PlayerActivity.EXTRA_TITLE, game.title)
                                putExtra(PlayerActivity.EXTRA_GAME_ID, game.id)
                                putExtra(PlayerActivity.EXTRA_BIOS_PATH, repository.biosPath().orEmpty())
                            }
                            playerLauncher.launch(intent)
                        }
                    }) { Text("▶ Play") }
                    TextButton(onClick = {
                        database.setFavorite(game.id, !game.favorite)
                        refresh()
                        selectedGame = null
                    }) { Text(if (game.favorite) "Remove favorite" else "Add to favorites") }
                    TextButton(onClick = {
                        val path = database.removeGame(game.id)
                        path?.let { File(it).delete() }
                        refresh()
                        selectedGame = null
                    }) { Text("Remove from library") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedGame = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your library is waiting.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Import games you own to get started. Game files stay on this device.",
                color = MaterialTheme.colorScheme.secondary
            )
            Button(onClick = onImport) { Text("Import your first game") }
        }
    }
}

@Composable
private fun GameCard(game: Game, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier.fillMaxWidth().height(82.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(game.system?.shortTitle ?: "ROM", fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Text(game.title, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2)
            Text(game.system?.title ?: game.systemId, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (game.isAvailableOnDevice) formatBytes(game.sizeBytes) else "Import needed",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 11.sp
                    )
                    if (game.playtimeSeconds > 0) {
                        Text(formatDuration(game.playtimeSeconds), color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                    }
                }
                TextButton(onClick = onFavorite) { Text(if (game.favorite) "★" else "☆", fontSize = 20.sp) }
            }
        }
    }
}

@Composable
private fun SyncScreen(
    email: String?,
    backups: List<com.retroplayer.android.data.SaveBackup>,
    status: String,
    busy: Boolean,
    onSync: () -> Unit,
    onImportSave: () -> Unit,
    onExportSave: (com.retroplayer.android.data.SaveBackup) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Cloud sync", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            if (email != null) "Signed in as " + email else "Sign in with your existing Retro Player account to sync library metadata and backups.",
            color = MaterialTheme.colorScheme.secondary
        )
        Button(onClick = onSync, enabled = email != null && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Syncing…" else "Sync now")
        }
        HorizontalDivider()
        Text("Save backups", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Import save or memory-card files exported by an emulator, then back them up to your Retro Player account. This backup area is separate from EmulatorJS's live in-player save storage.",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 13.sp
        )
        OutlinedButton(onClick = onImportSave, modifier = Modifier.fillMaxWidth()) {
            Text("＋ Import save backup")
        }
        if (backups.isEmpty()) {
            Text("No save backups imported yet.", color = MaterialTheme.colorScheme.secondary)
        } else {
            backups.forEach { backup ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(backup.name, fontWeight = FontWeight.SemiBold)
                            Text(formatBytes(backup.sizeBytes), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onExportSave(backup) }) { Text("Export") }
                    }
                }
            }
        }
        if (email == null) {
            Text("Account sign-in is available in the Account tab.", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun AccountScreen(
    cloud: RetroCloud,
    busy: Boolean,
    status: String,
    onStatus: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    onAuthChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(cloud.currentSession()?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    val session = cloud.currentSession()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Retro Player account", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (session != null) {
            Text("You're signed in as " + session.email)
            Text("Your library metadata and Android save backups can sync across devices.", color = MaterialTheme.colorScheme.secondary)
            Button(
                onClick = {
                    scope.launch {
                        onBusy(true)
                        runCatching { cloud.signOut() }
                            .onSuccess { onStatus("Signed out."); onAuthChanged() }
                            .onFailure { onStatus(it.message ?: "Sign out failed.") }
                        onBusy(false)
                    }
                },
                enabled = !busy
            ) { Text("Sign out") }
        } else {
            Text("Use the same email and password as the Retro Player website.", color = MaterialTheme.colorScheme.secondary)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    scope.launch {
                        onBusy(true)
                        val result = runCatching {
                            if (createAccount) cloud.signUp(email, password) else cloud.signIn(email, password)
                        }
                        result.onSuccess {
                            onStatus(if (createAccount) "Account created and signed in." else "Signed in successfully.")
                            password = ""
                            onAuthChanged()
                        }.onFailure { onStatus(it.message ?: "Account request failed.") }
                        onBusy(false)
                    }
                },
                enabled = !busy && email.isNotBlank() && password.length >= 8,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (createAccount) "Create account" else "Sign in") }
            TextButton(onClick = { createAccount = !createAccount }) {
                Text(if (createAccount) "Already have an account? Sign in" else "Create a new account")
            }
            TextButton(
                onClick = {
                    scope.launch {
                        onBusy(true)
                        runCatching { cloud.recoverPassword(email) }
                            .onSuccess { onStatus("Password reset email requested. Check your inbox.") }
                            .onFailure { onStatus(it.message ?: "Could not request password reset.") }
                        onBusy(false)
                    }
                },
                enabled = email.contains("@") && !busy
            ) { Text("Forgot password?") }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        Text(
            "Your sign-in tokens are encrypted using Android Keystore on this device. Only the public Supabase publishable key is included in the app.",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingsScreen(
    settings: SettingsRepository,
    repository: GameRepository,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onChooseBios: () -> Unit
) {
    val context = LocalContext.current
    var autoSync by remember { mutableStateOf(settings.autoSync) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var diagnostics by remember { mutableStateOf(settings.showDiagnostics) }
    var volume by remember { mutableStateOf(settings.volume) }
    val bios = repository.biosPath()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SettingToggle("Dark theme", "Use the dark Retro Player look.", darkTheme) { onThemeChange(it) }
        SettingToggle("Automatic cloud sync", "Sync every six hours when connected to the internet.", autoSync) {
            autoSync = it
            settings.autoSync = it
            RetroSyncScheduler.setEnabled(context, it)
        }
        SettingToggle("Touch haptics", "Short vibration feedback for touchscreen button presses.", haptics) {
            haptics = it
            settings.haptics = it
        }
        SettingToggle("Device diagnostics", "Show Android device and WebView details in settings.", diagnostics) {
            diagnostics = it
            settings.showDiagnostics = it
        }
        HorizontalDivider()
        Text("Emulator controls", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("The EmulatorJS player includes virtual controls, controller mapping, and per-game options. Bluetooth controller behavior depends on the Android WebView and controller model.", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        Text("Default audio level: " + (volume * 100).toInt() + "%")
        Slider(
            value = volume,
            onValueChange = { volume = it; settings.volume = it },
            valueRange = 0f..1f
        )
        HorizontalDivider()
        Text("PlayStation BIOS", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(
            if (bios != null) "A BIOS file is stored in private app storage." else "No BIOS imported. Some PS1 core configurations may need a user-provided BIOS.",
            color = MaterialTheme.colorScheme.secondary
        )
        OutlinedButton(onClick = onChooseBios, modifier = Modifier.fillMaxWidth()) {
            Text(if (bios != null) "Replace BIOS file" else "Import BIOS file")
        }
        if (diagnostics) {
            HorizontalDivider()
            Text("Device diagnostics", fontWeight = FontWeight.SemiBold)
            Text("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            Text("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")")
            Text("CPU cores: " + Runtime.getRuntime().availableProcessors())
            Text("Bluetooth gamepad support must be tested on this device.", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
        }
        Text(
            "Android phones and tablets are the target. Android TV support is not part of this release.",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RetroPlayerTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) {
        androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFF9DB4CC),
            background = Color(0xFF0B111A),
            surface = Color(0xFF121B27),
            surfaceVariant = Color(0xFF1C2938)
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF155E9A),
            secondary = Color(0xFF52677D),
            background = Color(0xFFF4F7FB),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE7EDF4)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "game.rom"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) name = it.getString(0) ?: name
    }
    return name
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return if (hours > 0) hours.toString() + "h " + minutes + "m" else minutes.toString() + "m"
}
