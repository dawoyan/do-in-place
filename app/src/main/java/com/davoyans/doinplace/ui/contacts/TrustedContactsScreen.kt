package com.davoyans.doinplace.ui.contacts

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.R
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.ContactStatus
import com.davoyans.doinplace.data.model.TrustedContact
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Invite code lookup result ──────────────────────────────────────────────────
sealed class InviteCodeLookupResult {
    data class Found(
        val inviteId: String,
        val inviterUserId: String,
        val inviterName: String,
        val inviterEmail: String
    ) : InviteCodeLookupResult()
    data class NotFound(val message: String) : InviteCodeLookupResult()
}

private fun normalizeCode(raw: String): String =
    raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

private fun looksLikeCode(s: String): Boolean {
    val n = normalizeCode(s)
    return n.length in 5..8 && n.all { it.isLetterOrDigit() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedContactsScreen(
    contacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref> = emptyList(),
    currentUserId: String,
    currentUserName: String,
    currentUserEmail: String,
    onInviteByEmail: (email: String) -> Unit,
    onPasteInvite: (String) -> Unit,
    onAcceptInvite: (TrustedContact) -> Unit,
    onRejectInvite: (TrustedContact) -> Unit,
    onRemoveContact: (TrustedContact) -> Unit,
    onEditContact: (TrustedContact) -> Unit = {},
    onCreateInviteCode: (suspend () -> String)? = null,
    onLookupInviteCode: (suspend (String) -> InviteCodeLookupResult)? = null,
    onRedeemInviteCode: (suspend (String, String, String, String) -> Unit)? = null,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Dialog / loading state ─────────────────────────────────────────────
    var pendingRemove         by remember { mutableStateOf<TrustedContact?>(null) }
    var isRefreshing          by remember { mutableStateOf(false) }

    // Create invite code
    var isGeneratingCode      by remember { mutableStateOf(false) }
    var generatedCode         by remember { mutableStateOf("") }
    var showCreateCodeDialog  by remember { mutableStateOf(false) }

    // Enter invite code
    var showEnterCodeDialog   by remember { mutableStateOf(false) }
    var codeInput             by remember { mutableStateOf("") }
    var codeError             by remember { mutableStateOf("") }
    var isLookingUpCode       by remember { mutableStateOf(false) }

    // Invite confirmation after successful lookup
    var foundInvite           by remember { mutableStateOf<InviteCodeLookupResult.Found?>(null) }

    // QR dialog (also uses generatedCode)
    var showQrDialog          by remember { mutableStateOf(false) }

    // Email (secondary)
    var showEmailDialog       by remember { mutableStateOf(false) }
    var emailInput            by remember { mutableStateOf("") }

    // ── Remove contact confirmation ────────────────────────────────────────
    pendingRemove?.let { contact ->
        val pref = displayPrefs.find { it.contactUserId == contact.contactUserId }
        val displayName = ContactDisplayRepository.resolveDisplayName(contact.contactUserId, contact, pref)
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.remove_contact_title)) },
            text = { Text("Remove $displayName from your trusted contacts?") },
            confirmButton = {
                TextButton(
                    onClick = { onRemoveContact(contact); pendingRemove = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── QR scanner ────────────────────────────────────────────────────────
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents?.trim() ?: return@rememberLauncherForActivityResult
        scope.launch {
            when {
                content.isBlank() ->
                    snackbarHostState.showSnackbar("Scan cancelled")
                "doinplace://invite" in content -> {
                    // Legacy URL format — hand to existing paste flow
                    onPasteInvite(content)
                    snackbarHostState.showSnackbar("Invite received — check Pending list below")
                }
                looksLikeCode(content) -> {
                    // New short-code format — pre-fill enter-code dialog
                    codeInput = content.trim()
                    codeError = ""
                    showEnterCodeDialog = true
                }
                else ->
                    snackbarHostState.showSnackbar("Not a valid Do In Place QR code")
            }
        }
    }

    // ── Create invite code dialog ──────────────────────────────────────────
    if (showCreateCodeDialog && generatedCode.isNotBlank()) {
        fun copyCode() {
            val clip = android.content.ClipData.newPlainText("invite_code", generatedCode)
            val mgr = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            mgr.setPrimaryClip(clip)
        }
        AlertDialog(
            onDismissRequest = { showCreateCodeDialog = false },
            title = { Text(stringResource(R.string.create_invite_code)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Send this code to your friend. They enter it in Do In Place to connect with you.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = generatedCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                copyCode()
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.code_copied)) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.copy_code))
                        }
                        OutlinedButton(
                            onClick = {
                                val msg = "Use this Do In Place invite code: $generatedCode"
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, msg)
                                        }, "Share invite code"
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.share_code))
                        }
                    }
                    Text(
                        "Code expires in 7 days · Single use",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCreateCodeDialog = false }) { Text(stringResource(R.string.done)) }
            }
        )
    }

    // ── QR dialog (QR encodes the invite code) ─────────────────────────────
    if (showQrDialog && generatedCode.isNotBlank()) {
        val qrBitmap = remember(generatedCode) { buildQrBitmap(generatedCode) }
        fun copyCode() {
            val clip = android.content.ClipData.newPlainText("invite_code", generatedCode)
            val mgr = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            mgr.setPrimaryClip(clip)
        }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(stringResource(R.string.my_qr_code)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Invite QR code",
                            modifier = Modifier.size(220.dp)
                        )
                    } else {
                        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Could not generate QR.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Text(
                        "Code: $generatedCode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Ask your friend to scan this QR, or share the code manually.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyCode()
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.code_copied)) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.copy_code), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                val msg = "Use this Do In Place invite code: $generatedCode"
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, msg)
                                        }, "Share invite code"
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.share), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    // ── Enter invite code dialog ───────────────────────────────────────────
    if (showEnterCodeDialog) {
        AlertDialog(
            onDismissRequest = { showEnterCodeDialog = false; codeInput = ""; codeError = "" },
            title = { Text(stringResource(R.string.enter_invite_code)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.invite_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it; codeError = "" },
                        label = { Text(stringResource(R.string.invite_code_label)) },
                        placeholder = { Text(stringResource(R.string.invite_code_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = codeError.isNotBlank(),
                        supportingText = if (codeError.isNotBlank()) {
                            { Text(codeError, color = MaterialTheme.colorScheme.error) }
                        } else null
                    )
                    if (isLookingUpCode) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onLookupInviteCode != null && codeInput.isNotBlank() && !isLookingUpCode) {
                            scope.launch {
                                isLookingUpCode = true
                                codeError = ""
                                val normalized = normalizeCode(codeInput)
                                val result = if (normalized.length < 5) {
                                    InviteCodeLookupResult.NotFound(context.getString(R.string.invalid_invite_code))
                                } else {
                                    onLookupInviteCode(normalized)
                                }
                                isLookingUpCode = false
                                when (result) {
                                    is InviteCodeLookupResult.Found -> {
                                        showEnterCodeDialog = false
                                        codeInput = ""
                                        foundInvite = result
                                    }
                                    is InviteCodeLookupResult.NotFound -> {
                                        codeError = result.message
                                    }
                                }
                            }
                        }
                    },
                    enabled = codeInput.isNotBlank() && !isLookingUpCode
                ) { Text(stringResource(R.string.connect)) }
            },
            dismissButton = {
                TextButton(onClick = { showEnterCodeDialog = false; codeInput = ""; codeError = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ── Inviter confirmation dialog ────────────────────────────────────────
    foundInvite?.let { found ->
        val inviterDisplay = found.inviterName.ifBlank { found.inviterEmail.ifBlank { "Someone" } }
        AlertDialog(
            onDismissRequest = { foundInvite = null },
            title = { Text("Connection found") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Person, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally)
                    )
                    Text(
                        "$inviterDisplay invited you to connect.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (found.inviterEmail.isNotBlank()) {
                        Text(
                            found.inviterEmail,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "Trusted contacts can assign tasks to each other.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val f = found
                    foundInvite = null
                    scope.launch {
                        onRedeemInviteCode?.invoke(f.inviteId, f.inviterUserId, f.inviterName, f.inviterEmail)
                        snackbarHostState.showSnackbar(
                            "Request sent. Waiting for $inviterDisplay to confirm.",
                            duration = SnackbarDuration.Long
                        )
                    }
                }) { Text(stringResource(R.string.send_request)) }
            },
            dismissButton = {
                TextButton(onClick = { foundInvite = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── Invite by email dialog (secondary) ────────────────────────────────
    if (showEmailDialog) {
        val isSelfEmail = emailInput.trim().lowercase() == currentUserEmail.lowercase() && currentUserEmail.isNotBlank()
        AlertDialog(
            onDismissRequest = { showEmailDialog = false; emailInput = "" },
            title = { Text(stringResource(R.string.invite_by_email)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "No email will be sent — no email provider is configured. " +
                        "This saves a contact request that the other person will see after signing in and syncing. " +
                        "For instant connection, use the invite code instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text(stringResource(R.string.friends_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = isSelfEmail
                    )
                    if (isSelfEmail) {
                        Text("You cannot invite yourself.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (emailInput.isNotBlank() && !isSelfEmail) {
                            onInviteByEmail(emailInput.trim())
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Contact request saved. No email was sent — share your invite code instead.",
                                    duration = SnackbarDuration.Long
                                )
                            }
                            emailInput = ""
                            showEmailDialog = false
                        }
                    },
                    enabled = emailInput.isNotBlank() && !isSelfEmail
                ) { Text(stringResource(R.string.send_invite)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false; emailInput = "" }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // ── Contact list ───────────────────────────────────────────────────────
    val selfEmail = currentUserEmail.lowercase()
    fun isSelf(c: TrustedContact) = c.contactUserId == currentUserId ||
            (selfEmail.isNotBlank() && c.contactEmail.lowercase() == selfEmail)
    val accepted = contacts.filter { it.status == ContactStatus.ACCEPTED && !isSelf(it) }
    val pending  = contacts.filter { it.status == ContactStatus.PENDING && !isSelf(it) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    onRefresh()
                    delay(1500)
                    isRefreshing = false
                }
            },
            state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState(),
            modifier = Modifier.padding(padding)
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Primary row: code-first actions
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    if (!isGeneratingCode && onCreateInviteCode != null) {
                                        scope.launch {
                                            isGeneratingCode = true
                                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                runCatching { onCreateInviteCode() }
                                            }
                                            isGeneratingCode = false
                                            val code = result.getOrNull() ?: ""
                                            if (code.isNotBlank()) {
                                                generatedCode = code
                                                showCreateCodeDialog = true
                                            } else {
                                                val errMsg = result.exceptionOrNull()?.message ?: ""
                                                val snack = if (errMsg.isSchemaError()) {
                                                    "Invite setup is not ready. Please update database schema."
                                                } else {
                                                    "Could not create invite code. Please try again."
                                                }
                                                snackbarHostState.showSnackbar(snack)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isGeneratingCode
                            ) {
                                if (isGeneratingCode) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.create_invite_code), style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { codeInput = ""; codeError = ""; showEnterCodeDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.VpnKey, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.enter_invite_code), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        // Secondary row: QR actions
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (!isGeneratingCode && onCreateInviteCode != null) {
                                        scope.launch {
                                            isGeneratingCode = true
                                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                runCatching { onCreateInviteCode() }
                                            }
                                            isGeneratingCode = false
                                            val code = result.getOrNull() ?: ""
                                            if (code.isNotBlank()) {
                                                generatedCode = code
                                                showQrDialog = true
                                            } else {
                                                val errMsg = result.exceptionOrNull()?.message ?: ""
                                                val snack = if (errMsg.isSchemaError()) {
                                                    "Invite setup is not ready. Please update database schema."
                                                } else {
                                                    "Could not create invite code. Please try again."
                                                }
                                                snackbarHostState.showSnackbar(snack)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isGeneratingCode
                            ) {
                                Icon(Icons.Default.QrCode, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.my_qr_btn), style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = {
                                    scanLauncher.launch(ScanOptions().apply {
                                        setPrompt("Scan QR code")
                                        setBeepEnabled(false)
                                        setOrientationLocked(true)
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    })
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scan_qr), style = MaterialTheme.typography.labelMedium)
                            }
                            // Email as tertiary option
                            OutlinedButton(
                                onClick = { showEmailDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Email, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.email), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                if (pending.isNotEmpty()) {
                    item { SectionHeader("Pending (${pending.size})") }
                    item {
                        Text(
                            "After accepting, ask the other person to scan your QR or enter your code so you appear in their list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    items(pending, key = { it.id }) { contact ->
                        val pref = displayPrefs.find { it.contactUserId == contact.contactUserId }
                        ContactCard(contact, pref, actions = {
                            TextButton(onClick = {
                                onAcceptInvite(contact)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "Connected! Ask ${contact.contactDisplayName.ifBlank { "them" }} to share their code with you too",
                                        duration = SnackbarDuration.Long
                                    )
                                }
                            }) { Text(stringResource(R.string.accept)) }
                            TextButton(onClick = { onRejectInvite(contact) }) { Text(stringResource(R.string.decline)) }
                        })
                    }
                }

                if (accepted.isNotEmpty()) {
                    item { SectionHeader("Contacts (${accepted.size})") }
                    items(accepted, key = { it.id }) { contact ->
                        val pref = displayPrefs.find { it.contactUserId == contact.contactUserId }
                        ContactCard(
                            contact, pref,
                            onEdit = { onEditContact(contact) },
                            onRemove = { pendingRemove = contact }
                        )
                    }
                }

                if (contacts.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillParentMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No contacts yet.\nCreate an invite code and send it to a friend.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun String.isSchemaError(): Boolean =
    contains("column", ignoreCase = true) && contains("does not exist", ignoreCase = true) ||
    contains("relation", ignoreCase = true) && contains("does not exist", ignoreCase = true) ||
    contains("not-null", ignoreCase = true) || contains("null value", ignoreCase = true) ||
    contains("42703", ignoreCase = true) || contains("42P01", ignoreCase = true)

private fun buildQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, sizePx, sizePx,
        mapOf(EncodeHintType.MARGIN to 1)
    )
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
        for (x in 0 until sizePx) for (y in 0 until sizePx)
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}.getOrNull()

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ContactCard(contact: TrustedContact, pref: ContactDisplayPref?, actions: @Composable () -> Unit) {
    val displayName = ContactDisplayRepository.resolveDisplayName(contact.contactUserId, contact, pref)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconIdToVector(pref?.iconId ?: "person"), null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, fontWeight = FontWeight.Medium)
                if (contact.contactEmail.isNotBlank()) {
                    Text(contact.contactEmail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Row { actions() }
        }
    }
}

@Composable
private fun ContactCard(
    contact: TrustedContact,
    pref: ContactDisplayPref?,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val displayName = ContactDisplayRepository.resolveDisplayName(contact.contactUserId, contact, pref)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconIdToVector(pref?.iconId ?: "person"), null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName, fontWeight = FontWeight.Medium)
                if (contact.contactEmail.isNotBlank()) {
                    Text(contact.contactEmail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                if (!pref?.nickname.isNullOrBlank()) {
                    Text(
                        "Nickname: ${pref!!.nickname}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, "Edit contact",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, "Remove contact",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}
