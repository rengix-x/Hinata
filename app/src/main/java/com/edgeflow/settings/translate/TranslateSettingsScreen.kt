package com.edgeflow.settings.translate

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.LiveTranslateEngineMode
import com.edgeflow.data.LiveTranslateRenderStyle
import com.edgeflow.data.MlKitSafeInit
import com.edgeflow.data.TranslateLanguageCatalog
import com.edgeflow.data.TranslateLanguageEntry
import com.edgeflow.settings.MetricSlider
import com.edgeflow.settings.SectionCard
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import com.example.ui.theme.NumericLabelStyle
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class LanguagePickerTarget {
    SOURCE,
    TARGET
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateSettingsScreen(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extendedColors = LocalEdgeFlowColors.current
    val scope = rememberCoroutineScope()

    var modelManager by remember { mutableStateOf<RemoteModelManager?>(null) }
    var mlKitUnavailable by remember { mutableStateOf(false) }

    var pickerTarget by remember { mutableStateOf<LanguagePickerTarget?>(null) }
    var showOnlineConsentDialog by remember { mutableStateOf(false) }
    var packSearchQuery by remember { mutableStateOf("") }
    var showAllPacks by remember { mutableStateOf(false) }
    var downloadedCodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busyCodes by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refreshDownloadedModels(manager: RemoteModelManager? = modelManager) {
        if (manager == null) {
            downloadedCodes = emptySet()
            return
        }
        val loaded = runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                .map { it.language }
                .toSet()
        }.getOrDefault(emptySet())
        downloadedCodes = loaded
    }

    LaunchedEffect(context) {
        val manager = withContext(Dispatchers.IO) {
            if (!MlKitSafeInit.ensureInitialized(context)) {
                null
            } else {
                runCatching { RemoteModelManager.getInstance() }.getOrNull()
            }
        }
        modelManager = manager
        mlKitUnavailable = (manager == null)
        if (manager != null) {
            refreshDownloadedModels(manager)
        }
    }

    fun downloadLanguagePack(code: String) {
        val manager = modelManager ?: return
        val mlCode = TranslateLanguage.fromLanguageTag(code) ?: return
        if (busyCodes.contains(code)) return
        busyCodes = busyCodes + code
        scope.launch {
            val conditionsBuilder = DownloadConditions.Builder()
            if (config.translateWifiOnlyDownload) {
                conditionsBuilder.requireWifi()
            }
            val result = runCatching {
                val model = TranslateRemoteModel.Builder(mlCode).build()
                manager.download(model, conditionsBuilder.build()).await()
            }
            refreshDownloadedModels(manager)
            busyCodes = busyCodes - code
            if (result.isFailure) {
                val msgRes = if (config.translateWifiOnlyDownload) {
                    R.string.translate_toast_wifi_required
                } else {
                    R.string.translate_toast_model_download_failed
                }
                Toast.makeText(context, msgRes, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteLanguagePack(code: String) {
        val manager = modelManager ?: return
        val mlCode = TranslateLanguage.fromLanguageTag(code) ?: return
        if (busyCodes.contains(code)) return
        busyCodes = busyCodes + code
        scope.launch {
            runCatching {
                val model = TranslateRemoteModel.Builder(mlCode).build()
                manager.deleteDownloadedModel(model).await()
            }
            refreshDownloadedModels(manager)
            busyCodes = busyCodes - code
        }
    }

    val visiblePacks = remember(packSearchQuery, showAllPacks, downloadedCodes) {
        val q = packSearchQuery.trim()
        if (q.isNotEmpty()) {
            TranslateLanguageCatalog.allLanguages.filter {
                TranslateLanguageCatalog.matchesQuery(it, q)
            }
        } else if (showAllPacks) {
            TranslateLanguageCatalog.allLanguages
        } else {
            TranslateLanguageCatalog.allLanguages.filter {
                it.pinned || downloadedCodes.contains(it.code)
            }
        }
    }

    val sourceEntry = TranslateLanguageCatalog.findByCode(config.translateSourceLang)
    val sourceDisplayTitle = if (config.translateSourceLang == TranslateLanguageCatalog.AUTO_CODE) {
        stringResource(R.string.translate_source_auto_short)
    } else {
        sourceEntry?.nativeName ?: config.translateSourceLang.uppercase()
    }
    val targetEntry = TranslateLanguageCatalog.findByCode(config.translateTargetLang)
    val targetDisplayTitle = targetEntry?.nativeName ?: config.translateTargetLang.uppercase()

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("translate_settings_screen")
    ) {
        // Minimal top app bar matching GeneralSettingsScreen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("translate_settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.translate_settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
        ) {
            // 1. Language Pair Card
            item {
                SectionCard(title = stringResource(R.string.translate_section_languages)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguageChipSelector(
                            label = stringResource(R.string.translate_label_source),
                            value = sourceDisplayTitle,
                            testTag = "translate_source_selector",
                            onClick = { pickerTarget = LanguagePickerTarget.SOURCE },
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            onClick = {
                                val (nextSrc, nextTgt) = TranslateLanguageCatalog.computeQuickSwap(
                                    config.translateSourceLang,
                                    config.translateTargetLang
                                )
                                onConfigChange(
                                    config.copy(
                                        translateSourceLang = nextSrc,
                                        translateTargetLang = nextTgt
                                    )
                                )
                            },
                            modifier = Modifier.testTag("translate_swap_languages_button")
                        ) {
                            Icon(
                                imageVector = HinataIcons.SwapHoriz,
                                contentDescription = stringResource(R.string.cd_swap_languages)
                            )
                        }
                        LanguageChipSelector(
                            label = stringResource(R.string.translate_label_target),
                            value = targetDisplayTitle,
                            testTag = "translate_target_selector",
                            onClick = { pickerTarget = LanguagePickerTarget.TARGET },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.translate_languages_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1.5 Translation Engine Card (Offline Local vs Opt-In Online High Quality)
            item {
                SectionCard(title = stringResource(R.string.translate_section_engine)) {
                    val engineOptions = listOf(
                        LiveTranslateEngineMode.OFFLINE_MLKIT to R.string.translate_engine_offline,
                        LiveTranslateEngineMode.ONLINE_HIGH_QUALITY to R.string.translate_engine_online
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        engineOptions.forEachIndexed { idx, (engine, labelRes) ->
                            SegmentedButton(
                                selected = config.translateEngineMode == engine,
                                onClick = {
                                    if (engine == LiveTranslateEngineMode.ONLINE_HIGH_QUALITY &&
                                        !config.translateOnlineConsentAccepted
                                    ) {
                                        showOnlineConsentDialog = true
                                    } else {
                                        onConfigChange(config.copy(translateEngineMode = engine).clamped())
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(idx, engineOptions.size),
                                icon = {},
                                modifier = Modifier.testTag("translate_engine_${engine.name}")
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val engineDescRes = if (config.translateEngineMode == LiveTranslateEngineMode.ONLINE_HIGH_QUALITY) {
                        R.string.translate_engine_online_desc
                    } else {
                        R.string.translate_engine_offline_desc
                    }
                    Text(
                        text = stringResource(engineDescRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. One-Tap Translate Button Behavior Card
            item {
                SectionCard(title = stringResource(R.string.translate_section_button_behavior)) {
                    Text(
                        text = stringResource(R.string.translate_button_behavior_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = extendedColors.cardBorder)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(R.string.translate_toggle_auto_clear_scroll_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.translate_toggle_auto_clear_scroll_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.translateAutoClearOnScroll,
                            onCheckedChange = {
                                onConfigChange(config.copy(translateAutoClearOnScroll = it))
                            },
                            modifier = Modifier.testTag("toggle_translate_auto_clear_on_scroll")
                        )
                    }
                }
            }

            // 3. Display Style & Filtering Card
            item {
                SectionCard(title = stringResource(R.string.translate_section_display)) {
                    val styleOptions = listOf(
                        LiveTranslateRenderStyle.ADAPTIVE_INPLACE to R.string.translate_style_inplace,
                        LiveTranslateRenderStyle.FROSTED_CARD to R.string.translate_style_frosted
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        styleOptions.forEachIndexed { idx, (style, labelRes) ->
                            SegmentedButton(
                                selected = config.translateRenderStyle == style,
                                onClick = { onConfigChange(config.copy(translateRenderStyle = style)) },
                                shape = SegmentedButtonDefaults.itemShape(idx, styleOptions.size),
                                icon = {},
                                modifier = Modifier.testTag("translate_style_${style.name}")
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    MetricSlider(
                        label = stringResource(R.string.translate_label_overlay_alpha),
                        valueText = "${(config.translateOverlayAlpha * 100f).roundToInt()}%",
                        value = config.translateOverlayAlpha,
                        range = EdgeConfig.MIN_TRANSLATE_OVERLAY_ALPHA..EdgeConfig.MAX_TRANSLATE_OVERLAY_ALPHA,
                        testTag = "slider_translate_overlay_alpha",
                        onValueChange = { onConfigChange(config.copy(translateOverlayAlpha = it).clamped()) }
                    )

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = extendedColors.cardBorder)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onConfigChange(config.copy(translateSkipTargetLanguage = !config.translateSkipTargetLanguage))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.translate_toggle_skip_target_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.translate_toggle_skip_target_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = config.translateSkipTargetLanguage,
                            onCheckedChange = {
                                onConfigChange(config.copy(translateSkipTargetLanguage = it))
                            },
                            modifier = Modifier.testTag("toggle_translate_skip_target")
                        )
                    }
                }
            }

            // 4. Offline Language Pack Manager Card (59 ML Kit languages, ~30MB each)
            item {
                SectionCard(title = stringResource(R.string.translate_section_packs)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onConfigChange(config.copy(translateWifiOnlyDownload = !config.translateWifiOnlyDownload))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.translate_toggle_wifi_only_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.translate_toggle_wifi_only_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = config.translateWifiOnlyDownload,
                            onCheckedChange = {
                                onConfigChange(config.copy(translateWifiOnlyDownload = it))
                            },
                            modifier = Modifier.testTag("toggle_translate_wifi_only")
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = extendedColors.cardBorder)
                    Spacer(Modifier.height(12.dp))

                    if (mlKitUnavailable) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("translate_mlkit_unavailable_notice"),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = stringResource(R.string.translate_mlkit_unavailable_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = packSearchQuery,
                        onValueChange = { packSearchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("translate_pack_search_input"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.translate_search_pack_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (packSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { packSearchQuery = "" }) {
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

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        visiblePacks.forEachIndexed { idx, entry ->
                            if (idx > 0) {
                                HorizontalDivider(color = extendedColors.cardBorder)
                            }
                            LanguagePackRow(
                                entry = entry,
                                isDownloaded = downloadedCodes.contains(entry.code),
                                isBusy = busyCodes.contains(entry.code),
                                onDownload = { downloadLanguagePack(entry.code) },
                                onDelete = { deleteLanguagePack(entry.code) }
                            )
                        }
                    }

                    if (packSearchQuery.isBlank()) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showAllPacks = !showAllPacks },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("translate_toggle_all_packs_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, extendedColors.cardBorder)
                        ) {
                            Text(
                                text = if (showAllPacks) {
                                    stringResource(R.string.translate_packs_show_popular)
                                } else {
                                    stringResource(
                                        R.string.translate_packs_show_all,
                                        TranslateLanguageCatalog.allLanguages.size
                                    )
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 5. 3-Step Quick Usage Guide Card
            item {
                SectionCard(title = stringResource(R.string.translate_section_guide)) {
                    GuideStepRow(1, stringResource(R.string.translate_guide_step1))
                    Spacer(Modifier.height(8.dp))
                    GuideStepRow(2, stringResource(R.string.translate_guide_step2))
                    Spacer(Modifier.height(8.dp))
                    GuideStepRow(3, stringResource(R.string.translate_guide_step3))
                }
            }
        }
    }

    val activePicker = pickerTarget
    if (activePicker != null) {
        TranslateLanguagePickerSheet(
            title = stringResource(
                if (activePicker == LanguagePickerTarget.SOURCE) {
                    R.string.translate_picker_source_title
                } else {
                    R.string.translate_picker_target_title
                }
            ),
            includeAuto = activePicker == LanguagePickerTarget.SOURCE,
            languages = if (activePicker == LanguagePickerTarget.SOURCE) {
                TranslateLanguageCatalog.sourceLanguages
            } else {
                TranslateLanguageCatalog.allLanguages
            },
            selectedCode = if (activePicker == LanguagePickerTarget.SOURCE) {
                config.translateSourceLang
            } else {
                config.translateTargetLang
            },
            downloadedCodes = downloadedCodes,
            onSelect = { selected ->
                val updated = if (activePicker == LanguagePickerTarget.SOURCE) {
                    config.copy(translateSourceLang = selected)
                } else {
                    config.copy(translateTargetLang = selected)
                }
                onConfigChange(updated.clamped())
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null }
        )
    }

    if (showOnlineConsentDialog) {
        AlertDialog(
            onDismissRequest = { showOnlineConsentDialog = false },
            icon = {
                Icon(
                    imageVector = HinataIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.translate_online_consent_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.translate_online_consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOnlineConsentDialog = false
                        onConfigChange(
                            config.copy(
                                translateOnlineConsentAccepted = true,
                                translateEngineMode = LiveTranslateEngineMode.ONLINE_HIGH_QUALITY
                            ).clamped()
                        )
                    },
                    modifier = Modifier.testTag("translate_online_consent_confirm")
                ) {
                    Text(text = stringResource(R.string.translate_online_consent_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showOnlineConsentDialog = false },
                    modifier = Modifier.testTag("translate_online_consent_cancel")
                ) {
                    Text(text = stringResource(R.string.translate_online_consent_cancel))
                }
            }
        )
    }
}

@Composable
private fun LanguageChipSelector(
    label: String,
    value: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalEdgeFlowColors.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, extendedColors.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LanguagePackRow(
    entry: TranslateLanguageEntry,
    isDownloaded: Boolean,
    isBusy: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val extendedColors = LocalEdgeFlowColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.nativeName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.englishName} · ${stringResource(R.string.translate_pack_size_hint)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            isBusy -> {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            isDownloaded -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.translate_pack_ready_short),
                        tint = extendedColors.statusActive,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("translate_pack_delete_${entry.code}")
                    ) {
                        Icon(
                            imageVector = HinataIcons.DeleteOutline,
                            contentDescription = stringResource(R.string.cd_delete_language_pack),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.testTag("translate_pack_download_${entry.code}")
                ) {
                    Icon(
                        imageVector = HinataIcons.CloudDownload,
                        contentDescription = stringResource(R.string.cd_download_language_pack),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStepRow(stepNumber: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber.toString(),
                style = NumericLabelStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
