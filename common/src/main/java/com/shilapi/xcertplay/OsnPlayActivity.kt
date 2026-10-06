// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.view.Window
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.network.ManualHotspotInterfaces
import com.shilapi.xcertplay.orchestration.ManualHotspotAddressMode
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
open class OsnPlayActivity : ComponentActivity() {
    protected open val modernUi: Boolean get() = resources.getBoolean(R.bool.config_osn_ui)
    protected open val updatesEnabled: Boolean get() = resources.getBoolean(R.bool.config_app_updates)
    internal open fun updateManager(context: Context): OsnUpdateManager = OsnUpdateManager.forApp(context)
    private var updater: OsnUpdateManager? = null
    private var updateSubscription: java.io.Closeable? = null
    private var updateStatus: TextView? = null
    private var updateProgress: ProgressBar? = null
    private var updateCheckButton: Button? = null
    private var updateActionButton: Button? = null
    private var updateCancelButton: Button? = null
    private var updateNotes: TextView? = null
    private var pendingUpdatePermission = false
    private val updateInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingUpdatePermission) {
            pendingUpdatePermission = false
            if (OsnUpdateInstaller.permitted(this)) handler.post { installUpdate() }
            else toast(getString(R.string.osn_update_permission_needed))
        }
    }
    private val bydFeatures get() = !modernUi && resources.getBoolean(R.bool.config_byd_features)
    private var palette = OsnAppearance.legacy
    private var uiScale = 1f
    private val BG get() = palette.background
    private val SURFACE get() = palette.surface
    private val BORDER get() = palette.border
    private val ACCENT get() = palette.accent
    private val TEXT get() = palette.text
    private val MUTED get() = palette.muted
    private val WARNING get() = palette.warning
    private var settingsCategory = "appearance"
    private var connectionWireless = true
    private var activeDialog: AlertDialog? = null
    private var statusBadge: TextView? = null
    private var statusDetail: TextView? = null
    private var clockLabel: TextView? = null
    private var appliedPaletteDark: Boolean? = null
    private var deviceMonitor: OsnUiDeviceMonitor? = null
    private var uiForeground = false
    private var hotspotCheck: TextView? = null
    private var permissionCheck: TextView? = null
    private var permissionBadge: TextView? = null
    private var hotspotStep: TextView? = null
    private var hotspotWarning: TextView? = null
    private var hotspotSettingsButton: Button? = null
    private var interfaceValue: TextView? = null
    @Volatile private var uiRenderCount = 0
    @Volatile private var uiMaxRenderMillis = 0L
    @Volatile private var uiMaxTickDelayMillis = 0L
    private var expectedTickAt: Long? = null
    private val cachedVersion by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1" }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var clusterSafeAreaDialog: Dialog? = null
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var pendingMicrophoneTransport: Boolean? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val wireless = pendingMicrophoneTransport
        pendingMicrophoneTransport = null
        if (granted && wireless != null) connect(wireless)
        else toast(getString(R.string.osn_microphone_required))
    }
    private var exportInProgress = false
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    private var bydVehicleAdvancedExpanded = false
    private var adbAccessState: BydAdbAccess.State? = null
    private var adbCheckInProgress = false
    private var adbCheckMayAsk = false
    private var adbCheckFailed = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var vehicleProbeOutcome: BydVehicleProbeOutcome? = null
    private var adbCheckGeneration = 0
    private var adbStatus: TextView? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var pendingVehicleReplacement: BydVehicleCapabilities? = null
    // The saved snapshot [pendingVehicleReplacement] was compared with.
    private var pendingVehicleReplacementExpected: BydVehicleCapabilities? = null
    private var pendingVehicleLostFields: Set<BydVehicleField> = emptySet()
    private var defaultVehicleStatus: BydAdbAccess.Status? = null
    private var vehicleDataReconnectPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
        validateSavedVehicleConfigurationAutomatically()
    }
    private data class VehicleProbeAttempt(
        val outcome: BydVehicleProbeOutcome,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val allowedOnlyOnce: Boolean = false,
    )

    private data class VehicleValidationAttempt(
        val status: BydAdbAccess.Status,
        val outcome: BydVehicleProbeOutcome? = null,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val savedFieldsReadable: Boolean = false,
    )
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            expectedTickAt?.let { uiMaxTickDelayMillis = maxOf(uiMaxTickDelayMillis, (now - it).coerceAtLeast(0)) }
            refreshStatus()
            expectedTickAt = android.os.SystemClock.elapsedRealtime() + 1000
            handler.postDelayed(this, 1000)
        }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_osnplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_osnplay_in_the_head_unit_s_app_p))
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (modernUi) setTheme(OsnAppearance.style(this))
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { OsnPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("OsnPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.takeIf { it in listOf("appearance", "display", "audio", "connection", "diagnostics", "updates") } ?: "appearance"
        connectionWireless = savedInstanceState?.getBoolean("connection_wireless") ?: AirPlayPersistence.loadWirelessEnabled(this)
        pendingUpdatePermission = savedInstanceState?.getBoolean("update_permission_pending") ?: false
        if (updatesEnabled) {
            updater = updateManager(applicationContext)
            updateSubscription = updater?.observe { refreshUpdateUi() }
        }
        if (modernUi) {
            val dispatcher = handler
            deviceMonitor = OsnUiDeviceMonitor(uiDeviceSource(applicationContext), java.util.concurrent.Executor { dispatcher.post(it) }, changed = {
                if (uiForeground && !isFinishing && !isDestroyed) { updateDeviceIndicators(); refreshStatus() }
            })
        }
        savedInstanceState?.getInt("scroll_y")?.let { pendingScrollY = it; renderedPage = renderKey() }
        render()
        deviceMonitor?.request()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        automaticVehicleValidationStarted = false
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        outState.putString("settings_category", settingsCategory)
        outState.putBoolean("connection_wireless", connectionWireless)
        outState.putBoolean("update_permission_pending", pendingUpdatePermission)
        outState.putInt("scroll_y", pendingScrollY ?: rootScroll?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onOsnPlayScreenShown()
    }

    override fun onStop() {
        clusterSafeAreaDialog?.dismiss()
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        uiForeground = true
        deviceMonitor?.request(force = !initialLaunch)
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); expectedTickAt = android.os.SystemClock.elapsedRealtime(); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) render()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                OsnPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
        if (updater != null) {
            refreshUpdateUi()
        }
    }
    override fun onPause() {
        uiForeground = false
        expectedTickAt = null
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        updateSubscription?.close(); updateSubscription = null
        deviceMonitor?.close()
        handler.removeCallbacks(tick)
        activeDialog?.dismiss()
        activeDialog = null
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        render()
    }

    private val isCompactLayout: Boolean
        get() = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) ||
            resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    private fun render() {
        val renderStarted = android.os.SystemClock.elapsedRealtime()
        if (modernUi) {
            uiScale = OsnAppearance.size(this) / 100f
            val dark = OsnAppearance.dark(this)
            if (appliedPaletteDark != dark) {
                palette = OsnAppearance.palette(this)
                setTheme(OsnAppearance.style(this))
                window.statusBarColor = BG
                window.navigationBarColor = BG
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !palette.dark
                    isAppearanceLightNavigationBars = !palette.dark
                }
                activeDialog?.takeIf { it.isShowing }?.let(::paintDialog)
                appliedPaletteDark = dark
            }
        }
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { renderedPage == renderKey() }
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        statusBadge = null; statusDetail = null; clockLabel = null; exportButton = null
        hotspotCheck = null; permissionCheck = null; permissionBadge = null; hotspotStep = null
        hotspotWarning = null; hotspotSettingsButton = null; interfaceValue = null
        updateStatus = null; updateProgress = null; updateCheckButton = null
        updateActionButton = null; updateCancelButton = null; updateNotes = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        val compact = isCompactLayout
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column().apply {
            if (compact) setPadding(dp(12), dp(10), dp(12), dp(12))
            else setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        scroll.addView(content)
        if (modernUi) {
            renderModern(scroll, content)
            finishRender(scroll, previousScrollY)
            uiRenderCount++
            uiMaxRenderMillis = maxOf(uiMaxRenderMillis, android.os.SystemClock.elapsedRealtime() - renderStarted)
            return
        }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = getString(R.string.carplay)
            },
            LinearLayout.LayoutParams(if (compact) dp(24) else dp(36), if (compact) dp(24) else dp(36)),
        )
        header.addView(
            label(getString(R.string.osnplay), if (compact) 18 else 26, TEXT, true).apply {
                setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, if (compact) dp(36) else dp(56), 1f),
        )
        if (page != "home" || !compact) {
            header.addView(
                button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
                    if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    else { page = "home"; render() }
                },
                LinearLayout.LayoutParams(if (compact) dp(80) else dp(130), if (compact) dp(36) else dp(56)),
            )
        }
        content.addView(header)
        content.addView(space(if (compact) 8 else 24))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        finishRender(scroll, previousScrollY)
    }

    private fun renderKey() = if (modernUi && page == "settings") "$page:$settingsCategory" else page

    private fun finishRender(scroll: ScrollView, previousScrollY: Int?) {
        renderedPage = renderKey()
        if (modernUi) updateDeviceIndicators()
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    private val settingsCategories get() = listOf(
        Triple("appearance", R.string.osn_appearance, R.drawable.ic_osn_theme),
        Triple("display", R.string.osn_display, R.drawable.ic_dp_display),
        Triple("audio", R.string.osn_audio, R.drawable.ic_dp_connection),
        Triple("connection", R.string.osn_connection, R.drawable.ic_dp_automation),
        Triple("diagnostics", R.string.osn_diagnostics, R.drawable.ic_dp_diagnostics),
    ) + if (updatesEnabled) listOf(Triple("updates", R.string.osn_updates, R.drawable.ic_dp_about)) else emptyList()

    private fun navigate(destination: String, category: String? = null) {
        page = destination
        category?.let { settingsCategory = it }
        render()
    }

    private fun uiIcon(resource: Int, size: Int = 24, tint: Int = ACCENT) = ImageView(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun navButton(title: String, icon: Int, selected: Boolean, action: () -> Unit) = button(title, false, action).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 17f * uiScale; maxLines = 2
        setTextColor(if (selected) ACCENT else MUTED)
        val drawable = getDrawable(icon)?.mutate()?.apply {
            setTint(if (selected) ACCENT else MUTED); setBounds(0, 0, dp(22), dp(22))
        }
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(12)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((ACCENT and 0xFFFFFF) or 0x22000000),
            rounded(if (selected) palette.tint else BG, if (selected) palette.tint else BG), null)
        isSelected = selected
    }

    private fun renderModern(scroll: ScrollView, content: LinearLayout) {
        val wide = wideUi()
        content.setPadding(dp(28), 0, dp(28), dp(28))
        val shell = if (wide) row() else column()
        shell.tag = "osn_ui_root"
        shell.setBackgroundColor(BG)
        val navigation = column().apply { setPadding(dp(18), dp(24), dp(18), dp(20)) }
        val logo = ImageView(this).apply {
            setImageResource(applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_carplay)
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = getString(R.string.app_name)
        }
        if (wide) {
            navigation.addView(logo, LinearLayout.LayoutParams(dp(60), dp(36)))
            navigation.addView(label(getString(R.string.app_name), 25, TEXT, true).apply { setPadding(0, dp(12), 0, 0) })
            navigation.addView(label("CARPLAY RECEIVER", 10, MUTED).apply { letterSpacing = .08f; setPadding(0, dp(7), 0, dp(32)) })
        }
        val nav = if (wide) column() else row()
        for ((key, title, icon) in listOf(Triple("home", R.string.osn_home, R.drawable.ic_osn_home),
            Triple("connection", R.string.osn_connection_page, R.drawable.ic_dp_connection),
            Triple("settings", R.string.settings, R.drawable.ic_dp_display))) {
            val caption = getString(title)
            nav.addView(navButton(caption, icon, page == key || page == "about" && key == "settings") { navigate(key) }
                .apply { tag = "nav-$key" }, if (wide) matchButton(8, 64) else LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        navigation.addView(nav)
        if (wide) {
            navigation.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            navigation.addView(navButton(getString(R.string.car_home), R.drawable.ic_osn_home, false) {
                openSystem(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }.apply { textSize = 14f * uiScale; compoundDrawablePadding = dp(8); setPadding(dp(10), 0, dp(10), 0) }, matchButton(8, 56))
            navigation.addView(label("${getString(R.string.app_name)} ${version()}", 11, MUTED).apply { setPadding(dp(12), dp(16), 0, 0) })
            shell.addView(navigation, LinearLayout.LayoutParams(dp(156), -1))
            shell.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(dp(1), -1))
        } else shell.addView(navigation)

        val workspace = column()
        val topbar = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(10), dp(28), dp(10)) }
        val pageName = when (page) { "settings" -> R.string.settings; "connection" -> R.string.osn_connection_page; "about" -> R.string.about; else -> R.string.osn_home }
        topbar.addView(label("${getString(R.string.app_name)} / ${getString(pageName)}", 14, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        topbar.addView(label(getString(OsnAppearance.mode(this).label), 13, MUTED).apply { setPadding(dp(10), 0, dp(18), 0) })
        clockLabel = label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 19, TEXT, true).also(topbar::addView)
        if (!wide) topbar.addView(button(getString(R.string.car_home), false) {
            openSystem(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }, LinearLayout.LayoutParams(dp(110), dp(52)))
        workspace.addView(topbar, LinearLayout.LayoutParams(-1, dp(if (wide) 70 else 64)))

        if (page == "settings") {
            val heading = column().apply { setPadding(dp(28), 0, dp(28), dp(22)) }
            pageHeading(heading, getString(R.string.settings), getString(R.string.osn_settings_hint))
            workspace.addView(heading)
            val layout = if (wide) row() else column()
            val categories = if (wide) column() else row()
            categories.setPadding(dp(if (wide) 24 else 12), 0, 0, 0)
            for ((key, title, icon) in settingsCategories) categories.addView(
                navButton(getString(title), icon, settingsCategory == key) { settingsCategory = key; render() }
                    .apply { tag = "category-$key" }, if (wide) matchButton(6, 62) else LinearLayout.LayoutParams(dp(175), dp(56)))
            if (wide) layout.addView(ScrollView(this).apply { addView(categories) }, LinearLayout.LayoutParams(dp(205), -1))
            else layout.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categories) })
            content.setPadding(dp(20), 0, dp(28), dp(24))
            layout.addView(scroll, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
            workspace.addView(layout, LinearLayout.LayoutParams(-1, 0, 1f))
            settings(content)
            content.addView(label(getString(R.string.osn_settings_saved), 13, MUTED))
        } else {
            workspace.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            when (page) { "connection" -> modernConnection(content); "about" -> about(content); else -> modernHome(content) }
        }
        shell.addView(workspace, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)
    }

    private fun pageHeading(parent: LinearLayout, title: String, hint: String) {
        parent.addView(label(title, 32, TEXT, true))
        parent.addView(label(hint, 16, MUTED).apply { setPadding(0, dp(7), 0, dp(22)) })
    }

    private fun twoColumns(parent: LinearLayout, first: View, second: View, ratio: Float = 1.5f) {
        if (wideUi()) parent.addView(row().apply {
            gravity = Gravity.TOP
            addView(first, LinearLayout.LayoutParams(0, -2, ratio))
            addView(View(this@OsnPlayActivity), LinearLayout.LayoutParams(dp(20), 1))
            addView(second, LinearLayout.LayoutParams(0, -1, 1f))
        }) else {
            parent.addView(first); parent.addView(space(18)); parent.addView(second)
        }
    }

    private fun badge(text: String, ready: Boolean = true) = label(text, 13, if (ready) ACCENT else WARNING, true).apply {
        background = rounded(palette.tint, palette.tint)
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }

    internal open fun uiDeviceSource(context: Context): () -> OsnUiDeviceSnapshot {
        val app = context.applicationContext
        return { OsnUiDeviceSnapshot.read(app) }
    }

    private fun TextView.updateText(value: CharSequence?) {
        if (text.toString() != value?.toString().orEmpty()) text = value
    }

    /** Update only existing labels; a background result must not rebuild a tab or editor. */
    private fun updateDeviceIndicators() {
        if (!modernUi) return
        val known = deviceMonitor?.latest != null
        val configured = hotspotConfigured()
        hotspotCheck?.apply {
            updateText(if (!known) getString(R.string.osn_reading_status)
                else "${if (configured) "✓" else "○"} ${getString(if (configured) R.string.osn_hotspot_saved else R.string.osn_hotspot_missing)}")
            setTextColor(if (!known || configured) MUTED else WARNING)
        }
        val permissions = requiredPermissionsReady(if (page == "connection") connectionWireless else true)
        permissionCheck?.apply {
            updateText(if (!known) getString(R.string.osn_reading_status)
                else "${if (permissions) "✓" else "○"} ${getString(if (permissions) R.string.osn_permissions_ready else R.string.osn_permissions_missing)}")
            setTextColor(if (!known || permissions) MUTED else WARNING)
        }
        permissionBadge?.apply {
            updateText(getString(if (!known) R.string.osn_reading_status else if (permissions) R.string.osn_permissions_ready else R.string.osn_permissions_missing))
            setTextColor(if (!known) MUTED else if (permissions) ACCENT else WARNING)
        }
        hotspotStep?.updateText("${if (configured && known) "✓" else "1"} ${getString(R.string.osn_step_hotspot)}")
        val showWarning = carHotspotOff()
        hotspotWarning?.visibility = if (showWarning) View.VISIBLE else View.GONE
        hotspotSettingsButton?.visibility = if (showWarning) View.VISIBLE else View.GONE
        interfaceValue?.updateText("${interfaceDisplaySummary()}  ›")
    }

    private fun interfaceDisplaySummary(): String {
        if (modernUi && deviceMonitor?.latest == null) return getString(R.string.osn_reading_status)
        val required = requiresManualHotspotInterface()
        val saved = AirPlayPersistence.loadManualHotspotInterface(this)
        return saved?.takeIf { !required || availableManualHotspotInterfaces().any { network -> network.name == it } }
            ?: getString(if (required) R.string.hotspot_network_pick_interface else R.string.auto)
    }

    private fun phoneChosen() = OsnPlayPreferences.phoneAddress(this) != null
    private fun requiredPermissionsReady(wireless: Boolean = true): Boolean {
        val permissions = buildList {
            if (resources.getBoolean(R.bool.config_require_microphone_permission)) add(Manifest.permission.RECORD_AUDIO)
            if (AirPlayPersistence.loadLocationReportingEnabled(this@OsnPlayActivity)) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (wireless) addAll(when {
                AirPlayPersistence.loadWirelessHotspotMode(this@OsnPlayActivity) == WirelessHotspotMode.EXISTING_WIFI ->
                    if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
                Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
                Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            })
        }
        return if (modernUi) permissions.all { it in deviceMonitor?.latest?.grantedPermissions.orEmpty() }
            else permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun hotspotConfigured(): Boolean {
        if (pendingCarHotspotSetup) return false
        if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.EXISTING_WIFI) {
            return hotspotError(AirPlayPersistence.loadExistingWifiSsid(this), AirPlayPersistence.loadExistingWifiPassphrase(this)) == null
        }
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return true
        return !pendingCarHotspotSetup && hotspotError(storedSsid(), storedPassword()) == null &&
            ManualHotspotSelection.error(requiresManualHotspotInterface(), true, WirelessHotspotMode.MANUAL,
                AirPlayPersistence.loadManualHotspotInterface(this), availableManualHotspotInterfaces()) == null
    }

    private fun selectedPhoneName() = if (phoneChosen()) OsnPlayPreferences.phoneName(this) else getString(R.string.osn_no_device)

    private fun modernHome(parent: LinearLayout) {
        pageHeading(parent, getString(R.string.osn_home_title), getString(R.string.osn_home_hint))
        val hero = card().apply { tag = "home-connection" }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply { letterSpacing = .1f }, LinearLayout.LayoutParams(0, -2, 1f))
        statusBadge = badge("").also(header::addView)
        hero.addView(header)
        status = label(getString(R.string.osn_ready_title), 32, TEXT, true).apply { setPadding(0, dp(24), 0, dp(10)) }
        hero.addView(status)
        statusDetail = label(getString(R.string.osn_home_description), 17, MUTED)
        hero.addView(statusDetail)
        val checks = row().apply { setPadding(0, dp(22), 0, dp(24)) }
        for ((ready, title) in listOf(hotspotConfigured() to R.string.osn_hotspot_saved,
            phoneChosen() to R.string.osn_phone_saved, requiredPermissionsReady() to R.string.osn_permissions_ready)) {
            val caption = if (ready) title else when (title) {
                R.string.osn_hotspot_saved -> R.string.osn_hotspot_missing
                R.string.osn_phone_saved -> R.string.osn_device_missing
                else -> R.string.osn_permissions_missing
            }
            val check = label("${if (ready) "✓" else "○"} ${getString(caption)}", 12, if (ready) MUTED else WARNING)
            if (title == R.string.osn_hotspot_saved) hotspotCheck = check
            if (title == R.string.osn_permissions_ready) permissionCheck = check
            checks.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
        }
        hero.addView(checks)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(true)
        }.apply { tag = "home-connect" }
        val actions = row()
        actions.addView(connectButton, LinearLayout.LayoutParams(0, dp(64), 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        actions.addView(button(getString(R.string.osn_usb), false) { connectionWireless = false; navigate("connection") }, LinearLayout.LayoutParams(dp(136), dp(64)))
        hero.addView(actions)
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        hero.addView(disconnectButton, matchButton(12, 56))
        hotspotWarning = label(getString(R.string.msg_car_hotspot_off, storedSsid()), 14, WARNING).apply { setPadding(0, dp(14), 0, 0); visibility = View.GONE }
        hero.addView(hotspotWarning)
        hotspotSettingsButton = button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }.apply { visibility = View.GONE }
        hero.addView(hotspotSettingsButton, matchButton(12, 56))
        val device = card()
        device.addView(label(getString(R.string.osn_my_device), 22, TEXT, true))
        val info = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(26), 0, dp(28)) }
        info.addView(uiIcon(R.drawable.ic_osn_phone, 72), LinearLayout.LayoutParams(dp(72), dp(104)))
        val names = column().apply { setPadding(dp(16), 0, 0, 0) }
        names.addView(label(selectedPhoneName(), 23, TEXT, true))
        names.addView(label(getString(if (phoneChosen()) R.string.osn_device_saved else R.string.osn_device_missing), 14, MUTED).apply { setPadding(0, dp(10), 0, 0) })
        info.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        device.addView(info)
        device.addView(button(getString(R.string.osn_change_device), false) { choosePhone() }, matchButton(8, 56))
        twoColumns(parent, hero, device)
        parent.addView(space(20))
        val quick = if (wideUi()) row() else column()
        val items = listOf(Triple(R.string.osn_quick_connection, R.string.osn_quick_connection_hint, R.drawable.ic_dp_connection),
            Triple(R.string.osn_quick_appearance, R.string.osn_quick_appearance_hint, R.drawable.ic_osn_theme),
            Triple(R.string.osn_quick_diagnostics, R.string.osn_quick_diagnostics_hint, R.drawable.ic_dp_diagnostics))
        items.forEachIndexed { index, (title, hint, icon) ->
            val item = card().apply {
                setPadding(dp(18), dp(18), dp(18), dp(18)); isClickable = true; isFocusable = true
                contentDescription = getString(title); tag = "quick-$index"
                addView(uiIcon(icon))
                addView(label(getString(title), 17, TEXT, true).apply { setPadding(0, dp(12), 0, dp(5)) })
                addView(label(getString(hint), 12, MUTED))
                setOnClickListener { when (index) { 0 -> navigate("connection"); 1 -> navigate("settings", "appearance"); else -> navigate("settings", "diagnostics") } }
            }
            if (quick.orientation == LinearLayout.HORIZONTAL) {
                if (index > 0) quick.addView(View(this), LinearLayout.LayoutParams(dp(16), 1))
                quick.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            } else quick.addView(item, matchButton(if (index > 0) 12 else 0, 120))
        }
        parent.addView(quick)
        parent.addView(label(getString(R.string.osn_home_setup_note), 13, MUTED).apply { setPadding(0, dp(20), 0, 0) })
    }

    private fun editModernHotspot() = askHotspotCredentials { name, password ->
        saveHotspotCredentials(name, password)
        pendingCarHotspotSetup = false
        applyWirelessLink(WirelessHotspotMode.MANUAL)
    }

    private fun modernConnection(parent: LinearLayout) {
        val heading = column()
        pageHeading(heading, getString(R.string.connect_phone), getString(R.string.osn_connection_hint))
        val transports = row().apply { setPadding(0, 0, 0, dp(20)) }
        for ((wireless, label) in listOf(true to R.string.osn_wireless, false to R.string.osn_usb)) {
            transports.addView(button(getString(label), connectionWireless == wireless) { connectionWireless = wireless; render() }
                .apply { tag = if (wireless) "transport-wireless" else "transport-usb"; textSize = 15f * uiScale; isSelected = connectionWireless == wireless }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (!wireless) marginStart = dp(8) })
        }
        if (wideUi()) parent.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            addView(transports, LinearLayout.LayoutParams(dp(255), -2))
        }) else { parent.addView(heading); parent.addView(transports) }
        val steps = row().apply { setPadding(0, 0, 0, dp(20)) }
        val stepTitles = if (connectionWireless) listOf(R.string.osn_step_hotspot, R.string.osn_step_device, R.string.osn_step_start)
            else listOf(R.string.osn_step_usb, R.string.osn_step_unlock, R.string.osn_step_start)
        for ((index, title) in stepTitles.withIndex()) {
            val ready = connectionWireless && (index == 0 && hotspotConfigured() || index == 1 && phoneChosen())
            val step = badge("${if (ready) "✓" else (index + 1).toString()} ${getString(title)}", true)
            if (connectionWireless && index == 0) hotspotStep = step
            steps.addView(step, LinearLayout.LayoutParams(0, dp(56), 1f).apply { if (index > 0) marginStart = dp(12) })
        }
        parent.addView(steps)
        val config = card()
        if (connectionWireless) {
            val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
            val manual = mode == WirelessHotspotMode.MANUAL
            val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
            val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
            config.addView(preferenceRow(titles[modes.indexOf(mode).coerceAtLeast(0)], getString(R.string.osn_change)) {
                dialogBuilder().setTitle(R.string.s_1_choose_your_connection)
                    .setSingleChoiceItems(titles.toTypedArray(), modes.indexOf(mode)) { dialog, index ->
                        dialog.dismiss()
                        if (modes[index] == WirelessHotspotMode.MANUAL) { pendingCarHotspotSetup = true; render() }
                        else if (modes[index] == WirelessHotspotMode.EXISTING_WIFI) {
                            askHotspotCredentials(existingWifi = true) { ssid, password ->
                                AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                                pendingCarHotspotSetup = false
                                applyWirelessLink(modes[index])
                            }
                        }
                        else { pendingCarHotspotSetup = false; applyWirelessLink(modes[index]) }
                    }.setNegativeButton(R.string.cancel, null).show()
            })
            if (manual) {
                config.addView(preferenceRow(getString(R.string.osn_hotspot_name), storedSsid().ifBlank { getString(R.string.osn_not_saved) }) { editModernHotspot() })
                config.addView(preferenceRow(getString(R.string.osn_hotspot_password), if (storedPassword().isNotEmpty()) "••••••••" else getString(if (storedSsid().isEmpty()) R.string.osn_not_saved else R.string.osn_open_hotspot)) { editModernHotspot() })
                manualHotspotNetworkControls(config)
                val actions = row().apply { setPadding(0, dp(16), 0, 0) }
                actions.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }.apply { textSize = 15f * uiScale }, LinearLayout.LayoutParams(0, dp(56), 1f))
                actions.addView(button(getString(R.string.osn_edit_hotspot), false) { editModernHotspot() }.apply { textSize = 15f * uiScale }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(10) })
                config.addView(actions)
                config.addView(label(getString(R.string.osn_interface_note), 13, MUTED).apply { setPadding(0, dp(14), 0, 0) })
            } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
                config.addView(label(getString(R.string.existing_wifi_instructions), 15, MUTED).apply { setPadding(0, dp(16), 0, dp(14)) })
                config.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 56))
                config.addView(button(getString(R.string.existing_wifi_details), false) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        render()
                    }
                }, matchButton(12, 56))
            } else {
                config.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 15, MUTED).apply { setPadding(0, dp(16), 0, dp(14)) })
                wifiDirectChannelControl(config)
                config.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 56))
            }
        } else {
            config.addView(label(getString(R.string.connect_with_usb), 22, TEXT, true))
            config.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 17, MUTED).apply { setPadding(0, dp(20), 0, dp(20)) })
            config.addView(label(getString(R.string.osn_interface_note), 14, MUTED))
        }
        val device = card()
        device.addView(label(getString(R.string.osn_devices_permissions), 22, TEXT, true))
        device.addView(label(if (connectionWireless) selectedPhoneName() else getString(R.string.osn_step_unlock), 23, TEXT, true).apply {
            setPadding(0, dp(22), 0, dp(14)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // Keep the primary action above secondary permission/settings controls at large UI sizes.
        connectButton = button(getString(R.string.connect_phone), true) { connect(connectionWireless) }.apply { tag = "connection-connect" }
        device.addView(connectButton, matchButton(12, 64))
        if (connectionWireless) device.addView(button(getString(R.string.osn_change_device), false) { choosePhone() }, matchButton(12, 56))
        val permissionsReady = requiredPermissionsReady(connectionWireless)
        permissionBadge = badge(getString(if (permissionsReady) R.string.osn_permissions_ready else R.string.osn_permissions_missing), permissionsReady)
        device.addView(permissionBadge, matchButton(16, 42))
        device.addView(button(getString(R.string.review_app_permissions), false) {
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, matchButton(12, 56))
        twoColumns(parent, config, device)
    }

    private fun settingsGroup(title: String): String = when (title) {
        getString(R.string.osn_theme_title), getString(R.string.osn_ui_preferences), getString(R.string.carplay_controls), getString(R.string.language_section_title) -> "appearance"
        getString(R.string.display_and_performance) -> "display"
        getString(R.string.audio_routing) -> "audio"
        getString(R.string.osn_updates) -> "updates"
        getString(R.string.diagnostics), getString(R.string.permissions_and_connection_help), getString(R.string.about) -> "diagnostics"
        else -> "connection"
    }

    private fun appearanceSettings(parent: LinearLayout) {
        section(parent, getString(R.string.osn_theme_title), R.drawable.ic_osn_theme) { card ->
            val options = row()
            for ((index, mode) in OsnAppearance.Mode.entries.withIndex()) {
                val selected = OsnAppearance.mode(this) == mode
                val tile = column().apply {
                    isClickable = true; isFocusable = true; isSelected = selected
                    tag = "theme-${mode.name.lowercase(Locale.ROOT)}"; contentDescription = getString(mode.label)
                    background = rounded(if (selected) palette.tint else BG, if (selected) ACCENT else BORDER)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    val mini = row().apply { background = rounded(if (mode == OsnAppearance.Mode.LIGHT) Color.rgb(237, 242, 237) else Color.rgb(27, 37, 30), BORDER); setPadding(dp(8), dp(8), dp(8), dp(8)) }
                    mini.addView(View(this@OsnPlayActivity).apply { setBackgroundColor(ACCENT) }, LinearLayout.LayoutParams(dp(13), -1))
                    mini.addView(View(this@OsnPlayActivity), LinearLayout.LayoutParams(dp(6), 1))
                    mini.addView(View(this@OsnPlayActivity).apply {
                        background = if (mode == OsnAppearance.Mode.SYSTEM) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.WHITE, Color.rgb(36, 49, 40)))
                            else android.graphics.drawable.ColorDrawable(if (mode == OsnAppearance.Mode.LIGHT) Color.WHITE else Color.rgb(51, 66, 55))
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                    addView(mini, LinearLayout.LayoutParams(-1, dp(44)))
                    addView(label("${getString(mode.label)}${if (selected) "  ✓" else ""}", 16, TEXT, true).apply { setPadding(0, dp(12), 0, 0) })
                    setOnClickListener { OsnAppearance.save(this@OsnPlayActivity, mode); render() }
                }
                options.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(12) })
            }
            card.addView(options)
            card.addView(label(getString(R.string.osn_theme_hint), 13, MUTED).apply { setPadding(0, dp(16), 0, 0) })
        }
        section(parent, getString(R.string.osn_ui_preferences)) { card ->
            choice(card, getString(R.string.osn_ui_size), OsnAppearance.sizes.map { "$it%" },
                OsnAppearance.sizes.indexOf(OsnAppearance.size(this)), reconnects = false) {
                OsnAppearance.saveSize(this, OsnAppearance.sizes[it]); render()
            }
            card.addView(label(getString(R.string.osn_ui_size_hint), 13, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
            card.addView(preferenceRow(getString(R.string.language_app_language), AppLocale.displayName(this, AppLocale.preference(this))) { AppLocale.showPicker(this, dialogBuilder()) })
            val modes = SettingsShortcut.Mode.entries
            choice(card, getString(R.string.osn_shortcut_title), modes.map { getString(it.label) },
                modes.indexOf(SettingsShortcut.mode(this)), reconnects = false) {
                SettingsShortcut.save(this, modes[it]); render()
            }
            toggle(card, getString(R.string.osn_shortcut_show_button), getString(R.string.osn_shortcut_hint), SettingsShortcut.button(this), enabled = SettingsShortcut.mode(this) != SettingsShortcut.Mode.BUTTON_ONLY) { SettingsShortcut.button(this, it) }
        }
    }

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        if (compact) {
            val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            status = label(getString(R.string.ready_when_you_are), 16, TEXT, true).apply {
                setPadding(0, 0, 0, dp(8))
            }
            card.addView(status)
            connectButton = button(getString(R.string.connect_phone), true) {
                if (CarPlayBackgroundSession.hasSession()) openProjection()
                else connect(true)
            }
            card.addView(connectButton, matchButton(0, 44))

            val buttonRow = row().apply {
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val usbBtn = button(getString(R.string.connect_with_usb), false) { connect(false) }
            val settingsBtn = button(getString(R.string.settings), false) { page = "settings"; render() }
            buttonRow.addView(usbBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            buttonRow.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(buttonRow)

            disconnectButton = button(getString(R.string.disconnect), false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.apply { visibility = View.GONE }
            card.addView(disconnectButton, matchButton(8, 38))

            content.addView(card)
            setupError?.let { content.addView(label(it, 13, WARNING).apply { setPadding(0, dp(6), 0, 0) }) }
            return
        }

        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label(getString(R.string.a_familiar_drive), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo), 19, MUTED))
        val card = card()
        card.addView(label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            card.addView(label(hotspotResultText(startupProblem), 15, WARNING))
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        } else if (carHotspotOff()) {
            card.addView(label(getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.make_osnplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${getString(R.string.home_public_preview)}${version()}", 12, MUTED).apply { letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    private fun settings(content: LinearLayout) {
        if (updatesEnabled) section(content, getString(R.string.osn_updates), R.drawable.ic_dp_about) { updateControls(it) }
        if (!modernUi) {
            content.addView(label(getString(R.string.your_drive_your_way), 34, TEXT, true))
            content.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        } else if (settingsCategory == "appearance") appearanceSettings(content)
        if (!modernUi) section(content, getString(R.string.carplay_controls), R.drawable.ic_dp_display) { card ->
            val gestureFingers = listOf(2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false) { page = "connection"; render() }, matchButton(12, 60))
        }
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_osnplay) else getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_osnplay_opens), getString(R.string.use_your_last_connection_type_and_selected_iphone), OsnPlayPreferences.autoConnect(this)) { OsnPlayPreferences.saveAutoConnect(this, it) }
            if (bydFeatures) adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            else toggle(card, getString(R.string.open_after_the_car_starts), getString(R.string.availability_depends_on_your_head_unit_s_startup_settings), AirPlayPersistence.loadAutoStartOnBoot(this)) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                UsbAutoConfirmService.isEnabled(this),
            ) {
                UsbAutoConfirmService.openSettings(this)
            }
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${OsnPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        if (!modernUi || settingsCategory == "connection") bydAdbSettings(content)
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            val nightModes = CarPlayNightMode.entries
            choice(
                card,
                getString(R.string.carplay_night_mode),
                listOf(
                    getString(R.string.carplay_night_system),
                    getString(R.string.carplay_night_ambient),
                    getString(R.string.carplay_night_day),
                    getString(R.string.carplay_night_night),
                ),
                nightModes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
            }
            card.addView(label(getString(R.string.carplay_night_hint), 14, MUTED))
            card.addView(label(getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(card)
            nightDelaySettingControl(card, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(button(getString(R.string.picture_adjustments), false) {
                startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            resolutionSettingControl(card, R.string.resolution, R.string.custom_resolution_hint,
                30..100, 100, R.string.custom_resolution_summary, { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) })
            if (!modernUi) {
                val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
                choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                    bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                    AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
                }
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            if (!modernUi) addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it) },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it) },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            toggle(card, getString(R.string.adapt_pip_resolution), getString(R.string.adapt_pip_resolution_description), AirPlayPersistence.loadAdaptPipResolution(this)) {
                AirPlayPersistence.saveAdaptPipResolution(this, it)
            }
        }
        section(content, getString(R.string.audio_routing)) { card ->
            toggle(card, getString(R.string.osn_early_focus), getString(R.string.osn_early_focus_hint), AirPlayPersistence.loadEarlyMediaFocus(this)) {
                AirPlayPersistence.saveEarlyMediaFocus(this, it)
            }
            if (modernUi) card.addView(button(getString(R.string.osn_audio_diagnostics), false) {
                val state = CarPlayBackgroundSession.snapshot()?.controller?.audioDiagnosticReport() ?: "no_active_session"
                dialogBuilder().setTitle(R.string.osn_audio_diagnostics).setMessage(getString(R.string.osn_audio_diagnostics_hint) + "\n\n" + state + "\nAndroid outputTypes=" + deviceMonitor?.latest?.audioOutputTypes.orEmpty())
                    .setPositiveButton(R.string.got_it, null).show()
            }, matchButton(0, 56))
            if (modernUi) {
                val presets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
                choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                    presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                    AirPlayPersistence.saveMediaBufferMillis(this, presets[it])
                }
            }
            toggle(card, getString(R.string.osn_media_sync), getString(R.string.osn_media_sync_description),
                AirPlayPersistence.loadSystemMediaSyncEnabled(this)) { AirPlayPersistence.saveSystemMediaSyncEnabled(this, it) }
            toggle(card, getString(R.string.osn_microphone_compatibility), getString(R.string.osn_microphone_compatibility_description),
                AirPlayPersistence.loadStandardMicrophoneInput(this)) { AirPlayPersistence.saveStandardMicrophoneInput(this, it) }
            val microphoneGranted = if (modernUi) Manifest.permission.RECORD_AUDIO in deviceMonitor?.latest?.grantedPermissions.orEmpty()
                else checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            card.addView(label(getString(if (microphoneGranted) R.string.osn_microphone_granted else R.string.osn_microphone_required), 14, if (microphoneGranted) MUTED else WARNING))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 56))
            toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
        }
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
            if (bydFeatures) card.addView(button(getString(if (bydVehicleAdvancedExpanded)
                R.string.hide_advanced_vehicle_data else R.string.advanced_vehicle_data), false) {
                bydVehicleAdvancedExpanded = !bydVehicleAdvancedExpanded
                render()
            }, matchButton(12, 56))
            if (bydFeatures && bydVehicleAdvancedExpanded) {
                advancedVehicleData(card)
                // Dashboard song needs ADB, not the navigation receiver; show it here when that card is hidden.
                if (!BydOutputSettings.available(this)) clusterSongSwitch(card)
            }
        }
        // Cluster video does not require a BYD navigation broadcast receiver.
        if (bydFeatures && resources.getBoolean(R.bool.config_cluster_map_available)) section(content, getString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.adb_cluster_activity_mode),
                getString(R.string.adb_cluster_activity_description), AirPlayPersistence.loadAdbClusterEnabled(this)) {
                AirPlayPersistence.saveAdbClusterEnabled(this, it)
                ClusterActivityOutput.stopForSettings()
                render()
                reconnectForClusterMap()
            }
            val adbCluster = AdbClusterRouter.enabled(this)
            if (adbCluster) {
                card.addView(button(getString(R.string.adb_cluster_authorize), false) { authorizeClusterRouting() }, matchButton(10, 56))
                card.addView(button(getString(R.string.adb_cluster_open), false) { ClusterActivityOutput.retry() }, matchButton(10, 56))
            }
            if (adbCluster && com.shilapi.xcertplay.hud.BydOemClusterNavi.applicable(this)) {
                val holds = com.shilapi.xcertplay.hud.BydOemClusterHold.entries
                card.addView(label(getString(R.string.oem_cluster_map_description), 14, MUTED))
                choice(card, getString(R.string.oem_cluster_map), holds.map { it.localizedLabel(this) },
                    holds.indexOf(BydOutputSettings.oemClusterHold(this))) { index ->
                    BydOutputSettings.setOemClusterHold(this, holds[index])
                    ClusterActivityOutput.stopForSettings()
                    reconnectForClusterMap()
                }
            }
            val clusterDisplay = ClusterMapPresentation.findDisplay(this)
            val clusterSize = clusterDisplay?.let { ClusterMapPresentation.sizeOf(it) }
            val diLink4 = adbCluster || (clusterDisplay != null && clusterSize != null &&
                DiLink4ClusterDisplay.matches(clusterDisplay.name, clusterSize.x, clusterSize.y))
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                if (clusterDisplay != null || adbCluster) getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c)
                else getString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
                reconnectForClusterMap()
            }
            if (clusterMapEnabled) {
                toggle(card, getString(R.string.center_map_card),
                    if (clusterDisplay != null || adbCluster) getString(R.string.center_map_card_description)
                    else getString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, getString(R.string.center_map_auto_hide), getString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_auto_hide_active)
                        else getString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                }
                if (clusterDisplay != null || adbCluster) {
                    if (DiLink51ClusterLayout.supported() && !adbCluster) {
                        val automatic = DiLink51ClusterLayout.automatic(this)
                        toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                            getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                            DiLink51ClusterLayout.saveAutomatic(this, it)
                            render()
                            reconnectForClusterMap()
                        }
                        val allowed = DiLink51ClusterMonitor.hasAccess(this)
                        card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                            else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                        card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                        if (!automatic) {
                            val themes = DiLink51ClusterLayout.Theme.entries
                            choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                                DiLink51ClusterLayout.saveTheme(this, themes[it])
                                reconnectForClusterMap()
                            }
                            card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                        }
                        val contrasts = DiLink51ClusterLayout.Contrast.entries
                        choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                            DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                            reconnectForClusterMap()
                        }
                    } else {
                        if (diLink4) clusterSafeAreaControls(card)
                        val sizes = CarPlayClusterDisplay.scalePresets
                        val contents = CarPlayClusterDisplay.Content.entries
                        val content = AirPlayPersistence.loadClusterContent(this)
                        val customCard = CarPlayClusterDisplay.usesCustomTurnCard(content)
                        val officialCardOnly = content == CarPlayClusterDisplay.Content.TURN_CARD
                        choice(card, getString(R.string.dashboard_shows), listOf(
                            getString(R.string.dashboard_content_map),
                            getString(R.string.dashboard_content_turn_card),
                            getString(R.string.dashboard_content_map_with_turn_card),
                            getString(R.string.dashboard_content_map_with_custom_turn_card),
                        ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                            val next = contents[it]
                            AirPlayPersistence.saveClusterContent(this, next)
                            render()
                            if (content.url != next.url) reconnectForClusterMap()
                        }
                        if (customCard) {
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_size),
                                ClusterTurnCardOverlay.sizePercents,
                                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
                            ) { it -> getString(R.string.turn_card_overlay_size_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_opacity),
                                ClusterTurnCardOverlay.opacityPercents,
                                AirPlayPersistence.loadClusterTurnCardOpacityPercent(this),
                            ) { it -> getString(R.string.turn_card_overlay_opacity_option, it) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOpacityPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_horizontal),
                                ClusterTurnCardOverlay.xPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_left), getString(R.string.marker_right), 50) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, v) } })
                            card.addView(overlaySliderRow(
                                getString(R.string.turn_card_overlay_vertical),
                                ClusterTurnCardOverlay.yPercents,
                                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                            ) { it -> overlayOffsetLabel(it, getString(R.string.marker_up), getString(R.string.marker_down), 40) }
                                .also { it.onSave = { v -> AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, v) } })
                            card.addView(button(getString(R.string.reset_turn_card_overlay), false) {
                                AirPlayPersistence.saveClusterTurnCardOverlayXPercent(this, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
                                AirPlayPersistence.saveClusterTurnCardOverlayYPercent(this, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT)
                                render()
                            }, matchButton(10, 56))
                            card.addView(label(getString(R.string.turn_card_overlay_note), 14, MUTED))
                        }
                        val turnCard = officialCardOnly
                        if (!diLink4) {
                            choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                                listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                                sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                            }
                        }
                        if (!diLink4 || AirPlayPersistence.loadClusterSafeAreaRect(this) == null) {
                            val across = CarPlayClusterDisplay.horizontalSteps.toList()
                            choice(card, getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal), across.map { markerStepLabel(it, getString(R.string.marker_left), getString(R.string.marker_right)) },
                                across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                            }
                            val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                            choice(card, getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical), upDown.map { markerStepLabel(it, getString(R.string.marker_up), getString(R.string.marker_down)) },
                                upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                            }
                            card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                                AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                                AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                                render()
                                reconnectForClusterMap()
                            }, matchButton(10, 56))
                        }
                        if (!diLink4) {
                            toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                                getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                                BydOutputSettings.clusterStreamPause(this)) {
                                BydOutputSettings.setClusterStreamPause(this, it)
                                if (it) checkAdbState(mayAsk = true)
                            }
                        }
                    }
                }
            }
        }
        if (BydOutputSettings.available(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (BydOutputSettings.standaloneHudAvailable(this)) {
                toggle(card, getString(R.string.song_on_hud), getString(R.string.song_on_hud_description),
                    BydOutputSettings.hudSong(this)) { BydOutputSettings.setHudSong(this, it) }
            }
            clusterSongSwitch(card)
        }
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_osnplay), false) { page = "about"; render() }, matchButton(0, 60))
        }
        if (!modernUi) languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.osnplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_osnplay), 16, MUTED))
        }
        if (updatesEnabled) section(content, getString(R.string.osn_updates), R.drawable.ic_dp_about) { updateControls(it) }
    }

    private fun updateControls(card: LinearLayout) {
        val manager = updater ?: return
        card.addView(label(getString(R.string.osn_update_current, version()), 18, TEXT, true))
        card.addView(label(getString(R.string.osn_update_source), 14, MUTED))
        card.addView(label(getString(R.string.osn_update_manual_hint), 14, MUTED))
        updateStatus = label("", 16, TEXT).apply { tag = "update-status" }
        card.addView(updateStatus)
        updateProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; tag = "update-progress" }
        card.addView(updateProgress, LinearLayout.LayoutParams(-1, dp(16)))
        updateCheckButton = button(getString(R.string.osn_update_check), false) { manager.check() }.apply { tag = "update-check" }
        card.addView(updateCheckButton, matchButton(12, 56))
        updateActionButton = button(getString(R.string.osn_update_download), true) {
            if (manager.state.phase == OsnUpdatePhase.READY) installUpdate() else manager.download()
        }.apply { tag = "update-action" }
        card.addView(updateActionButton, matchButton(12, 56))
        updateCancelButton = button(getString(R.string.cancel), false) { manager.cancel() }.apply { tag = "update-cancel" }
        card.addView(updateCancelButton, matchButton(12, 56))
        updateNotes = label("", 14, MUTED).apply { tag = "update-notes" }
        card.addView(updateNotes)
        card.addView(label(getString(R.string.osn_update_install_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        card.addView(button(getString(R.string.osn_update_open_releases), false) {
            openSystem(Intent(Intent.ACTION_VIEW, Uri.parse(OsnUpdateProtocol.RELEASES_URL)))
        }, matchButton(12, 56))
        refreshUpdateUi()
    }

    private fun refreshUpdateUi() {
        val state = updater?.state ?: return
        val release = state.release
        val percent = if (release == null) 0 else (state.bytes * 100 / release.apk.size).toInt().coerceIn(0, 100)
        val message = when (state.phase) {
            OsnUpdatePhase.IDLE -> getString(R.string.osn_update_idle)
            OsnUpdatePhase.CHECKING -> getString(R.string.osn_update_checking)
            OsnUpdatePhase.AVAILABLE -> getString(R.string.osn_update_available, release?.versionName.orEmpty())
            OsnUpdatePhase.LATEST -> getString(R.string.osn_update_latest)
            OsnUpdatePhase.NO_RELEASE -> getString(R.string.osn_update_no_release)
            OsnUpdatePhase.NO_PACKAGE -> getString(R.string.osn_update_no_package)
            OsnUpdatePhase.INCOMPATIBLE -> getString(R.string.osn_update_incompatible, release?.versionName.orEmpty(), release?.minSdk ?: 0)
            OsnUpdatePhase.DOWNLOADING -> getString(R.string.osn_update_downloading, release?.versionName.orEmpty(), percent)
            OsnUpdatePhase.VERIFYING, OsnUpdatePhase.PREPARING_INSTALL -> getString(R.string.osn_update_verifying)
            OsnUpdatePhase.READY -> getString(R.string.osn_update_ready)
            OsnUpdatePhase.CANCELLING -> getString(R.string.osn_update_cancelling)
            OsnUpdatePhase.CANCELLED -> getString(R.string.osn_update_cancelled)
            OsnUpdatePhase.ERROR -> getString(when (state.error) {
                OsnUpdateError.RATE_LIMIT -> R.string.osn_update_error_rate
                OsnUpdateError.INVALID_RELEASE, OsnUpdateError.INVALID_METADATA -> R.string.osn_update_error_metadata
                OsnUpdateError.PACKAGE -> R.string.osn_update_error_package
                OsnUpdateError.VERSION -> R.string.osn_update_error_version
                OsnUpdateError.SIGNATURE -> R.string.osn_update_error_signature
                OsnUpdateError.HASH -> R.string.osn_update_error_hash
                OsnUpdateError.SIZE -> R.string.osn_update_error_size
                OsnUpdateError.ANDROID_VERSION -> R.string.osn_update_error_android
                OsnUpdateError.STORAGE -> R.string.osn_update_error_storage
                OsnUpdateError.CACHE_MISSING -> R.string.osn_update_error_cache
                else -> R.string.osn_update_error_network
            })
        }
        updateStatus?.updateText(message)
        val busy = state.phase in setOf(OsnUpdatePhase.CHECKING, OsnUpdatePhase.DOWNLOADING, OsnUpdatePhase.VERIFYING, OsnUpdatePhase.PREPARING_INSTALL, OsnUpdatePhase.CANCELLING)
        updateProgress?.apply { visibility = if (busy) View.VISIBLE else View.GONE; isIndeterminate = state.phase != OsnUpdatePhase.DOWNLOADING; progress = percent }
        updateCheckButton?.isEnabled = !busy
        updateActionButton?.apply {
            visibility = if (release != null && state.phase in setOf(OsnUpdatePhase.AVAILABLE, OsnUpdatePhase.READY, OsnUpdatePhase.CANCELLED, OsnUpdatePhase.ERROR)) View.VISIBLE else View.GONE
            isEnabled = !busy
            updateText(getString(if (state.phase == OsnUpdatePhase.READY) R.string.osn_update_install else R.string.osn_update_download))
        }
        updateCancelButton?.apply {
            visibility = if (state.phase in setOf(OsnUpdatePhase.DOWNLOADING, OsnUpdatePhase.VERIFYING, OsnUpdatePhase.CANCELLING)) View.VISIBLE else View.GONE
            isEnabled = state.phase != OsnUpdatePhase.CANCELLING
        }
        updateNotes?.apply {
            visibility = if (release != null) View.VISIBLE else View.GONE
            updateText(getString(R.string.osn_update_notes) + "\n" + (release?.notes?.takeIf { it.isNotBlank() } ?: getString(R.string.osn_update_no_notes)))
        }
    }
    private fun installUpdate() {
        val manager = updater ?: return
        if (manager.state.phase != OsnUpdatePhase.READY) return
        if (!OsnUpdateInstaller.permitted(this)) {
            pendingUpdatePermission = true
            runCatching { updateInstallPermission.launch(OsnUpdateInstaller.permissionIntent(this)) }.onFailure {
                pendingUpdatePermission = false; toast(getString(R.string.osn_update_installer_missing))
            }
            return
        }
        manager.prepareInstall { file ->
            if (!uiForeground || isFinishing || isDestroyed) return@prepareInstall
            val open = {
                if (uiForeground && !isFinishing && !isDestroyed) runCatching { launchUpdateInstaller(file) }
                    .onFailure { toast(getString(R.string.osn_update_installer_missing)) }
                Unit
            }
            if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
            else open()
        }
    }
    internal open fun launchUpdateInstaller(file: File) { startActivity(OsnUpdateInstaller.installIntent(this, file)) }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            (if (modernUi) deviceMonitor?.latest?.hotspotEnabled else com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this)) == false &&
            !(CarHotspotSettings.enabled(this) && (if (modernUi) deviceMonitor?.latest?.hotspotControlPermitted == true else CarHotspotTethering.permitted(this)))

    private fun bydAdbSettings(parent: LinearLayout) {
        if (!bydFeatures) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        if (!(if (modernUi) deviceMonitor?.latest?.bydHeadUnit == true else CarHotspotSetup.isBydHeadUnit(this))) {
            Log.i("OsnPlay-Hotspot", "settings hidden: BYD head unit not detected")
            return
        }
        val controls = column().apply { visibility = View.GONE }
        bydAdbControls = controls
        parent.addView(controls)
        Thread({
            val access = runCatching { CarHotspotSetup.check(applicationContext) }
                .onFailure { Log.w("OsnPlay-Hotspot", "settings ADB check failed", it) }
                .getOrDefault(LocalAdb.Access.UNREACHABLE)
            Log.i("OsnPlay-Hotspot", "settings eligibility: byd=true adb=$access visible=${CarHotspotSettings.visible(true, access)}")
            runOnUiThread {
                if (bydAdbControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
                if (CarHotspotSettings.visible(true, access)) {
                    controls.visibility = View.VISIBLE
                    renderBydAdbControls(controls, access)
                }
            }
        }, "osnplay-hotspot-adb-check").start()
    }

    private fun renderBydAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.byd_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@OsnPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                    if (!it) startupHotspotCancelled = true
                }
            }
            adbStatus = label(getString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED).also(card::addView)
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && bydVehicleAdvancedExpanded) render()
            }
        }, "osnplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean =
        adbCheckInProgress || vehicleProbeAuthorizationInProgress || vehicleProbeInProgress

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("OsnPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "osnplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        dialogBuilder().setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${OsnPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_osnplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc),
            getString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        pendingCarHotspotSetup = false
                        applyWirelessLink(candidate)
                    }
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
            manualHotspotNetworkControls(parent)
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun manualHotspotAddressModeLabel(mode: ManualHotspotAddressMode): String = getString(when (mode) {
        ManualHotspotAddressMode.AUTO -> R.string.hotspot_network_auto
        ManualHotspotAddressMode.IPV4 -> R.string.hotspot_network_ipv4
        ManualHotspotAddressMode.IPV6 -> R.string.hotspot_network_ipv6
    })

    private fun manualHotspotNetworkControls(parent: LinearLayout) {
        val addressSummary: (ManualHotspotAddressMode) -> String = {
            getString(R.string.hotspot_network_address_summary, manualHotspotAddressModeLabel(it))
        }
        val addressControl = button(addressSummary(AirPlayPersistence.loadManualHotspotAddressMode(this)), false) {}
        addressControl.setOnClickListener {
            val modes = ManualHotspotAddressMode.entries
            val current = AirPlayPersistence.loadManualHotspotAddressMode(this)
            var selected = current
            dialogBuilder().setTitle(R.string.hotspot_network_address_title)
                .setSingleChoiceItems(modes.map(::manualHotspotAddressModeLabel).toTypedArray(), modes.indexOf(current)) { _, which ->
                    selected = modes[which]
                }
                .setPositiveButton(R.string.save) { _, _ ->
                    AirPlayPersistence.saveManualHotspotAddressMode(this, selected)
                    addressControl.text = addressSummary(selected)
                    toast(getString(R.string.saved_for_your_next_connection))
                    if (modernUi) render()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        if (modernUi) parent.addView(preferenceRow(getString(R.string.osn_address_mode),
            if (AirPlayPersistence.loadManualHotspotAddressMode(this) == ManualHotspotAddressMode.AUTO) getString(R.string.auto)
            else manualHotspotAddressModeLabel(AirPlayPersistence.loadManualHotspotAddressMode(this))) { addressControl.performClick() })
        else parent.addView(addressControl, matchButton(12, 60))
        val required = requiresManualHotspotInterface()
        val interfaceSummary: (String?) -> String = {
            val available = !required || availableManualHotspotInterfaces().any { network -> network.name == it }
            getString(R.string.hotspot_network_interface_summary,
                it?.takeIf { available } ?: getString(if (required) R.string.hotspot_network_pick_interface else R.string.auto))
        }
        val interfaceControl = button(interfaceSummary(AirPlayPersistence.loadManualHotspotInterface(this)), false) {}
        interfaceControl.setOnClickListener {
            if (modernUi && deviceMonitor?.latest == null) {
                deviceMonitor?.request(force = true)
                toast(getString(R.string.osn_reading_status))
                return@setOnClickListener
            }
            val current = AirPlayPersistence.loadManualHotspotInterface(this)
            val choices = ManualHotspotSelection.choices(availableManualHotspotInterfaces(), required)
            if (choices.isEmpty()) {
                dialogBuilder().setTitle(R.string.hotspot_network_interface_title)
                    .setMessage(R.string.hotspot_network_no_interfaces)
                    .setPositiveButton(R.string.got_it, null).show()
                return@setOnClickListener
            }
            val names = choices.map { it?.name }
            val labels = choices.map { network ->
                if (network == null) getString(R.string.auto) else {
                    val addresses = network.addresses.filterIsInstance<java.net.Inet4Address>()
                        .joinToString(", ") { it.hostAddress.orEmpty() }
                    "${network.name} · ${addresses.takeIf { it.isNotEmpty() } ?: "IPv6"}"
                }
            }
            var selected = current?.takeIf { it in names }
            val dialog = dialogBuilder().setTitle(R.string.hotspot_network_interface_title)
                .setSingleChoiceItems(labels.toTypedArray(), names.indexOf(selected)) { choiceDialog, which ->
                    selected = names[which]
                    (choiceDialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }
                .setPositiveButton(R.string.save) { _, _ ->
                    val error = ManualHotspotSelection.error(required, true, WirelessHotspotMode.MANUAL,
                        selected, availableManualHotspotInterfaces())
                    if (error != null) { toast(getString(error.messageResource)); return@setPositiveButton }
                    AirPlayPersistence.saveManualHotspotInterface(this, selected)
                    interfaceControl.text = interfaceSummary(selected)
                    toast(getString(R.string.saved_for_your_next_connection))
                    if (modernUi) render()
                }
                .setNegativeButton(R.string.cancel, null).show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !required || selected != null
        }
        if (modernUi) parent.addView(preferenceRow(getString(R.string.osn_network_interface),
            interfaceDisplaySummary(), valueTag = "interface-status") { interfaceControl.performClick() })
        else parent.addView(interfaceControl, matchButton(12, 60))
        if (!modernUi) parent.addView(label(getString(if (required) R.string.hotspot_network_manual_description else R.string.hotspot_network_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    internal open fun requiresManualHotspotInterface(): Boolean =
        resources.getBoolean(R.bool.config_require_hotspot_interface)

    internal open fun availableManualHotspotInterfaces() = if (modernUi) deviceMonitor?.latest?.interfaces.orEmpty() else ManualHotspotInterfaces.available()

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            dialogBuilder().setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        dialogBuilder().setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@OsnPlayActivity) else storedSsid())
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@OsnPlayActivity) else storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = dialogBuilder().setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            paintDialog(dialog)
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    /** A 2%-step slider row for overlay placement; every step saves, so the card moves live. */
    private fun overlaySliderRow(title: String, values: List<Int>, current: Int, describe: (Int) -> String): OverlaySliderRow =
        OverlaySliderRow(this, title, values, current, describe)

    private inner class OverlaySliderRow(
        context: android.content.Context,
        title: String,
        private val steps: List<Int>,
        current: Int,
        private val describe: (Int) -> String,
    ) : LinearLayout(context) {
        var onSave: (Int) -> Unit = {}
        val slider: SeekBar

        init {
            orientation = VERTICAL
            val valueView = label(describe(current), 16, ACCENT, true)
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            head.addView(label(title, 16, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(valueView)
            addView(head)
            slider = SeekBar(context).apply {
                max = steps.lastIndex
                progress = steps.indexOf(current).coerceIn(steps.indices)
                minHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = steps[progress.coerceIn(steps.indices)]
                        valueView.text = describe(value)
                        if (fromUser) onSave(value)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        }
    }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_osnplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = dialogBuilder().setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    /** The 0.2.9 Dashboard song setting, shown once: in the BYD navigation card, or under Advanced vehicle data. */
    private fun clusterSongSwitch(card: LinearLayout) {
        toggle(card, getString(R.string.cluster_song),
            getString(R.string.cluster_song_description),
            BydOutputSettings.clusterSong(this), enabled = !adbSwitchChangePending) {
            BydOutputSettings.setClusterSong(this, it)
            if (it) checkAdbState(mayAsk = true)
            BydNavigationOutputs.clusterSongChanged(it)
        }
    }

    private fun advancedVehicleData(card: LinearLayout) {
        val legacyMode = BydOutputSettings.legacyVehicleProbe(this)
        card.addView(label(getString(R.string.advanced_vehicle_data_description), 14, MUTED)
            .apply { setPadding(0, dp(12), 0, 0) })
        choice(
            card,
            getString(R.string.vehicle_data_mode),
            listOf(
                getString(R.string.vehicle_data_mode_default),
                getString(R.string.vehicle_data_mode_legacy),
            ),
            if (legacyMode) 1 else 0,
            reconnects = false,
            announcesReconnect = vehicleDataSwitchesOn(),
            // A switch during ADB work would be dropped by the running step.
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress(),
        ) { selectVehicleDataMode(it == 1) }
        if (!legacyMode) {
            defaultVehicleData(card)
            return
        }
        val capabilities = displayedVehicleCapabilities()
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress || automaticVehicleValidationInProgress ->
                getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null && capabilities != null -> getString(R.string.vehicle_probe_saved_automatic)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = capabilities != null && !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            pendingVehicleLostFields.isEmpty() &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        if (capabilities != null) {
            showVehicleProbeResults(card, capabilities)
            showVehicleDataSettings(card, capabilities)
        }
        if (vehicleProbeAuthorizationInProgress || adbCheckInProgress ||
            vehicleProbeInProgress || automaticVehicleValidationInProgress) return
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        if (pendingVehicleLostFields.isNotEmpty()) {
            card.addView(label(
                getString(
                    R.string.vehicle_probe_lost_saved_fields,
                    localizedVehicleFields(pendingVehicleLostFields),
                ),
                14,
                WARNING,
            ).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(button(getString(R.string.replace_saved_vehicle_data_anyway), false) {
                replaceSavedVehicleDataAnyway()
            }, matchButton(10, 56))
        }
        val needsUserAction = capabilities == null || adbCheckFailed || vehicleProbeOutcome?.error != null ||
            pendingVehicleLostFields.isNotEmpty() ||
            adbAccessState in setOf(BydAdbAccess.State.NOT_APPROVED, BydAdbAccess.State.ADB_OFF, BydAdbAccess.State.PAIRING_ONLY)
        if (needsUserAction) {
            val title = if (adbAccessState == BydAdbAccess.State.NOT_APPROVED) {
                R.string.request_adb_authorization
            } else if (capabilities == null) {
                R.string.probe_adb_and_vehicle_data
            } else {
                R.string.probe_vehicle_data_again
            }
            card.addView(button(getString(title), false) {
                probeVehicleData(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending && !adbCheckInProgress }, matchButton(10, 56))
        }
    }

    private fun defaultVehicleData(card: LinearLayout) {
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress -> getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        val busy = vehicleProbeAuthorizationInProgress || adbCheckInProgress || vehicleProbeInProgress
        if (!busy && adbAccessState == BydAdbAccess.State.READY) {
            defaultVehicleStatus?.let { showDefaultVehicleReadings(card, it) }
        }
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        showVehicleDataSettings(card, capabilities = null)
        if (!busy) {
            card.addView(button(getString(R.string.check_adb_access), false) {
                checkAdbState(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending }, matchButton(10, 56))
        }
    }

    /** The last default-mode reads; a value an enabled switch needs is a warning when missing. */
    private fun showDefaultVehicleReadings(card: LinearLayout, status: BydAdbAccess.Status) {
        fun reading(value: String?, unreadable: Int, needed: Boolean) {
            val text = value ?: getString(unreadable).takeIf { needed } ?: return
            card.addView(label(text, 14, if (value != null) MUTED else WARNING))
        }
        val battery = status.batteryPercent?.let { percent ->
            status.rangeKm?.let { getString(R.string.adb_battery_reading, percent.roundToInt(), it) }
        }
        reading(battery, R.string.adb_battery_unreadable, BydOutputSettings.batteryToIphone(this))
        reading(status.speedKmh?.let { getString(R.string.adb_vehicle_speed_reading, it.roundToInt()) },
            R.string.adb_vehicle_speed_unreadable, BydOutputSettings.wheelSpeedToIphone(this))
        reading(status.gear?.let { getString(R.string.adb_vehicle_gear_reading, it.letter.toString()) },
            R.string.adb_vehicle_gear_unreadable,
            BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
    }

    private fun selectVehicleDataMode(legacyMode: Boolean) {
        if (adbSwitchChangePending) return
        // A stale mode dialog must also invalidate a probe when the Boolean stays the same.
        synchronized(vehicleOperationLock) { vehicleProbeGeneration++ }
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        if (!legacyMode) {
            cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
            synchronized(vehicleOperationLock) {
                BydOutputSettings.setLegacyVehicleProbe(this, false)
            }
            vehicleProbeOutcome = null
            pendingVehicleReplacement = null
            pendingVehicleLostFields = emptySet()
            defaultVehicleStatus = null
            render()
            // The session was built from the legacy probe; apply once the default reads succeed.
            vehicleDataReconnectPending = vehicleDataSwitchesOn()
            if (vehicleDataReconnectPending) checkAdbState(mayAsk = true)
            return
        }
        if (BydVehicleFieldStore.load(this) == null) {
            probeVehicleData(mayAsk = true, activateLegacyModeOnSuccess = true)
            return
        }
        synchronized(vehicleOperationLock) {
            BydOutputSettings.setLegacyVehicleProbe(this, true)
        }
        vehicleProbeOutcome = null
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        scheduleAutomaticVehicleValidation()
        render()
        // With every vehicle-data switch off, the mode changes nothing CarPlay was told.
        if (vehicleDataSwitchesOn()) reconnectForVehicleSetting()
    }

    // Probe results are saved before they are shown, so the saved snapshot is the newest without
    // trusting the clock.
    private fun displayedVehicleCapabilities(): BydVehicleCapabilities? =
        BydVehicleFieldStore.load(this) ?: vehicleProbeOutcome?.capabilities

    private fun localizedVehicleFields(fields: Set<BydVehicleField>): String = fields.map { field ->
        getString(when (field) {
            BydVehicleField.SPEED -> R.string.vehicle_field_speed
            BydVehicleField.GEAR -> R.string.vehicle_field_gear
            BydVehicleField.SOC, BydVehicleField.RANGE, BydVehicleField.REMAINING_KWH ->
                R.string.vehicle_field_battery
            BydVehicleField.BMS_STATE -> R.string.vehicle_field_charging
        })
    }.distinct().joinToString()

    private fun replaceSavedVehicleDataAnyway() {
        val candidate = pendingVehicleReplacement ?: return
        // Never over a snapshot saved since the comparison; a failed write is shown, not thrown.
        val error = runCatching {
            BydVehicleFieldStore.replaceAnyway(applicationContext, pendingVehicleReplacementExpected, candidate)
        }.fold(
            onSuccess = { replaced -> if (replaced) null else getString(R.string.vehicle_probe_snapshot_changed) },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        Log.i(BYD_VEHICLE_TAG, "user replaced saved vehicle data error=${error ?: "none"}")
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        vehicleProbeOutcome = error?.let { BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = it) }
        render()
    }

    private fun showVehicleProbeResults(card: LinearLayout, capabilities: BydVehicleCapabilities) {
        val catalogFields = capabilities.fields.values.count { it.address?.source == BydFieldSource.FIRMWARE }
        card.addView(label(getString(if (capabilities.catalogAvailable)
            R.string.vehicle_probe_catalog_ready else R.string.vehicle_probe_catalog_fallback, catalogFields),
            14, if (capabilities.catalogAvailable) MUTED else WARNING).apply { setPadding(0, dp(14), 0, 0) })

        val speed = capabilities.result(BydVehicleField.SPEED)
        val speedValue = speed.value
        val speedText = when {
            speedValue != null -> getString(R.string.adb_vehicle_speed_reading, speedValue.roundToInt())
            speed.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_speed))
            else -> getString(R.string.adb_vehicle_speed_unreadable)
        }
        card.addView(label(speedText, 14, if (speed.supported) MUTED else WARNING))
        val gear = capabilities.result(BydVehicleField.GEAR)
        val gearValue = gear.value
        val gearText = when {
            gearValue != null -> getString(R.string.adb_vehicle_gear_reading, gearLetter(gearValue.toInt()))
            gear.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_gear))
            else -> getString(R.string.adb_vehicle_gear_unreadable)
        }
        card.addView(label(gearText, 14, if (gear.supported) MUTED else WARNING))

        if (capabilities.batterySupported) {
            val percentValue = capabilities.result(BydVehicleField.SOC).value
            val rangeValue = capabilities.result(BydVehicleField.RANGE).value
            val energy = capabilities.result(BydVehicleField.REMAINING_KWH).value
            card.addView(label(if (percentValue == null || rangeValue == null) {
                getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_battery))
            } else if (energy != null) {
                getString(R.string.vehicle_probe_battery_reading,
                    percentValue.roundToInt(), rangeValue.roundToInt(), energy)
            } else {
                getString(R.string.vehicle_probe_battery_without_energy,
                    percentValue.roundToInt(), rangeValue.roundToInt())
            }, 14, if (energy != null || percentValue == null) MUTED else WARNING))
        } else {
            card.addView(label(getString(R.string.adb_battery_unreadable), 14, WARNING))
        }
        val charging = capabilities.result(BydVehicleField.BMS_STATE)
        val chargingValue = charging.value
        val chargingText = when {
            chargingValue != null -> getString(R.string.vehicle_probe_charging_state, chargingValue.toInt())
            charging.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_charging))
            else -> getString(R.string.vehicle_probe_charging_unreadable)
        }
        card.addView(label(chargingText, 14, if (charging.supported) MUTED else WARNING))
    }

    private fun showVehicleDataSettings(card: LinearLayout, capabilities: BydVehicleCapabilities?) {
        if (capabilities == null || capabilities.batterySupported) {
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setBatteryToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT)
                        R.string.percent_default else R.string.percent_value, it)
                }, lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
        }
        if (capabilities == null || capabilities.motionSupported) {
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
        if (capabilities == null || capabilities.gearSupported) {
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setVideoWhileParked(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
    }

    private fun onVehicleDataSettingChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectForVehicleSetting()
        } else if (BydOutputSettings.legacyVehicleProbe(this)) {
            scheduleAutomaticVehicleValidation()
            reconnectForVehicleSetting()
        } else {
            // Default mode applies the switch once a check reads what the enabled switches need.
            vehicleDataReconnectPending = true
            checkAdbState(mayAsk = true)
        }
    }

    // The approval dialog can open only after an explicit user action.
    private fun checkAdbState(mayAsk: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val legacy = BydOutputSettings.legacyVehicleProbe(this)
        // Only a validation this check interrupts runs again; a Dashboard switch needs no vehicle check.
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val generation = ++adbCheckGeneration
        adbCheckInProgress = true
        adbCheckMayAsk = mayAsk
        adbCheckFailed = false
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("osnplay-adb-state") {
            val result = runCatching {
                if (legacy) {
                    BydAdbAccess.Status(backend.checkState(applicationContext, mayAsk))
                } else {
                    // The default battery path must publish its first sample before CarPlay reconnects.
                    backend.check(applicationContext, mayAsk)
                }
            }
            runOnUiThread {
                val current = generation == adbCheckGeneration
                if (current) {
                    adbCheckInProgress = false
                    adbCheckMayAsk = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = result.isFailure
                val status = result.getOrNull()
                adbAccessState = status?.state
                if (!legacy) defaultVehicleStatus = status
                if (!legacy && adbAccessState == BydAdbAccess.State.READY) {
                    vehicleProbeOutcome = null
                }
                render()
                // Unreadable data keeps the current connection; the page shows what is missing.
                if (!legacy && vehicleDataReconnectPending && status?.state == BydAdbAccess.State.READY &&
                    enabledVehicleDataReadable(null, status)) {
                    vehicleDataReconnectPending = false
                    reconnectForVehicleSetting()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /** First probe or user retry: one click handles ADB authorization, probing and persistence. */
    private fun probeVehicleData(mayAsk: Boolean) =
        probeVehicleData(mayAsk, activateLegacyModeOnSuccess = false)

    private fun probeVehicleData(mayAsk: Boolean, activateLegacyModeOnSuccess: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val expectedLegacyMode = BydOutputSettings.legacyVehicleProbe(this)
        val expectedSnapshot = cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
        val generation = ++vehicleProbeGeneration
        vehicleProbeAuthorizationInProgress = mayAsk
        vehicleProbeInProgress = !mayAsk
        adbCheckFailed = false
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        Log.i(BYD_VEHICLE_TAG, "user vehicle probe starting mayAsk=$mayAsk")
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("osnplay-byd13-probe") {
            val app = applicationContext
            val attempt = runCatching {
                val access = backend.checkState(app, mayAsk)
                if (access != BydAdbAccess.State.READY) {
                    VehicleProbeAttempt(BydVehicleProbeOutcome(access))
                } else {
                    runOnUiThread {
                        if (generation == vehicleProbeGeneration && !isFinishing && !isDestroyed) {
                            vehicleProbeAuthorizationInProgress = false
                            vehicleProbeInProgress = true
                            render()
                        }
                    }
                    var candidate = backend.probe(app, persist = false)
                    if (candidate.access == BydAdbAccess.State.NOT_APPROVED) {
                        // adbd confirms "Always allow" before it saves the key, so this new connection can be early.
                        Log.i(BYD_VEHICLE_TAG, "probe connection not approved yet; retrying once")
                        Thread.sleep(ADB_KEY_SAVE_WAIT_MILLIS)
                        candidate = backend.probe(app, persist = false)
                    }
                    val candidateCapabilities = candidate.capabilities
                    when {
                        candidate.access == BydAdbAccess.State.NOT_APPROVED -> VehicleProbeAttempt(
                            outcome = candidate,
                            allowedOnlyOnce = true,
                        )
                        candidateCapabilities == null -> VehicleProbeAttempt(candidate)
                        else -> {
                            // Invalidation must guard persistence/publication, not only the UI callback.
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleProbeGeneration ||
                                    BydOutputSettings.legacyVehicleProbe(app) != expectedLegacyMode) null
                                else BydVehicleFieldStore.replaceAutomatically(
                                    app,
                                    expectedSnapshot,
                                    candidateCapabilities,
                                )
                            } ?: return@runCatching VehicleProbeAttempt(
                                outcome = BydVehicleProbeOutcome(candidate.access),
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> VehicleProbeAttempt(candidate)
                                replacement.snapshotChanged -> VehicleProbeAttempt(
                                    outcome = candidate,
                                    snapshotChanged = true,
                                )
                                else -> VehicleProbeAttempt(
                                    outcome = BydVehicleProbeOutcome(candidate.access),
                                    heldCandidate = candidateCapabilities,
                                    lostFields = replacement.lostFields,
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                VehicleProbeAttempt(
                    BydVehicleProbeOutcome(
                        BydAdbAccess.State.READY,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            runOnUiThread {
                val current = generation == vehicleProbeGeneration
                if (current) {
                    vehicleProbeAuthorizationInProgress = false
                    vehicleProbeInProgress = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                pendingVehicleReplacement = attempt.heldCandidate
                pendingVehicleReplacementExpected = expectedSnapshot
                pendingVehicleLostFields = attempt.lostFields
                vehicleProbeOutcome = when {
                    attempt.allowedOnlyOnce -> attempt.outcome.copy(
                        error = getString(R.string.vehicle_probe_allowed_once),
                    )
                    attempt.snapshotChanged -> attempt.outcome.copy(
                        capabilities = null,
                        error = getString(R.string.vehicle_probe_snapshot_changed),
                    )
                    else -> attempt.outcome
                }
                adbAccessState = attempt.outcome.access
                val activatedLegacyMode = activateLegacyModeOnSuccess &&
                    vehicleProbeOutcome?.capabilities != null
                if (activatedLegacyMode) {
                    BydOutputSettings.setLegacyVehicleProbe(this, true)
                }
                if (vehicleProbeOutcome?.capabilities != null) automaticVehicleValidationStarted = true
                Log.i(
                    BYD_VEHICLE_TAG,
                    "user vehicle probe access=${attempt.outcome.access} " +
                        "saved=${vehicleProbeOutcome?.capabilities != null} " +
                        "lost=${attempt.lostFields.joinToString()} " +
                        "error=${vehicleProbeOutcome?.error ?: "none"}",
                )
                render()
                if (activatedLegacyMode && vehicleDataSwitchesOn()) reconnectForVehicleSetting()
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /**
     * After the first saved probe, validation is automatic and never asks for authorization. ADB
     * transport failure keeps the saved snapshot; only two complete READY-but-unreadable checks
     * trigger one automatic field re-probe.
     */
    private fun validateSavedVehicleConfigurationAutomatically() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) return
        val saved = BydVehicleFieldStore.load(applicationContext) ?: return
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationInProgress) {
            // A request during a run, such as a switch just turned on, runs once this one ends.
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationStarted) return
        automaticVehicleValidationStarted = true
        automaticVehicleValidationInProgress = true
        val generation = ++vehicleValidationGeneration
        Log.i(BYD_VEHICLE_TAG, "automatic vehicle validation starting savedFirmware=${saved.firmwareKey}")
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("osnplay-byd13-auto-validate") {
            val app = applicationContext
            val validation = runCatching {
                var status = backend.check(app, mayAsk = false)
                var outcome: BydVehicleProbeOutcome? = null
                var heldCandidate: BydVehicleCapabilities? = null
                var lostFields: Set<BydVehicleField> = emptySet()
                var snapshotChanged = false
                var readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                if (status.state == BydAdbAccess.State.READY && !enabledVehicleDataReadable(saved, status)) {
                    Log.w(BYD_VEHICLE_TAG, "saved vehicle fields unreadable; validating once more")
                    try {
                        Thread.sleep(VEHICLE_VALIDATION_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // A cancelled validation reads nothing more alongside the operation that replaced it.
                    if (generation != vehicleValidationGeneration) {
                        return@runCatching VehicleValidationAttempt(
                            status = status,
                            snapshotChanged = true,
                        )
                    }
                    status = backend.check(app, mayAsk = false)
                    readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                    if (status.state == BydAdbAccess.State.READY && !readable) {
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        Log.w(BYD_VEHICLE_TAG, "saved vehicle fields still unreadable; automatic re-probe starting")
                        val candidate = backend.probe(app, persist = false)
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        outcome = candidate
                        candidate.capabilities?.let { next ->
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleValidationGeneration) null
                                else BydVehicleFieldStore.replaceAutomatically(app, saved, next)
                            } ?: return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> Unit
                                replacement.snapshotChanged -> {
                                    snapshotChanged = true
                                    outcome = null
                                }
                                else -> {
                                    heldCandidate = next
                                    lostFields = replacement.lostFields
                                    outcome = BydVehicleProbeOutcome(candidate.access)
                                }
                            }
                        }
                    }
                }
                VehicleValidationAttempt(
                    status = status,
                    outcome = outcome,
                    heldCandidate = heldCandidate,
                    lostFields = lostFields,
                    snapshotChanged = snapshotChanged,
                    savedFieldsReadable = readable,
                )
            }
            runOnUiThread {
                val current = generation == vehicleValidationGeneration
                if (current) automaticVehicleValidationInProgress = false
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = validation.isFailure
                validation.exceptionOrNull()?.let { Log.w(BYD_VEHICLE_TAG, "automatic vehicle validation failed", it) }
                validation.getOrNull()?.let { result ->
                    adbAccessState = result.outcome?.access ?: result.status.state
                    when {
                        result.snapshotChanged -> Unit
                        result.savedFieldsReadable && result.outcome == null -> {
                            // An open offer to replace stays: this check may not have read its lost fields.
                            vehicleProbeOutcome = null
                        }
                        result.lostFields.isNotEmpty() -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = result.heldCandidate
                            pendingVehicleReplacementExpected = saved
                            pendingVehicleLostFields = result.lostFields
                        }
                        result.outcome != null -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = null
                            pendingVehicleLostFields = emptySet()
                        }
                    }
                    Log.i(
                        BYD_VEHICLE_TAG,
                        "automatic vehicle validation access=${adbAccessState} " +
                            "reprobed=${result.outcome != null} " +
                            "saved=${result.outcome?.capabilities != null && result.lostFields.isEmpty()} " +
                            "lost=${result.lostFields.joinToString()} " +
                            "snapshotChanged=${result.snapshotChanged} " +
                            "error=${result.outcome?.error ?: "none"}",
                    )
                }
                if (page == "settings" && bydVehicleAdvancedExpanded) {
                    render()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    private fun scheduleAutomaticVehicleValidation() {
        if (!bydFeatures || !BydOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationStarted = false
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): BydVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        if (resumeAfter) automaticVehicleValidationStarted = false
        vehicleValidationGeneration++
        automaticVehicleValidationInProgress = false
        BydVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress() || automaticVehicleValidationInProgress) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    /** Whether [status] has every reading an enabled switch needs; null [capabilities] is default mode. */
    private fun enabledVehicleDataReadable(
        capabilities: BydVehicleCapabilities?,
        status: BydAdbAccess.Status,
    ): Boolean {
        if (BydOutputSettings.batteryToIphone(this) && capabilities?.batterySupported != false &&
            (status.batteryPercent == null || status.rangeKm == null)) return false
        if (BydOutputSettings.wheelSpeedToIphone(this) && capabilities?.motionSupported != false &&
            (status.speedKmh == null || status.gear == null)) return false
        if (BydOutputSettings.videoWhileParked(this) && capabilities?.gearSupported != false && status.gear == null) return false
        return true
    }

    private fun adbLinkStatusText(state: BydAdbAccess.State): String = when (state) {
        BydAdbAccess.State.READY -> getString(R.string.adb_access_ready)
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_enabled_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun gearLetter(value: Int): String = when (value) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        else -> "D"
    }

    private fun reconnectForVehicleSetting() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun vehicleDataSwitchesOn() = BydOutputSettings.batteryToIphone(this) ||
        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this)

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun clusterSafeAreaControls(card: LinearLayout) {
        val rect = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                AirPlayPersistence.loadClusterMarkerVerticalStep(this))
        card.addView(label(getString(R.string.safe_area_mapping_summary,
            rect.width, rect.height, rect.left, rect.top, 1920, 720), 14, MUTED))
        card.addView(button(getString(R.string.cluster_safe_area_edit), false) {
            openClusterSafeAreaEditor()
        }, matchButton(10, 56))
        card.addView(button(getString(R.string.cluster_safe_area_reset), false) {
            AirPlayPersistence.clearClusterSafeAreaRect(this)
            render()
            reconnectForClusterMap()
        }, matchButton(10, 56))
        card.addView(label(getString(R.string.cluster_safe_area_hint), 14, MUTED))
    }

    private fun openClusterSafeAreaEditor() {
        clusterSafeAreaDialog?.dismiss()
        val previewOwner = Any()
        val initial = AirPlayPersistence.loadClusterSafeAreaRect(this)
            ?: DiLink4ClusterDisplay.defaultSafeAreaRect(
                AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                AirPlayPersistence.loadClusterMarkerVerticalStep(this))
        val editor = SafeAreaEditorView(this).apply {
            setBackgroundColor(Color.rgb(35, 39, 45))
            setRect(initial, 1920, 720)
        }
        // A standalone dialog gives the weighted preview an exact available height.
        // AlertDialog's wrap-content custom panel can collapse it to zero.
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = column().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.rgb(35, 39, 45))
        }
        panel.addView(label(getString(R.string.cluster_safe_area_edit), 18, Color.WHITE, true))
        panel.addView(label(getString(R.string.cluster_safe_area_live_hint), 14, MUTED))
        panel.addView(ClusterSafeAreaPreviewFrame(this, editor), LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = row()
        actions.addView(button(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button(getString(if (CarPlayBackgroundSession.hasSession())
            R.string.apply_and_reconnect else R.string.save), true) {
            editor.currentRectForSource()?.let { AirPlayPersistence.saveClusterSafeAreaRect(this, it) }
            dialog.dismiss()
            render()
            reconnectForClusterMap()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        panel.addView(actions)
        dialog.setContentView(panel, ViewGroup.LayoutParams(-1, -1))
        editor.onRectChanged = { ClusterActivityOutput.updateSafeAreaPreview(previewOwner, it) }
        clusterSafeAreaDialog = dialog
        dialog.setOnDismissListener {
            editor.onRectChanged = null
            ClusterActivityOutput.endSafeAreaPreview(previewOwner)
            if (clusterSafeAreaDialog === dialog) clusterSafeAreaDialog = null
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt())
        ClusterActivityOutput.beginSafeAreaPreview(previewOwner, initial)
    }

    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        dialogBuilder().setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = dialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                paintDialog(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = dialogBuilder().setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                paintDialog(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            fields.addView(label(getString(R.string.ambient_light_threshold_value), 16, MUTED))
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@OsnPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = dialogBuilder()
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                paintDialog(dialog)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (resources.getBoolean(R.bool.config_require_microphone_permission) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicrophoneTransport = wireless
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (modernUi && wireless && requiresManualHotspotInterface() && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL && deviceMonitor?.latest == null) {
            deviceMonitor?.request(force = true)
            toast(getString(R.string.osn_reading_status))
            return
        }
        val interfaceError = ManualHotspotSelection.error(requiresManualHotspotInterface(), wireless,
            AirPlayPersistence.loadWirelessHotspotMode(this), AirPlayPersistence.loadManualHotspotInterface(this),
            availableManualHotspotInterfaces())
        if (interfaceError != null) {
            page = "connection"
            render()
            toast(getString(interfaceError.messageResource))
            return
        }
        if (wireless && OsnPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("osnplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            dialogBuilder().setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            dialogBuilder().setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        dialogBuilder().setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                OsnPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        dialogBuilder().setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        dialogBuilder().setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        // Some head units update the system resources without delivering a uiMode
        // callback to a locale-wrapped window. Refresh the palette, not the session.
        if (modernUi && palette.dark != OsnAppearance.dark(this)) { render(); return }
        val running = CarPlayBackgroundSession.hasSession()
        if (modernUi) {
            if (uiForeground && (page == "home" || page == "connection")) deviceMonitor?.request()
            clockLabel?.updateText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            val bluetoothEnabled = deviceMonitor?.latest?.bluetoothEnabled
            val ready = deviceMonitor?.latest != null && hotspotConfigured() && !carHotspotOff() && phoneChosen() && requiredPermissionsReady() && bluetoothEnabled != false
            status?.updateText(when {
                setupError != null -> getString(R.string.setup_needs_attention)
                CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
                running -> getString(R.string.connecting_to_your_iphone)
                else -> getString(R.string.osn_ready_title)
            })
            statusBadge?.apply {
                updateText(getString(when {
                    setupError != null -> R.string.osn_status_setup
                    CarPlayBackgroundSession.active -> R.string.carplay_connected
                    running -> R.string.osn_status_connecting
                    deviceMonitor?.latest == null -> R.string.osn_reading_status
                    ready -> R.string.osn_status_ready
                    else -> R.string.osn_status_setup
                }))
                val color = if (setupError != null || !running && !CarPlayBackgroundSession.active && deviceMonitor?.latest != null && !ready) WARNING else ACCENT
                if (currentTextColor != color) setTextColor(color)
            }
            statusDetail?.updateText(when {
                setupError != null -> setupError
                CarPlayBackgroundSession.active -> getString(R.string.osn_connected_hint)
                running -> getString(R.string.osn_connecting_hint)
                bluetoothEnabled == false -> getString(R.string.osn_bluetooth_off)
                else -> getString(R.string.osn_home_description)
            })
            connectButton?.updateText(if (page == "connection") getString(if (connectionWireless) R.string.connect_phone else R.string.connect_with_usb)
                else getString(if (running) R.string.open_carplay else R.string.connect_phone))
            connectButton?.isEnabled = setupError == null
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            if (lastRunning != running) disconnectButton?.isEnabled = true
            lastRunning = running
            return
        }
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            OsnPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${OsnPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun authorizeClusterRouting() {
        val app = applicationContext
        Thread({
            val result = runCatching {
                com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(app)).use {
                    it.connect(mayAsk = true)
                }
            }.getOrNull()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    toast(if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY)
                        getString(R.string.adb_access_ready) else getString(R.string.adb_not_approved))
                    if (result == com.shilapi.xcertplay.adb.LocalAdb.Access.READY) ClusterActivityOutput.retry()
                }
            }
        }, "adb-cluster-authorize").start()
    }

    private fun reportFileName() = "${getString(R.string.app_name)}-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("${getString(R.string.app_name)} ${version()} · diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("CarPlay audio: ${CarPlayBackgroundSession.snapshot()?.controller?.audioDiagnosticReport() ?: "no_active_session"}")
                    appendLine("Android audio outputTypes=${deviceMonitor?.latest?.audioOutputTypes.orEmpty()}")
                    if (modernUi) appendLine("UI appearance: mode=${OsnAppearance.mode(appContext)} dark=${OsnAppearance.dark(appContext)} systemUiMode=${appContext.resources.configuration.uiMode} sizePercent=${OsnAppearance.size(appContext)}")
                    if (modernUi) {
                        appendLine("UI performance: renders=$uiRenderCount maxBuildMs=$uiMaxRenderMillis maxTickDelayMs=$uiMaxTickDelayMillis")
                        appendLine("UI device status: ${deviceMonitor?.report() ?: "not_started"}")
                        val metrics = resources.displayMetrics
                        appendLine("UI display: pixels=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi} fontScale=${resources.configuration.fontScale} screenDp=${resources.configuration.screenWidthDp}x${resources.configuration.screenHeightDp}")
                    }
                    appendLine("Saved manual hotspot network: interface=${AirPlayPersistence.loadManualHotspotInterface(appContext) ?: if (appContext.resources.getBoolean(R.bool.config_require_hotspot_interface)) "unselected" else "auto"} addressMode=${AirPlayPersistence.loadManualHotspotAddressMode(appContext)}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Audio compatibility: earlyMediaFocus=${AirPlayPersistence.loadEarlyMediaFocus(appContext)} systemMediaSync=${AirPlayPersistence.loadSystemMediaSyncEnabled(appContext)} standardMicrophone=${AirPlayPersistence.loadStandardMicrophoneInput(appContext)} microphonePermission=${appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED} opusInput=${com.shilapi.xcertplay.media.MicrophoneCodecSupport.opusAvailable()}")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    if (bydFeatures) {
                    appendLine("--- Current cluster display diagnostics (even when disabled) ---")
                    appendLine(ClusterMapPresentation.diagnosticReport(appContext))
                    appendLine()
                    appendLine("--- ADB cluster activity routing ---")
                    appendLine("adbClusterActivityEnabled=${AirPlayPersistence.loadAdbClusterEnabled(appContext)}")
                    appendLine("clusterActivityMainTask=${ClusterActivityOutput.mainTaskId} surfaceValid=${ClusterActivityOutput.surface?.isValid}")
                    AdbClusterRouter.report(appContext).lineSequence().forEach { line ->
                        DiagnosticRedactor.redact(line)?.let { appendLine(it) }
                    }
                    appendLine()
                    appendLine("--- Standalone HUD compatibility ---")
                    appendLine(BydOutputSettings.standaloneHudDiagnosticReport(appContext))
                    appendLine()
                    appendLine("--- BYD vehicle-data probe ---")
                    appendLine(
                        "mode=${if (BydOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                            "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                            "battery=${BydOutputSettings.batteryToIphone(appContext)} " +
                            "wheelSpeed=${BydOutputSettings.wheelSpeedToIphone(appContext)} " +
                            "parkedVideo=${BydOutputSettings.videoWhileParked(appContext)}",
                    )
                    val bydCapabilities = BydVehicleFieldStore.load(appContext)
                    if (bydCapabilities == null) {
                        appendLine("no saved successful probe")
                    } else {
                        appendLine(
                            "catalog=${bydCapabilities.catalogAvailable} detectedAt=${bydCapabilities.detectedAtMillis} " +
                                "savedFirmware=${bydCapabilities.firmwareKey} " +
                                "currentFirmware=${BydVehicleFieldStore.firmwareKey()}",
                        )
                        for (field in BydVehicleField.entries) {
                            val probe = bydCapabilities.result(field)
                            appendLine("${field.name}: supported=${probe.supported} " +
                                (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                                    ?: "address=none"))
                        }
                    }
                    appendLine()
                    }
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${OsnPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    appendLine("--- Recent own-app process exits (Android 11+) ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    dialogBuilder().setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/${getString(R.string.app_name)}/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    dialogBuilder().setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "osnplay-export").start()
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        dialogBuilder().setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }

    private fun dialogBuilder(): AlertDialog.Builder = object : AlertDialog.Builder(this,
        if (!modernUi) 0 else if (palette.dark) R.style.Theme_OsnPlay_Dark_Dialog else R.style.Theme_OsnPlay_Light_Dialog) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            activeDialog = dialog
            dialog.setOnShowListener { paintDialog(dialog) }
        }
    }

    private fun paintDialog(dialog: AlertDialog) {
        if (!modernUi) return
        dialog.context.theme.applyStyle(if (palette.dark) R.style.Theme_OsnPlay_Dark_Dialog else R.style.Theme_OsnPlay_Light_Dialog, true)
        dialog.window?.setBackgroundDrawable(rounded(SURFACE, BORDER))
        fun paint(view: View) {
            if (view is TextView) {
                if (view.getTag(R.id.osn_text_role) == null) {
                    val basePixels = (view.getTag(R.id.osn_base_text_size) as? Float) ?: view.textSize.also { view.setTag(R.id.osn_base_text_size, it) }
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, basePixels * uiScale)
                }
                view.setTextColor(when (view.getTag(R.id.osn_text_role)) {
                    "muted" -> MUTED; "warning" -> WARNING; "accent" -> ACCENT; else -> TEXT
                })
                view.setHintTextColor(MUTED)
            }
            if (view is EditText) view.backgroundTintList = ColorStateList.valueOf(ACCENT)
            if (view is CompoundButton) view.buttonTintList = ColorStateList.valueOf(ACCENT)
            if (view is ViewGroup) for (index in 0 until view.childCount) paint(view.getChildAt(index))
        }
        dialog.window?.decorView?.let(::paint)
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.setTextColor(ACCENT)
        }
    }

    private fun permissionHelp(title: String, body: String) {
        dialogBuilder().setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("OsnPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("OsnPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x336F9FD9),
            rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = cachedVersion
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title)) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        if (modernUi && page == "settings" && settingsGroup(title) != settingsCategory) return
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(this).apply {
            contentDescription = title; isChecked = value; isEnabled = enabled; minHeight = dp(56)
            thumbTintList = ColorStateList.valueOf(if (modernUi) Color.WHITE else ACCENT)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT, BORDER))
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        line.addView(control)
        parent.addView(line)
        return control
    }
    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current.coerceIn(options.indices)
        if (modernUi) {
            val value = label("${options[selection]}  ›", 15, MUTED).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            val line = row().apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(68); isClickable = true; isFocusable = true; isEnabled = enabled
                setPadding(0, dp(10), 0, dp(10)); contentDescription = "$title · ${options[selection]}"
                addView(label(title, 18, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(value, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener {
                    var pending = selection
                    dialogBuilder().setTitle(title).setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pending = index }
                        .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                            if (pending != selection) {
                                selection = pending; save(selection); value.text = "${options[selection]}  ›"
                                contentDescription = "$title · ${options[selection]}"
                                if (reconnects && CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this@OsnPlayActivity))
                            }
                        }.setNegativeButton(R.string.cancel, null).show()
                }
            }
            parent.addView(line)
            parent.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
            return
        }
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            dialogBuilder().setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun preferenceRow(title: String, value: String, valueTag: String? = null, action: () -> Unit): View = column().apply {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            isClickable = true; isFocusable = true; contentDescription = "$title · $value"
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((ACCENT and 0xFFFFFF) or 0x22000000), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            addView(label(title, 17, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            val valueView = label("$value  ›", 14, MUTED).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(dp(8), dp(10), 0, dp(10)) }
            if (valueTag == "interface-status") interfaceValue = valueView
            addView(valueView, LinearLayout.LayoutParams(0, -2, 1.2f))
            setOnClickListener { action() }
        }
        addView(line)
        addView(View(this@OsnPlayActivity).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size * uiScale; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        setTag(R.id.osn_text_role, when (color) { MUTED -> "muted"; WARNING -> "warning"; ACCENT -> "accent"; else -> "text" })
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f * uiScale
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, if (primary) palette.onAccent else TEXT)))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((ACCENT and 0x00FFFFFF) or 0x33000000), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER, if (modernUi) 14 else 20), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun wideUi() = resources.configuration.screenWidthDp / uiScale >= 850
    private fun dp(value: Int) = (value * resources.displayMetrics.density * uiScale).roundToInt()
    companion object {
        private const val BYD_VEHICLE_TAG = "OsnPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
    }
}
