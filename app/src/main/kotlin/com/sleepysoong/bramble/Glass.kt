package com.sleepysoong.bramble

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

private val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalDark = staticCompositionLocalOf { false }
private val CardShape = RoundedCornerShape(22.dp)
private val PillShape = RoundedCornerShape(50)

enum class GlassTone { Thin, Regular, Thick }
enum class GlassTint { Neutral, Accent, Destructive }

/** Shared physical timing, including a small release overshoot. */
internal object GlassMotion {
    fun press() = spring<Float>(1f, 1100f, .0005f)
    fun release() = spring<Float>(.5f, 600f, .0005f)
    fun select() = spring<Float>(.7f, 420f, .001f)
}

@Composable
fun GlassHost(dark: Boolean, content: @Composable BoxScope.() -> Unit) {
    val enabled = !LocalInspectionMode.current && Build.VERSION.SDK_INT >= 31
    val backdrop = if (enabled) rememberLayerBackdrop { drawRect(Color.White); drawContent() } else null
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalDark provides dark) {
        Box(Modifier.fillMaxSize()) {
            // The producer contains only the solid canvas; all consumers are siblings.
            Box(Modifier.matchParentSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.background))
            content()
        }
    }
}

@Composable
private fun Modifier.glassMaterial(
    shape: Shape,
    tone: GlassTone,
    tint: GlassTint = GlassTint.Neutral,
    pressure: Float = 0f,
    focused: Boolean = false,
    lifted: Boolean = true
): Modifier {
    val dark = LocalDark.current
    val backdrop = LocalBackdrop.current
    val scheme = MaterialTheme.colorScheme
    val surface = when (tint) {
        GlassTint.Accent -> scheme.primaryContainer
        GlassTint.Destructive -> scheme.errorContainer
        GlassTint.Neutral -> if (dark) Color(0xFF303330) else Color.White
    }
    val alpha = (when (tone) {
        GlassTone.Thin -> .26f
        GlassTone.Regular -> .34f
        GlassTone.Thick -> .52f
    } * if (dark) 1.22f else 1f).coerceAtMost(.60f)
    val blurRadius = when (tone) { GlassTone.Thin -> 5.dp; GlassTone.Regular -> 10.dp; GlassTone.Thick -> 16.dp }
    val lensHeight = when (tone) { GlassTone.Thin -> 8.dp; GlassTone.Regular -> 16.dp; GlassTone.Thick -> 30.dp }
    val lensAmount = when (tone) { GlassTone.Thin -> 14.dp; GlassTone.Regular -> 26.dp; GlassTone.Thick -> 52.dp }
    val innerAlpha = when (tone) { GlassTone.Thin -> .05f; GlassTone.Regular -> .08f; GlassTone.Thick -> .10f }
    val rim = if (focused) scheme.primary.copy(alpha = .65f)
        else scheme.onSurface.copy(alpha = if (dark) .18f else .13f)
    val material = if (backdrop == null) Modifier.background(surface.copy(alpha = if (dark) .72f else .86f), shape)
    else Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(saturation = 1.06f, brightness = if (dark) 0f else .015f)
            blur(blurRadius.toPx() * (1f - pressure * .55f))
            if (Build.VERSION.SDK_INT >= 33) lens(
                refractionHeight = minOf(lensHeight.toPx() * (1f + pressure * .35f), size.minDimension / 2.6f),
                refractionAmount = minOf(lensAmount.toPx() * (1f + pressure * .25f), size.minDimension * .45f),
                depthEffect = false, chromaticAberration = false
            )
        },
        // Broad highlights wash out cards; reserve the specular rim for the liquid thumb.
        highlight = null,
        shadow = if (lifted) ({ Shadow(18.dp, DpOffset(0.dp, 6.dp), if (dark) Color.Black else Color(0xFF2E2016), if (dark) .34f else .13f) }) else null,
        innerShadow = { InnerShadow(10.dp, DpOffset(0.dp, 1.5.dp), Color.Black, innerAlpha * if (dark) 1.4f else 1f) },
        onDrawSurface = { drawRect(surface.copy(alpha = (if (dark) alpha * .86f else alpha) * (1f - pressure * .2f))) }
    )
    return then(material).border(if (focused) 1.dp else .5.dp, rim, shape).clip(shape)
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    tone: GlassTone = GlassTone.Regular,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.glassMaterial(CardShape, tone).padding(contentPadding), content = content)
}

@Composable
fun GlassButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    accent: Boolean = false, enabled: Boolean = true, destructive: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .955f else 1f,
        if (pressed) GlassMotion.press() else GlassMotion.release(), label = "button-scale")
    val pressure by animateFloatAsState(if (pressed) 1f else 0f,
        if (pressed) GlassMotion.press() else GlassMotion.release(), label = "button-lens")
    val tint = when { destructive -> GlassTint.Destructive; accent -> GlassTint.Accent; else -> GlassTint.Neutral }
    val color = when { destructive -> MaterialTheme.colorScheme.error; accent -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface }
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .defaultMinSize(minHeight = 48.dp)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .glassMaterial(PillShape, GlassTone.Thin, tint, pressure, lifted = false)
        .padding(horizontal = 18.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = color.copy(alpha = if (enabled) 1f else .4f), textAlign = TextAlign.Center)
    }
}

