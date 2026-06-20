package com.davoyans.doinplace.ui.cards

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.SavedCardEntity
import com.davoyans.doinplace.data.repository.SavedCardSaveResult
import com.davoyans.doinplace.ui.common.MaxBrightnessWhileVisible
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.util.UUID

data class SavedCardDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val codeType: String = SavedCardCodeTypes.BARCODE,
    val barcodeFormat: String? = BarcodeFormat.CODE_128.name,
    val codeValue: String = "",
    val note: String = "",
    val passwordOrPin: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val allowTypeEditing: Boolean = true,
    val allowCodeEditing: Boolean = true,
    val isManualEntry: Boolean = true
) {
    fun toEntity(): SavedCardEntity = SavedCardEntity(
        id = id,
        name = name.trim(),
        codeType = codeType,
        barcodeFormat = when {
            codeType == SavedCardCodeTypes.QR -> BarcodeFormat.QR_CODE.name
            barcodeFormat.isNullOrBlank() -> BarcodeFormat.CODE_128.name
            else -> barcodeFormat
        },
        codeValue = codeValue.trim(),
        note = note.trim().ifBlank { null },
        passwordOrPinEncrypted = passwordOrPin.trim().ifBlank { null },
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis()
    )
}

data class ScannedCardPayload(
    val codeValue: String,
    val codeType: String,
    val barcodeFormat: String?
)

