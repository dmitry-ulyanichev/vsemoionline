package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.res.Configuration
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }

    // VseMoiOnline: Provisioning configuration
    companion object {
        private const val PREFS_NAME = "vsemoionline_prefs"
        private const val PREF_LAST_WORKING_URL = "last_working_provisioning_url"
        private const val PREF_XRAY_UUID = "xray_uuid"
        private const val PREF_PLAN = "plan"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val PREF_VLESS_URI = "vless_uri"
        private const val PREF_PAID_DAYS_REMAINING = "paid_days_remaining"
        private const val PREF_TRAFFIC_REMAINING_GB = "traffic_remaining_gb"
        private const val PREF_TRAFFIC_TOTAL_GB = "traffic_total_gb"
        private const val PREF_THROTTLE_MBPS = "throttle_mbps"
        private const val PREF_CABINET_URL = "cabinet_url"
        private const val PREF_LAST_STATUS_POLL_MS = "last_status_poll_ms"
        private const val PREF_SERVER_ZONE = "server_zone"
        private const val PREF_SERVER_REGION = "server_region"

        private const val STATUS_POLL_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        // Two-cycle provisioning timeouts for better UX
        private const val PROVISION_TIMEOUT_QUICK_MS = 3000  // 3 seconds - Cycle 1: quick scan
        private const val PROVISION_TIMEOUT_PATIENT_MS = 10000 // 10 seconds - Cycle 2: patient retry

        private const val PRIMARY_PROVISION_URL = "https://vmonl.store/provision"

        // Fallback platform URLs - return plain text with current provisioning domain/IP
        private val FALLBACK_PLATFORM_URLS = listOf(
            "https://gist.githubusercontent.com/dmitry-ulyanichev/0b552cd7f00f8735f8a4832321825162/raw/endpoint.txt"
//            "https://raw.githubusercontent.com/YOUR_USERNAME/provision/main/endpoint.txt",
//            "https://YOUR_WORKER.YOUR_SUBDOMAIN.workers.dev/provision",
//            "https://YOUR_PROJECT.vercel.app/api/provision",
//            "https://YOUR_SITE.netlify.app/.netlify/functions/provision",
//            "https://pastebin.com/raw/YOUR_PASTE_ID",
//            "https://gitlab.com/snippets/YOUR_SNIPPET_ID/raw"
            // Add more as needed
        )
    }

    private data class ZoneItem(val zone: String, val region: String, val available: Boolean)

    internal val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            provisionThenConnect()
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    // VseMoiOnline: Flag to prevent duplicate provisioning attempts
    private var isProvisioningInProgress = false

    // VseMoiOnline: Set when an activation deep link is being processed.
    // checkAndAutoProvision skips if this is non-null to avoid a free-provision race.
    private var pendingActivationToken: String? = null

    // VseMoiOnline: Status polling job — active only while VPN is running
    private var statusPollingJob: Job? = null

    // VseMoiOnline: Code input fields and paste-in-progress flag
    private lateinit var codeFields: Array<EditText>
    private var isPasting = false

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, getString(R.string.title_file_chooser)))

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.vsm_toolbar_title)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else {
                val blocked = connectionBlockedReason()
                if (blocked != null) {
                    toast(blocked)
                    return@setOnClickListener
                }
                if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                    val intent = VpnService.prepare(this)
                    if (intent == null) {
                        provisionThenConnect()
                    } else {
                        requestVpnPermission.launch(intent)
                    }
                } else {
                    provisionThenConnect()
                }
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        // Set version in navigation header
        binding.navView.getHeaderView(0)?.findViewById<android.widget.TextView>(R.id.tv_version_header)?.text =
            "v${com.v2ray.ang.BuildConfig.VERSION_NAME} (${SpeedtestManager.getLibVersion()})"

        initGroupTab()
        setupViewModel()
        setupVsmUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // VseMoiOnline: Handle magic link on app startup
        handleMagicLink(intent)

        applyAnimatedBackgrounds()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // VseMoiOnline: Handle magic link when app is already running
        handleMagicLink(intent)
    }

    /**
     * VseMoiOnline: Handle deep links
     * vsemoionline://import?url=...    — fetch VLESS config from URL (legacy magic link)
     * vsemoionline://activate?token=... — activate paid subscription token
     */
    private fun handleMagicLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "vsemoionline") return

        when (data.host) {
            "import" -> {
                val provisionUrl = data.getQueryParameter("url") ?: run {
                    Log.w(AppConfig.TAG, "VseMoiOnline: 'import' deep link missing 'url' parameter")
                    return
                }
                Log.i(AppConfig.TAG, "VseMoiOnline: Import deep link — provisioning from: $provisionUrl")
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_PATIENT_MS)
                    if (success) saveLastWorkingProvisionUrl(provisionUrl)
                    withContext(Dispatchers.Main) { binding.pbWaiting.hide() }
                }
            }
            "activate" -> {
                val token = data.getQueryParameter("token") ?: run {
                    Log.w(AppConfig.TAG, "VseMoiOnline: 'activate' deep link missing 'token' parameter")
                    return
                }
                Log.i(AppConfig.TAG, "VseMoiOnline: Activate deep link received")
                pendingActivationToken = token
                handleActivateDeepLink(token)
            }
            else -> Log.w(AppConfig.TAG, "VseMoiOnline: Unknown deep link host: ${data.host}")
        }
    }

    /**
     * VseMoiOnline: Consume an activation token via POST /activate, then re-provision
     * to get the paid-inbound VLESS config.
     */
    private fun handleActivateDeepLink(token: String) {
        // On first launch backendBaseUrl may not yet be stored — fall back to primary URL.
        val backendBaseUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
            ?: PRIMARY_PROVISION_URL.removeSuffix("/provision").trimEnd('/')

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = java.net.URL("$backendBaseUrl/activate").openConnection()
                    as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.instanceFollowRedirects = false
                connection.outputStream.use {
                    it.write("token=${java.net.URLEncoder.encode(token, "UTF-8")}".toByteArray(Charsets.UTF_8))
                }
                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 301..303) {
                    // Backend redirects to /activate/success on valid token — re-provision for paid config
                    val success = fetchAndImportConfig("$backendBaseUrl/provision", PROVISION_TIMEOUT_PATIENT_MS)
                    withContext(Dispatchers.Main) {
                        pendingActivationToken = null
                        if (success) {
                            toastSuccess("Подписка активирована!")
                            binding.tvCodeSuccess.visibility = View.VISIBLE
                            binding.tvCodeSuccess.postDelayed(
                                { binding.tvCodeSuccess.visibility = View.GONE }, 3000
                            )
                            if (mainViewModel.isRunning.value == true) restartV2Ray()
                        } else {
                            toast("Подписка активирована. Перезапустите приложение для обновления конфигурации.")
                        }
                    }
                } else {
                    // Token rejected — could be genuinely invalid, or user tapped the browser's
                    // "Continue" chip a second time after a successful activation.
                    // Check stored plan to avoid a confusing error in the latter case.
                    val currentPlan = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(PREF_PLAN, "free")
                    withContext(Dispatchers.Main) {
                        pendingActivationToken = null
                        if (currentPlan == "paid") {
                            toastSuccess("Подписка уже активирована!")
                        } else {
                            toastError("Код недействителен или уже использован")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "VseMoiOnline: Activation error", e)
                withContext(Dispatchers.Main) {
                    pendingActivationToken = null
                    toastError("Ошибка активации. Проверьте подключение к интернету.")
                }
            }
        }
    }

    /**
     * VseMoiOnline: Get or create persistent device UUID
     * Stored in SharedPreferences to identify this device across sessions
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
            Log.i(AppConfig.TAG, "VseMoiOnline: Generated new device UUID: $deviceId")
        } else {
            Log.i(AppConfig.TAG, "VseMoiOnline: Using existing device UUID: $deviceId")
        }

        return deviceId
    }

    /**
     * VseMoiOnline: Save last successful provisioning URL
     * This URL will be tried first on next provisioning attempt
     */
    private fun saveLastWorkingProvisionUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_WORKING_URL, url).apply()
        Log.i(AppConfig.TAG, "VseMoiOnline: Saved last working provisioning URL: $url")
    }

    /**
     * VseMoiOnline: Get last successful provisioning URL
     * Returns null if no URL was previously saved
     */
    private fun getLastWorkingProvisionUrl(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(PREF_LAST_WORKING_URL, null)
    }

    private fun getAndroidId(): String? {
        return try {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveProvisionData(xrayUuid: String, plan: String, vlessUri: String, provisionUrl: String, cabinetUrl: String?) {
        val baseUrl = provisionUrl.removeSuffix("/provision").trimEnd('/')
        val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_XRAY_UUID, xrayUuid)
            .putString(PREF_PLAN, plan)
            .putString(PREF_VLESS_URI, vlessUri)
            .putString(PREF_BACKEND_BASE_URL, baseUrl)
        if (cabinetUrl != null) editor.putString(PREF_CABINET_URL, cabinetUrl)
        editor.apply()
        Log.i(AppConfig.TAG, "VseMoiOnline: Provision data saved — plan=$plan uuid=$xrayUuid base=$baseUrl")
    }

    /**
     * VseMoiOnline: Core two-cycle provisioning fallback chain.
     *
     * Priority order:
     * 1. Last working URL (if exists)
     * 2. Primary hardcoded URL
     * 3. Fallback platform URLs (which return the current provisioning endpoint)
     *
     * Cycle 1 (Quick scan): tries all URLs with PROVISION_TIMEOUT_QUICK_MS.
     * Cycle 2 (Patient retry): retries only URLs that timed out in Cycle 1.
     *
     * Must be called from an IO-dispatcher coroutine. Returns true if any URL succeeded.
     */
    private suspend fun runProvisionFallbackChain(): Boolean {
        val urlsToTry = mutableListOf<Pair<String, Boolean>>()
        getLastWorkingProvisionUrl()?.let {
            urlsToTry.add(it to true)
            Log.i(AppConfig.TAG, "VseMoiOnline: Will try last working URL first: $it")
        }
        urlsToTry.add(PRIMARY_PROVISION_URL to true)
        FALLBACK_PLATFORM_URLS.forEach { urlsToTry.add(it to false) }

        Log.i(AppConfig.TAG, "VseMoiOnline: Starting Cycle 1 - quick scan (${PROVISION_TIMEOUT_QUICK_MS}ms timeout)")
        var success = false
        val timedOutUrls = mutableListOf<Pair<String, Boolean>>()

        for ((url, isDirect) in urlsToTry) {
            if (success) break
            try {
                Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Attempting provisioning from: $url")
                val provisionUrl = if (isDirect) url else fetchProvisioningEndpoint(url, PROVISION_TIMEOUT_QUICK_MS)
                if (provisionUrl != null) {
                    val result = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_QUICK_MS)
                    if (result) {
                        success = true
                        saveLastWorkingProvisionUrl(provisionUrl)
                        Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Provisioning succeeded from: $url")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Timeout from $url, will retry in Cycle 2")
                timedOutUrls.add(url to isDirect)
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Failed from $url: ${e.message}")
            }
        }

        if (!success && timedOutUrls.isNotEmpty()) {
            withContext(Dispatchers.Main) { toast(R.string.provisioning_retry_message) }
            Log.i(AppConfig.TAG, "VseMoiOnline: Starting Cycle 2 - patient retry (${PROVISION_TIMEOUT_PATIENT_MS}ms timeout) for ${timedOutUrls.size} URLs")
            for ((url, isDirect) in timedOutUrls) {
                if (success) break
                try {
                    Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 2 - Retrying provisioning from: $url")
                    val provisionUrl = if (isDirect) url else fetchProvisioningEndpoint(url, PROVISION_TIMEOUT_PATIENT_MS)
                    if (provisionUrl != null) {
                        val result = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_PATIENT_MS)
                        if (result) {
                            success = true
                            saveLastWorkingProvisionUrl(provisionUrl)
                            Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 2 - Provisioning succeeded from: $url")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: Cycle 2 - Failed from $url: ${e.message}")
                }
            }
        }

        return success
    }

    private fun showProvisioningFailedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.provisioning_failed_title)
            .setMessage(R.string.provisioning_failed_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * VseMoiOnline: Try provisioning with fallback URLs using two-cycle approach.
     * Used for fresh (unprovisioned) devices on first launch.
     */
    private fun tryProvisioningWithFallback() {
        if (isProvisioningInProgress) {
            Log.i(AppConfig.TAG, "VseMoiOnline: Provisioning already in progress, skipping")
            return
        }
        isProvisioningInProgress = true
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = runProvisionFallbackChain()
                withContext(Dispatchers.Main) {
                    if (!success) {
                        Log.e(AppConfig.TAG, "VseMoiOnline: Background provisioning failed silently — user will be informed on connect")
                    }
                    binding.pbWaiting.hide()
                }
            } finally {
                isProvisioningInProgress = false
            }
        }
    }

    /**
     * VseMoiOnline: Fetch provisioning endpoint from a platform URL
     * Platform URLs return plain text like "103.241.67.124:8888" or "provision.example.com"
     * This method constructs the full provisioning URL from that response
     *
     * @param platformUrl The platform URL to fetch from
     * @param timeoutMs Timeout in milliseconds for this request
     */
    private fun fetchProvisioningEndpoint(platformUrl: String, timeoutMs: Int): String? {
        return try {
            val url = java.net.URL(platformUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val endpointText = connection.inputStream.bufferedReader().readText().trim()
                connection.disconnect()

                // Construct full provisioning URL
                // If response contains "http://" or "https://", use as-is
                // Otherwise, assume it's "host:port" or "domain" and construct URL
                if (endpointText.startsWith("http://") || endpointText.startsWith("https://")) {
                    endpointText
                } else {
                    // Assume HTTP for now; will migrate to HTTPS with real domains
                    "http://$endpointText/provision"
                }
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "VseMoiOnline: Failed to fetch from platform $platformUrl: ${e.message}")
            throw e // Re-throw to allow timeout detection
        }
    }

    /**
     * VseMoiOnline: Provision this device with the backend.
     * POSTs { device_fingerprint, android_id } to the provision endpoint,
     * parses the JSON response, stores xray_uuid/plan/vless_uri, and imports the VLESS config.
     *
     * @param provisionUrl Full URL of the /provision endpoint
     * @param timeoutMs Timeout in milliseconds
     * @return true if successful, false otherwise
     */
    private suspend fun fetchAndImportConfig(provisionUrl: String, timeoutMs: Int): Boolean {
        return try {
            val deviceFingerprint = getOrCreateDeviceId()
            val androidId = getAndroidId()

            val jsonBody = buildString {
                append("{\"device_fingerprint\":\"$deviceFingerprint\"")
                if (androidId != null) append(",\"android_id\":\"$androidId\"")
                append("}")
            }

            Log.i(AppConfig.TAG, "VseMoiOnline: POSTing to provision: $provisionUrl")

            val url = java.net.URL(provisionUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val json = org.json.JSONObject(responseText)
                val vlessUri = json.getString("vless_uri")
                val xrayUuid = json.getString("xray_uuid")
                val plan = json.getString("plan")
                val cabinetUrl = if (json.isNull("cabinet_url")) null else json.getString("cabinet_url")

                saveProvisionData(xrayUuid, plan, vlessUri, provisionUrl, cabinetUrl)
                val provZone   = if (json.isNull("zone"))   null else json.optString("zone")
                val provRegion = if (json.isNull("region")) null else json.optString("region")
                if (provZone != null || provRegion != null) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                        if (provZone   != null) putString(PREF_SERVER_ZONE,   provZone)
                        if (provRegion != null) putString(PREF_SERVER_REGION, provRegion)
                        apply()
                    }
                }
                Log.i(AppConfig.TAG, "VseMoiOnline: Provisioned — plan=$plan, importing config")

                // Immediately fetch status so days_remaining/traffic are populated before UI renders
                try {
                    pollStatus(xrayUuid)
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putLong(PREF_LAST_STATUS_POLL_MS, System.currentTimeMillis())
                        .apply()
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: Post-provision status poll failed: ${e.message}")
                }

                withContext(Dispatchers.Main) {
                    // Remove any existing VseMoiVPN server before importing updated config
                    mainViewModel.removeAllServer()
                    importBatchConfig(vlessUri)
                    toast(R.string.toast_success)
                    updateSubscriptionHeader()
                    updateSubBlock()
                    updateServerRow()
                }
                true
            } else {
                connection.disconnect()
                Log.w(AppConfig.TAG, "VseMoiOnline: Provision HTTP error: $responseCode")
                false
            }

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "VseMoiOnline: Provision failed for $provisionUrl", e)
            throw e // Re-throw to allow timeout detection in two-cycle logic
        }
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val xrayUuid = prefs.getString(PREF_XRAY_UUID, null) ?: run {
            Log.w(AppConfig.TAG, "VseMoiOnline: No xray_uuid stored, skipping status polling")
            return
        }
        statusPollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    pollStatus(xrayUuid)
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: Status poll failed: ${e.message}")
                }
                delay(45_000)
            }
        }
        Log.i(AppConfig.TAG, "VseMoiOnline: Status polling started")
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private suspend fun pollStatus(xrayUuid: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val backendBaseUrl = prefs.getString(PREF_BACKEND_BASE_URL, null) ?: return

        val connection = java.net.URL("$backendBaseUrl/status/$xrayUuid").openConnection()
            as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val responseCode = connection.responseCode
        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            Log.w(AppConfig.TAG, "VseMoiOnline: Status poll HTTP $responseCode")
            return
        }

        val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
        connection.disconnect()

        val plan = json.getString("plan")
        val currentDomain = if (json.isNull("current_domain")) null else json.getString("current_domain")
        val daysRemaining = if (json.isNull("days_remaining")) 0 else json.getInt("days_remaining")
        val throttleMbps = if (json.isNull("throttle_mbps")) 0f else json.getDouble("throttle_mbps").toFloat()
        val trafficTotalGb = if (!json.isNull("traffic_cap_mb"))
            json.getDouble("traffic_cap_mb").toFloat() / 1000f
        else
            if (plan == "paid") 250f else 25f
        Log.d(AppConfig.TAG, "VseMoiOnline: Status — plan=$plan domain=$currentDomain throttle=${throttleMbps}Mbps cap=${trafficTotalGb}GB")
        val trafficRemainingGb: Float = when {
            !json.isNull("traffic_consumed_mb") -> {
                val consumedGb = json.getDouble("traffic_consumed_mb").toFloat() / 1000f
                (trafficTotalGb - consumedGb).coerceAtLeast(0f)
            }
            else -> trafficTotalGb
        }

        val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_PLAN, plan)
            .putInt(PREF_PAID_DAYS_REMAINING, daysRemaining)
            .putFloat(PREF_TRAFFIC_TOTAL_GB, trafficTotalGb)
            .putFloat(PREF_TRAFFIC_REMAINING_GB, trafficRemainingGb)
            .putFloat(PREF_THROTTLE_MBPS, throttleMbps)
        if (!json.isNull("cabinet_url")) editor.putString(PREF_CABINET_URL, json.getString("cabinet_url"))
        editor.apply()

        withContext(Dispatchers.Main) {
            val dbgDays = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_PAID_DAYS_REMAINING, -1)
            Log.i(AppConfig.TAG, "VseMoiOnline: Post-poll UI update on main thread — days=$dbgDays")
            updateSubscriptionHeader()
            updateSubBlock()
            refreshFabIdleAppearance()
        }

        // Phase 2: if current_domain changed, update backend_base_url and re-provision.
        // Skipped for now — domain_names table is empty in Phase 1.
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            adapter.notifyDataSetChanged()  // Refresh adapter to update switch states
            if (isRunning) {
                setFabConnected(true)
                binding.fab.contentDescription = getString(R.string.action_stop_service)
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
                startStatusPolling()
                updateVpnStatusLabel("CONNECTED")
            } else {
                binding.fab.contentDescription = getString(R.string.tasker_start_service)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                stopStatusPolling()
                refreshFabIdleAppearance()
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = false  // VseMoiOnline: tab group hidden in main UX
    }

    internal fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    /**
     * VseMoiOnline: Re-provision before connecting to ensure the UUID is registered with Xray.
     * Handles mid-session Xray restarts: user notices traffic stopped → disconnects → taps connect
     * → UUID re-registered → VPN starts. Falls back to cached config if backend unreachable.
     */
    private fun provisionThenConnect() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val storedUuid = prefs.getString(PREF_XRAY_UUID, null)
        binding.pbWaiting.show()
        if (storedUuid == null) {
            // Fresh device — background provisioning hasn't succeeded yet; try now explicitly.
            lifecycleScope.launch(Dispatchers.IO) {
                val success = try {
                    runProvisionFallbackChain()
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: Pre-connect provision failed for fresh device: ${e.message}")
                    false
                }
                withContext(Dispatchers.Main) {
                    binding.pbWaiting.hide()
                    if (success) startV2Ray() else showProvisioningFailedDialog()
                }
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                runProvisionFallbackChain()
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: Pre-connect provision failed: ${e.message}")
                false
            }
            if (success) {
                prefs.edit().putLong(PREF_LAST_STATUS_POLL_MS, System.currentTimeMillis()).apply()
            }
            withContext(Dispatchers.Main) {
                binding.pbWaiting.hide()
                if (success) {
                    startV2Ray()
                } else {
                    Log.e(AppConfig.TAG, "VseMoiOnline: Pre-connect provision failed — not starting VPN")
                    showProvisioningFailedDialog()
                }
            }
        }
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
        updateSubscriptionHeader()
        updateSubBlock()
        refreshFabIdleAppearance()

        // VseMoiOnline: Auto-provision on first launch if no servers configured
        checkAndAutoProvision()
    }

    /**
     * VseMoiOnline: Ensures the device is provisioned and the Xray user is registered.
     *
     * On every cold start, for a known device, calls /provision (rate-limited to 30 min).
     * This re-registers the UUID with Xray (handles restarts/redeploys on any server) and
     * returns a fresh VLESS URI (handles server reassignment). Falls back to the cached
     * config if the backend is unreachable. Within the rate-limit window the cached config
     * is used directly; MMKV is re-populated from SharedPreferences if it was cleared.
     *
     * Fresh device (no stored UUID): full provisioning via two-cycle fallback chain.
     */
    private fun checkAndAutoProvision() {
        // Activation deep link takes ownership of provisioning — don't race it.
        if (pendingActivationToken != null) {
            Log.i(AppConfig.TAG, "VseMoiOnline: Skipping auto-provision — activation in progress")
            return
        }

        val serverList = MmkvManager.decodeServerList()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (serverList.size > 1) {
            // Multiple configs — stale entries from a previous free→paid upgrade. Clean up.
            val storedVlessUri = prefs.getString(PREF_VLESS_URI, null)
            if (storedVlessUri != null) {
                Log.w(AppConfig.TAG, "VseMoiOnline: Found ${serverList.size} configs, expected 1 — removing stale entries")
                mainViewModel.removeAllServer()
                importBatchConfig(storedVlessUri)
                return
            }
            mainViewModel.removeAllServer()
            // fall through to full provisioning below
        }

        val storedXrayUuid = prefs.getString(PREF_XRAY_UUID, null)
        val storedVlessUri = prefs.getString(PREF_VLESS_URI, null)

        if (storedXrayUuid == null || storedVlessUri == null) {
            // Fresh device — full provisioning via fallback chain.
            Log.i(AppConfig.TAG, "VseMoiOnline: No servers configured, starting provisioning")
            tryProvisioningWithFallback()
            return
        }

        // Known device — re-provision on cold start, rate-limited to 30 min.
        // Ensures the UUID is registered with Xray (handles restarts on any server instance)
        // and the VLESS URI is current (handles server reassignment between sessions).
        val lastPollMs = prefs.getLong(PREF_LAST_STATUS_POLL_MS, 0L)
        val elapsed = System.currentTimeMillis() - lastPollMs
        Log.i(AppConfig.TAG, "VseMoiOnline: App-open check — uuid=${storedXrayUuid.take(8)} elapsed=${elapsed}ms limit=${STATUS_POLL_INTERVAL_MS}ms")

        if (elapsed < STATUS_POLL_INTERVAL_MS) {
            // Within rate-limit window — re-import cached config into MMKV if it was cleared.
            if (serverList.isEmpty()) {
                Log.i(AppConfig.TAG, "VseMoiOnline: Within poll interval, re-importing stored config")
                importBatchConfig(storedVlessUri)
            }
            return
        }

        val provisionUrl = prefs.getString(PREF_LAST_WORKING_URL, null) ?: PRIMARY_PROVISION_URL
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(AppConfig.TAG, "VseMoiOnline: App-open re-provision from $provisionUrl")
                val success = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_QUICK_MS)
                if (success) {
                    prefs.edit().putLong(PREF_LAST_STATUS_POLL_MS, System.currentTimeMillis()).apply()
                    saveLastWorkingProvisionUrl(provisionUrl)
                    Log.i(AppConfig.TAG, "VseMoiOnline: App-open re-provision complete")
                    return@launch
                }
                Log.w(AppConfig.TAG, "VseMoiOnline: App-open primary provision failed, trying fallback chain")
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: App-open primary provision failed: ${e.message}, trying fallback chain")
            }
            // Primary URL failed — try the full fallback chain before giving up.
            val fallbackSuccess = runProvisionFallbackChain()
            if (!fallbackSuccess) {
                Log.w(AppConfig.TAG, "VseMoiOnline: Re-provision failed, using cached config")
                if (serverList.isEmpty()) {
                    withContext(Dispatchers.Main) { importBatchConfig(storedVlessUri) }
                }
            }
        }
    }

    public override fun onPause() {
        super.onPause()
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    // ── VseMoiOnline UI ──────────────────────────────────────────────────

    /**
     * Wire up all VseMoiOnline-specific click handlers and initialise the UI
     * from whatever data is already stored in SharedPreferences.
     */
    private fun setupVsmUi() {
        binding.layoutSubHeader.setOnClickListener { toggleSubBlock() }
        binding.tvSubLink.setOnClickListener { openCabinetUrl() }
        val isNightSetup = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        if (isNightSetup) {
            binding.tvSubLink.setBackgroundResource(R.drawable.pill_link_bg_dark)
        }
        binding.rowServer.setOnClickListener { onServerRowTapped() }
        binding.btnPay.setOnClickListener { openCabinetUrl() }
        binding.btnFamily.setOnClickListener { /* Phase 3 — family referral */ }
        binding.btnRenew.setOnClickListener { openCabinetUrl() }
        binding.btnCodeSubmit.setOnClickListener { submitActivationCode() }
        setupCodeFields()
        updateSubscriptionHeader()
        updateServerRow()
        updateSubBlock()
    }

    private fun toggleSubBlock() {
        val expanding = binding.layoutSubBody.visibility != View.VISIBLE
        binding.layoutSubBody.visibility = if (expanding) View.VISIBLE else View.GONE
        binding.tvSubChevron.animate()
            .rotation(if (expanding) 0f else 180f)
            .setDuration(200)
            .start()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val plan = prefs.getString(PREF_PLAN, "free") ?: "free"
        val days = prefs.getInt(PREF_PAID_DAYS_REMAINING, 0)
        val trafficTotal = prefs.getFloat(PREF_TRAFFIC_TOTAL_GB, if (plan == "paid") 250f else 25f)
        val trafficRemaining = prefs.getFloat(PREF_TRAFFIC_REMAINING_GB, trafficTotal)
        val trafficPct = (trafficRemaining / trafficTotal).coerceIn(0f, 1f)
        val effectiveDays = if (plan == "paid" && trafficRemaining <= 0f) 0 else days
        updateCollapsedHint(expanding, plan, effectiveDays, trafficPct)
    }

    private fun openCabinetUrl() {
        val url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_CABINET_URL, null) ?: return
        Utils.openUri(this, url)
    }

    private fun onServerRowTapped() {
        val plan = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PLAN, "free")
        if (plan != "paid") {
            AlertDialog.Builder(this)
                .setTitle(R.string.vsm_server_dialog_title)
                .setMessage(R.string.vsm_server_dialog_body)
                .setNegativeButton(R.string.vsm_server_dialog_cancel) { _, _ -> }
                .setPositiveButton(R.string.vsm_server_dialog_cta) { _, _ -> openCabinetUrl() }
                .show()
            return
        }
        showZonePicker()
    }

    private fun showZonePicker() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val zones = try { fetchZones() } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: fetchZones failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) {
                binding.pbWaiting.hide()
                if (zones.isNullOrEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(R.string.vsm_zone_switch_error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@withContext
                }
                val currentRegion = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_SERVER_REGION, null)
                val labels = zones.map { z ->
                    val flag      = regionToFlag(z.region)
                    val connected = if (z.region == currentRegion)
                        " — ${getString(R.string.vsm_zone_connected)}" else ""
                    "$flag ${z.zone}$connected"
                }.toTypedArray()
                val currentIdx = zones.indexOfFirst { it.region == currentRegion }.takeIf { it >= 0 } ?: 0

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.vsm_zone_picker_title)
                    .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                        dialog.dismiss()
                        val selected = zones[which]
                        if (selected.region != currentRegion) {
                            switchZone(selected.region, selected.zone)
                        }
                    }
                    .setNegativeButton(R.string.vsm_server_dialog_cancel, null)
                    .show()
            }
        }
    }

    private suspend fun fetchZones(): List<ZoneItem> {
        val baseUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null) ?: return emptyList()
        val conn = java.net.URL("$baseUrl/zones").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout    = 5000
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val arr = org.json.JSONArray(text)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ZoneItem(
                zone      = obj.getString("zone"),
                region    = obj.optString("region", ""),
                available = obj.optBoolean("available", true)
            )
        }
    }

    private fun switchZone(region: String, zone: String) {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fingerprint = getOrCreateDeviceId()
                val baseUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_BACKEND_BASE_URL, null)
                if (baseUrl == null) {
                    withContext(Dispatchers.Main) {
                        binding.pbWaiting.hide()
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.vsm_zone_switch_error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return@launch
                }

                val body = "{\"region\":\"$region\"}"
                val conn = java.net.URL("$baseUrl/servers/select")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput      = true
                conn.connectTimeout = 8000
                conn.readTimeout    = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-fingerprint", fingerprint)
                val androidId = getAndroidId()
                if (androidId != null) conn.setRequestProperty("x-android-id", androidId)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code == 200) {
                    val json     = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    val vlessUri     = json.getString("vless_uri")
                    val returnedZone = json.optString("zone", zone)
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_VLESS_URI, vlessUri)
                        .putString(PREF_SERVER_ZONE, returnedZone)
                        .putString(PREF_SERVER_REGION, region)
                        .apply()
                    withContext(Dispatchers.Main) {
                        binding.pbWaiting.hide()
                        mainViewModel.removeAllServer()
                        importBatchConfig(vlessUri)
                        updateServerRow()
                        restartV2Ray()
                    }
                } else if (code == 503) {
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        binding.pbWaiting.hide()
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.vsm_zone_unavailable)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        binding.pbWaiting.hide()
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.vsm_zone_switch_error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "VseMoiOnline: Zone switch failed", e)
                withContext(Dispatchers.Main) {
                    binding.pbWaiting.hide()
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(R.string.vsm_zone_switch_error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun setupCodeFields() {
        codeFields = arrayOf(
            binding.etCode1, binding.etCode2, binding.etCode3, binding.etCode4,
            binding.etCode5, binding.etCode6, binding.etCode7, binding.etCode8
        )

        val alphanumericFilter = InputFilter { source, _, _, _, _, _ ->
            if (isPasting) return@InputFilter null  // handled by paste logic
            source.filter { it.isLetterOrDigit() }.toString().uppercase()
        }

        for (i in codeFields.indices) {
            val field = codeFields[i]
            field.filters = arrayOf(alphanumericFilter, InputFilter.LengthFilter(1))

            // Paste detection: if pasted text is longer than 1 char, try to fill all fields
            field.filters = arrayOf(
                InputFilter { source, start, end, _, _, _ ->
                    val chunk = source.subSequence(start, end).toString()
                    if (chunk.length > 1) {
                        // This is a paste — handle it asynchronously to avoid filter recursion
                        field.post { handleCodePaste(chunk) }
                        return@InputFilter ""  // block the raw paste into this single field
                    }
                    if (isPasting) return@InputFilter null
                    chunk.filter { it.isLetterOrDigit() }.toString().uppercase()
                        .takeIf { it.isNotEmpty() } ?: ""
                },
                InputFilter.LengthFilter(1)
            )

            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isPasting) return
                    if ((s?.length ?: 0) == 1 && i < codeFields.lastIndex) {
                        codeFields[i + 1].requestFocus()
                    }
                    checkSubmitEnabled()
                }
            })

            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (field.text.isNullOrEmpty() && i > 0) {
                        val prev = codeFields[i - 1]
                        prev.requestFocus()
                        prev.text?.clear()
                        checkSubmitEnabled()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun handleCodePaste(raw: String) {
        // Strip hyphens/spaces, uppercase, keep only alphanumeric
        val clean = raw.replace("-", "").replace(" ", "")
            .filter { it.isLetterOrDigit() }.toString().uppercase()
        if (clean.length != 8) return  // wrong format — ignore
        isPasting = true
        for (i in codeFields.indices) {
            codeFields[i].setText(clean[i].toString())
        }
        isPasting = false
        codeFields.last().requestFocus()
        checkSubmitEnabled()
    }

    private fun checkSubmitEnabled() {
        val allFilled = codeFields.all { it.text?.length == 1 }
        binding.btnCodeSubmit.isEnabled = allFilled
        binding.btnCodeSubmit.alpha = if (allFilled) 1f else 0.4f
    }

    private fun submitActivationCode() {
        val code = codeFields.joinToString("") { it.text.toString() }
        if (code.length != 8) return
        codeFields.forEach { it.text?.clear() }
        codeFields.first().requestFocus()
        checkSubmitEnabled()
        pendingActivationToken = code
        handleActivateDeepLink(code)
    }

    /**
     * Refresh the subscription row text and colour from SharedPreferences.
     * Call after provisioning or status poll.
     */
    private fun updateSubscriptionHeader() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val plan = prefs.getString(PREF_PLAN, "free")
        val days = prefs.getInt(PREF_PAID_DAYS_REMAINING, 0)
        val trafficRemainingGb = prefs.getFloat(PREF_TRAFFIC_REMAINING_GB, Float.MAX_VALUE)
        // Traffic exhaustion overrides days: show 0 so the user understands why VPN is blocked
        val effectiveDays = if (plan == "paid" && trafficRemainingGb <= 0f) 0 else days

        if (plan == "free") {
            binding.tvSubValue.text = getString(R.string.vsm_sub_free)
            binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_free))
            binding.tvSubValue.clearAnimation()
            setSubValueUrgentPulse(false)
        } else {
            binding.tvSubValue.text = formatDaysRu(effectiveDays)
            when {
                effectiveDays <= 3 -> {
                    binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_urgent))
                    binding.tvSubValue.clearAnimation()
                    setSubValueUrgentPulse(true)
                }
                effectiveDays <= 5 -> {
                    binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_warn))
                    binding.tvSubValue.clearAnimation()
                    setSubValueUrgentPulse(false)
                }
                else -> {
                    binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_ok))
                    binding.tvSubValue.clearAnimation()
                    setSubValueUrgentPulse(false)
                }
            }
        }

        binding.tvSubLink.visibility = if (plan == "free") View.GONE else View.VISIBLE
    }

    private fun startBlinking(view: View) {
        val anim = AlphaAnimation(1.0f, 0.15f).apply {
            duration = 550
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        view.startAnimation(anim)
    }

    private fun updateServerRow() {
        val prefs  = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val region = prefs.getString(PREF_SERVER_REGION, null)
        val zone   = prefs.getString(PREF_SERVER_ZONE, null) ?: "Германия, Франкфурт"
        binding.tvServerFlag.text = regionToFlag(region)
        binding.tvServerName.text = zone

        val plan = prefs.getString(PREF_PLAN, "free")
        binding.tvServerArrow.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (plan == "paid") R.color.vsm_sub_ok else R.color.vsm_border
            )
        )
    }

    private fun regionToFlag(region: String?): String = when {
        region == null                                                  -> "🌐"
        region.startsWith("EU-MD")                                     -> "🇲🇩"
        region.startsWith("EU-FR") || region.startsWith("EU-DE")      -> "🇩🇪"
        region.startsWith("EU-")                                       -> "🇪🇺"
        region.startsWith("US-")                                       -> "🇺🇸"
        else                                                            -> "🌐"
    }

    /**
     * Update the collapsible sub-block: donut charts, comparison table, action buttons.
     * Uses values saved by the last /provision or /status response.
     */
    private fun updateSubBlock() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val plan = prefs.getString(PREF_PLAN, "free")
        val days = prefs.getInt(PREF_PAID_DAYS_REMAINING, 0)
        val trafficTotalGb = prefs.getFloat(PREF_TRAFFIC_TOTAL_GB, if (plan == "paid") 250f else 25f)
        val trafficRemainingGb = prefs.getFloat(PREF_TRAFFIC_REMAINING_GB, trafficTotalGb)
        val effectiveDays = if (plan == "paid" && trafficRemainingGb <= 0f) 0 else days
        val throttleMbps = prefs.getFloat(PREF_THROTTLE_MBPS, 0f)

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val ringBg = ContextCompat.getColor(this, R.color.vsm_ring_bg)

        // ── Traffic ring ─────────────────────────────────────────────────
        val trafficFraction = (trafficRemainingGb / trafficTotalGb).coerceIn(0f, 1f)
        binding.chartTraffic.apply {
            ringBgColor = ringBg
            ringColor = trafficRingColor(trafficFraction)
            fraction = trafficFraction
            centerValue = formatGb(trafficRemainingGb)
            centerSub = getString(R.string.vsm_traffic_of_format, trafficTotalGb.toInt())
            subTextColor = ContextCompat.getColor(this@MainActivity, R.color.vsm_hint_text)
            showGhostArc = false
        }

        // ── Speed ring ───────────────────────────────────────────────────
        // throttle_mbps from /status represents the tier cap (0 = not yet polled)
        val trafficExhausted = trafficRemainingGb <= 0f
        val speedFraction = if (trafficExhausted || throttleMbps <= 0f) 0f
                            else (throttleMbps / 25f).coerceIn(0f, 1f)
        val speedColor = when {
            trafficExhausted -> ContextCompat.getColor(this, R.color.vsm_traffic_red)
            plan == "free"   -> ContextCompat.getColor(this, R.color.vsm_speed_blue)
            else             -> ContextCompat.getColor(this, R.color.vsm_traffic_green)
        }

        binding.chartSpeed.apply {
            ringBgColor = ringBg
            ringColor = speedColor
            fraction = speedFraction
            centerValue = if (trafficExhausted) "0.0"
            else if (throttleMbps > 0f)
                if (throttleMbps % 1f == 0f) throttleMbps.toInt().toString()
                else String.format("%.1f", throttleMbps)
            else "–"
            centerSub = getString(R.string.vsm_speed_of_format, 25)
            subTextColor = if (trafficExhausted)
                ContextCompat.getColor(this@MainActivity, R.color.vsm_traffic_red)
            else
                ContextCompat.getColor(this@MainActivity, R.color.vsm_hint_text)
            showGhostArc = (plan == "free") && !trafficExhausted
            ghostColor = ContextCompat.getColor(this@MainActivity, R.color.vsm_mint)
            ghostAlpha = if (isNight) 77 else 56
        }

        // ── Table and buttons ────────────────────────────────────────────
        // Free traffic cell is dynamic (from traffic_cap_mb via PREF_TRAFFIC_TOTAL_GB).
        // Paid traffic cell stays static — /status for free users doesn't return the paid cap.
        binding.tvCmpFreeTraffic.text = "${trafficTotalGb.toInt()} ГБ"

        binding.layoutCmpTable.visibility = if (plan == "free") View.VISIBLE else View.GONE
        updateSubButtons(plan ?: "free", effectiveDays, trafficFraction)

        // ── Activation code section ───────────────────────────────────────
        // Show for free users and paid users running low on time or out of traffic
        binding.layoutActivationSection.visibility =
            if (plan == "free" || (plan == "paid" && effectiveDays <= 3)) View.VISIBLE else View.GONE

        // ── Collapsed hint ────────────────────────────────────────────────
        // Initialize on every data refresh, not just on toggle
        val bodyVisible = binding.layoutSubBody.visibility == View.VISIBLE
        updateCollapsedHint(bodyVisible, plan ?: "free", effectiveDays, trafficFraction)
    }

    private fun trafficRingColor(fraction: Float): Int = ContextCompat.getColor(
        this, when {
            fraction > 0.50f -> R.color.vsm_traffic_green
            fraction > 0.25f -> R.color.vsm_traffic_amber
            fraction > 0.10f -> R.color.vsm_traffic_orange
            else             -> R.color.vsm_traffic_red
        }
    )

    /** Format GB value: integer when ≥ 10, one decimal place when < 10. */
    private fun formatGb(gb: Float): String =
        if (gb >= 10f) gb.toInt().toString() else String.format("%.1f", gb)

    // ── End VseMoiOnline UI ──────────────────────────────────────────────

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.source_code -> Utils.openUri(this, AppConfig.VSM_APP_URL)
            R.id.oss_licenses -> {
                val webView = android.webkit.WebView(this)
                webView.loadUrl("file:///android_asset/open_source_licenses.html")
                android.app.AlertDialog.Builder(this)
                    .setTitle("Open source licenses")
                    .setView(webView)
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            R.id.tg_channel -> Utils.openUri(this, AppConfig.VSM_TG_URL)
            R.id.privacy_policy -> Utils.openUri(this, AppConfig.VSM_PRIVACY_URL)
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    // ── Background colour cycling ─────────────────────────────────────────

    private fun applyAnimatedBackgrounds() {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Toolbar: dark navy drifting through purple/wine neighbours
        val toolbarColors = if (isNight) intArrayOf(
            Color.parseColor("#0F1222"),   // base
            Color.parseColor("#131021"),   // blue-purple
            Color.parseColor("#17101F"),   // deeper purple
            Color.parseColor("#15101E"),   // wine-purple
            Color.parseColor("#121120")    // indigo
        ) else intArrayOf(
            Color.parseColor("#1F2344"),   // base
            Color.parseColor("#241F45"),   // blue-purple
            Color.parseColor("#29193E"),   // purple-wine
            Color.parseColor("#261C43"),   // deep indigo
            Color.parseColor("#221E45")    // indigo-purple
        )

        // VPN area: surface shifting through lavender and blush tints
        val vpnColors = if (isNight) intArrayOf(
            Color.parseColor("#262C4A"),   // base
            Color.parseColor("#2C2849"),   // purple
            Color.parseColor("#301F44"),   // wine-purple
            Color.parseColor("#2C2848"),   // muted purple
            Color.parseColor("#282B4A")    // blue-purple
        ) else intArrayOf(
            Color.parseColor("#F5F5F5"),   // base
            Color.parseColor("#F3F1F9"),   // lavender
            Color.parseColor("#F8F1F5"),   // blush
            Color.parseColor("#F5F1F8"),   // soft violet
            Color.parseColor("#F7F2F6")    // rose-lavender
        )

        animateBackground(binding.toolbar,     toolbarColors, 9_000L)
        animateBackground(binding.mainContent, vpnColors,    12_000L)
    }

    private fun animateBackground(view: View, colors: IntArray, halfCycleMs: Long) {
        ValueAnimator.ofArgb(*colors).apply {
            duration     = halfCycleMs
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.REVERSE   // seamless loop — no jump at ends
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { view.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    // ── Change 6: subscription button animations ─────────────────────────

    private var renewPulseAnimator: ValueAnimator? = null

    private fun formatDaysRu(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        val word = when {
            mod100 in 11..14 -> "дней"
            mod10 == 1       -> "день"
            mod10 in 2..4    -> "дня"
            else             -> "дней"
        }
        return "$n $word"
    }

    private fun updateSubButtons(plan: String, daysRemaining: Int, trafficFraction: Float) {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        when {
            plan == "free" -> {
                binding.btnPayContainer.visibility = View.VISIBLE
                binding.btnFamily.visibility = View.GONE
                binding.btnRenew.visibility = View.GONE
                stopRenewPulse()
            }
            daysRemaining <= 3 || trafficFraction < 0.10f -> {
                binding.btnPayContainer.visibility = View.GONE
                binding.btnFamily.visibility = View.GONE
                binding.btnRenew.visibility = View.VISIBLE
                val urgentColor = ContextCompat.getColor(this, R.color.vsm_sub_urgent)
                val urgentHiColor = ContextCompat.getColor(this, R.color.vsm_sub_urgent_hi)
                startRenewPulse(urgentColor, urgentHiColor)
            }
            else -> {
                binding.btnPayContainer.visibility = View.GONE
                binding.btnRenew.visibility = View.GONE
                binding.btnFamily.visibility = View.VISIBLE
                stopRenewPulse()
                if (isNight) {
                    binding.btnFamily.setTextColor(
                        ContextCompat.getColor(this, R.color.vsm_sub_ok))
                    binding.btnFamily.strokeColor = ColorStateList.valueOf(
                        Color.argb(51, 129, 199, 132))
                }
            }
        }
    }

    private fun startRenewPulse(colorA: Int, colorB: Int) {
        renewPulseAnimator?.cancel()
        renewPulseAnimator = ValueAnimator.ofArgb(colorA, colorB).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                binding.btnRenew.setTextColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    private fun stopRenewPulse() {
        renewPulseAnimator?.cancel()
        renewPulseAnimator = null
    }

    // ── Change 7: tv_sub_value urgent pulse ──────────────────────────────

    private var subValuePulseAnimator: ValueAnimator? = null

    private fun setSubValueUrgentPulse(urgent: Boolean) {
        subValuePulseAnimator?.cancel()
        if (!urgent) {
            subValuePulseAnimator = null
            return
        }
        val colorA = ContextCompat.getColor(this, R.color.vsm_sub_urgent)
        val colorB = ContextCompat.getColor(this, R.color.vsm_sub_urgent_hi)
        subValuePulseAnimator = ValueAnimator.ofArgb(colorA, colorB).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                binding.tvSubValue.setTextColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    // ── Change 8: collapsed hint badge ───────────────────────────────────

    private var hintPulseAnimator: ValueAnimator? = null

    private fun updateCollapsedHint(subBodyVisible: Boolean, plan: String,
                                     daysRemaining: Int, trafficPct: Float) {
        if (subBodyVisible) {
            binding.tvSubTrafficHint.visibility = View.GONE
            hintPulseAnimator?.cancel()
            return
        }
        val daysUrgent  = plan == "paid" && daysRemaining <= 3
        val trafficWarn = trafficPct in 0.10f..0.30f
        val trafficUrge = trafficPct < 0.10f

        when {
            daysUrgent || trafficUrge -> {
                val label = when {
                    daysUrgent -> "$daysRemaining д."
                    else -> "${(trafficPct * 100).toInt()}% трафика"
                }
                binding.tvSubTrafficHint.text = label
                binding.tvSubTrafficHint.visibility = View.VISIBLE
                val colorA = ContextCompat.getColor(this, R.color.vsm_sub_urgent)
                val colorB = ContextCompat.getColor(this, R.color.vsm_sub_urgent_hi)
                hintPulseAnimator?.cancel()
                hintPulseAnimator = ValueAnimator.ofArgb(colorA, colorB).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        binding.tvSubTrafficHint.setTextColor(it.animatedValue as Int)
                    }
                    start()
                }
            }
            trafficWarn -> {
                val pct = (trafficPct * 100).toInt()
                binding.tvSubTrafficHint.text = "$pct% трафика"
                binding.tvSubTrafficHint.setTextColor(
                    ContextCompat.getColor(this, R.color.vsm_sub_warn))
                binding.tvSubTrafficHint.visibility = View.VISIBLE
                hintPulseAnimator?.cancel()
            }
            else -> {
                binding.tvSubTrafficHint.visibility = View.GONE
                hintPulseAnimator?.cancel()
            }
        }
    }

    // ── Change 11: VPN status label ──────────────────────────────────────

    private fun updateVpnStatusLabel(state: String) {
        binding.tvVpnStatus.text = when (state) {
            "CONNECTED"  -> getString(R.string.vpn_connected)
            "CONNECTING" -> getString(R.string.vpn_connecting)
            else         -> getString(R.string.vpn_disconnected)
        }
    }

    // ── Change 2: FAB gradient + pulse ───────────────────────────────────

    private fun setFabConnected(connected: Boolean) {
        binding.fab.setImageResource(R.drawable.ic_power)
        if (connected) {
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.vsm_fab_stop))
            binding.fabHalo.backgroundTintList = ColorStateList.valueOf(
                Color.argb(80, 183, 28, 28))   // semi-transparent dark red
            startFabPulse()
        } else {
            refreshFabIdleAppearance()
        }
    }

    /** Returns a user-facing block reason if connection should be prevented, null if allowed. */
    private fun connectionBlockedReason(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val plan = prefs.getString(PREF_PLAN, null) ?: return null
        if (plan == "paid" && prefs.getInt(PREF_PAID_DAYS_REMAINING, Int.MAX_VALUE) == 0)
            return getString(R.string.vpn_blocked_days)
        if (prefs.getFloat(PREF_TRAFFIC_REMAINING_GB, Float.MAX_VALUE) <= 0f)
            return getString(R.string.vpn_blocked_traffic)
        return null
    }

    /** Apply blocked (grey) or normal idle (green) FAB appearance + status label. No-op when VPN is running. */
    private fun refreshFabIdleAppearance() {
        if (mainViewModel.isRunning.value == true) return
        val reason = connectionBlockedReason()
        if (reason != null) {
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fabHalo.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.fabHalo.alpha = 0f
            stopFabPulse()
            binding.tvVpnStatus.text = reason
        } else {
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.vsm_traffic_green))
            binding.fabHalo.backgroundTintList = ColorStateList.valueOf(
                Color.argb(60, 86, 171, 123))
            binding.fabHalo.alpha = 0.7f
            stopFabPulse()
            binding.tvVpnStatus.text = getString(R.string.vpn_disconnected)
        }
    }

    private var fabPulseAnimator: android.animation.ObjectAnimator? = null

    private fun startFabPulse() {
        fabPulseAnimator?.cancel()
        binding.fabHalo.alpha = 0.75f
        binding.fabHalo.scaleX = 1.0f
        binding.fabHalo.scaleY = 1.0f
        val scaleX = android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.35f)
        val scaleY = android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.35f)
        val alpha  = android.animation.PropertyValuesHolder.ofFloat("alpha",  0.75f, 0.15f)
        fabPulseAnimator = android.animation.ObjectAnimator
            .ofPropertyValuesHolder(binding.fabHalo, scaleX, scaleY, alpha).apply {
                duration = 1200
                repeatMode = android.animation.ObjectAnimator.REVERSE
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
    }

    private fun stopFabPulse() {
        fabPulseAnimator?.cancel()
        fabPulseAnimator = null
        binding.fabHalo.scaleX = 1.0f
        binding.fabHalo.scaleY = 1.0f
    }
}