package com.sleepysoong.bramble

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

private val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalDark = staticCompositionLocalOf { false }
private val CardShape = RoundedCornerShape(22.dp)
private val PillShape = RoundedCornerShape(50)

enum class GlassTone { Thin, Regular, Thick }

@Composable
fun GlassHost(dark: Boolean, content: @Composable BoxScope.() -> Unit) {
    val enabled = !LocalInspectionMode.current && Build.VERSION.SDK_INT >= 31
    val backdrop = if (enabled) rememberLayerBackdrop { drawRect(Color.White); drawContent() } else null
    val canvas = if (dark) Color(0xFF161715) else Color(0xFFF8F7F3)
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalDark provides dark) {
        Box(Modifier.fillMaxSize()) {
            // Only the flat canvas is recorded. Every glass consumer is a sibling.
            Box(Modifier.matchParentSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).background(canvas))
            content()
        }
    }
}

@Composable
private fun Modifier.glassMaterial(shape: Shape, tone: GlassTone, accent: Boolean = false): Modifier {
    val dark = LocalDark.current
    val backdrop = LocalBackdrop.current
    val tint = if (accent) {
        if (dark) Color(0xFF3A665C) else Color(0xFFBCE3D7)
    } else if (dark) Color(0xFF303330) else Color.White
    val alpha = when (tone) { GlassTone.Thin -> .26f; GlassTone.Regular -> .34f; GlassTone.Thick -> .52f } * if (dark) 1.22f else 1f
    val blur = when (tone) { GlassTone.Thin -> 5.dp; GlassTone.Regular -> 10.dp; GlassTone.Thick -> 16.dp }
    val lensHeight = when (tone) { GlassTone.Thin -> 8.dp; GlassTone.Regular -> 16.dp; GlassTone.Thick -> 30.dp }
    val lensAmount = when (tone) { GlassTone.Thin -> 14.dp; GlassTone.Regular -> 26.dp; GlassTone.Thick -> 52.dp }
    val rim = if (dark) Color.White.copy(alpha=.18f) else Color.Black.copy(alpha=.13f)
    val material = if (backdrop == null) Modifier.background(tint.copy(alpha=if (dark) .72f else .86f), shape)
        else Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                colorControls(saturation=1.06f, brightness=if (dark) 0f else .015f)
                blur(blur.toPx())
                if (Build.VERSION.SDK_INT >= 33) lens(
                    refractionHeight=minOf(lensHeight.toPx(), size.minDimension/2.6f),
                    refractionAmount=minOf(lensAmount.toPx(), size.minDimension*.45f),
                    depthEffect=false, chromaticAberration=false
                )
            },
            highlight = null,
            shadow = { Shadow(18.dp, DpOffset(0.dp, 6.dp), if (dark) Color.Black else Color(0xFF2E2016), if (dark) .34f else .13f) },
            innerShadow = if (tone == GlassTone.Thin) null else ({ InnerShadow(10.dp, DpOffset(0.dp, 1.5.dp), Color.Black, if (dark) .11f else .08f) }),
            onDrawSurface = { drawRect(tint.copy(alpha=if (dark) alpha*.86f else alpha)) }
        )
    return then(material).border(.5.dp, rim, shape).clip(shape)
}

@Composable
fun GlassPanel(modifier: Modifier = Modifier, tone: GlassTone = GlassTone.Regular, content: @Composable () -> Unit) {
    Box(modifier.glassMaterial(CardShape, tone).padding(20.dp)) { content() }
}

@Composable
fun GlassButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .955f else 1f,
        spring(dampingRatio=if (pressed) 1f else .5f, stiffness=if (pressed) 1100f else 600f, visibilityThreshold=.0005f), label="press")
    Box(modifier.graphicsLayer { scaleX=scale; scaleY=scale }
        .defaultMinSize(minHeight=44.dp)
        .clickable(source, indication=null, enabled=enabled, onClick=onClick)
        .glassMaterial(PillShape, GlassTone.Thin, accent)
        .padding(horizontal=20.dp, vertical=11.dp)) {
        Text(label, color=if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=.4f))
    }
}

@Composable
fun GlassField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false) {
    Column(modifier.glassMaterial(RoundedCornerShape(20.dp), GlassTone.Regular).padding(horizontal=16.dp, vertical=12.dp)) {
        Text(label, style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value=value, onValueChange=onValueChange, singleLine=true,
            textStyle=TextStyle(color=MaterialTheme.colorScheme.onSurface),
            visualTransformation=if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier=Modifier.padding(top=4.dp).widthIn(min=180.dp))
    }
}
