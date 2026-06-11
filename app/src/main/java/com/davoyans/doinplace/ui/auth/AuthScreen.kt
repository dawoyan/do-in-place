package com.davoyans.doinplace.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.BuildConfig
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthScreen(
    authClient: SupabaseAuthClient,
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    val googleEnabled = googleClientId.isNotBlank()

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        loading = true; error = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val account = GoogleSignIn
                        .getSignedInAccountFromIntent(result.data)
                        .getResult(ApiException::class.java)
                    val idToken = account.idToken
                        ?: return@withContext Result.failure(Exception("No ID token from Google"))
                    authClient.signInWithGoogle(idToken)
                } catch (e: ApiException) {
                    Result.failure(Exception("Google sign-in failed: ${e.statusCode}"))
                }
            }
            loading = false
            res.fold(onSuccess = { onAuthenticated() }, onFailure = { error = it.message ?: "Google sign-in failed" })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Do In Place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Place-based reminders", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(32.dp))

        // ── Google Sign-In ─────────────────────────────────────────────────
        if (googleEnabled) {
            OutlinedButton(
                onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(googleClientId)
                        .requestEmail()
                        .build()
                    val client = GoogleSignIn.getClient(context, gso)
                    client.signOut() // clear cached account so picker always shows
                    googleLauncher.launch(client.signInIntent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) {
                Text("Continue with Google")
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Divider(Modifier.weight(1f))
                Text("  or  ", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Divider(Modifier.weight(1f))
            }
        }

        // ── Email / password ───────────────────────────────────────────────
        if (!isLogin) {
            OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(value = email, onValueChange = { email = it },
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        if (info.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(info, modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                loading = true; error = ""; info = ""
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        if (isLogin) authClient.signIn(email.trim(), password)
                        else {
                            if (displayName.isBlank())
                                return@withContext Result.failure(Exception("Display name is required"))
                            authClient.signUp(email.trim(), password, displayName.trim())
                        }
                    }
                    loading = false
                    res.fold(
                        onSuccess = { onAuthenticated() },
                        onFailure = { e ->
                            if (e is SupabaseAuthClient.EmailConfirmationPendingException) {
                                info = e.message ?: ""
                            } else {
                                error = e.message ?: "Authentication failed"
                            }
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && email.isNotBlank() && password.isNotBlank()
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (isLogin) "Sign in" else "Create account")
        }

        TextButton(onClick = { isLogin = !isLogin; error = ""; info = "" }) {
            Text(if (isLogin) "No account? Register" else "Have an account? Sign in")
        }
    }
}
