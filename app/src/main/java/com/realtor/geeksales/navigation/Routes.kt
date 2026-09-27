package com.realtor.geeksales.navigation

import android.util.Log
import androidx.navigation.NavController

object Routes {
    const val DASHBOARD = "dashboard"
    const val CUSTOMER_LIST = "customers"
    const val CUSTOMER_EDIT = "customer/edit/{id}"
    const val CUSTOMER_DETAIL = "customer/{id}"
    const val DIAL_QUEUE = "dial_queue"
    const val IMPORT_EXPORT = "io"
    const val SETTINGS = "settings"
    const val COMPLIANCE = "compliance"

    fun customerEdit(id: Long = 0L) = "customer/edit/$id"
    fun customerDetail(id: Long) = "customer/$id"
}

/**
 * 统一安全导航：
 * 1. try-catch 兜底：导航异常不再导致崩溃或静默失败（历史"点了没反应"的嫌疑之一）
 * 2. launchSingleTop：防止快速连点把同一页面叠多个进返回栈
 */
private var lastNavTime = 0L
fun NavController.safeNavigate(route: String) {
    val now = System.currentTimeMillis()
    if (now - lastNavTime < 250) return  // 连点防抖
    runCatching {
        navigate(route) { launchSingleTop = true }
    }.onFailure { Log.e("Nav", "navigate failed: $route", it) }
    lastNavTime = now
}
