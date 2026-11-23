package com.edgeflow.settings

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.icons.HinataIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.edgeflow.data.EdgeConfig
import com.edgeflow.data.EdgeVisualStyle
import com.edgeflow.service.CustomMediaLoader
import com.example.R
import com.example.ui.theme.LocalEdgeFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Settings section for configuring custom transparent GIF / Animated WebP / PNG / JPEG
 * side-edge gesture feedback using zero-permission [ActivityResultContracts.OpenDocument]
 * and Android's native `ImageDecoder` / `AnimatedImageDrawable`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomMediaSectionCard(
    config: EdgeConfig,
    onConfigChange: (EdgeConfig) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extendedColors = LocalEdgeFlowColors.current

    var isCopying by remember { mutableStateOf(false) }
    var previewDrawable by remember { mutableStateOf<Drawable?>(null) }

    // Decode live preview drawable on IO thread whenever path or version changes
    LaunchedEffect(config.customMediaPath, config.customMediaVersion) {
        val prev = previewDrawable
        if (prev is AnimatedImageDrawable) {
            runCatching { prev.stop() }
        }
        previewDrawable = if (config.customMediaPath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                CustomMediaLoader.decodeDrawableSync(
                    filePath = config.customMediaPath,
                    maxLongEdgePx = CustomMediaLoader.MAX_DECODE_LONG_EDGE_PX
                )
            }
        } else {
            null
        }
    }

    // Strictly stop animated preview drawable when leaving composition
    DisposableEffect(previewDrawable) {
        val current = previewDrawable
        if (current is AnimatedImageDrawable) {
            runCatching { current.start() }
        }
        onDispose {
            if (current is AnimatedImageDrawable) {
                runCatching { current.stop() }
            }
        }
    }

    val mediaSavedMsg = stringResource(R.string.custom_media_saved)
    val mediaTooLargeMsg = stringResource(R.string.custom_media_error_too_large)
    val mediaInvalidMsg = stringResource(R.string.custom_media_error_invalid)
    val mediaIoErrorMsg = stringResource(R.string.custom_media_error_io)
    val mediaResetMsg = stringResource(R.string.custom_media_reset_done)

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        SettingsActivity.isWaitingForChildActivityResult = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isCopying = true
            val result = withContext(Dispatchers.IO) {
                CustomMediaLoader.copyPickedMediaToInternal(context, uri)
            }
            isCopying = false
            when (result) {
                is CustomMediaLoader.CopyResult.Success -> {
                    val nextStyle = if (config.edgeVisualStyle == EdgeVisualStyle.DEFAULT_DROPLET) {
                        EdgeVisualStyle.CUSTOM_MEDIA_ONLY
                    } else {
                        config.edgeVisualStyle
                    }
                    onConfigChange(
                        config.copy(
                            edgeVisualStyle = nextStyle,
                            customMediaPath = result.filePath,
                            customMediaVersion = result.version
                        )
                    )
                    snackbarHostState.showSnackbar(mediaSavedMsg)
                }
                CustomMediaLoader.CopyResult.TooLarge -> {
                    snackbarHostState.showSnackbar(mediaTooLargeMsg)
                }
                CustomMediaLoader.CopyResult.InvalidFormat -> {
                    snackbarHostState.showSnackbar(mediaInvalidMsg)
                }
                CustomMediaLoader.CopyResult.IoError -> {
                    snackbarHostState.showSnackbar(mediaIoErrorMsg)
                }
            }
        }
    }

    SectionCard(title = stringResource(R.string.section_custom_media)) {
        Text(
            text = stringResource(R.string.desc_custom_media),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.label_edge_visual_style),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))

        val styles = EdgeVisualStyle.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            styles.forEachIndexed { index, style ->
                val label = when (style) {
                    EdgeVisualStyle.DEFAULT_DROPLET -> stringResource(R.string.visual_style_default)
                    EdgeVisualStyle.CUSTOM_MEDIA_ONLY -> stringResource(R.string.visual_style_custom_only)
                    EdgeVisualStyle.DROPLET_WITH_CUSTOM_MEDIA -> stringResource(R.string.visual_style_droplet_media)
                }
                SegmentedButton(
                    selected = config.edgeVisualStyle == style,
                    onClick = { onConfigChange(config.copy(edgeVisualStyle = style)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size),
                    icon = {},
                    modifier = Modifier.testTag("edge_visual_style_${style.name}")
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Live animated preview + Pick / Reset actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .testTag("custom_media_preview_box"),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, extendedColors.cardBorder)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val drawable = previewDrawable
                    when {
                        isCopying -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                        drawable != null -> {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                                factory = { ctx ->
                                    ImageView(ctx).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                    }
                                },
                                update = { imageView ->
                                    imageView.setImageDrawable(drawable)
                                    imageView.alpha = config.customMediaAlpha
                                    if (drawable is AnimatedImageDrawable && !drawable.isRunning) {
                                        runCatching { drawable.start() }
                                    }
                                }
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = HinataIcons.Image,
                                contentDescription = stringResource(R.string.cd_custom_media_empty),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        SettingsActivity.isWaitingForChildActivityResult = true
                        runCatching {
                            pickMediaLauncher.launch(
                                arrayOf("image/gif", "image/webp", "image/png", "image/jpeg")
                            )
                        }.onFailure {
                            SettingsActivity.isWaitingForChildActivityResult = false
                        }
                    },
                    enabled = !isCopying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pick_custom_media_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = HinataIcons.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (config.hasCustomMedia()) R.string.button_change_custom_media
                            else R.string.button_pick_custom_media
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (config.hasCustomMedia()) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val prev = previewDrawable
                                if (prev is AnimatedImageDrawable) {
                                    runCatching { prev.stop() }
                                }
                                previewDrawable = null
                                withContext(Dispatchers.IO) {
                                    CustomMediaLoader.deleteCustomMedia(context, config.customMediaPath)
                                }
                                onConfigChange(
                                    config.copy(
                                        edgeVisualStyle = EdgeVisualStyle.DEFAULT_DROPLET,
                                        customMediaPath = "",
                                        customMediaVersion = 0L
                                    )
                                )
                                snackbarHostState.showSnackbar(mediaResetMsg)
                            }
                        },
                        enabled = !isCopying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_custom_media_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = HinataIcons.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.button_reset_custom_media),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (config.edgeVisualStyle != EdgeVisualStyle.DEFAULT_DROPLET && !config.hasCustomMedia()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.hint_custom_media_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (config.edgeVisualStyle != EdgeVisualStyle.DEFAULT_DROPLET || config.hasCustomMedia()) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = extendedColors.cardBorder)
            Spacer(Modifier.height(10.dp))

            MetricSlider(
                label = stringResource(R.string.label_custom_media_size),
                valueText = "${config.customMediaSizeDp} dp",
                value = config.customMediaSizeDp.toFloat(),
                range = EdgeConfig.MIN_CUSTOM_MEDIA_SIZE_DP.toFloat()..EdgeConfig.MAX_CUSTOM_MEDIA_SIZE_DP.toFloat(),
                testTag = "slider_custom_media_size",
                onValueChange = {
                    onConfigChange(config.copy(customMediaSizeDp = it.roundToInt()))
                }
            )

            MetricSlider(
                label = stringResource(R.string.label_custom_media_alpha),
                valueText = "${(config.customMediaAlpha * 100).roundToInt()}%",
                value = config.customMediaAlpha,
                range = EdgeConfig.MIN_CUSTOM_MEDIA_ALPHA..1.0f,
                testTag = "slider_custom_media_alpha",
                onValueChange = {
                    onConfigChange(config.copy(customMediaAlpha = it))
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_custom_media_mirror_right),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.desc_custom_media_mirror_right),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = config.customMediaMirrorRight,
                    onCheckedChange = {
                        onConfigChange(config.copy(customMediaMirrorRight = it))
                    },
                    modifier = Modifier.testTag("toggle_custom_media_mirror_right")
                )
            }
        }
    }
}
