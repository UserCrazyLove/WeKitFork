package dev.ujhhgtg.wekit.ui.content

import android.icu.text.Transliterator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.hooks.api.core.models.IWeContact
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

@Composable
private fun BaseContactSelector(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredContacts: List<IWeContact>,
    confirmButtonText: String,
    confirmButtonEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    selectionKey: Any, // Triggers re-grouping immediately when selection changes
    isSelected: (IWeContact) -> Boolean, // Identifies if a contact belongs in the top section
    leadingControl: @Composable LazyItemScope.(IWeContact) -> Unit,
    onItemClick: (IWeContact) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { ('A'..'Z').toList() + '#' }

    // Native Android ICU Transliterator to convert Hanzi to Latin Pinyin strings
    val transliterator = remember {
        try {
            Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
        } catch (_: Exception) {
            null
        }
    }

    // 1. Group items, prioritizing selected items at the absolute top
    val groupedContacts = remember(filteredContacts, transliterator, selectionKey) {
        filteredContacts.groupBy { contact ->
            if (isSelected(contact)) {
                "Selected"
            } else {
                val name = contact.displayName.trim()
                if (name.isEmpty()) return@groupBy "#"

                val firstChar = name.first()
                if (firstChar.uppercaseChar() in 'A'..'Z') {
                    firstChar.uppercaseChar().toString()
                } else if (transliterator != null) {
                    // Converts e.g., "张" -> "zhāng" -> "zhang" -> "Z"
                    val pinyin = transliterator.transliterate(firstChar.toString())
                    val initial = pinyin.firstOrNull()?.uppercaseChar() ?: '#'
                    if (initial in 'A'..'Z') initial.toString() else "#"
                } else {
                    "#"
                }
            }
        }.toSortedMap { c1, c2 ->
            // Sorting order: "Selected" -> 'A'..'Z' -> "#"
            when {
                c1 == c2 -> 0
                c1 == "Selected" -> -1
                c2 == "Selected" -> 1
                c1 == "#" -> 1
                c2 == "#" -> -1
                else -> c1.compareTo(c2)
            }
        }
    }

    // 2. Map group headers to flat absolute index positions inside LazyColumn
    val sectionIndices = remember(groupedContacts) {
        val mapping = mutableMapOf<String, Int>()
        var currentFlatIndex = 0
        groupedContacts.forEach { (letter, contactsInGroup) ->
            mapping[letter] = currentFlatIndex
            currentFlatIndex += 1 // For the sticky header item
            currentFlatIndex += contactsInGroup.size // For the row items
        }
        mapping
    }

    AlertDialogContent(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    placeholder = { Text("搜索昵称或微信号") },
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = "Search") },
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Main Contact Scroll List
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        groupedContacts.forEach { (letter, contactsInGroup) ->
                            stickyHeader(key = "header_$letter") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = if (letter == "Selected") "已选" else letter,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            items(
                                items = contactsInGroup,
                                key = { it.wxId }
                            ) { contact ->
                                Row(
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .clickable { onItemClick(contact) }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    leadingControl(contact)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    AsyncImage(
                                        model = contact.avatarUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = contact.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = contact.wxId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // A-Z Sidebar Navigator
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(start = 8.dp, end = 4.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        alphabet.forEach { letter ->
                            val isAvailable = groupedContacts.containsKey(letter.toString())
                            Text(
                                text = letter.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAvailable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                },
                                modifier = Modifier
                                    .clickable {
                                        // Find target fallback letter, ensuring we skip jumping to "Selected" via A-Z index clicks
                                        val targetLetter = sectionIndices.keys
                                            .filter { it != "Selected" }
                                            .firstOrNull { it.first() >= letter }
                                            ?: sectionIndices.keys.lastOrNull()

                                        targetLetter?.let { tl ->
                                            sectionIndices[tl]?.let { targetIndex ->
                                                coroutineScope.launch {
                                                    listState.scrollToItem(targetIndex)
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text("取消") }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmButtonEnabled
            ) {
                Text(confirmButtonText)
            }
        }
    )
}

@Composable
fun SingleContactSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxId by remember { mutableStateOf(initialSelectedWxId) }

    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        confirmButtonText = "确定",
        confirmButtonEnabled = selectedWxId != null,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxId!!) },
        selectionKey = selectedWxId ?: "",
        isSelected = { it.wxId == selectedWxId },
        leadingControl = { contact ->
            RadioButton(
                selected = contact.wxId == selectedWxId,
                onClick = null
            )
        },
        onItemClick = { contact ->
            selectedWxId = contact.wxId
        }
    )
}

@Composable
fun ContactsSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxIds by remember { mutableStateOf(initialSelectedWxIds) }

    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        confirmButtonText = "确定 (${selectedWxIds.size})",
        confirmButtonEnabled = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxIds) },
        selectionKey = selectedWxIds,
        isSelected = { it.wxId in selectedWxIds },
        leadingControl = { contact ->
            Checkbox(
                checked = contact.wxId in selectedWxIds,
                onCheckedChange = null
            )
        },
        onItemClick = { contact ->
            selectedWxIds = if (contact.wxId in selectedWxIds) {
                selectedWxIds - contact.wxId
            } else {
                selectedWxIds + contact.wxId
            }
        }
    )
}
