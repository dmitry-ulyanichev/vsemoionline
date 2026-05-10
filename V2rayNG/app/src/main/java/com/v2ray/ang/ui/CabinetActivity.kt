package com.v2ray.ang.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityCabinetBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

class CabinetActivity : BaseActivity() {
    private val binding by lazy { ActivityCabinetBinding.inflate(layoutInflater) }

    private data class ApiResponse(
        val json: JSONObject,
        val cabinetSessionToken: String? = null,
    )

    private class ApiException(
        val statusCode: Int,
        message: String,
    ) : IllegalStateException(message)

    private data class CabinetMember(
        val id: Int,
        val role: String,
        val displayName: String,
        val email: String?,
        val daysRemaining: Int,
        val activationCode: String?,
        val activationRaw: String?,
        val activationUrl: String?,
    )

    private data class CabinetProductPlan(
        val label: String,
        val deviceLimit: Int,
        val usedSlots: Int,
        val trafficLabel: String,
        val trafficUsedLabel: String,
        val trafficUsedPct: Int,
        val isFull: Boolean,
        val nextLabel: String?,
        val nextDeviceLimit: Int,
        val nextTrafficLabel: String?,
    )

    private data class CabinetHistoryEntry(
        val dateLabel: String,
        val descriptionHtml: String,
        val daysLabel: String,
        val daysClass: String,
    )

    private data class CabinetHistoryPage(
        val items: List<CabinetHistoryEntry>,
        val nextCursor: String?,
        val hasMore: Boolean,
        val filter: String,
    )

    private data class DownloadOption(
        val badge: String?,
        val name: String,
        val size: String,
        val url: String,
    )

    private enum class StatusMode {
        INFO,
        ERROR,
        SUCCESS
    }

    companion object {
        private const val PREFS_NAME = "vsemoionline_prefs"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val PREF_CABINET_EMAIL = "cabinet_email"
        private const val PREF_CABINET_SESSION_TOKEN = "cabinet_session_token"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_PLAN = "plan"
        private const val PREF_XRAY_UUID = "xray_uuid"
        private const val PRIMARY_PROVISION_URL = "https://vmonl.store/provision"
        private const val ANDROID_DOWNLOAD_PATH = "/download/android"
        private const val CABINET_SESSION_COOKIE_NAME = "cabinet_session"
        private const val CABINET_CODE_LENGTH = 6
    }

    private var members: List<CabinetMember> = emptyList()
    private var productPlan: CabinetProductPlan? = null
    private var hasChanged = false
    private var attemptedTrustedDeviceSession = false
    private var historyFilter = "payment"
    private var historyNextCursor: String? = null
    private var historyHasMore = false
    private var isLoadingHistory = false
    private var downloadExpanded = false

    private val paymentActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            hasChanged = true
            loadCabinet()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        configureVseMoiSystemBars()

        title = getString(R.string.vsm_toolbar_title)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.navigationContentDescription = getString(R.string.title_pref_back)

        binding.btnCabinetSendCode.setOnClickListener { sendCode(showVerificationState = true) }
        binding.btnCabinetResendCode.setOnClickListener { sendCode(showVerificationState = false) }
        binding.btnCabinetVerify.setOnClickListener { verifyCode() }
        binding.btnCabinetRefresh.setOnClickListener { loadCabinet() }
        binding.btnCabinetRenew.setOnClickListener {
            paymentActivity.launch(Intent(this, PaymentActivity::class.java))
        }
        binding.btnCabinetAddMember.setOnClickListener {
            val displayName = binding.etCabinetMemberName.text?.toString()?.trim().orEmpty()
            val email = binding.etCabinetMemberEmail.text?.toString()?.trim()?.lowercase().orEmpty()
            if (displayName.isBlank() && email.isBlank()) {
                showMemberNameError(true)
            } else {
                showMemberNameError(false)
                addMember(displayName = displayName, email = email)
            }
        }

        binding.btnHistoryToggle.setOnClickListener {
            val isExpanded = binding.layoutHistoryBody.visibility == View.VISIBLE
            if (isExpanded) {
                binding.layoutHistoryBody.visibility = View.GONE
                binding.ivHistoryArrow.rotation = 0f
            } else {
                binding.layoutHistoryBody.visibility = View.VISIBLE
                binding.ivHistoryArrow.rotation = 180f
                if (binding.llHistoryRows.childCount == 0) {
                    loadHistory(reset = true)
                }
            }
        }

        binding.chipHistoryAll.setOnClickListener { setHistoryFilter("all") }
        binding.chipHistoryPayment.setOnClickListener { setHistoryFilter("payment") }
        binding.chipHistoryDistribution.setOnClickListener { setHistoryFilter("distribution") }
        binding.chipHistoryPayment.isSelected = true
        binding.btnHistoryLoadMore.setOnClickListener { loadHistory(reset = false) }

        binding.btnHistoryLoadMore.text = getString(R.string.vsm_cabinet_show_more)
        binding.btnHistoryLoadMore.visibility = View.GONE
        binding.layoutHistoryBody.visibility = View.GONE
        binding.layoutHistoryEmpty.visibility = View.GONE
        binding.layoutHistoryTable.visibility = View.VISIBLE

