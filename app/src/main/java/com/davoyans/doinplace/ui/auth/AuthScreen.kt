package com.davoyans.doinplace.ui.auth

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        loading = true; error = ""
        scope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    if (result.resultCode != Activity.RESULT_OK) {
                        val code = try {
                            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                                .getResult(ApiException::class.java)
                            -1
                        } catch (e: ApiException) {
                            Log.d("AUTH_GOOGLE", "signIn failed code=${e.statusCode} hint=check SHA-1/SHA-256 and Web Client ID")
                            e.statusCode
                        }
                        throw Exception(if (code == -1) "Google sign-in cancelled" else googleErrorMessage(code))
                    }
                    val account = try {
                        GoogleSignIn.getSignedInAccountFromIntent(result.data)
                            .getResult(ApiException::class.java)
                    } catch (e: ApiException) {
                        Log.d("AUTH_GOOGLE", "signIn failed code=${e.statusCode} hint=check SHA-1/SHA-256 and Web Client ID")
                        throw Exception(googleErrorMessage(e.statusCode))
                    }
                    val idToken = account.idToken
                        ?: throw Exception("No ID token from Google — make sure a Web OAuth client exists in Google Cloud Console and its SHA-1/SHA-256 are registered in Firebase.")
                    Log.d("AUTH_GOOGLE", "idToken present, signing in to Supabase")
                    authClient.signInWithGoogle(idToken).getOrThrow()
                }
            }
            loading = false
            res.fold(onSuccess = { onAuthenticated() }, onFailure = { error = it.message ?: "Google sign-in failed" })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Do In Place", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Place-based reminders",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(48.dp))

        OutlinedButton(
            onClick = {
                Log.d("AUTH_GOOGLE", "start package=${context.packageName} webClientIdPresent=${googleClientId.isNotBlank()}")
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(googleClientId)
                    .requestEmail()
                    .build()
                val client = GoogleSignIn.getClient(context, gso)
                client.signOut()
                googleLauncher.launch(client.signInIntent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Continue with Google")
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Use your Google account to continue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )

        if (error.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun googleErrorMessage(statusCode: Int): String = when (statusCode) {
    10    -> "Google sign-in configuration error (code 10). Register this app's SHA-1 fingerprint in Firebase Console and make sure the Web Client ID is correct."
    12501 -> "Google sign-in was cancelled."
    12500 -> "Google sign-in failed. Try again."
    7     -> "Network error during Google sign-in. Check your connection."
    else  -> "Google sign-in failed (code $statusCode)."
}
