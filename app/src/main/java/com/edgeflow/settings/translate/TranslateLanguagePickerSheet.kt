package com.edgeflow.settings.translate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgeflow.data.TranslateLanguageCatalog
import com.edgeflow.data.TranslateLanguageEntry
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslateLanguagePickerSheet(
    title: String,
    includeAuto: Boolean,
    languages: List<TranslateLanguageEntry>,
    selectedCode: String,
    downloadedCodes: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    val filteredPinned = remember(languages, searchQuery) {
        languages.filter { it.pinned && TranslateLanguageCatalog.matchesQuery(it, searchQuery) }
    }
    val filteredOthers = remember(languages, searchQuery) {
        languages.filter { !it.pinned && TranslateLanguageCatalog.matchesQuery(it, searchQuery) }
    }
    val showAuto = includeAuto && (
        searchQuery.isBlank() ||
            "auto".contains(searchQuery.trim().lowercase())
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("translate_language_picker_sheet")
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("translate_language_search_input"),
                placeholder = {
                    Text(
                        text = stringResource(R.string.translate_search_language_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = stringResource(R.string.cd_clear_search)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = extendedColors.cardBorder,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (showAuto) {
                    item(key = "lang_auto") {
                        val selected = selectedCode == TranslateLanguageCatalog.AUTO_CODE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(TranslateLanguageCatalog.AUTO_CODE) }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .testTag("translate_pick_lang_auto"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = HinataIcons.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.translate_source_auto_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.translate_source_auto_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (filteredPinned.isNotEmpty()) {
                    item(key = "header_pinned") {
                        Text(
                            text = stringResource(R.string.translate_lang_section_popular),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    items(filteredPinned, key = { "pinned_${it.code}" }) { entry ->
                        LanguagePickerRow(
                            entry = entry,
                            selected = selectedCode == entry.code,
                            downloaded = downloadedCodes.contains(entry.code),
                            onClick = { onSelect(entry.code) }
                        )
                    }
                }

                if (filteredOthers.isNotEmpty()) {
                    item(key = "header_all") {
                        Text(
                            text = stringResource(R.string.translate_lang_section_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)
                        )
                    }
                    items(filteredOthers, key = { "other_${it.code}" }) { entry ->
                        LanguagePickerRow(
                            entry = entry,
                            selected = selectedCode == entry.code,
                            downloaded = downloadedCodes.contains(entry.code),
                            onClick = { onSelect(entry.code) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerRow(
    entry: TranslateLanguageEntry,
    selected: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .testTag("translate_pick_lang_${entry.code}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.nativeName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.englishName} (${entry.code.uppercase()})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (downloaded) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = extendedColors.statusActiveContainer,
                border = BorderStroke(0.5.dp, extendedColors.statusActive.copy(alpha = 0.35f))
            ) {
                Text(
                    text = stringResource(R.string.translate_pack_ready_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = extendedColors.statusActive,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
