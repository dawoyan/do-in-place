package com.davoyans.doinplace.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.TrustedContact

data class IconDef(val id: String, val label: String, val vector: ImageVector)

// "person" is the default — ContactAvatar shows initials circle instead of this icon.
// All other ids render the corresponding vector icon.
val CONTACT_ICON_DEFS: List<IconDef> = listOf(
    IconDef("person",    "Initials",  Icons.Default.Person),
    IconDef("man",       "Man",       Icons.Default.Man),
    IconDef("woman",     "Woman",     Icons.Default.Woman),
    IconDef("boy",       "Boy",       Icons.Default.Boy),
    IconDef("girl",      "Girl",      Icons.Default.Girl),
    IconDef("elder",     "Elder",     Icons.Default.Elderly),
    IconDef("baby",      "Baby",      Icons.Default.ChildCare),
    IconDef("family",    "Family",    Icons.Default.FamilyRestroom),
    IconDef("group",     "Group",     Icons.Default.People),
    IconDef("heart",     "Love",      Icons.Default.Favorite),
    IconDef("star",      "Best",      Icons.Default.Star),
    IconDef("home",      "Home",      Icons.Default.Home),
    IconDef("work",      "Work",      Icons.Default.Work),
    IconDef("school",    "Student",   Icons.Default.School),
    IconDef("sport",     "Sport",     Icons.Default.SportsSoccer),
    IconDef("cafe",      "Cafe",      Icons.Default.LocalCafe),
    IconDef("music",     "Music",     Icons.Default.MusicNote),
    IconDef("doctor",    "Doctor",    Icons.Default.LocalHospital),
    IconDef("pets",      "Pet",       Icons.Default.Pets),
    IconDef("emoji",     "Happy",     Icons.Default.EmojiPeople),
)

fun iconIdToVector(iconId: String): ImageVector =
    CONTACT_ICON_DEFS.find { it.id == iconId }?.vector ?: Icons.Default.Person

private val AVATAR_COLORS = listOf(
    Color(0xFF1976D2), Color(0xFF7B1FA2), Color(0xFF388E3C),
    Color(0xFFF57C00), Color(0xFFD32F2F), Color(0xFF0097A7),
    Color(0xFF5D4037), Color(0xFF455A64)
)

fun avatarColorForSeed(seed: String): Color {
    val idx = ((seed.hashCode() % AVATAR_COLORS.size) + AVATAR_COLORS.size) % AVATAR_COLORS.size
    return AVATAR_COLORS[idx]
}

@Composable
fun ContactAvatar(
    iconId: String,
    displayName: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    if (iconId == "person" || iconId.isBlank()) {
        val initials = displayName.trim()
            .split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }
        Box(
            modifier.size(size).background(avatarColorForSeed(displayName), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initials,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    } else {
        Icon(
            iconIdToVector(iconId),
            contentDescription = null,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

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
                        contact.contactDisplayName.ifBlank { contact.contactEmail }.ifBlank { "" },
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
            val previewName = nickname.ifBlank { contact.contactDisplayName.ifBlank { contact.contactEmail } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ContactAvatar(iconId = selectedIconId, displayName = previewName, size = 48.dp)
                Column {
                    Text(
                        previewName,
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
                            if (def.id == "person") {
                                ContactAvatar(iconId = "person", displayName = previewName, size = 28.dp)
                            } else {
                                Icon(
                                    def.vector,
                                    contentDescription = def.label,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
