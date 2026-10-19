package dev.earbuds.buds.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidSlider
import com.kyant.backdrop.catalog.components.LiquidToggle
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.tanh

internal val LocalControlBackdrop=staticCompositionLocalOf<Backdrop> { error("Missing control backdrop") }

@Composable
internal fun GlassSnackbarHost(state: SnackbarHostState, backdrop: Backdrop) {
    SnackbarHost(state,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp)) { data ->
        CompositionLocalProvider(LocalControlBackdrop provides backdrop) {
            GlassCard(Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite }) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(data.visuals.message,color=MaterialTheme.colorScheme.onSurface,
                        style=MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        data.visuals.actionLabel?.let { label ->
                            GlassButton(onClick={data.performAction()},blurRadius=8.dp) { Text(label) }
                        }
                        GlassButton(onClick={data.dismiss()},blurRadius=12.dp) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

/** Controls sample only the sibling background, never the layer containing themselves. */
@Composable
internal fun GlassControlScene(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors=MaterialTheme.colorScheme
    val backdrop=rememberLayerBackdrop()
    Box(modifier) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Brush.linearGradient(
            listOf(colors.surface,colors.primaryContainer,colors.tertiaryContainer,colors.secondaryContainer,colors.surface)
        )))
        CompositionLocalProvider(LocalControlBackdrop provides backdrop, content=content)
    }
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val surface=MaterialTheme.colorScheme.surface
    Column(modifier.drawBackdrop(
        backdrop=LocalControlBackdrop.current,shape={RoundedCornerShape(28.dp)},
        effects={vibrancy();blur(5.dp.toPx());lens(18.dp.toPx(),24.dp.toPx(),depthEffect=true)},
        onDrawSurface={drawRect(surface.copy(alpha=if(Build.VERSION.SDK_INT>=31) 0.28f else 0.94f))},
    ),content=content)
}

@Composable
internal fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                         blurRadius: Dp = 2.dp,
                         content: @Composable RowScope.() -> Unit) {
    LiquidButton(onClick=onClick,backdrop=LocalControlBackdrop.current,
        modifier=modifier.alpha(if(enabled) 1f else 0.45f),enabled=enabled,blurRadius=blurRadius,
        surfaceColor=MaterialTheme.colorScheme.surface.copy(alpha=if(Build.VERSION.SDK_INT>=31) 0.18f else 0.94f),
        content=content)
}

