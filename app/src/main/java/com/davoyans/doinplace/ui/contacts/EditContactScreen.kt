package com.davoyans.doinplace.ui.contacts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.TrustedContact

data class IconDef(val id: String, val label: String, val vector: ImageVector)

val CONTACT_ICON_DEFS: List<IconDef> = listOf(
    IconDef("person",    "Person",   Icons.Default.Person),
    IconDef("account",   "Profile",  Icons.Default.AccountCircle),
    IconDef("people",    "People",   Icons.Default.People),
    IconDef("child",     "Child",    Icons.Default.ChildCare),
    IconDef("home",      "Home",     Icons.Default.Home),
    IconDef("star",      "Star",     Icons.Default.Star),
    IconDef("heart",     "Heart",    Icons.Default.Favorite),
    IconDef("trophy",    "Trophy",   Icons.Default.EmojiEvents),
    IconDef("pets",      "Pets",     Icons.Default.Pets),
    IconDef("nature",    "Nature",   Icons.Default.EmojiNature),
    IconDef("sport",     "Sport",    Icons.Default.SportsSoccer),
    IconDef("flower",    "Flower",   Icons.Default.LocalFlorist),
    IconDef("music",     "Music",    Icons.Default.MusicNote),
    IconDef("cafe",      "Cafe",     Icons.Default.LocalCafe),
    IconDef("science",   "Science",  Icons.Default.Science),
    IconDef("bolt",      "Energy",   Icons.Default.ElectricBolt),
)

fun iconIdToVector(iconId: String): ImageVector =
    CONTACT_ICON_DEFS.find { it.id == iconId }?.vector ?: Icons.Default.Person

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactScreen(
    contact: TrustedContact,
    existingPref: ContactDisplayPref?,
    onSave: (nickname: String, iconId: String) -> Unit,
    onBack: () -> Unit
) {
    var nickname by remember { mutableStateOf(existingPref?.nickname ?: "") }
    var selectedIconId by remember { mutableStateOf(existingPref?.iconId ?: "person") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        contact.contactDisplayName.ifBlank { contact.contactEmail }.ifBlank { "Contact" },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Preview
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    iconIdToVector(selectedIconId),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        nickname.ifBlank { contact.contactDisplayName.ifBlank { contact.contactEmail } },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        contact.contactEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            HorizontalDivider()

            // Nickname field
            Text("Nickname (optional)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 24) nickname = it },
                label = { Text("Nickname") },
                placeholder = { Text(contact.contactDisplayName.ifBlank { contact.contactEmail }) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("${nickname.length}/24 · Leave blank to use real name") }
            )

            HorizontalDivider()

            // Icon picker
            Text("Icon", style = MaterialTheme.typography.labelLarge)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CONTACT_ICON_DEFS) { def ->
                    val isSelected = def.id == selectedIconId
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .then(
                                if (isSelected) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.shapes.medium
                                ) else Modifier
                            )
                            .clickable { selectedIconId = def.id }
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                def.vector,
                                contentDescription = def.label,
                                modifier = Modifier.size(28.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                def.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(nickname.trim(), selectedIconId) },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}