@Composable
fun GlassIconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .88f else 1f,
        if (pressed) GlassMotion.press() else GlassMotion.release(), label = "icon-press")
    Box(Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics { contentDescription = label }
        .clickable(source, indication = null, role = Role.Button, onClick = onClick)
        .glassMaterial(PillShape, GlassTone.Thin, pressure = if (pressed) 1f else 0f, lifted = false),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
fun GlassBadge(label: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier.glassMaterial(PillShape, GlassTone.Thin, if (accent) GlassTint.Accent else GlassTint.Neutral, lifted = false)
        .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(6.dp).background(if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, PillShape))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GlassField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(modifier.glassMaterial(RoundedCornerShape(18.dp), GlassTone.Regular, focused = focused, lifted = false)
        .padding(start = 16.dp, end = if (secret) 8.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused) scheme.primary else scheme.onSurfaceVariant)
            BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false, imeAction = if (secret) ImeAction.Done else ImeAction.Next),
                visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label })
        }
        if (secret) GlassIconButton(if (revealed) "토큰 숨기기" else "토큰 보기", { revealed = !revealed }) {
            Text(if (revealed) "숨김" else "보기", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
    }
}

/** Labels and tint are recorded separately, never inside the pill that consumes them. */
@Composable
fun GlassSegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (options.isEmpty()) return
    val selected = selectedIndex.coerceIn(options.indices)
    val scheme = MaterialTheme.colorScheme
    val global = LocalBackdrop.current
    val labelsLayer = rememberLayerBackdrop()
    val position = remember { Animatable(selected.toFloat(), visibilityThreshold = .001f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressure by animateFloatAsState(if (pressed || dragging) 1f else 0f,
        if (pressed || dragging) GlassMotion.press() else GlassMotion.release(), label = "segment-press")
    val latestSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(selected, dragging) { if (!dragging) position.animateTo(selected.toFloat(), GlassMotion.select()) }
    val inset = 3.dp

    @Composable
    fun Labels(recorded: Boolean, m: Modifier) {
        Row(m.fillMaxSize().padding(horizontal = inset)) {
            options.forEachIndexed { index, label ->
                val selection = if (recorded) Modifier else Modifier.selectable(
                    selected = index == selected, interactionSource = source, indication = null, role = Role.Tab,
                    onClick = { latestSelect(index) })
                Box(Modifier.weight(1f).fillMaxHeight().then(selection), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = if (recorded) scheme.onPrimaryContainer else lerp(scheme.onSurfaceVariant, scheme.onPrimaryContainer, (1f - abs(position.value - index)).coerceIn(0f, 1f)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    BoxWithConstraints(modifier.height(48.dp).selectableGroup()) {
        val segmentWidth = (maxWidth - inset * 2) / options.size
        val widthPixels = with(LocalDensity.current) { segmentWidth.toPx() }
        Box(Modifier.matchParentSize().glassMaterial(PillShape, GlassTone.Thick, lifted = false))
        Labels(true, Modifier.clearAndSetSemantics {}.alpha(0f).layerBackdrop(labelsLayer)
            .drawBehind { drawRect(scheme.primaryContainer) })
        Labels(false, Modifier)
        val pill = Modifier.offset(x = inset + segmentWidth * position.value)
            .width(segmentWidth).fillMaxHeight().padding(vertical = inset)
            .testTag("theme-thumb").clearAndSetSemantics {}
            .clickable(source, indication = null, onClick = {})
            .draggable(rememberDraggableState { delta ->
                scope.launch { position.snapTo((position.value + delta / widthPixels).coerceIn(0f, options.lastIndex.toFloat())) }
            }, Orientation.Horizontal, interactionSource = source,
                onDragStarted = { dragging = true },
                onDragStopped = { latestSelect(position.value.roundToInt().coerceIn(options.indices)); dragging = false })
        if (global == null) {
            Box(pill.background(scheme.primaryContainer, PillShape))
        } else Box(pill.drawBackdrop(
            backdrop = rememberCombinedBackdrop(global, labelsLayer), shape = { PillShape },
            effects = {
                vibrancy()
                if (Build.VERSION.SDK_INT >= 33) lens(
                    minOf(10.dp.toPx() * (.25f + pressure * .75f), size.minDimension / 2.6f),
                    minOf(14.dp.toPx() * (.25f + pressure * .75f), size.minDimension * .45f),
                    depthEffect = false, chromaticAberration = false)
            },
            highlight = { Highlight.Default.copy(alpha = .35f + .65f * pressure.coerceIn(0f, 1f)) },
            shadow = { Shadow(6.dp, DpOffset(0.dp, 2.dp), Color.Black, .08f) },
            innerShadow = { InnerShadow(6.dp, DpOffset(0.dp, 1.dp), Color.Black, .06f + pressure.coerceIn(0f, 1f) * .14f) },
            layerBlock = {
                val swell = 1f + .1f * pressure
                val stretch = .18f * tanh(abs(position.velocity) * .12f)
                scaleX = swell * (1f + stretch); scaleY = swell * (1f - stretch * .3f)
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = .08f * pressure.coerceIn(0f, 1f))) }
        ))
    }
}