private enum class ScanScreenState {
    RequestingPermission,
    LaunchingScanner,
    PermissionDenied,
    ScanCancelled
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsTabScreen(
    cards: List<SavedCardEntity>,
    onOpenCard: (SavedCardEntity) -> Unit,
    onEditCard: (SavedCardEntity) -> Unit,
    onScanCard: () -> Unit,
    onAddFromPhoto: () -> Unit,
    onAddManual: () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredCards = remember(cards, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            cards
        } else {
            cards.filter { card ->
                card.name.lowercase().contains(query) ||
                    card.codeValue.lowercase().contains(query) ||
                    card.note.orEmpty().lowercase().contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_cards), fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = bottomBar
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onScanCard, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cards_scan))
                }
                OutlinedButton(onClick = onAddFromPhoto, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cards_add_photo))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onAddManual, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cards_add_manual))
                }
                Spacer(Modifier.weight(1f))
            }

            if (cards.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            if (cards.isEmpty()) {
                EmptyCardsState()
            } else if (filteredCards.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.cards_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.cards_saved_locally),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    items(filteredCards, key = { it.id }) { card ->
                        SavedCardRow(
                            card = card,
                            onClick = { onOpenCard(card) },
                            onEdit = { onEditCard(card) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanCardScreen(
    onDetected: (ScannedCardPayload) -> Unit,
    onAddManual: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var scanState by rememberSaveable { mutableStateOf(ScanScreenState.RequestingPermission) }
    var scanLaunchToken by rememberSaveable { mutableIntStateOf(0) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val rawValue = result.contents?.trim()
        if (rawValue.isNullOrBlank()) {
            scanState = ScanScreenState.ScanCancelled
            return@rememberLauncherForActivityResult
        }

        val formatName = result.formatName?.trim().orEmpty()
        val codeType = if (formatName == BarcodeFormat.QR_CODE.name) {
            SavedCardCodeTypes.QR
        } else {
            SavedCardCodeTypes.BARCODE
        }
        onDetected(
            ScannedCardPayload(
                codeValue = rawValue,
                codeType = codeType,
                barcodeFormat = if (formatName.isBlank()) null else formatName
            )
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scanState = if (granted) {
            scanLaunchToken += 1
            ScanScreenState.LaunchingScanner
        } else {
            ScanScreenState.PermissionDenied
        }
    }

    fun requestPermissionOrLaunch() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scanLaunchToken += 1
            scanState = ScanScreenState.LaunchingScanner
        } else {
            permissionRequested = true
            scanState = ScanScreenState.RequestingPermission
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionRequested) {
            requestPermissionOrLaunch()
        }
    }

    LaunchedEffect(scanLaunchToken) {
        if (scanLaunchToken > 0) {
            scanLauncher.launch(
                ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                    setPrompt(context.getString(R.string.cards_scan_prompt))
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cards_scan)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (scanState) {
                ScanScreenState.RequestingPermission,
                ScanScreenState.LaunchingScanner -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.cards_camera_permission_message),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                ScanScreenState.PermissionDenied -> {
                    PermissionDeniedContent(
                        onRetry = ::requestPermissionOrLaunch,
                        onAddManual = onAddManual
                    )
                }

                ScanScreenState.ScanCancelled -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.CreditCard,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                        Text(
                            text = stringResource(R.string.cards_scan_cancelled),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::requestPermissionOrLaunch) {
                                Text(stringResource(R.string.cards_scan_again))
                            }
                            OutlinedButton(onClick = onAddManual) {
                                Text(stringResource(R.string.cards_add_manual))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SaveCardScreen(
    initialDraft: SavedCardDraft,
    onSaveCard: suspend (SavedCardDraft, Boolean) -> SavedCardSaveResult,
    onOpenExisting: (SavedCardEntity) -> Unit,
    onSaved: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(initialDraft.id) { mutableStateOf(initialDraft.name) }
    var codeType by rememberSaveable(initialDraft.id) { mutableStateOf(initialDraft.codeType) }
    var codeValue by rememberSaveable(initialDraft.id) { mutableStateOf(initialDraft.codeValue) }
    var note by rememberSaveable(initialDraft.id) { mutableStateOf(initialDraft.note) }
    var passwordOrPin by rememberSaveable(initialDraft.id) { mutableStateOf(initialDraft.passwordOrPin) }
    var duplicateCard by remember { mutableStateOf<SavedCardEntity?>(null) }
    var pendingDraft by remember { mutableStateOf<SavedCardDraft?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var codeValueError by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    fun buildDraft(): SavedCardDraft {
        val normalizedType = codeType
        val normalizedFormat = when (normalizedType) {
            SavedCardCodeTypes.QR -> BarcodeFormat.QR_CODE.name
            else -> initialDraft.barcodeFormat
                ?.takeIf { it != BarcodeFormat.QR_CODE.name }
                ?: BarcodeFormat.CODE_128.name
        }
        return initialDraft.copy(
            name = name,
            codeType = normalizedType,
            barcodeFormat = normalizedFormat,
            codeValue = codeValue,
            note = note,
            passwordOrPin = passwordOrPin
        )
    }

    fun validate(): SavedCardDraft? {
        val draft = buildDraft()
        nameError = draft.name.trim().isEmpty()
        codeValueError = draft.codeValue.trim().isEmpty()
        return if (nameError || codeValueError) null else draft
    }

    suspend fun persist(draft: SavedCardDraft, allowDuplicate: Boolean) {
        isSaving = true
        when (val result = onSaveCard(draft, allowDuplicate)) {
            is SavedCardSaveResult.Saved -> {
                duplicateCard = null
                onSaved(result.cardId)
            }
            is SavedCardSaveResult.Duplicate -> {
                pendingDraft = draft
                duplicateCard = result.existing
            }
        }
        isSaving = false
    }

    duplicateCard?.let { existing ->
        AlertDialog(
            onDismissRequest = { duplicateCard = null },
            title = { Text(stringResource(R.string.cards_duplicate_title)) },
            text = {
                Text(stringResource(R.string.cards_duplicate_message, existing.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        duplicateCard = null
                        onOpenExisting(existing)
                    }
                ) {
                    Text(stringResource(R.string.cards_open_existing))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val draft = pendingDraft ?: return@TextButton
                            duplicateCard = null
                            scope.launch { persist(draft, allowDuplicate = true) }
                        }
                    ) {
                        Text(stringResource(R.string.cards_save_duplicate))
                    }
                    TextButton(onClick = { duplicateCard = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cards_save)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!initialDraft.isManualEntry) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cards_detected_code),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = initialDraft.codeValue,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "${stringResource(R.string.cards_type)}: ${barcodeTypeLabel(initialDraft.codeType)} · ${barcodeFormatLabel(initialDraft.barcodeFormat)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = false
                },
                label = { Text(stringResource(R.string.cards_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = nameError,
                supportingText = {
                    if (nameError) {
                        Text(stringResource(R.string.cards_name_required))
                    }
                }
            )

            if (initialDraft.allowTypeEditing) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.cards_code_type),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = codeType == SavedCardCodeTypes.QR,
                            onClick = { codeType = SavedCardCodeTypes.QR },
                            label = { Text(stringResource(R.string.cards_type_qr)) }
                        )
                        FilterChip(
                            selected = codeType == SavedCardCodeTypes.BARCODE,
                            onClick = { codeType = SavedCardCodeTypes.BARCODE },
                            label = { Text(stringResource(R.string.cards_type_barcode)) }
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = "${barcodeTypeLabel(codeType)} · ${barcodeFormatLabel(initialDraft.barcodeFormat)}",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.cards_code_type)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = codeValue,
                onValueChange = {
                    if (initialDraft.allowCodeEditing) {
                        codeValue = it
                        codeValueError = false
                    }
                },
                label = { Text(stringResource(R.string.cards_code_value)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = codeValueError,
                readOnly = !initialDraft.allowCodeEditing,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text
                ),
                supportingText = {
                    if (codeValueError) {
                        Text(stringResource(R.string.cards_code_value_required))
                    }
                }
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.cards_note)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            OutlinedTextField(
                value = passwordOrPin,
                onValueChange = { passwordOrPin = it },
                label = { Text(stringResource(R.string.cards_password_pin)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.hide_password else R.string.show_password
                            )
                        )
                    }
                }
            )

            if (codeType == SavedCardCodeTypes.BARCODE && initialDraft.allowTypeEditing) {
                Text(
                    text = stringResource(R.string.cards_manual_barcode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val draft = validate() ?: return@Button
                        scope.launch { persist(draft, allowDuplicate = false) }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.cards_save))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    card: SavedCardEntity,
    onEdit: () -> Unit,
    onBack: () -> Unit
) {
    MaxBrightnessWhileVisible()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(card.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.cards_edit))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    val widthPx = constraints.maxWidth.coerceAtLeast(240)
                    val heightPx = (widthPx * 0.42f).toInt().coerceAtLeast(160)
                    val renderResult = remember(card.id, card.codeType, card.barcodeFormat, card.codeValue, widthPx, heightPx) {
                        renderSavedCardBitmap(card, widthPx, heightPx)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = androidx.compose.ui.graphics.Color.White,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            BarcodeImage(
                                bitmap = renderResult.bitmap,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            )
                        }
                        if (renderResult.usedQrFallback) {
                            Text(
                                text = stringResource(R.string.cards_render_fallback_qr),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.Center
                            )
                        } else if (renderResult.bitmap == null) {
                            Text(
                                text = stringResource(R.string.cards_render_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCardScreen(
    card: SavedCardEntity,
    onSave: (SavedCardDraft) -> Unit,
    onDelete: (SavedCardEntity) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    var codeType by rememberSaveable(card.id) { mutableStateOf(card.codeType) }
    var codeValue by rememberSaveable(card.id) { mutableStateOf(card.codeValue) }
    var note by rememberSaveable(card.id) { mutableStateOf(card.note.orEmpty()) }
    var passwordOrPin by rememberSaveable(card.id) { mutableStateOf(card.passwordOrPinEncrypted.orEmpty()) }
    var nameError by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.cards_delete_title)) },
            text = { Text(stringResource(R.string.cards_delete_message, card.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(card)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cards_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = false
                },
                label = { Text(stringResource(R.string.cards_name)) },
                singleLine = true,
                isError = nameError,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    if (nameError) {
                        Text(stringResource(R.string.cards_name_required))
                    }
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.cards_code_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = codeType == SavedCardCodeTypes.QR,
                        onClick = { codeType = SavedCardCodeTypes.QR },
                        label = { Text(stringResource(R.string.cards_type_qr)) }
                    )
                    FilterChip(
                        selected = codeType == SavedCardCodeTypes.BARCODE,
                        onClick = { codeType = SavedCardCodeTypes.BARCODE },
                        label = { Text(stringResource(R.string.cards_type_barcode)) }
                    )
                }
            }
            OutlinedTextField(
                value = codeValue,
                onValueChange = { codeValue = it },
                label = { Text(stringResource(R.string.cards_code_value)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.cards_note)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            OutlinedTextField(
                value = passwordOrPin,
                onValueChange = { passwordOrPin = it },
                label = { Text(stringResource(R.string.cards_password_pin)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        if (passwordOrPin.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("saved_card_secret", passwordOrPin))
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            }
                        }
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.hide_password else R.string.show_password
                                )
                            )
                        }
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        if (name.trim().isBlank()) {
                            nameError = true
                        } else {
                            onSave(
                                SavedCardDraft(
                                    id = card.id,
                                    name = name,
                                    codeType = codeType,
                                    barcodeFormat = if (codeType == SavedCardCodeTypes.QR) BarcodeFormat.QR_CODE.name else card.barcodeFormat ?: BarcodeFormat.CODE_128.name,
                                    codeValue = codeValue,
                                    note = note,
                                    passwordOrPin = passwordOrPin,
                                    createdAt = card.createdAt,
                                    allowTypeEditing = true,
                                    allowCodeEditing = true,
                                    isManualEntry = false
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.save))
                }
            }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text(" ${stringResource(R.string.delete)}")
            }
        }
    }
}

@Composable
private fun EmptyCardsState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.CreditCard,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Text(
                text = stringResource(R.string.cards_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.cards_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.cards_saved_locally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun SavedCardRow(
    card: SavedCardEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CreditCard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.name, fontWeight = FontWeight.Medium)
                Text(
                    text = "${barcodeTypeLabel(card.codeType)} · ${barcodeFormatLabel(card.barcodeFormat)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.cards_edit))
            }
        }
    }
}

@Composable
private fun BarcodeImage(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) {
        Box(
            modifier = modifier
                .height(220.dp)
                .background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.cards_render_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier
    )
}

@Composable
private fun PermissionDeniedContent(
    onRetry: () -> Unit,
    onAddManual: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.navigationBarsPadding()
    ) {
        Icon(
            Icons.Default.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
        Text(
            text = stringResource(R.string.cards_camera_permission_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.cards_camera_permission_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.cards_try_again))
            }
            OutlinedButton(onClick = onAddManual) {
                Text(stringResource(R.string.cards_add_manual))
            }
        }
    }
}