@Composable
internal fun GlassChoice(selected: Boolean, onClick: () -> Unit, enabled: Boolean,
                         modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    LiquidButton(onClick=onClick,backdrop=LocalControlBackdrop.current,enabled=enabled,
        modifier=modifier.alpha(if(enabled) 1f else 0.45f).semantics { this.selected=selected },
        surfaceColor=(if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .copy(alpha=if(Build.VERSION.SDK_INT>=31) (if(selected) 0.6f else 0.2f) else 0.94f)) { label() }
}

private fun Modifier.blockDisabled(enabled: Boolean): Modifier = this
    .alpha(if(enabled) 1f else 0.45f)
    .pointerInput(enabled) {
        if(!enabled) awaitPointerEventScope {
            while(true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
internal fun GlassSlider(value: Float, onValueChange: (Float) -> Unit, enabled: Boolean,
                         valueRange: ClosedFloatingPointRange<Float>, steps: Int,
                         modifier: Modifier = Modifier, onValueChangeFinished: () -> Unit = {}) {
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinish by rememberUpdatedState(onValueChangeFinished)
    val currentEnabled by rememberUpdatedState(enabled)
    fun snap(v: Float): Float {
        val fraction=((v-valueRange.start)/(valueRange.endInclusive-valueRange.start)).coerceIn(0f,1f)
        return valueRange.start+(fraction*(steps+1)).roundToInt().toFloat()/(steps+1)*(valueRange.endInclusive-valueRange.start)
    }
    LiquidSlider(value={value},onValueChange={if(currentEnabled) currentChange(snap(it))},
        onValueChangeFinished={if(currentEnabled) currentFinish()},valueRange=valueRange,
        visibilityThreshold=0.001f,backdrop=LocalControlBackdrop.current,
        modifier=modifier.heightIn(min=48.dp).blockDisabled(enabled).semantics {
            progressBarRangeInfo=ProgressBarRangeInfo(value,valueRange,steps)
            if(!enabled) disabled()
            setProgress { target ->
                if(enabled) { currentChange(snap(target));currentFinish();true } else false
            }
        })
}

@Composable
internal fun GlassSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean,
                         modifier: Modifier = Modifier) {
    val currentEnabled by rememberUpdatedState(enabled)
    val change by rememberUpdatedState(onCheckedChange)
    LiquidToggle(selected={checked},onSelect={if(currentEnabled) change(it)},
        backdrop=LocalControlBackdrop.current,modifier=modifier.heightIn(min=48.dp).blockDisabled(enabled).semantics {
            role=Role.Switch
            toggleableState=if(checked) ToggleableState.On else ToggleableState.Off
            if(!enabled) disabled()
            onClick { if(enabled) {change(!checked);true} else false }
        })
}

@Composable
internal fun GlassMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    GlassButton(onClick=onClick,modifier=Modifier.fillMaxWidth()) { text() }
}

@Composable
internal fun GlassPicker(title: String, expanded: Boolean, onToggle: () -> Unit,
                         onDismiss: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
                         content: @Composable ColumnScope.() -> Unit) {
    val radius by animateDpAsState(if(expanded) 24.dp else 32.dp,
        spring(dampingRatio=0.85f,stiffness=360f),label="picker curvature")
    val expansion=remember { Animatable(if(expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        expansion.animateTo(if(expanded) 1f else 0f,spring(dampingRatio=0.85f,stiffness=360f))
    }
    val scope=rememberCoroutineScope()
    val interaction=remember(scope) { InteractiveHighlight(scope) }
    val surface=MaterialTheme.colorScheme.surface
    BackHandler(enabled=expanded,onBack=onDismiss)
    Column(modifier.graphicsLayer {
        val pressure=if(enabled) interaction.pressProgress.coerceIn(0f,1f) else 0f
        val drag=interaction.offset
        val x=if(enabled) tanh(drag.x/size.width.coerceAtLeast(1f)) else 0f
        val y=if(enabled) tanh(drag.y/size.height.coerceAtLeast(1f)) else 0f
        val motion=(abs(expansion.velocity)/12f).coerceIn(0f,1f)
        // Small, bounded deformation: the whole glass surface follows the title's touch.
        scaleX=1f+pressure*0.025f+abs(x)*pressure*0.045f+motion*0.012f
        scaleY=1f+pressure*0.035f+abs(y)*pressure*0.06f+motion*0.025f
        translationX=x*10.dp.toPx()*pressure
        translationY=y*8.dp.toPx()*pressure
        if(Build.VERSION.SDK_INT>=31) {
            // Directional blur approximates motion exposure, clearing completely at rest.
            renderEffect=if(motion>0.02f) android.graphics.RenderEffect.createBlurEffect(
                (0.15f*motion).dp.toPx().coerceAtLeast(0.01f),
                (0.9f*motion).dp.toPx().coerceAtLeast(0.01f),
                android.graphics.Shader.TileMode.CLAMP,
            ).asComposeRenderEffect() else null
        }
    }.drawBackdrop(
        backdrop=LocalControlBackdrop.current,shape={RoundedCornerShape(radius)},
        effects={
            val pressure=if(enabled) interaction.pressProgress.coerceIn(0f,1f) else 0f
            vibrancy();blur(3.dp.toPx())
            lens((12f+pressure*3f).dp.toPx(),(24f+pressure*4f).dp.toPx(),depthEffect=true)
        },
        onDrawSurface={drawRect(surface.copy(alpha=if(Build.VERSION.SDK_INT>=31) 0.25f else 0.94f))},
    ).animateContentSize(spring(dampingRatio=0.85f,stiffness=360f))) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
            .clickable(enabled=enabled,role=Role.Button,onClick=onToggle)
            .then(if(enabled) interaction.modifier.then(interaction.gestureModifier) else Modifier)
            .semantics { stateDescription=if(expanded) "Expanded" else "Collapsed" }
            .padding(horizontal=16.dp,vertical=12.dp),
            horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            Text(title,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else 0.45f))
        }
        if(expanded) {
            Column(Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
        }
    }
}

@Composable
internal fun GlassDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
                         text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
                         dismissButton: @Composable () -> Unit) {
    Dialog(onDismissRequest=onDismissRequest) {
        // Dialogs have a separate window: establish a local provider rather than sampling across owners.
        GlassControlScene {
            GlassCard {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    title();text();confirmButton();dismissButton()
                }
            }
        }
    }
}
