package com.davoyans.doinplace.ui.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class InviteData(
    val userId: String,
    val name: String,
    val email: String
)

@Composable
fun InviteAcceptScreen(
    invite: InviteData,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Person, null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    "Trusted contact invite",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    buildString {
                        val displayName = invite.name.ifBlank { null }
                        if (displayName != null) {
                            append("$displayName")
                            if (invite.email.isNotBlank()) append("\n(${invite.email})")
                        } else if (invite.email.isNotBlank()) {
                            append(invite.email)
                        } else {
                            append("Someone")
                        }
                        append("\nwants to add you as a trusted contact.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )

                Text(
                    "Trusted contacts can assign tasks to each other.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                HorizontalDivider()

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f)
                    ) { Text("Decline") }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f)
                    ) { Text("Accept") }
                }
            }
        }
    }
}
