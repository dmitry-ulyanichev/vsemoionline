package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RulesetItem
import org.json.JSONArray
import org.json.JSONObject

object ManagedClientRulesManager {
    private const val MANAGED_REMARKS_PREFIX = "VseMoiOnline:"

    data class ApplyResult(
        val packageCount: Int,
        val routingRuleCount: Int,
    )

    fun applyRules(rules: JSONObject, source: String): ApplyResult? {
        return try {
            val android = rules.optJSONObject("android") ?: return null
            if (!android.optBoolean("enabled", true)) return null

            val appMode = android.optString("app_mode", "")
            val managedPackages = jsonStringList(android.optJSONArray("packages"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            if (appMode == "bypass_selected_apps" && managedPackages.isNotEmpty()) {
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
                MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
                MmkvManager.encodeSettings(AppConfig.PREF_MANAGED_PER_APP_PROXY_SET, managedPackages)
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, buildEffectivePackageSet(managedPackages))
            }

            val domainStrategy = android.optString("routing_domain_strategy", "")
            if (domainStrategy.isNotBlank()) {
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, domainStrategy)
            }

            val managedRoutingRules = parseManagedRoutingRules(android.optJSONArray("routing_rules"))
            if (managedRoutingRules.isNotEmpty()) {
                val existing = MmkvManager.decodeRoutingRulesets() ?: mutableListOf()
                val merged = existing
                    .filterNot { it.remarks?.startsWith(MANAGED_REMARKS_PREFIX) == true }
                    .toMutableList()
                merged.addAll(0, managedRoutingRules)
                MmkvManager.encodeRoutingRulesets(merged)
            }

            val result = ApplyResult(managedPackages.size, managedRoutingRules.size)
            Log.i(
                AppConfig.TAG,
                "VseMoiOnline: applied client rules from $source packages=${result.packageCount} routing=${result.routingRuleCount}"
            )
            result
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "VseMoiOnline: failed to apply client rules from $source: ${e.message}")
            null
        }
    }

    fun saveUserPackageSelection(selectedPackages: Set<String>, visiblePackages: Set<String>? = null) {
        val managed = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_MANAGED_PER_APP_PROXY_SET) ?: mutableSetOf()
        if (managed.isEmpty()) {
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, selectedPackages.toMutableSet())
            return
        }

        val previousAdded = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_USER_ADDED_SET) ?: mutableSetOf()
        val previousRemoved = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_USER_REMOVED_SET) ?: mutableSetOf()
        val selected = selectedPackages.toSet()
        val hiddenPackages = visiblePackages?.let { visible -> { pkg: String -> !visible.contains(pkg) } }

        val userAdded = previousAdded
            .filter { hiddenPackages?.invoke(it) == true }
            .plus(selected.subtract(managed))
            .toMutableSet()
        val removableManaged = visiblePackages?.let { managed.intersect(it) } ?: managed
        val userRemoved = previousRemoved
            .filter { hiddenPackages?.invoke(it) == true }
            .plus(removableManaged.subtract(selected))
            .toMutableSet()

        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_USER_ADDED_SET, userAdded)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_USER_REMOVED_SET, userRemoved)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, buildEffectivePackageSet(managed))
    }

    private fun buildEffectivePackageSet(managedPackages: Set<String>): MutableSet<String> {
        val userAdded = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_USER_ADDED_SET) ?: mutableSetOf()
        val userRemoved = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_USER_REMOVED_SET) ?: mutableSetOf()
        return managedPackages
            .filterNot { userRemoved.contains(it) }
            .plus(userAdded)
            .toMutableSet()
    }

    private fun parseManagedRoutingRules(array: JSONArray?): MutableList<RulesetItem> {
        val result = mutableListOf<RulesetItem>()
        if (array == null) return result

        for (i in 0 until array.length()) {
            val rule = array.optJSONObject(i) ?: continue
            val outboundTag = rule.optString("outboundTag", "")
            if (outboundTag !in setOf(AppConfig.TAG_DIRECT, AppConfig.TAG_PROXY, AppConfig.TAG_BLOCKED)) continue
            result.add(
                RulesetItem(
                    remarks = rule.optString("remarks", "VseMoiOnline: managed routing"),
                    ip = jsonStringList(rule.optJSONArray("ip")).ifEmpty { null },
                    domain = jsonStringList(rule.optJSONArray("domain")).ifEmpty { null },
                    outboundTag = outboundTag,
                    port = rule.optString("port").ifBlank { null },
                    network = rule.optString("network").ifBlank { null },
                    protocol = jsonStringList(rule.optJSONArray("protocol")).ifEmpty { null },
                    enabled = rule.optBoolean("enabled", true),
                    locked = if (rule.has("locked")) rule.optBoolean("locked") else true,
                )
            )
        }

        return result
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }
}
