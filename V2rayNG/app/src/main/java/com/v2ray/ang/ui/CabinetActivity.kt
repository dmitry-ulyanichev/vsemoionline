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
    private var hasChanged = false
    private var attemptedTrustedDeviceSession = false

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
            addMember(
                displayName = binding.etCabinetMemberName.text?.toString()?.trim().orEmpty(),
                email = binding.etCabinetMemberEmail.text?.toString()?.trim()?.lowercase().orEmpty(),
            )
        }
        binding.btnCabinetDistribute.setOnClickListener { distributeEvenly() }
        binding.btnCabinetLogout.setOnClickListener {
            clearCabinetSession()
            showLoginState()
            showStatus(getString(R.string.vsm_cabinet_logged_out), StatusMode.INFO)
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
        binding.btnCabinetLogout.isEnabled = !isBusy
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
                val lastPaymentDate = runCatching {
                    parseLatestPaymentDate(getJson("/cabinet/api/history?filter=payment&limit=1").json)
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    members = loadedMembers
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

    private fun parseLatestPaymentDate(history: JSONObject): String? {
        return history.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optString("date_label")
            ?.takeIf { it.isNotBlank() }
    }

    private fun renderCabinet(summary: JSONObject?, lastPaymentDate: String?) {
        val owner = members.firstOrNull { it.role == "owner" }
        val depositDays = summary?.optDouble("paid_days_balance", 0.0)?.toInt() ?: 0
        val ownerDays = owner?.daysRemaining ?: 0
        val isSingleMember = members.size <= 1

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
        binding.tvCabinetDepositTitle.text = getString(R.string.vsm_cabinet_distribution_title_format, depositDays)
        binding.layoutCabinetDistribution.visibility = if (members.size > 1 && depositDays > 0) View.VISIBLE else View.GONE
        binding.btnCabinetDistribute.visibility = if (members.size > 1 && depositDays > 0) View.VISIBLE else View.GONE

        binding.llCabinetMembers.removeAllViews()
        members.forEach { member ->
            binding.llCabinetMembers.addView(buildMemberView(member))
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
                rightMargin = dp(10)
            }
        })
        nameRow.addView(buildIconButton(R.drawable.ic_edit_24dp, getString(R.string.vsm_cabinet_edit_name)) {
            showEditMemberDialog(member)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        })
        header.addView(nameRow)
        header.addView(buildMemberControls(member))
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

        addActivationViews(card, member)

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
            activationBox.addView(buildCodeCopyRow(member.activationCode, member.activationRaw ?: member.activationCode))
        }
        if (member.activationUrl != null) {
            activationBox.addView(buildLinkCopyRow(member.activationUrl))
        }
        activationBox.addView(buildOutlineButton(R.string.vsm_cabinet_reissue_code) { reissueActivation(member) })
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
            addView(buildSymbolButton("-", canMoveDay) { moveDayToFewest(member) })
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

    private fun showEditMemberDialog(member: CabinetMember) {
        val input = dialogInput(getString(R.string.vsm_cabinet_name_hint), InputType.TYPE_CLASS_TEXT).apply {
            setText(member.displayName)
            setSelection(text?.length ?: 0)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.vsm_cabinet_edit_name)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vsm_cabinet_save) { _, _ ->
                updateMemberName(member, input.text?.toString()?.trim().orEmpty())
            }
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.vsm_cabinet_remove_member)
            .setMessage(getString(R.string.vsm_cabinet_remove_confirm, member.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vsm_cabinet_remove) { _, _ ->
                mutateCabinet(
                    path = "/cabinet/api/family/members/${member.id}/remove",
                    body = JSONObject(),
                    successMessage = getString(R.string.vsm_cabinet_member_removed),
                )
            }
            .show()
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
