package dev.earbuds.buds.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule

@Composable
internal fun Modifier.glassChrome(backdrop: Backdrop): Modifier {
    val surface=MaterialTheme.colorScheme.surface
    return drawBackdrop(
        backdrop=backdrop,
        shape={ContinuousCapsule},
        effects={
            vibrancy()
            blur(4.dp.toPx())
            lens(18.dp.toPx(),28.dp.toPx(),depthEffect=true)
        },
        onDrawSurface={drawRect(surface.copy(alpha=if (Build.VERSION.SDK_INT>=31) 0.22f else 0.94f))},
    )
}

@Composable
internal fun GlassTabs(backdrop: Backdrop, selected: Int, onSelect: (Int) -> Unit) {
    LiquidBottomTabs(
        selectedTabIndex={selected},onTabSelected=onSelect,backdrop=backdrop,tabsCount=3,
        modifier=Modifier.fillMaxWidth().selectableGroup(),
    ) {
        val tabs=listOf(
            Triple("Sound",Icons.Outlined.GraphicEq,Icons.Filled.GraphicEq),
            Triple("Gestures",Icons.Outlined.TouchApp,Icons.Filled.TouchApp),
            Triple("Device",Icons.Outlined.Headset,Icons.Filled.Headset),
        )
        tabs.forEachIndexed { index,(title,outline,filled) ->
            LiquidBottomTab(onClick={onSelect(index)},
                modifier=Modifier.semantics { this.selected=selected==index }) {
                Icon(if (selected==index) filled else outline,null)
                Text(title,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
