package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

object ManagedClientRulesManager {
    private const val MANAGED_REMARKS_PREFIX = "VseMoiOnline:"

    data class ApplyResult(
        val explicitPackageCount: Int,
        val prefixCount: Int,
        val expandedPrefixPackageCount: Int,
        val effectivePackageCount: Int,
        val routingRuleCount: Int,
        val changed: Boolean,
    )

    fun applyRules(context: Context, rules: JSONObject, source: String): ApplyResult? {
        return try {
            val android = rules.optJSONObject("android") ?: return null
            if (!android.optBoolean("enabled", true)) return null

            val appMode = android.optString("app_mode", "")
            val explicitPackages = jsonStringList(android.optJSONArray("packages"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            val packagePrefixes = jsonStringList(android.optJSONArray("package_prefixes"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            val expandedPrefixPackages = expandInstalledPackagePrefixes(context, packagePrefixes)
            val managedPackages = explicitPackages
                .plus(expandedPrefixPackages)
                .toMutableSet()
            var changed = false
            if (appMode == "bypass_selected_apps" && managedPackages.isNotEmpty()) {
                val oldManaged = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_MANAGED_PER_APP_PROXY_SET) ?: mutableSetOf()
                val oldEffective = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) ?: mutableSetOf()
                val effectivePackages = buildEffectivePackageSet(managedPackages)
                changed = changed || oldManaged != managedPackages || oldEffective != effectivePackages
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
                MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
                MmkvManager.encodeSettings(AppConfig.PREF_MANAGED_PER_APP_PROXY_SET, managedPackages)
                MmkvManager.encodeSettings(AppConfig.PREF_MANAGED_PER_APP_PROXY_PREFIX_SET, packagePrefixes)
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, effectivePackages)
            }

            val domainStrategy = android.optString("routing_domain_strategy", "")
            if (domainStrategy.isNotBlank()) {
                changed = changed || MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) != domainStrategy
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, domainStrategy)
            }

            val managedRoutingRules = parseManagedRoutingRules(android.optJSONArray("routing_rules"))
            if (managedRoutingRules.isNotEmpty()) {
                val existing = MmkvManager.decodeRoutingRulesets() ?: mutableListOf()
                val previousManaged = existing
                    .filter { it.remarks?.startsWith(MANAGED_REMARKS_PREFIX) == true }
                changed = changed || previousManaged != managedRoutingRules
                val merged = existing
                    .filterNot { it.remarks?.startsWith(MANAGED_REMARKS_PREFIX) == true }
                    .toMutableList()
                merged.addAll(0, managedRoutingRules)
                MmkvManager.encodeRoutingRulesets(merged)
            }

            MmkvManager.encodeSettings(AppConfig.PREF_CLIENT_RULES_VERSION, rules.optLong("version", 0L).toString())
            MmkvManager.encodeSettings(AppConfig.PREF_CLIENT_RULES_UPDATED_AT, rules.optString("updated_at", ""))
            MmkvManager.encodeSettings(AppConfig.PREF_CLIENT_RULES_TTL_SECONDS, rules.optLong("ttl_seconds", 86400L).toString())
            MmkvManager.encodeSettings(AppConfig.PREF_CLIENT_RULES_LAST_APPLY_SOURCE, source)

            val result = ApplyResult(
                explicitPackageCount = explicitPackages.size,
                prefixCount = packagePrefixes.size,
                expandedPrefixPackageCount = expandedPrefixPackages.size,
                effectivePackageCount = (MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) ?: mutableSetOf()).size,
                routingRuleCount = managedRoutingRules.size,
                changed = changed,
            )
            Log.i(
                AppConfig.TAG,
                "VseMoiOnline: applied client rules from $source packages=${result.effectivePackageCount} prefixes=${result.prefixCount} routing=${result.routingRuleCount}"
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

    fun markNetworkRefresh(timeMs: Long = System.currentTimeMillis()) {
        MmkvManager.encodeSettings(AppConfig.PREF_CLIENT_RULES_LAST_REFRESH_MS, timeMs.toString())
    }

    fun buildDiagnosticsText(context: Context): String {
        val managed = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_MANAGED_PER_APP_PROXY_SET) ?: mutableSetOf()
        val effective = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET) ?: mutableSetOf()
        val prefixes = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_MANAGED_PER_APP_PROXY_PREFIX_SET) ?: mutableSetOf()
        val installedPackages = installedPackageNames(context)
        val installedEffectiveCount = effective.count { installedPackages.contains(it) }
        val routingRules = MmkvManager.decodeRoutingRulesets() ?: mutableListOf()
        val hasRuIpRule = routingRules.any { it.enabled && it.outboundTag == AppConfig.TAG_DIRECT && it.ip?.contains("geoip:ru") == true }
        val hasRuDomainRule = routingRules.any {
            it.enabled && it.outboundTag == AppConfig.TAG_DIRECT && it.domain?.any { domain ->
                domain == "geosite:category-ru" || domain == "domain:2ip.ru" || domain.endsWith(":ru-available-only-inside")
            } == true
        }
        val lastRefresh = MmkvManager.decodeSettingsString(AppConfig.PREF_CLIENT_RULES_LAST_REFRESH_MS)?.toLongOrNull()
        val lastRefreshText = lastRefresh?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: "нет"
        val proxySharing = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
        val socksPort = SettingsManager.getSocksPort()
        val httpPart = if (Utils.isXray()) "" else ", HTTP ${SettingsManager.getHttpPort()}"

        return listOf(
            "Раздельная маршрутизация: ${yesNo(MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false))}",
            "Режим обхода: ${yesNo(MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false))}",
            "Версия правил: ${MmkvManager.decodeSettingsString(AppConfig.PREF_CLIENT_RULES_VERSION, "-")}",
            "Последнее обновление: $lastRefreshText",
            "Управляемые пакеты: ${managed.size}",
            "Префиксы пакетов: ${prefixes.size}",
            "Установленные исключения: $installedEffectiveCount из ${effective.size}",
            "Стратегия доменов: ${MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "-")}",
            "RU GeoIP/домены: ${yesNo(hasRuIpRule && hasRuDomainRule)}",
            "Локальный прокси: 127.0.0.1:$socksPort$httpPart, с паролем",
            "Доступ из LAN: ${if (proxySharing) "открыт" else "закрыт"}",
        ).joinToString("\n")
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

    private fun expandInstalledPackagePrefixes(context: Context, prefixes: Set<String>): Set<String> {
        if (prefixes.isEmpty()) return emptySet()
        return installedPackageNames(context)
            .filter { packageName -> prefixes.any { prefix -> packageName.startsWith(prefix) } }
            .toSet()
    }

    private fun installedPackageNames(context: Context): Set<String> {
        return try {
            context.packageManager
                .getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "VseMoiOnline: failed to read installed packages for client rules: ${e.message}")
            emptySet()
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"
}
