package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityPaymentBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.Utils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PaymentActivity : BaseActivity() {
    private val binding by lazy { ActivityPaymentBinding.inflate(layoutInflater) }

    private class ApiException(
        val apiCode: String,
        message: String,
    ) : IllegalStateException(message)

    private data class Plan(
        val code: String,
        val label: String,
        val productPlanCode: String,
        val durationCode: String,
        val durationLabel: String,
        val deviceLimit: Int,
        val trafficLabel: String,
        val months: Int,
        val priceLabel: String,
        val oldPriceLabel: String,
        val saveText: String,
        val currency: String,
        val billingMode: String,
        val available: Boolean,
        val availableMethods: List<String>,
    )

    private data class PaymentMethod(
        val code: String,
        val label: String,
        val description: String,
    )

    private enum class StatusMode {
        INFO,
        ERROR,
        SUCCESS
    }

    private data class PlanGroup(
        val code: String,
        val labelRes: Int,
        val deviceHintRes: Int,
        val trafficHintRes: Int,
    )

    companion object {
        private const val PREFS_NAME = "vsemoionline_prefs"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val PREF_FAMILY_ROLE = "family_role"
        private const val PREF_PLAN = "plan"
        private const val PREF_PAID_DAYS_REMAINING = "paid_days_remaining"
        private const val PREF_TRAFFIC_TOTAL_GB = "traffic_total_gb"
        private const val PREF_PRODUCT_PLAN_CODE = "product_plan_code"
        private const val PREF_DURATION_CODE = "duration_code"
        private const val PRIMARY_PROVISION_URL = "https://vmonl.store/provision"
    }

    private val planGroups by lazy {
        listOf(
            PlanGroup(
                code = "personal",
                labelRes = R.string.vsm_payment_group_personal,
                deviceHintRes = R.string.vsm_payment_group_personal_devices,
                trafficHintRes = R.string.vsm_payment_group_personal_traffic,
            ),
            PlanGroup(
                code = "family",
                labelRes = R.string.vsm_payment_group_family,
                deviceHintRes = R.string.vsm_payment_group_family_devices,
                trafficHintRes = R.string.vsm_payment_group_family_traffic,
            ),
            PlanGroup(
                code = "all_mine",
                labelRes = R.string.vsm_payment_group_all_mine,
                deviceHintRes = R.string.vsm_payment_group_all_mine_devices,
                trafficHintRes = R.string.vsm_payment_group_all_mine_traffic,
            ),
        )
    }

    private var plans: List<Plan> = emptyList()
    private var methods: List<PaymentMethod> = emptyList()
    private var selectedPlanGroupCode: String = "personal"
    private var selectedPlanCode: String = ""
    private var selectedMethodCode: String = ""
    private var currentPaymentId: String? = null
    private var statusJob: Job? = null
    private val restoreSubscriptionActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
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

        binding.btnPaymentCreate.setOnClickListener { createPayment() }
        binding.btnPaymentOpenCheckout.setOnClickListener { openCheckoutAgain() }
        binding.btnPaymentRestore.setOnClickListener { openRestoreSubscription() }
        binding.btnPaymentDone.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
        binding.tvPaymentMemberWarning.visibility = if (isRegularFamilyMember()) View.VISIBLE else View.GONE

        loadPaymentConfig()
    }

    override fun onDestroy() {
        statusJob?.cancel()
        super.onDestroy()
    }

    private fun backendBaseUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
            ?: PRIMARY_PROVISION_URL.removeSuffix("/provision").trimEnd('/')
    }

    private fun isRegularFamilyMember(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_FAMILY_ROLE, null) == "member"
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    private fun getAndroidId(): String? {
        return try {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
    }

    private fun setBusy(isBusy: Boolean) {
        binding.btnPaymentCreate.isEnabled = !isBusy && selectedPlanCode.isNotBlank() && selectedMethodCode.isNotBlank()
        binding.etPaymentEmail.isEnabled = !isBusy
        binding.rgPaymentPlans.isEnabled = !isBusy
        binding.rgPaymentMethods.isEnabled = !isBusy
        binding.btnPaymentCreate.alpha = if (isBusy) 0.7f else 1f
    }

    private fun showStatus(message: String, mode: StatusMode = StatusMode.INFO) {
        binding.tvPaymentStatus.visibility = View.VISIBLE
        binding.tvPaymentStatus.text = message
        when (mode) {
            StatusMode.INFO -> {
                binding.tvPaymentStatus.setBackgroundResource(R.drawable.restore_status_info_bg)
                binding.tvPaymentStatus.setTextColor(getColor(R.color.vsm_link))
            }
            StatusMode.ERROR -> {
                binding.tvPaymentStatus.setBackgroundResource(R.drawable.restore_status_error_bg)
                binding.tvPaymentStatus.setTextColor(getColor(R.color.vsm_sub_urgent))
            }
            StatusMode.SUCCESS -> {
                binding.tvPaymentStatus.setBackgroundResource(R.drawable.restore_status_success_bg)
                binding.tvPaymentStatus.setTextColor(getColor(R.color.vsm_sub_ok))
            }
        }
    }

    private fun updateHeader(titleRes: Int, introRes: Int) {
        title = getString(R.string.vsm_toolbar_title)
        binding.tvPaymentTitle.setText(titleRes)
        binding.tvPaymentIntro.setText(introRes)
    }

    private fun loadPaymentConfig() {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = getJson("${backendBaseUrl()}/api/public/payment-config?currency=RUB")
                val selection = json.optJSONObject("selection")
                val planItems = mutableListOf<Plan>()
                val methodItems = mutableListOf<PaymentMethod>()

                val plansJson = json.optJSONArray("plans")
                if (plansJson != null) {
                    for (index in 0 until plansJson.length()) {
                        val item = plansJson.optJSONObject(index) ?: continue
                        val planCode = item.optString("code")
                        val durationCode = item.optString("duration_code").ifBlank {
                            durationCodeFromPlanCode(planCode)
                        }
                        planItems += Plan(
                            code = planCode,
                            label = item.optString("label", planCode),
                            productPlanCode = item.optString("product_plan_code").ifBlank {
                                productPlanCodeFromPlanCode(planCode)
                            },
                            durationCode = durationCode,
                            durationLabel = item.optString("duration_label")
                                .ifBlank { item.optString("label", planCode) },
                            deviceLimit = item.optInt("device_limit", 0),
                            trafficLabel = item.optString("traffic_label"),
                            months = item.optInt("months", monthsFromDurationCode(durationCode)),
                            priceLabel = item.optString("price_label")
                                .ifBlank { formatAmount(item.optString("amount"), item.optString("currency", "RUB")) },
                            oldPriceLabel = item.optString("old_price_label"),
                            saveText = item.optString("save_text").ifBlank { item.optString("badge") },
                            currency = item.optString("currency", selection?.optString("currency", "RUB") ?: "RUB"),
                            billingMode = item.optString("billing_mode", selection?.optString("billing_mode", "one_time") ?: "one_time"),
                            available = item.optBoolean("available", true),
                            availableMethods = readStringArray(item.optJSONArray("available_methods")),
                        )
                    }
                }

                val methodsJson = json.optJSONArray("methods")
                if (methodsJson != null) {
                    for (index in 0 until methodsJson.length()) {
                        val item = methodsJson.optJSONObject(index) ?: continue
                        methodItems += PaymentMethod(
                            code = item.optString("code"),
                            label = item.optString("label", item.optString("code")),
                            description = item.optString("description"),
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    plans = planItems.filter { it.code.isNotBlank() && it.available }
                    methods = methodItems.filter { it.code.isNotBlank() }
                    applyInitialPlanSelection()
                    selectedMethodCode = selection?.optString("payment_method")
                        ?.takeIf { value -> methods.any { it.code == value } }
                        ?: methods.firstOrNull()?.code.orEmpty()
                    renderOptions()
                    setBusy(false)
                    if (plans.isEmpty() || methods.isEmpty()) {
                        showStatus(getString(R.string.vsm_payment_unavailable), StatusMode.ERROR)
                    }
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: payment config failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_payment_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun renderOptions() {
        renderPlanTabs()

        val visiblePlans = plans
            .filter { it.productPlanCode == selectedPlanGroupCode }
            .sortedWith(compareBy<Plan> { it.months.takeIf { months -> months > 0 } ?: Int.MAX_VALUE }.thenBy { it.code })
        val fallbackPlan = visiblePlans.firstOrNull()
        if (fallbackPlan != null && visiblePlans.none { it.code == selectedPlanCode }) {
            selectedPlanCode = fallbackPlan.code
        }

        binding.rgPaymentPlans.setOnCheckedChangeListener(null)
        binding.rgPaymentPlans.removeAllViews()
        visiblePlans.forEach { plan ->
            binding.rgPaymentPlans.addView(buildRadioButton(
                id = View.generateViewId(),
                text = buildPlanOptionText(plan),
                checked = plan.code == selectedPlanCode,
                tag = plan.code,
            ))
        }
        binding.rgPaymentPlans.setOnCheckedChangeListener { group, checkedId ->
            selectedPlanCode = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: selectedPlanCode
            val plan = plans.firstOrNull { it.code == selectedPlanCode }
            if (plan != null && plan.availableMethods.isNotEmpty() && selectedMethodCode !in plan.availableMethods) {
                selectedMethodCode = methods.firstOrNull { it.code in plan.availableMethods }?.code.orEmpty()
                renderOptions()
            }
            setBusy(false)
        }

        binding.rgPaymentMethods.setOnCheckedChangeListener(null)
        binding.rgPaymentMethods.removeAllViews()
        methods.forEach { method ->
            val subtitle = method.description.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            binding.rgPaymentMethods.addView(buildRadioButton(
                id = View.generateViewId(),
                text = "${method.label}$subtitle",
                checked = method.code == selectedMethodCode,
                tag = method.code,
            ))
        }
        binding.rgPaymentMethods.setOnCheckedChangeListener { group, checkedId ->
            selectedMethodCode = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: selectedMethodCode
            setBusy(false)
        }
    }

    private fun renderPlanTabs() {
        binding.layoutPaymentPlanTabs.removeAllViews()
        val availableGroups = planGroups.filter { group ->
            plans.any { it.productPlanCode == group.code }
        }.ifEmpty { planGroups }

        if (availableGroups.none { it.code == selectedPlanGroupCode }) {
            selectedPlanGroupCode = availableGroups.firstOrNull()?.code ?: "personal"
        }

        availableGroups.forEachIndexed { index, group ->
            val tab = buildPlanTab(group, group.code == selectedPlanGroupCode)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(7)
            }
            binding.layoutPaymentPlanTabs.addView(tab, params)
        }
    }

    private fun buildPlanTab(group: PlanGroup, selected: Boolean): TextView {
        val groupPlans = plans.filter { it.productPlanCode == group.code }
        val samplePlan = groupPlans.firstOrNull()
        val deviceHint = samplePlan?.deviceLimit
            ?.takeIf { it > 0 }
            ?.let { "до $it устройств" }
            ?: getString(group.deviceHintRes)
        val trafficHint = samplePlan?.trafficLabel
            ?.takeIf { it.isNotBlank() }
            ?.replace("/мес.", "/мес")
            ?: getString(group.trafficHintRes)

        val title = getString(group.labelRes)
        val body = "$deviceHint\n$trafficHint"

        return TextView(this).apply {
            text = buildPlanTabText(title, body, selected)
            gravity = Gravity.CENTER
            minHeight = dp(78)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.05f)
            background = roundedBackground(
                fillColor = getColor(if (selected) R.color.vsm_pill_fill else R.color.vsm_surface2),
                strokeColor = getColor(if (selected) R.color.vsm_cabinet_border_strong else R.color.vsm_border),
                radiusDp = 14,
            )
            isSelected = selected
            isEnabled = groupPlans.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.45f
            setOnClickListener {
                if (selectedPlanGroupCode != group.code) {
                    selectedPlanGroupCode = group.code
                    selectedPlanCode = plans
                        .filter { it.productPlanCode == group.code }
                        .minWithOrNull(compareBy<Plan> { it.months.takeIf { months -> months > 0 } ?: Int.MAX_VALUE }.thenBy { it.code })
                        ?.code
                        .orEmpty()
                    renderOptions()
                    setBusy(false)
                }
            }
        }
    }

    private fun buildPlanTabText(title: String, body: String, selected: Boolean): SpannableString {
        val text = "$title\n$body"
        val titleColor = getColor(if (selected) R.color.vsm_link else R.color.vsm_restore_card_title)
        val bodyColor = getColor(R.color.vsm_restore_card_hint)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(titleColor), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(bodyColor), title.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.9f), title.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildPlanOptionText(plan: Plan): String {
        val period = plan.durationLabel.ifBlank { plan.label }
        val suffix = plan.saveText.takeIf { it.isNotBlank() && it != period }?.let { "\n$it" }.orEmpty()
        val oldPrice = plan.oldPriceLabel.takeIf { it.isNotBlank() }?.let { "  ($it)" }.orEmpty()
        return "$period  •  ${plan.priceLabel}$oldPrice$suffix"
    }

    private fun buildRadioButton(id: Int, text: String, checked: Boolean, tag: String): RadioButton {
        return RadioButton(this).apply {
            this.id = id
            this.text = text
            this.tag = tag
            this.isChecked = checked
            textSize = 14f
            setTextColor(getColor(R.color.vsm_restore_card_title))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    private fun applyInitialPlanSelection() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentPlan = prefs.getString(PREF_PLAN, "free") ?: "free"
        val preferredGroup = if (currentPlan == "free") {
            "personal"
        } else {
            prefs.getString(PREF_PRODUCT_PLAN_CODE, null)
                ?.takeIf { plans.any { plan -> plan.productPlanCode == it } }
                ?: inferProductPlanFromTraffic(prefs.getFloat(PREF_TRAFFIC_TOTAL_GB, 0f))
        }
        val preferredDuration = if (currentPlan == "free") {
            "1m"
        } else {
            prefs.getString(PREF_DURATION_CODE, null)
                ?.takeIf { it.isNotBlank() }
                ?: inferDurationFromDays(prefs.getInt(PREF_PAID_DAYS_REMAINING, 0))
        }

        selectedPlanGroupCode = preferredGroup.takeIf { group ->
            plans.any { it.productPlanCode == group }
        } ?: "personal"
        selectedPlanCode = plans.firstOrNull {
            it.productPlanCode == selectedPlanGroupCode && it.durationCode == preferredDuration
        }?.code
            ?: plans.firstOrNull {
                it.productPlanCode == selectedPlanGroupCode && it.durationCode == "1m"
            }?.code
            ?: plans.firstOrNull { it.productPlanCode == selectedPlanGroupCode }?.code
            ?: plans.firstOrNull()?.also { selectedPlanGroupCode = it.productPlanCode }?.code
            ?: ""
    }

    private fun inferProductPlanFromTraffic(trafficTotalGb: Float): String {
        return when {
            trafficTotalGb >= 1500f -> "all_mine"
            trafficTotalGb >= 350f -> "family"
            else -> "personal"
        }
    }

    private fun inferDurationFromDays(daysRemaining: Int): String {
        return when {
            daysRemaining > 200 -> "12m"
            daysRemaining > 100 -> "6m"
            daysRemaining > 45 -> "3m"
            else -> "1m"
        }
    }

    private fun productPlanCodeFromPlanCode(code: String): String {
        return when {
            code.startsWith("all_mine_") -> "all_mine"
            code.startsWith("family_") -> "family"
            code.startsWith("personal_") -> "personal"
            else -> "personal"
        }
    }

    private fun durationCodeFromPlanCode(code: String): String {
        return Regex("(1m|3m|6m|12m)$").find(code)?.value ?: code
    }

    private fun monthsFromDurationCode(durationCode: String): Int {
        return when (durationCode) {
            "1m" -> 1
            "3m" -> 3
            "6m" -> 6
            "12m" -> 12
            else -> 0
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun createPayment() {
        val email = binding.etPaymentEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            toast(R.string.vsm_restore_email_required)
            return
        }
        val plan = plans.firstOrNull { it.code == selectedPlanCode }
        if (plan == null || selectedMethodCode.isBlank()) {
            showStatus(getString(R.string.vsm_payment_unavailable), StatusMode.ERROR)
            return
        }
        if (plan.availableMethods.isNotEmpty() && selectedMethodCode !in plan.availableMethods) {
            showStatus(getString(R.string.vsm_payment_unavailable), StatusMode.ERROR)
            return
        }

        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("plan_code", plan.code)
                    .put("currency", plan.currency)
                    .put("billing_mode", plan.billingMode)
                    .put("payment_method", selectedMethodCode)
                    .put("email", email)
                    .put("device_fingerprint", getOrCreateDeviceId())
                    .put("android_id", getAndroidId())
                val payment = postJson("${backendBaseUrl()}/payment/create", body)
                currentPaymentId = payment.optString("payment_id").takeIf { it.isNotBlank() }
                val redirectUrl = payment.optString("redirect_url").takeIf { it.isNotBlank() }

                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showProcessingState(payment.optString("payment_id"), redirectUrl)
                    if (redirectUrl != null) {
                        Utils.openUri(this@PaymentActivity, redirectUrl)
                    }
                    startStatusPolling()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: payment create failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    if (e is ApiException && e.apiCode == "email_belongs_to_existing_account") {
                        showExistingEmailRestorePrompt(e.message ?: getString(R.string.vsm_payment_restore_existing_email))
                    } else {
                        binding.btnPaymentRestore.visibility = View.GONE
                        showStatus(e.message ?: getString(R.string.vsm_payment_generic_error), StatusMode.ERROR)
                    }
                }
            }
        }
    }

    private fun showExistingEmailRestorePrompt(message: String) {
        updateHeader(R.string.vsm_restore_title, R.string.vsm_restore_intro)
        binding.layoutPaymentProcessing.visibility = View.GONE
        binding.layoutPaymentCard.visibility = View.VISIBLE
        showStatus(message, StatusMode.INFO)
        binding.btnPaymentRestore.visibility = View.VISIBLE
        setOpenCheckoutVisibility(false)
        binding.btnPaymentDone.visibility = View.GONE
    }

    private fun showProcessingState(paymentId: String, redirectUrl: String?) {
        updateHeader(R.string.vsm_payment_processing_title, R.string.vsm_payment_processing_intro)
        binding.layoutPaymentCard.visibility = View.GONE
        binding.layoutPaymentProcessing.visibility = View.VISIBLE
        binding.tvPaymentStatus.visibility = View.GONE
        binding.btnPaymentRestore.visibility = View.GONE
        setOpenCheckoutVisibility(redirectUrl != null)
        binding.btnPaymentOpenCheckout.tag = redirectUrl
        binding.btnPaymentDone.visibility = View.GONE
        binding.pbPaymentPolling.visibility = View.VISIBLE
        binding.tvPaymentProcessingTitle.text = getString(R.string.vsm_payment_processing_card_title)
        binding.tvPaymentProcessingBody.text = getString(R.string.vsm_payment_processing_body)
        binding.tvPaymentId.visibility = if (paymentId.isNotBlank()) View.VISIBLE else View.GONE
        binding.tvPaymentId.text = if (paymentId.isNotBlank()) getString(R.string.vsm_payment_id_label, paymentId) else ""
    }

    private fun showConfirmedState() {
        plans.firstOrNull { it.code == selectedPlanCode }?.let { plan ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_PRODUCT_PLAN_CODE, plan.productPlanCode)
                .putString(PREF_DURATION_CODE, plan.durationCode)
                .apply()
        }
        updateHeader(R.string.vsm_payment_success_title, R.string.vsm_payment_success_intro)
        binding.layoutPaymentCard.visibility = View.GONE
        binding.layoutPaymentProcessing.visibility = View.VISIBLE
        binding.tvPaymentStatus.visibility = View.GONE
        binding.pbPaymentPolling.visibility = View.GONE
        setOpenCheckoutVisibility(false)
        binding.btnPaymentDone.visibility = View.VISIBLE
        binding.tvPaymentProcessingTitle.text = getString(R.string.vsm_payment_success_card_title)
        binding.tvPaymentProcessingBody.text = getString(R.string.vsm_payment_success_card_body)
        binding.tvPaymentId.visibility = View.GONE
    }

    private fun showFailedState() {
        updateHeader(R.string.vsm_payment_failed_title, R.string.vsm_payment_failed_body)
        binding.layoutPaymentCard.visibility = View.GONE
        binding.layoutPaymentProcessing.visibility = View.VISIBLE
        binding.pbPaymentPolling.visibility = View.GONE
        setOpenCheckoutVisibility(false)
        binding.btnPaymentDone.visibility = View.GONE
        binding.tvPaymentProcessingTitle.text = getString(R.string.vsm_payment_failed_title)
        binding.tvPaymentProcessingBody.text = getString(R.string.vsm_payment_failed_body)
        showStatus(getString(R.string.vsm_payment_failed), StatusMode.ERROR)
    }

    private fun openRestoreSubscription() {
        val email = binding.etPaymentEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        val intent = Intent(this, RestoreSubscriptionActivity::class.java).apply {
            if (email.isNotBlank()) {
                putExtra(RestoreSubscriptionActivity.EXTRA_PREFILL_EMAIL, email)
            }
        }
        restoreSubscriptionActivity.launch(intent)
    }

    private fun setOpenCheckoutVisibility(isVisible: Boolean) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.tvPaymentOpenCheckoutHint.visibility = visibility
        binding.btnPaymentOpenCheckout.visibility = visibility
    }

    private fun openCheckoutAgain() {
        val url = binding.btnPaymentOpenCheckout.tag as? String ?: return
        Utils.openUri(this, url)
    }

    private fun startStatusPolling() {
        val paymentId = currentPaymentId ?: return
        statusJob?.cancel()
        statusJob = lifecycleScope.launch(Dispatchers.IO) {
            repeat(60) {
                delay(4_000)
                try {
                    val payment = getJson("${backendBaseUrl()}/payment/$paymentId")
                    val status = payment.optString("status")
                    withContext(Dispatchers.Main) {
                        when (status) {
                            "confirmed" -> {
                                statusJob?.cancel()
                                showConfirmedState()
                            }
                            "failed" -> {
                                statusJob?.cancel()
                                showFailedState()
                            }
                            else -> Unit
                        }
                    }
                    if (status == "confirmed" || status == "failed") return@launch
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: payment status poll failed: ${e.message}")
                }
            }
        }
    }

    private fun getJson(urlString: String): JSONObject {
        val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        return readJsonResponse(connection)
    }

    private fun postJson(urlString: String, body: JSONObject): JSONObject {
        val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readJsonResponse(connection)
    }

    private fun readJsonResponse(connection: java.net.HttpURLConnection): JSONObject {
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()

        if (responseCode !in 200..299) {
            val errorJson = runCatching { JSONObject(text) }.getOrNull()
            val message = errorJson?.optString("error")
            val code = errorJson?.optString("code")
            throw ApiException(
                apiCode = code?.takeIf { it.isNotBlank() } ?: "request_failed",
                message = message?.takeIf { it.isNotBlank() } ?: getString(R.string.vsm_payment_generic_error),
            )
        }

        return JSONObject(text)
    }

    private fun formatAmount(amount: String, currency: String): String {
        if (amount.isBlank()) return ""
        val suffix = when (currency.uppercase()) {
            "RUB" -> "₽"
            "USD" -> "$"
            "EUR" -> "€"
            else -> currency.uppercase()
        }
        return "$amount $suffix"
    }

    private fun readStringArray(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) values += value
        }
        return values
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
