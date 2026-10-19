package dev.earbuds.buds.ui

import android.os.Build
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earbuds.buds.PairedEarbuds
import dev.earbuds.buds.R
import dev.earbuds.buds.connection.BudsRepository
import dev.earbuds.buds.connection.BudsState
import dev.earbuds.buds.connection.ConnectionPhase
import dev.earbuds.buds.protocol.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt
import dev.earbuds.buds.ui.GlassButton as Button
import dev.earbuds.buds.ui.GlassButton as FilledTonalButton
import dev.earbuds.buds.ui.GlassButton as OutlinedButton
import dev.earbuds.buds.ui.GlassButton as TextButton
import dev.earbuds.buds.ui.GlassSlider as Slider
import dev.earbuds.buds.ui.GlassSwitch as Switch
import dev.earbuds.buds.ui.GlassChoice as FilterChip
import dev.earbuds.buds.ui.GlassMenuItem as DropdownMenuItem
import dev.earbuds.buds.ui.GlassDialog as AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudsApp(
    devices: List<PairedEarbuds>, bluetoothAllowed: Boolean, notificationsAllowed: Boolean,
    promotedAllowed: Boolean, savedAddress: String?, requestPermissions: () -> Unit,
    openBluetooth: () -> Unit, connect: (String) -> Unit, disconnect: () -> Unit,
    openLiveSettings: () -> Unit,
) {
    val context=LocalContext.current
    val dark=isSystemInDarkTheme()
    val colors=when {
        Build.VERSION.SDK_INT>=31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT>=31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary=Color(0xFF9BD4B6))
        else -> lightColorScheme(primary=Color(0xFF356B57),secondary=Color(0xFF53655A))
    }
    MaterialTheme(colorScheme=colors) {
        val state by BudsRepository.state.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(2) }
        val snackbar=remember { SnackbarHostState() }
        val glass=rememberLayerBackdrop {
            drawRect(colors.surface)
            drawContent()
        }
        LaunchedEffect(state.phase) { if (state.phase==ConnectionPhase.CONNECTED) tab=0 }
        LaunchedEffect(state.message) {
            state.message?.let { snackbar.showSnackbar(it); BudsRepository.clearMessage() }
        }
        val pageScroll=rememberScrollState()
        val pageScrollLock=remember { mutableStateOf(false) }
        // A coasting page must not keep moving under a finger that just grabbed the slider.
        LaunchedEffect(pageScrollLock.value) {
            if (pageScrollLock.value) pageScroll.scroll(MutatePriority.PreventUserInput) {}
        }
        Scaffold(
            snackbarHost={GlassSnackbarHost(snackbar,glass)},
            topBar={
                Box(Modifier.statusBarsPadding().padding(horizontal=16.dp,vertical=8.dp)
                    .glassChrome(glass)) {
                    TopAppBar(
                        title={Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(painterResource(if (dark) R.drawable.ic_makealight_light else R.drawable.ic_makealight_black),
                                null,Modifier.size(25.dp),tint=Color.Unspecified)
                            Spacer(Modifier.width(12.dp)); Text("Buds",fontWeight=FontWeight.SemiBold)
                        }},
                        actions={StatusChip(state.phase)},
                        colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.Transparent),
                        windowInsets=WindowInsets(0,0,0,0),
                    )
                }
            },
            bottomBar={
                Box(Modifier.navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp)) {
                    GlassTabs(glass,tab,onSelect={tab=it})
                }
            },
        ) { padding ->
            CompositionLocalProvider(LocalPageScrollLock provides pageScrollLock) {
                // Only the backdrop is recorded. Both glass bars are outside this subtree.
                GlassControlScene(Modifier.fillMaxSize().layerBackdrop(glass)) {
                    // Suspended while a finger rests on the noise slider so value drags never scroll the page.
                    Column(Modifier.fillMaxSize().consumeWindowInsets(padding)
                        .verticalScroll(pageScroll,enabled=!pageScrollLock.value)
                        .padding(padding)
                        .padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.height(4.dp))
                        when(tab) {
                            0 -> SoundPage(state)
                            1 -> GesturesPage(state)
                            else -> DevicePage(state,devices,bluetoothAllowed,notificationsAllowed,promotedAllowed,
                                savedAddress,requestPermissions,openBluetooth,connect,disconnect,openLiveSettings)
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(phase: ConnectionPhase) {
    val (label, tint) = when (phase) {
        ConnectionPhase.CONNECTED -> "Connected" to MaterialTheme.colorScheme.primary
        ConnectionPhase.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionPhase.ERROR -> "Error" to MaterialTheme.colorScheme.error
        else -> "Disconnected" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(end=20.dp)) {
        Icon(Icons.Outlined.Circle,contentDescription=null,modifier=Modifier.size(8.dp),tint=tint)
        Spacer(Modifier.width(6.dp))
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EarbudsArtwork() {
    Box(Modifier.size(116.dp).glassChrome(LocalControlBackdrop.current)
        .semantics { contentDescription="Earbuds illustration" },contentAlignment=Alignment.Center) {
        Icon(
            painterResource(if (isSystemInDarkTheme()) R.drawable.ic_makealight_light else R.drawable.ic_makealight_black),
            contentDescription=null,modifier=Modifier.size(66.dp),tint=Color.Unspecified,
        )
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Medium)
            subtitle?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun BatteryCards(info: DeviceInfo?) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("Left" to info?.left,"Right" to info?.right,"Case" to info?.case).forEach { (title,battery) ->
            GlassCard(modifier=Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(title,style=MaterialTheme.typography.labelLarge)
            Text(battery?.let { "${it.percent}%" } ?: "—",style=MaterialTheme.typography.headlineSmall,
                fontWeight=FontWeight.SemiBold,
                modifier=Modifier.semantics { contentDescription="${title} battery ${battery?.percent?.let { "$it percent" } ?: "no data"}" })
                    Text(if (battery?.charging==true) "Charging" else "Battery",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun SoundPage(state: BudsState) {
    val snapshot=state.snapshot
    val enabled=state.phase==ConnectionPhase.CONNECTED && !state.busy
    Text("Your sound.\nYour way.",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Medium)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Redmi Buds 6 Pro",style=MaterialTheme.typography.titleMedium)
            Text(snapshot?.info?.firmware?.let { "Firmware $it" } ?: "Connect on the Device tab",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EarbudsArtwork()
    }
    BatteryCards(snapshot?.info)
    if (snapshot==null) {
        SectionCard("Ready when you are","Choose your paired earbuds on the Device tab to get started.") {}
        return
    }
    NoiseControls(snapshot,enabled)
    EqControls(snapshot,enabled)
    SectionCard("Adaptive sound","Fine-tune playback to your ears and surroundings.") {
        SettingSwitch("Adaptive sound",snapshot.adaptiveSound,enabled) {
            BudsRepository.command { toggle(41,it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoiseControls(snapshot: Snapshot, enabled: Boolean) {
    SectionCard("Noise control") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            NoiseMode.entries.forEachIndexed { index,mode ->
                FilterChip(selected=snapshot.mode==mode,enabled=enabled,modifier=Modifier.weight(1f),
                    onClick={BudsRepository.command { noise(mode) }},
                    label={Text(mode.title,style=MaterialTheme.typography.labelMedium)})
            }
        }
        if (snapshot.mode!=NoiseMode.OFF) {
            // Adaptive ANC picks the strength itself: the slider is read-only and mirrors the
            // level the buds currently report instead of a leftover local drag value.
            val adaptive=snapshot.mode==NoiseMode.ANC && snapshot.adaptiveAnc
            var level by remember(snapshot.mode,snapshot.level) { mutableFloatStateOf(snapshot.level.toFloat()) }
            val maximum=if (snapshot.mode==NoiseMode.ANC) 19 else 2
            val shown=(if (adaptive) snapshot.level.toFloat() else level).coerceIn(0f,maximum.toFloat())
            val sliderEnabled=enabled && !adaptive
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if (snapshot.mode==NoiseMode.ANC) "Strength" else "Transparency")
                Text(if (snapshot.mode==NoiseMode.ANC) "${shown.roundToInt()+1} / 20"
                    else listOf("Regular","Voice","Ambient")[shown.roundToInt().coerceIn(0,2)],
                    color=MaterialTheme.colorScheme.primary)
            }
            Slider(value=shown,onValueChange={level=it},enabled=sliderEnabled,
                valueRange=0f..maximum.toFloat(),steps=maximum-1,
                onValueChangeFinished={BudsRepository.command { noise(snapshot.mode,level.roundToInt()) }},
                modifier=Modifier.lockScrollWhilePressed(enabled=sliderEnabled).semantics {
                    contentDescription="${snapshot.mode.title} strength"
                    stateDescription="${shown.roundToInt()+1} of ${maximum+1}"
                })
            if (snapshot.mode==NoiseMode.ANC) {
                SettingSwitch("Adaptive ANC",snapshot.adaptiveAnc,enabled) { BudsRepository.command { toggle(37,it) } }
                SettingSwitch("Personalized ANC",snapshot.personalizedAnc,enabled) { BudsRepository.command { toggle(59,it) } }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
        Switch(checked=checked,onCheckedChange=change,enabled=enabled,
            modifier=Modifier.semantics { contentDescription=title })
    }
}

@Composable
private fun EqControls(snapshot: Snapshot, enabled: Boolean) {
    SectionCard("Audio balance","Pick a preset or shape your own equalizer.") {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            EqPreset.entries.forEach { preset ->
                FilterChip(selected=snapshot.eq==preset,onClick={BudsRepository.command { preset(preset) }},
                    enabled=enabled,label={Text(preset.title)})
            }
        }
        if (snapshot.eq==EqPreset.CUSTOM) {
            var gains by remember(snapshot.profile) { mutableStateOf(snapshot.profile.bands.map { it.gain }) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                snapshot.profile.bands.forEachIndexed { index,band ->
                    Column(Modifier.width(80.dp),horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("${gains[index]} dB",style=MaterialTheme.typography.labelMedium)
                        Slider(value=gains[index].toFloat(),enabled=enabled,
                            valueRange=snapshot.profile.minGain.toFloat()..snapshot.profile.maxGain.toFloat(),
                            steps=(snapshot.profile.maxGain-snapshot.profile.minGain-1).coerceAtLeast(0),
                            onValueChange={value -> gains=gains.mapIndexed { i,g -> if(i==index) value.roundToInt() else g }},
                            modifier=Modifier.semantics {
                                contentDescription="${band.frequency} Hz gain"
                                stateDescription="${gains[index]} dB"
                            })
                        Text(if (band.frequency>=1000) "${band.frequency/1000}k" else "${band.frequency}",
                            style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Button(enabled=enabled && gains!=snapshot.profile.bands.map { it.gain },
                onClick={BudsRepository.command { saveEq(gains) }},modifier=Modifier.fillMaxWidth()) {
                Text("Save custom EQ")
            }
        }
    }
}

@Composable
private fun GesturesPage(state: BudsState) {
    Text("A touch of\npersonal.",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Medium)
    Text("Choose what each earbud does.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    val snapshot=state.snapshot
    val enabled=state.phase==ConnectionPhase.CONNECTED && !state.busy
    if (snapshot==null) {
        SectionCard("Connect your earbuds","Gesture settings will appear once connected.") {}; return
    }
    Gesture.entries.forEach { gesture ->
        snapshot.gestures[gesture]?.let { (left,right) ->
            SectionCard(gesture.title) {
                GesturePicker("Left",left,gesture.allowedActions,enabled) {
                    BudsRepository.command { gesture(gesture,false,it) }
                }
                GesturePicker("Right",right,gesture.allowedActions,enabled) {
                    BudsRepository.command { gesture(gesture,true,it) }
                }
            }
        }
    }
}

@Composable
private fun GesturePicker(title: String, selected: GestureAction, choices: List<GestureAction>, enabled: Boolean,
                          change: (GestureAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(title,style=MaterialTheme.typography.bodyLarge)
        GlassPicker(title=selected.title,expanded=expanded,onToggle={expanded=!expanded},
            onDismiss={expanded=false},enabled=enabled,modifier=Modifier.weight(1f).padding(start=12.dp)) {
                choices.forEach { option ->
                    DropdownMenuItem(text={Text(option.title)},onClick={expanded=false;change(option)})
                }
        }
    }
}

@Composable
private fun RegionSection(state: BudsState) {
    val context=LocalContext.current
    val info=state.snapshot?.info
    val current=info?.pid?.let(Region::fromOrNull)
    SectionCard("Region & firmware","Beta · switch the earbuds between global and China ROMs") {
        Text("Current: ${current?.title ?: "—"} ROM · Firmware ${info?.firmware ?: "—"} · " +
            "PID2717:${(info?.pid ?: 0).toString(16).uppercase().padStart(4,'0')}",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val ota=state.ota
        when {
            ota!=null -> {
                LinearProgressIndicator(progress={ota.progress/100f},modifier=Modifier.fillMaxWidth())
                Text("${ota.stage} · ${ota.progress}%",style=MaterialTheme.typography.bodyMedium)
                Text("Keep the buds in the open case next to the phone until this finishes.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.phase!=ConnectionPhase.CONNECTED || current==null -> {
                Text("Connect your earbuds to switch firmware regions.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val target=Region.entries.first { it!=current }
                val catalog=FirmwareCatalog.forRegion(target)
                var confirming by remember(current,info?.firmware) { mutableStateOf(false) }
                FilledTonalButton(onClick={confirming=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
                    Text("Switch to ${target.title} ROM (${catalog.version})")
                }
                if (confirming) AlertDialog(
                    onDismissRequest={confirming=false},
                    title={Text("Switch to ${target.title} ROM?")},
                    text={
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("This flashes ${target.title} ROM ${catalog.version} " +
                                "(${catalog.size/1024/1024} MB) over your current ${current.title} ROM. " +
                                "The switch is experimental and is usually one-way: once the other region's " +
                                "version is installed, the earbuds may refuse the original ROM as a downgrade.")
                            Text("· Charge the case above 50% and keep the lid open")
                            Text("· Keep the phone next to the case and do not use the earbuds")
                            Text("· Never open Bluetooth settings or the Xiaomi app during the flash")
                        }
                    },
                    confirmButton={
                        Button(onClick={confirming=false;BudsRepository.flash(context.cacheDir,target)}) {
                            Text("Flash ${target.title} ROM")
                        }
                    },
                    dismissButton={ TextButton(onClick={confirming=false}) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun DevicePage(
    state: BudsState, devices: List<PairedEarbuds>, bluetoothAllowed: Boolean,
    notificationsAllowed: Boolean, promotedAllowed: Boolean, savedAddress: String?,
    requestPermissions: () -> Unit, openBluetooth: () -> Unit, connect: (String) -> Unit,
    disconnect: () -> Unit, openLiveSettings: () -> Unit,
) {
    Text("Meet your\nearbuds.",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Medium)
    val connected=state.phase==ConnectionPhase.CONNECTED
    var chosen by remember(devices,savedAddress) {
        mutableStateOf(devices.firstOrNull { it.address==savedAddress } ?: devices.firstOrNull())
    }
    var expanded by remember { mutableStateOf(false) }
    SectionCard("Connection","Redmi Buds 6 Pro · local Bluetooth control") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) { EarbudsArtwork() }
        if (!bluetoothAllowed) {
            Text("Allow Nearby devices so Buds can connect to your paired earphones.")
            Button(onClick=requestPermissions,modifier=Modifier.fillMaxWidth()) { Text("Allow access") }
        } else if (devices.isEmpty()) {
            Text("Pair your Redmi Buds 6 Pro in Android Bluetooth settings first.")
            Button(onClick=openBluetooth,modifier=Modifier.fillMaxWidth()) { Text("Bluetooth settings") }
        } else {
            GlassPicker(title=chosen?.name ?: "Choose earbuds",expanded=expanded,
                onToggle={expanded=!expanded},onDismiss={expanded=false},
                enabled=!connected && state.phase!=ConnectionPhase.CONNECTING,modifier=Modifier.fillMaxWidth()) {
                    devices.forEach { device -> DropdownMenuItem(text={Text(device.name)},
                        onClick={chosen=device;expanded=false}) }
            }
            Button(onClick={if (connected || state.phase==ConnectionPhase.CONNECTING) disconnect() else chosen?.let { connect(it.address) }},
                modifier=Modifier.fillMaxWidth(),enabled=chosen!=null) {
                Text(if (connected) "Disconnect" else if (state.phase==ConnectionPhase.CONNECTING) "Cancel connection" else "Connect earbuds")
            }
        }
        state.snapshot?.let {
            val pid=it.info.pid.toString(16).uppercase().padStart(4,'0')
            Text("Firmware ${it.info.firmware} · 2717:$pid · ${Region.fromOrNull(it.info.pid)?.title ?: "?"} ROM",
                style=MaterialTheme.typography.bodySmall)
        }
    }
    RegionSection(state)
    SectionCard("Live status","Battery and listening mode, without opening the app.") {
        Text(when {
            !notificationsAllowed -> "Notifications are disabled. Allow them to see ongoing earbud status."
            Build.VERSION.SDK_INT<36 -> "Live Updates need Android 16. You'll still get a standard ongoing notification."
            promotedAllowed -> "Live Updates are allowed. Android decides when the status chip is shown."
            else -> "Enable Live Updates in Android's notification settings for the promoted status chip."
        },style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick=if (notificationsAllowed) openLiveSettings else requestPermissions,
            modifier=Modifier.fillMaxWidth()) { Text(if (notificationsAllowed) "Live Update settings" else "Allow notifications") }
    }
    SectionCard("Made for your sound") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            Icon(
                painterResource(if (isSystemInDarkTheme()) R.drawable.ic_makealight_light else R.drawable.ic_makealight_black),
                contentDescription=null,
                modifier=Modifier.size(96.dp),
                tint=Color.Unspecified,
            )
        }
        Text("Material You colors follow your wallpaper. Controls use the verified Redmi Buds 6 Pro protocol.",
            style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
