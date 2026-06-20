package com.davoyans.doinplace.ui.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.TrustedContact
import com.davoyans.doinplace.data.repository.ContactDisplayRepository
import com.davoyans.doinplace.ui.contacts.ContactAvatar
import com.davoyans.doinplace.util.DiagLog

@Composable
fun ShareShoppingListDialog(
    acceptedContacts: List<TrustedContact>,
    displayPrefs: List<ContactDisplayPref> = emptyList(),
    onShare: (List<TrustedContact>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember { mutableStateSetOf<String>() }

    LaunchedEffect(Unit) {
        val missingIcons = acceptedContacts.count { c ->
            displayPrefs.none { it.contactUserId == c.contactUserId }
        }
        DiagLog.d("SHARE_PICKER", "contacts=${acceptedContacts.size} missingIcons=$missingIcons")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share list with…") },
        text = {
            if (acceptedContacts.isEmpty()) {
                Text(
                    "No accepted connections yet.\nAdd connections from the Contacts tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(acceptedContacts) { contact ->
                        val isSelected = contact.contactUserId in selected
                        val pref = displayPrefs.find { it.contactUserId == contact.contactUserId }
                        val displayName = ContactDisplayRepository.resolveDisplayName(
                            contact.contactUserId, contact, pref
                        )
                        val iconId = pref?.iconId ?: "person"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) selected.remove(contact.contactUserId)
                                    else selected.add(contact.contactUserId)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) selected.add(contact.contactUserId)
                                    else selected.remove(contact.contactUserId)
                                }
                            )
                            ContactAvatar(iconId = iconId, displayName = displayName, size = 28.dp)
                            Column {
                                Text(displayName, style = MaterialTheme.typography.bodyMedium)
                                if (contact.contactEmail.isNotBlank() && displayName != contact.contactEmail) {
                                    Text(
                                        contact.contactEmail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = acceptedContacts.filter { it.contactUserId in selected }
                    onShare(chosen)
                },
                enabled = selected.isNotEmpty()
            ) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