        binding.btnDownloadToggle.setOnClickListener {
            downloadExpanded = !downloadExpanded
            if (downloadExpanded) {
                binding.layoutDownloadBody.visibility = View.VISIBLE
                binding.ivDownloadArrow.rotation = 180f
                if (binding.llDownloadOptions.childCount == 0) {
                    populateDownloadOptions()
                }
            } else {
                binding.layoutDownloadBody.visibility = View.GONE
                binding.ivDownloadArrow.rotation = 0f
            }
        }

        binding.btnCabinetDistribute.setOnClickListener { distributeEvenly() }

        binding.tvContactTelegram.setOnClickListener {
            Utils.openUri(this, "https://t.me/vsemoionline_bot")
        }

        binding.tvContactEmail.setOnClickListener {
            Utils.openUri(this, "mailto:vsemoionlinevpn@gmail.com")
        }

        prefillEmail()
        if (cabinetSessionToken().isNullOrBlank()) {
            openWithTrustedDevice()
        } else {
            loadCabinet()
        }
    }

    override fun finish() {
        if (hasChanged) {
            setResult(Activity.RESULT_OK)
        }
        super.finish()
    }

    private fun prefillEmail() {
        val email = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_CABINET_EMAIL, null)
            ?.trim()
            .orEmpty()
        if (email.isBlank()) return
        binding.etCabinetEmail.setText(email)
        binding.etCabinetEmail.setSelection(binding.etCabinetEmail.text?.length ?: 0)
    }

    private fun showLoginState() {
        binding.tvCabinetTitle.setText(R.string.vsm_cabinet_title)
        binding.tvCabinetIntro.setText(R.string.vsm_cabinet_intro)
        binding.layoutCabinetLogin.visibility = View.VISIBLE
        binding.layoutCabinetContent.visibility = View.GONE
        binding.layoutCabinetVerify.visibility = View.GONE
        binding.tvCabinetLoginTitle.setText(R.string.vsm_cabinet_login_title)
        binding.tvCabinetLoginBody.setText(R.string.vsm_cabinet_login_body)
    }

    private fun showTrustedDeviceLoadingState() {
        binding.tvCabinetTitle.setText(R.string.vsm_cabinet_title)
        binding.tvCabinetIntro.setText(R.string.vsm_cabinet_opening)
        binding.layoutCabinetLogin.visibility = View.GONE
        binding.layoutCabinetContent.visibility = View.GONE
    }

    private fun showVerificationState() {
        binding.layoutCabinetVerify.visibility = View.VISIBLE
        binding.tvCabinetLoginTitle.setText(R.string.vsm_cabinet_code_title)
        binding.tvCabinetLoginBody.setText(R.string.vsm_cabinet_code_body)
        binding.etCabinetCode.requestFocus()
    }

    private fun showContentState() {
        binding.tvCabinetTitle.setText(R.string.vsm_cabinet_title)
        binding.tvCabinetIntro.setText(R.string.vsm_cabinet_intro_loaded)
        binding.layoutCabinetLogin.visibility = View.GONE
        binding.layoutCabinetContent.visibility = View.VISIBLE
    }

    private fun backendBaseUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
            ?: PRIMARY_PROVISION_URL.removeSuffix("/provision").trimEnd('/')
    }

    private fun backendOrigin(): String {
        val url = URL(backendBaseUrl())
        val port = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
        return "${url.protocol}://${url.host}$port"
    }

    private fun androidDownloadUrl(): String = "${backendOrigin()}$ANDROID_DOWNLOAD_PATH"

    private fun cabinetSessionToken(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_CABINET_SESSION_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun clearCabinetSession() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(PREF_CABINET_SESSION_TOKEN)
            .apply()
    }

    private fun deviceFingerprint(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun xrayUuid(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_XRAY_UUID, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun isLocalPaidDevice(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_PLAN, null) == "paid"
    }

    private fun setBusy(isBusy: Boolean) {
        binding.pbCabinet.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.btnCabinetSendCode.isEnabled = !isBusy
        binding.btnCabinetVerify.isEnabled = !isBusy
        binding.btnCabinetResendCode.isEnabled = !isBusy
        binding.btnCabinetRefresh.isEnabled = !isBusy
        binding.btnCabinetRenew.isEnabled = !isBusy
        binding.btnCabinetAddMember.isEnabled = !isBusy
        binding.btnCabinetDistribute.isEnabled = !isBusy
        binding.etCabinetEmail.isEnabled = !isBusy
        binding.etCabinetCode.isEnabled = !isBusy
        binding.etCabinetMemberName.isEnabled = !isBusy
        binding.etCabinetMemberEmail.isEnabled = !isBusy
    }

    private fun showStatus(message: String, mode: StatusMode = StatusMode.INFO) {
        binding.tvCabinetStatus.visibility = View.VISIBLE
        binding.tvCabinetStatus.text = message
        when (mode) {
            StatusMode.INFO -> {
                binding.tvCabinetStatus.setBackgroundResource(R.drawable.restore_status_info_bg)
                binding.tvCabinetStatus.setTextColor(getColor(R.color.vsm_link))
            }
            StatusMode.ERROR -> {
                binding.tvCabinetStatus.setBackgroundResource(R.drawable.restore_status_error_bg)
                binding.tvCabinetStatus.setTextColor(getColor(R.color.vsm_sub_urgent))
            }
            StatusMode.SUCCESS -> {
                binding.tvCabinetStatus.setBackgroundResource(R.drawable.restore_status_success_bg)
                binding.tvCabinetStatus.setTextColor(getColor(R.color.vsm_sub_ok))
            }
        }
    }

    private fun sendCode(showVerificationState: Boolean) {
        val email = binding.etCabinetEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            toast(R.string.vsm_restore_email_required)
            return
        }

        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = postJson(
                    path = "/cabinet/send-code",
                    body = JSONObject().put("email", email),
                    includeSession = false,
                    includeOrigin = false,
                )
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    if (showVerificationState) {
                        showVerificationState()
                    }
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_CABINET_EMAIL, email)
                        .apply()
                    showStatus(response.json.optString("message", getString(R.string.vsm_cabinet_code_sent)), StatusMode.INFO)
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: cabinet send-code failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_cabinet_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun openWithTrustedDevice() {
        val fingerprint = deviceFingerprint()
        val xrayUuid = xrayUuid()
        if (!isLocalPaidDevice() || fingerprint.isNullOrBlank() || xrayUuid.isNullOrBlank()) {
            showLoginState()
            return
        }

        attemptedTrustedDeviceSession = true
        showTrustedDeviceLoadingState()
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = postJson(
                    path = "/cabinet/app-session",
                    body = JSONObject()
                        .put("device_fingerprint", fingerprint)
                        .put("xray_uuid", xrayUuid),
                    includeSession = false,
                    includeOrigin = false,
                )
                val token = extractSessionToken(response)
                    ?: throw IllegalStateException(getString(R.string.vsm_cabinet_session_missing))
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_CABINET_SESSION_TOKEN, token)
                    .apply {
                        response.json.optString("email").takeIf { it.isNotBlank() }?.let {
                            putString(PREF_CABINET_EMAIL, it)
                        }
                    }
                    .apply()
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    loadCabinet()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: cabinet app-session failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showLoginState()
                    if (e is ApiException && e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                        binding.tvCabinetStatus.visibility = View.GONE
                    } else {
                        showStatus(getString(R.string.vsm_cabinet_auto_login_failed), StatusMode.INFO)
                    }
                }
            }
        }
    }

    private fun verifyCode() {
        val email = binding.etCabinetEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        val code = binding.etCabinetCode.text?.toString()?.replace("\\D".toRegex(), "").orEmpty()
        if (email.isBlank()) {
            toast(R.string.vsm_restore_email_required)
            return
        }
        if (code.length != CABINET_CODE_LENGTH) {
            toast(getString(R.string.vsm_cabinet_code_required))
            return
        }

        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = postJson(
                    path = "/cabinet/verify",
                    body = JSONObject()
                        .put("email", email)
                        .put("code", code),
                    includeSession = false,
                    includeOrigin = false,
                )
                val token = extractSessionToken(response)
                    ?: throw IllegalStateException(getString(R.string.vsm_cabinet_session_missing))
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_CABINET_EMAIL, email)
                    .putString(PREF_CABINET_SESSION_TOKEN, token)
                    .apply()
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(getString(R.string.vsm_cabinet_login_success), StatusMode.SUCCESS)
                    loadCabinet()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: cabinet verify failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_cabinet_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun extractSessionToken(response: ApiResponse): String? {
        return response.cabinetSessionToken
            ?: response.json.optString("session_token").takeIf { it.isNotBlank() }
    }

    private fun loadCabinet() {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val family = getJson("/cabinet/api/family").json
                val summary = family.optJSONObject("family")
                val loadedMembers = parseMembers(family)
                val loadedProductPlan = parseProductPlan(family)
                val lastPaymentDate = runCatching {
                    parseLatestPaymentDate(getJson("/cabinet/api/history?filter=payment&limit=1").json)
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    members = loadedMembers
                    productPlan = loadedProductPlan
                    attemptedTrustedDeviceSession = false
                    renderCabinet(summary, lastPaymentDate)
                    showContentState()
                    binding.tvCabinetStatus.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: cabinet load failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    if (e is ApiException && e.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        clearCabinetSession()
                        if (!attemptedTrustedDeviceSession) {
                            openWithTrustedDevice()
                        } else {
                            showLoginState()
                            showStatus(getString(R.string.vsm_cabinet_login_expired), StatusMode.INFO)
                        }
                    } else {
                        showStatus(e.message ?: getString(R.string.vsm_cabinet_generic_error), StatusMode.ERROR)
                    }
                }
            }
        }
    }

    private fun loadHistory(reset: Boolean = false) {
        if (isLoadingHistory) return
        isLoadingHistory = true
        val cursor = if (reset) null else historyNextCursor

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = getJson(
                    "/cabinet/api/history?filter=$historyFilter&limit=10" +
                            if (cursor != null) "&cursor=$cursor" else ""
                )
                val page = parseHistoryPage(response.json)
                withContext(Dispatchers.Main) {
                    if (reset) {
                        binding.llHistoryRows.removeAllViews()
                    }
                    historyNextCursor = if (page.nextCursor.isNullOrBlank()) null else page.nextCursor
                    historyHasMore = page.hasMore
                    renderHistoryItems(page.items)
                    val totalShown = binding.llHistoryRows.childCount
                    binding.tvHistoryCount.text = getString(R.string.vsm_cabinet_shown_count, totalShown)
                    binding.tvHistoryCount.visibility = if (totalShown > 0) View.VISIBLE else View.GONE
                    binding.btnHistoryLoadMore.visibility =
                        if (historyHasMore) View.VISIBLE else View.GONE
                    binding.layoutHistoryEmpty.visibility =
                        if (page.items.isEmpty() && reset) View.VISIBLE else View.GONE
                    binding.layoutHistoryTable.visibility =
                        if (page.items.isEmpty() && reset) View.GONE else View.VISIBLE
                    isLoadingHistory = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingHistory = false
                    showStatus(e.message ?: getString(R.string.vsm_cabinet_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun parseHistoryPage(json: JSONObject): CabinetHistoryPage {
        val itemsArray = json.optJSONArray("items") ?: org.json.JSONArray()
        val items = mutableListOf<CabinetHistoryEntry>()
        for (i in 0 until itemsArray.length()) {
            val entry = itemsArray.optJSONObject(i) ?: continue
            items += CabinetHistoryEntry(
                dateLabel = entry.optString("date_label"),
                descriptionHtml = entry.optString("description_html"),
                daysLabel = entry.optString("days_label"),
                daysClass = entry.optString("days_class"),
            )
        }
        return CabinetHistoryPage(
            items = items,
            nextCursor = json.optString("nextCursor").takeIf { it.isNotBlank() },
            hasMore = json.optBoolean("hasMore", false),
            filter = json.optString("filter", "payment"),
        )
    }

    private fun setHistoryFilter(filter: String) {
        if (historyFilter == filter) return
        historyFilter = filter
        historyNextCursor = null
        binding.chipHistoryAll.isSelected = filter == "all"
        binding.chipHistoryPayment.isSelected = filter == "payment"
        binding.chipHistoryDistribution.isSelected = filter == "distribution"
        loadHistory(reset = true)
    }

    private fun parseMembers(family: JSONObject): List<CabinetMember> {
        val array = family.optJSONArray("members") ?: return emptyList()
        val result = mutableListOf<CabinetMember>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val entitlement = item.optJSONObject("entitlement")
            val activation = item.optJSONObject("activation")
            result += CabinetMember(
                id = item.optInt("id"),
                role = item.optString("role"),
                displayName = item.optString("display_name", getString(R.string.vsm_cabinet_member_fallback)),
                email = item.optString("email").takeIf { it.isNotBlank() },
                daysRemaining = entitlement?.optInt("days_remaining") ?: 0,
                activationCode = activation?.optString("token")?.takeIf { it.isNotBlank() },
                activationRaw = activation?.optString("token_raw")?.takeIf { it.isNotBlank() },
                activationUrl = activation?.optString("activation_url")?.takeIf { it.isNotBlank() },
            )
        }
        return result
    }

    private fun parseProductPlan(family: JSONObject): CabinetProductPlan? {
        val plan = family.optJSONObject("product_plan") ?: return null
        val next = plan.optJSONObject("next_plan")
        return CabinetProductPlan(
            label = plan.optString("label").takeIf { it.isNotBlank() } ?: return null,
            deviceLimit = plan.optInt("device_limit", 0),
            usedSlots = plan.optInt("used_slots", members.size),
            trafficLabel = plan.optString("traffic_label").takeIf { it.isNotBlank() } ?: "",
            trafficUsedLabel = plan.optString("traffic_used_label").takeIf { it.isNotBlank() } ?: "0 МБ",
            trafficUsedPct = plan.optInt("traffic_used_pct", 0).coerceIn(0, 100),
            isFull = plan.optBoolean("is_full", false),
            nextLabel = next?.optString("label")?.takeIf { it.isNotBlank() },
            nextDeviceLimit = next?.optInt("device_limit", 0) ?: 0,
            nextTrafficLabel = next?.optString("traffic_label")?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseLatestPaymentDate(history: JSONObject): String? {
        return history.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optString("date_label")
            ?.takeIf { it.isNotBlank() }
    }

    private fun renderCabinet(summary: JSONObject?, lastPaymentDate: String?) {
        val owner = members.firstOrNull { it.role == "owner" }
        val ownerDays = owner?.daysRemaining ?: 0
        val isSingleMember = members.size <= 1

        binding.layoutCabinetPlanSummary.removeAllViews()
        productPlan?.let {
            binding.layoutCabinetPlanSummary.addView(buildPlanSummaryView(it))
        }
        binding.layoutCabinetDaysHero.visibility = if (isSingleMember) View.VISIBLE else View.GONE
        (binding.layoutCabinetSubscriptionStats.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = if (isSingleMember) dp(22) else dp(14)
            binding.layoutCabinetSubscriptionStats.layoutParams = it
        }
        binding.tvCabinetDaysLeft.text = ownerDays.toString()
        binding.tvCabinetStatusBadge.setText(
            if (ownerDays > 0) R.string.vsm_cabinet_status_active else R.string.vsm_cabinet_status_inactive
        )
        binding.tvCabinetStatusBadge.setTextColor(
            getColor(if (ownerDays > 0) R.color.vsm_sub_ok else R.color.vsm_sub_urgent)
        )
        binding.tvCabinetLastPayment.text = lastPaymentDate ?: getString(R.string.vsm_cabinet_no_payment_date)
        binding.layoutCabinetDistribution.visibility = View.GONE

        binding.llCabinetMembers.removeAllViews()
        members.forEach { member ->
            binding.llCabinetMembers.addView(buildMemberView(member))
        }
    }

    private fun buildPlanSummaryView(plan: CabinetProductPlan): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

            addView(TextView(this@CabinetActivity).apply {
                text = plan.label
                setTextColor(getColor(R.color.vsm_restore_card_title))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@CabinetActivity).apply {
                text = "${plan.trafficLabel} · до ${plan.deviceLimit} устройств"
                setTextColor(getColor(R.color.vsm_restore_card_body))
                textSize = 14f
                setPadding(0, dp(5), 0, 0)
            })
            addView(LinearLayout(this@CabinetActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, 0)
                addView(buildPlanMeter(
                    label = getString(R.string.vsm_cabinet_devices_meter),
                    value = getString(R.string.vsm_cabinet_devices_used_format, plan.usedSlots, plan.deviceLimit),
                    pct = if (plan.deviceLimit > 0) ((plan.usedSlots * 100) / plan.deviceLimit).coerceIn(0, 100) else 0,
                ))
                addView(buildPlanMeter(
                    label = getString(R.string.vsm_cabinet_traffic_meter),
                    value = getString(
                        R.string.vsm_cabinet_traffic_used_format,
                        plan.trafficUsedLabel,
                        plan.trafficLabel.replace("/мес", ""),
                    ),
                    pct = plan.trafficUsedPct,
                ).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
                })
            })
            if (plan.isFull && !plan.nextLabel.isNullOrBlank()) {
                addView(TextView(this@CabinetActivity).apply {
                    text = "Пакет заполнен. Чтобы добавить ещё одно устройство, перейдите на «${plan.nextLabel}»: до ${plan.nextDeviceLimit} устройств, ${plan.nextTrafficLabel ?: ""}."
                    setTextColor(getColor(R.color.vsm_traffic_amber))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(10), 0, 0)
                })
            }
        }
    }

    private fun buildPlanMeter(label: String, value: String, pct: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.cabinet_inner_card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(LinearLayout(this@CabinetActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@CabinetActivity).apply {
                    text = label
                    setTextColor(getColor(R.color.vsm_restore_card_body))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@CabinetActivity).apply {
                    text = value
                    setTextColor(getColor(R.color.vsm_restore_card_title))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                })
            })
            addView(FrameLayout(this@CabinetActivity).apply {
                setBackgroundResource(R.drawable.cabinet_meter_track_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(6),
                ).apply {
                    topMargin = dp(9)
                }
                addView(View(this@CabinetActivity).apply {
                    setBackgroundResource(R.drawable.cabinet_meter_fill_bg)
                    layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
                })
                post {
                    val fill = getChildAt(0)
                    fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply {
                        width = ((width * pct.coerceIn(0, 100)) / 100f).toInt()
                    }
                }
            })
        }
    }

    private fun buildMemberView(member: CabinetMember): View {
        val isOwner = member.role == "owner"
        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (isOwner) dp(14) else dp(12)
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.restore_card_bg)
            setPadding(dp(20), if (isOwner) dp(28) else dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (isOwner) topMargin = dp(10)
            }
        }

        if (isOwner) {
            wrapper.addView(TextView(this).apply {
                text = getString(R.string.vsm_cabinet_owner_badge)
                setTextColor(getColor(R.color.vsm_link))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = true
                setBackgroundResource(R.drawable.cabinet_owner_badge_bg)
                setPadding(dp(18), dp(4), dp(18), dp(4))
                layoutParams = FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = dp(18)
                    gravity = Gravity.TOP or Gravity.LEFT
                }
            })
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }

        nameRow.addView(TextView(this).apply {
            text = member.displayName.ifBlank { getString(R.string.vsm_cabinet_member_fallback) }
            setTextColor(getColor(R.color.vsm_restore_card_title))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        })

        nameRow.addView(buildIconButton(R.drawable.ic_edit_24dp, getString(R.string.vsm_cabinet_edit_name)) {
            showEditMemberDialog(member)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginStart = dp(1)
            }
        })
        header.addView(nameRow)
        card.addView(header)

        val status = when {
            member.activationRaw != null -> getString(R.string.vsm_cabinet_waiting_activation)
            else -> getString(R.string.vsm_cabinet_device_bound)
        }
        card.addView(TextView(this).apply {
            text = status
            setTextColor(getColor(R.color.vsm_restore_card_body))
            textSize = 16f
            setPadding(0, dp(16), 0, 0)
        })

        if (hasActivation(member)) {
            addActivationViews(card, member)
        }

        if (!isOwner) {
            card.addView(buildDangerButton(R.string.vsm_cabinet_remove_member) { confirmRemoveMember(member) }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                    gravity = Gravity.RIGHT
                }
            })
        }

        wrapper.addView(card)
        if (isOwner) {
            wrapper.getChildAt(0)?.bringToFront()
        }
        return wrapper
    }

    private fun hasActivation(member: CabinetMember): Boolean {
        return member.activationCode != null || member.activationRaw != null || member.activationUrl != null
    }

    private fun renderHistoryItems(items: List<CabinetHistoryEntry>) {
        val tableBody = binding.llHistoryRows ?: return
        for (entry in items) {
            tableBody.addView(buildHistoryRow(entry))
        }
    }

    private fun buildHistoryRow(entry: CabinetHistoryEntry): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        wrapper.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.vsm_border))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1),
            )
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(TextView(this).apply {
            text = entry.dateLabel
            setTextColor(getColor(R.color.vsm_restore_card_body))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.28f).apply {
                rightMargin = dp(8)
            }
        })

        row.addView(TextView(this).apply {
            text = buildHistoryDescription(entry.descriptionHtml)
            setTextColor(getColor(R.color.vsm_restore_card_title))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.44f).apply {
                rightMargin = dp(8)
            }
        })

        row.addView(TextView(this).apply {
            text = entry.daysLabel
            val colorRes = when {
                entry.daysClass.contains("positive") -> R.color.vsm_sub_ok
                else -> R.color.vsm_restore_card_body
            }
            setTextColor(getColor(colorRes))
            textSize = 13f
            gravity = Gravity.END
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.18f)
        })

        wrapper.addView(row)
        return wrapper
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&rarr;", "→")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("<em>(.*?)</em>"), "$1")
            .replace(Regex("<strong>(.*?)</strong>"), "$1")
    }

    private fun buildHistoryDescription(html: String): CharSequence {
        val spannable = SpannableStringBuilder()
        val regex = Regex("""<span class="(ev-\w+)">(.*?)</span>(.*)""")
        val match = regex.find(html)

        if (match != null) {
            val cssClass = match.groupValues[1]
            val label = decodeHtmlEntities(match.groupValues[2])
            val rest = decodeHtmlEntities(match.groupValues[3])

            val colorRes = when (cssClass) {
                "ev-payment" -> R.color.vsm_sub_ok
                "ev-allocate", "ev-distribute" -> R.color.vsm_link
                "ev-rebalance" -> R.color.vsm_traffic_amber
                "ev-neutral" -> R.color.vsm_restore_card_body
                else -> R.color.vsm_restore_card_body
            }

            val labelSpan = SpannableStringBuilder(label)
            labelSpan.setSpan(
                ForegroundColorSpan(getColor(colorRes)),
                0, label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.append(labelSpan)
            spannable.append(rest)
        } else {
            spannable.append(decodeHtmlEntities(html))
        }

        return spannable
    }

    private fun populateDownloadOptions() {
        val baseUrl = androidDownloadUrl()
        val options = listOf(
            DownloadOption(getString(R.string.vsm_cabinet_download_recommended), getString(R.string.vsm_cabinet_download_modern), "26,3 МБ", baseUrl),
            DownloadOption(null, getString(R.string.vsm_cabinet_download_old), "26,6 МБ", "${baseUrl}/older-phones"),
            DownloadOption(null, getString(R.string.vsm_cabinet_download_universal), "60,9 МБ", "${baseUrl}/universal"),
        )

        binding.llDownloadOptions.removeAllViews()
        options.forEach { option ->
            binding.llDownloadOptions.addView(buildDownloadOption(option.badge, option.name, option.size, option.url))
        }
    }

    private fun buildDownloadOption(badge: String?, name: String, size: String, url: String): View {
        val parentView = binding.llDownloadOptions
        val isFirst = parentView.childCount == 0

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = if (isFirst) dp(10) else dp(10)
            }

            addView(LinearLayout(this@CabinetActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.cabinet_inner_card_bg)
                setPadding(
                    dp(16),
                    if (badge != null) dp(24) else dp(14),  // More top padding when badge exists
                    dp(16),
                    dp(14)
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (badge != null) {
                        topMargin = dp(10)
                    }
                }

                addView(LinearLayout(this@CabinetActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    addView(LinearLayout(this@CabinetActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                        addView(TextView(this@CabinetActivity).apply {
                            text = name
                            setTextColor(getColor(R.color.vsm_restore_card_title))
                            textSize = 15f
                            typeface = Typeface.DEFAULT_BOLD
                        })
                    })

                    addView(TextView(this@CabinetActivity).apply {
                        text = size
                        setTextColor(getColor(R.color.vsm_restore_card_body))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(10) }
                    })
                })

                addView(buildOutlineButton(R.string.vsm_cabinet_download_copy_link) {
                    copyText(url)
                }.apply {
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                        topMargin = dp(12)
                    }
                })
            })

            if (badge != null) {
                addView(TextView(this@CabinetActivity).apply {
                    text = badge
                    setTextColor(getColor(R.color.vsm_sub_ok))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = true
                    setBackgroundResource(R.drawable.cabinet_owner_badge_bg)
                    setPadding(dp(18), dp(4), dp(18), dp(4))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = dp(18)
                        gravity = Gravity.TOP or Gravity.LEFT
                    }
                })
            }
        }
    }

    private fun addActivationViews(card: LinearLayout, member: CabinetMember) {
        val activationBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.cabinet_inner_card_bg)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(28)
            }
        }
        val hasToken = member.activationCode != null || member.activationRaw != null || member.activationUrl != null
        if (hasToken) {
            activationBox.addView(sectionLabel(R.string.vsm_cabinet_install_title))
            activationBox.addView(TextView(this).apply {
                setText(R.string.vsm_cabinet_share_download)
                setTextColor(getColor(R.color.vsm_link))
                textSize = 16f
                setPadding(0, dp(14), 0, dp(18))
                setOnClickListener { copyText(androidDownloadUrl()) }
            })
            activationBox.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.vsm_border))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply {
                    bottomMargin = dp(18)
                }
            })
        }
        activationBox.addView(sectionLabel(R.string.vsm_cabinet_activation_title))
        activationBox.addView(TextView(this).apply {
            text = if (member.activationRaw != null || member.activationUrl != null) {
                getString(R.string.vsm_cabinet_activation_pending_body)
            } else {
                getString(R.string.vsm_cabinet_activation_ready_body)
            }
            setTextColor(getColor(R.color.vsm_restore_card_body))
            textSize = 16f
            setPadding(0, dp(16), 0, 0)
        })
        if (member.activationCode != null) {
            activationBox.addView(buildCodeCopyRow(member.activationRaw ?: member.activationCode, member.activationRaw ?: member.activationCode))
        }
        if (member.activationUrl != null) {
            activationBox.addView(buildLinkCopyRow(member.activationUrl))
        }
        card.addView(activationBox)
    }

    private fun buildCodeCopyRow(code: String, copyValue: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(18)
            }
            addView(TextView(this@CabinetActivity).apply {
                text = code
                setTextColor(getColor(R.color.vsm_restore_card_title))
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                setBackgroundResource(R.drawable.cabinet_token_bg)
                setPadding(dp(18), dp(9), dp(18), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(10)
                }
            })
            addView(buildInlineCopyButton { copyText(copyValue) })
        }
    }

    private fun buildLinkCopyRow(url: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(14)
            }
            addView(TextView(this@CabinetActivity).apply {
                text = url
                setTextColor(getColor(R.color.vsm_link))
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(10)
                }
                setOnClickListener { Utils.openUri(this@CabinetActivity, url) }
            })
            addView(buildInlineCopyButton { copyText(url) })
        }
    }

    private fun buildInlineCopyButton(action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { action() }
            addView(ImageView(this@CabinetActivity).apply {
                setImageResource(R.drawable.ic_copy)
                setColorFilter(getColor(R.color.vsm_restore_card_body))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    rightMargin = dp(6)
                }
            })
            addView(TextView(this@CabinetActivity).apply {
                setText(R.string.vsm_cabinet_copy_short)
                setTextColor(getColor(R.color.vsm_restore_card_body))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun sectionLabel(labelRes: Int): TextView {
        return TextView(this).apply {
            setText(labelRes)
            setTextColor(getColor(R.color.vsm_restore_card_body))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            letterSpacing = 0.07f
        }
    }

    private fun buildMemberControls(member: CabinetMember): LinearLayout {
        val canMoveDay = members.any { it.id != member.id } && member.daysRemaining > 0
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(LinearLayout(this@CabinetActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(TextView(this@CabinetActivity).apply {
                    text = member.daysRemaining.toString()
                    includeFontPadding = false
                    setTextColor(getColor(R.color.vsm_sub_ok))
                    textSize = 36f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@CabinetActivity).apply {
                    text = getString(R.string.vsm_cabinet_days_label)
                    includeFontPadding = false
                    setTextColor(getColor(R.color.vsm_mint))
                    textSize = 14f
                    gravity = Gravity.CENTER
                })
            })
            addView(buildSymbolButton("-", canMoveDay) { moveDayToFewest(member) })
            addView(buildSymbolButton("+", true) { allocateDay(member) })
        }
    }

    private fun buildIconButton(iconRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            background = getDrawable(R.drawable.cabinet_outline_button_bg)
            backgroundTintList = null
            setColorFilter(getColor(R.color.vsm_link))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                leftMargin = dp(6)
            }
        }
    }

    private fun buildSymbolButton(symbol: String, enabled: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            setTextColor(getColor(R.color.vsm_link))
            setBackgroundResource(R.drawable.cabinet_outline_button_bg)
            setOnClickListener { if (enabled) action() }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                leftMargin = dp(6)
            }
        }
    }

    private fun buildOutlineButton(labelRes: Int, action: () -> Unit): Button {
        return Button(this).apply {
            setText(labelRes)
            isAllCaps = false
            minHeight = dp(56)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.vsm_link))
            background = getDrawable(R.drawable.cabinet_outline_button_bg)
            backgroundTintList = null
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
    }

    private fun buildDangerButton(labelRes: Int, action: () -> Unit): Button {
        return Button(this).apply {
            setText(labelRes)
            isAllCaps = false
            minHeight = dp(38)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(getColor(R.color.vsm_sub_urgent))
            background = getDrawable(R.drawable.cabinet_danger_outline_button_bg)
            backgroundTintList = null
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
    }

    private fun dialogInput(hint: String, type: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = type
            maxLines = 1
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
    }

    private fun addMember(displayName: String, email: String) {
        if (displayName.isBlank() && email.isBlank()) {
            toast(R.string.vsm_cabinet_name_or_email_required)
            return
        }
        mutateCabinet(
            path = "/cabinet/api/family/members",
            body = JSONObject()
                .put("display_name", displayName.takeIf { it.isNotBlank() })
                .put("email", email.takeIf { it.isNotBlank() }),
            successMessage = getString(R.string.vsm_cabinet_member_added),
            afterSuccess = {
                binding.etCabinetMemberName.text?.clear()
                binding.etCabinetMemberEmail.text?.clear()
            },
        )
    }

    private fun showMemberNameError(show: Boolean) {
        binding.tvCabinetMemberNameError.visibility = if (show) View.VISIBLE else View.GONE
        binding.etCabinetMemberName.background = if (show)
            getDrawable(R.drawable.restore_input_error_bg)
        else
            getDrawable(R.drawable.restore_input_bg)
    }

    private fun showEditMemberDialog(member: CabinetMember) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_member_name, null)
        val input = view.findViewById<EditText>(R.id.etEditMemberName)
        input.setText(member.displayName)
        input.setSelection(input.text?.length ?: 0)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<Button>(R.id.btnEditMemberCancel).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnEditMemberSave).setOnClickListener {
            updateMemberName(member, input.text?.toString()?.trim().orEmpty())
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun updateMemberName(member: CabinetMember, displayName: String) {
        if (displayName.isBlank()) {
            toast(R.string.vsm_cabinet_name_required)
            return
        }
        mutateCabinet(
            path = "/cabinet/api/family/members/${member.id}",
            body = JSONObject().put("display_name", displayName),
            successMessage = getString(R.string.vsm_cabinet_member_updated),
        )
    }

    private fun allocateDay(member: CabinetMember) {
        mutateCabinet(
            path = "/cabinet/api/family/members/${member.id}/allocate",
            body = JSONObject().put("days", 1),
            successMessage = getString(R.string.vsm_cabinet_day_allocated),
        )
    }

    private fun moveDayToFewest(member: CabinetMember) {
        val target = members
            .filter { it.id != member.id }
            .minByOrNull { it.daysRemaining }
            ?: return
        mutateCabinet(
            path = "/cabinet/api/family/rebalance",
            body = JSONObject()
                .put("from_member_id", member.id)
                .put("to_member_id", target.id)
                .put("days", 1),
            successMessage = getString(R.string.vsm_cabinet_day_moved),
        )
    }

    private fun reissueActivation(member: CabinetMember) {
        mutateCabinet(
            path = "/cabinet/api/family/members/${member.id}/activation",
            body = JSONObject(),
            successMessage = getString(R.string.vsm_cabinet_code_reissued),
        )
    }

    private fun distributeEvenly() {
        mutateCabinet(
            path = "/cabinet/api/family/distribute-evenly",
            body = JSONObject(),
            successMessage = getString(R.string.vsm_cabinet_distributed),
        )
    }

    private fun confirmRemoveMember(member: CabinetMember) {
        val view = layoutInflater.inflate(R.layout.dialog_confirm_remove_member, null)
        view.findViewById<TextView>(R.id.tvConfirmRemoveMessage).text =
            getString(R.string.vsm_cabinet_remove_confirm, member.displayName)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<Button>(R.id.btnConfirmRemoveCancel).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnConfirmRemoveConfirm).setOnClickListener {
            mutateCabinet(
                path = "/cabinet/api/family/members/${member.id}/remove",
                body = JSONObject(),
                successMessage = getString(R.string.vsm_cabinet_member_removed),
            )
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun mutateCabinet(
        path: String,
        body: JSONObject,
        successMessage: String,
        afterSuccess: (() -> Unit)? = null,
    ) {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                postJson(path, body, includeSession = true, includeOrigin = true)
                withContext(Dispatchers.Main) {
                    hasChanged = true
                    setBusy(false)
                    showStatus(successMessage, StatusMode.SUCCESS)
                    afterSuccess?.invoke()
                    loadCabinet()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: cabinet mutation failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_cabinet_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun getJson(path: String): ApiResponse {
        val connection = openConnection(path)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        addSessionCookie(connection)
        return readJsonResponse(connection)
    }

    private fun postJson(
        path: String,
        body: JSONObject,
        includeSession: Boolean,
        includeOrigin: Boolean,
    ): ApiResponse {
        val connection = openConnection(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (includeSession) addSessionCookie(connection)
        if (includeOrigin) {
            connection.setRequestProperty("Origin", backendOrigin())
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readJsonResponse(connection)
    }

    private fun openConnection(path: String): HttpURLConnection {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return (URL("${backendBaseUrl().removeSuffix("/")}$normalizedPath").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
    }

    private fun addSessionCookie(connection: HttpURLConnection) {
        val token = cabinetSessionToken() ?: return
        connection.setRequestProperty("Cookie", "$CABINET_SESSION_COOKIE_NAME=$token")
    }

    private fun readJsonResponse(connection: HttpURLConnection): ApiResponse {
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        val sessionToken = parseCabinetSessionCookie(connection)
        connection.disconnect()

        if (responseCode !in 200..299) {
            val errorJson = runCatching { JSONObject(text) }.getOrNull()
            val message = errorJson?.optString("error")
            throw ApiException(
                statusCode = responseCode,
                message = message?.takeIf { it.isNotBlank() } ?: getString(R.string.vsm_cabinet_generic_error),
            )
        }

        return ApiResponse(JSONObject(text.ifBlank { "{}" }), sessionToken)
    }

    private fun parseCabinetSessionCookie(connection: HttpURLConnection): String? {
        val cookies = connection.headerFields.entries
            .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.value
            .orEmpty()
        val prefix = "$CABINET_SESSION_COOKIE_NAME="
        val encoded = cookies.asSequence()
            .flatMap { it.split(";").asSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return URLDecoder.decode(encoded, Charsets.UTF_8.name())
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cabinet_activation", text))
        toastSuccess(getString(R.string.vsm_cabinet_copied))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
