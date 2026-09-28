package com.realtor.geeksales

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.realtor.geeksales.navigation.Routes
import com.realtor.geeksales.navigation.safeNavigate
import com.realtor.geeksales.ui.screen.ComplianceScreen
import com.realtor.geeksales.ui.screen.CustomerDetailScreen
import com.realtor.geeksales.ui.screen.CustomerEditScreen
import com.realtor.geeksales.ui.screen.CustomerListScreen
import com.realtor.geeksales.ui.screen.DashboardScreen
import com.realtor.geeksales.ui.screen.DialQueueScreen
import com.realtor.geeksales.ui.screen.FollowUpDialog
import com.realtor.geeksales.ui.screen.ImportExportScreen
import com.realtor.geeksales.ui.screen.SettingsScreen
import com.realtor.geeksales.ui.components.ToastHost
import com.realtor.geeksales.ui.screen.complianceAgreed
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.BgElev
import com.realtor.geeksales.ui.theme.Divider
import com.realtor.geeksales.ui.theme.GeekSalesTheme
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.viewmodel.MainActivityViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GeekSalesTheme { AppRoot() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val mainVm: MainActivityViewModel = hiltViewModel()
    val postCallPrompt by mainVm.postCallPrompt.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val navBack by nav.currentBackStackEntryAsState()
    val showBottom = navBack?.destination?.route in listOf(
        Routes.DASHBOARD, Routes.CUSTOMER_LIST, Routes.DIAL_QUEUE, Routes.IMPORT_EXPORT, Routes.SETTINGS
    )

    var showCompliance by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!complianceAgreed(ctx)) showCompliance = true
    }

    var followUpSheetFor by remember { mutableStateOf<Long?>(null) }
    val showSheet = postCallPrompt != null || followUpSheetFor != null

    // 首次启动（合规同意后）引导核心权限：
    // READ_PHONE_STATE 缺失会导致挂断不弹登记卡片且无任何提示（"功能没反应"的直接来源）；
    // CALL_PHONE 用于详情页「直接拨打」（默认拨号盘 ACTION_DIAL 不需要，拒绝也不影响）；
    // READ_CALL_LOG 用于 Android 12+ 获取呼入号码；POST_NOTIFICATIONS 用于跟进提醒。
    // 只请求一次，用户拒绝后可在设置页手动开启；设置页会实时显示各项授权状态。
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    var permRequested by remember { mutableStateOf(false) }
    LaunchedEffect(showCompliance) {
        if (!showCompliance && !permRequested) {
            permRequested = true
            val prefs = ctx.getSharedPreferences("tma_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("perm_requested_v1", false)) {
                prefs.edit().putBoolean("perm_requested_v1", true).apply()
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            }
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (showBottom && !showCompliance) {
                BottomBar(navBack?.destination?.route) { r ->
                    runCatching {
                        nav.navigate(r) {
                            popUpTo(Routes.DASHBOARD) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Bg)) {
            NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
                composable(Routes.DASHBOARD) {
                    if (showCompliance) ComplianceScreen(onBack = { }, onAgree = { showCompliance = false })
                    else DashboardScreen(onNav = { route ->
                    runCatching {
                        nav.navigate(route) {
                            popUpTo(Routes.DASHBOARD) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }
                })
                }
                composable(Routes.CUSTOMER_LIST) {
                    CustomerListScreen(onNav = { nav.safeNavigate(it) })
                }
                composable(
                    Routes.CUSTOMER_EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })
                ) { e ->
                    val editId = e.arguments?.getLong("id") ?: 0L
                    CustomerEditScreen(id = editId, onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
                }
                composable(
                    Routes.CUSTOMER_DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { e ->
                    val id = e.arguments?.getLong("id") ?: 0L
                    CustomerDetailScreen(
                        id = id,
                        onNav = { nav.safeNavigate(it) },
                        onBack = { nav.popBackStack() },
                        openFollowUp = { cid -> followUpSheetFor = cid }
                    )
                }
                composable(Routes.DIAL_QUEUE) {
                    DialQueueScreen(onNav = { nav.safeNavigate(it) }, onBack = { nav.popBackStack() })
                }
                composable(Routes.IMPORT_EXPORT) {
                    ImportExportScreen(onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        // 仅导航到合规路由，不修改 showCompliance：
                        // 已同意的用户返回工作台不应再次被声明页拦截
                        onNavCompliance = { nav.navigate(Routes.COMPLIANCE) },
                        onOpenPermSettings = {
                            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        }
                    )
                }
                composable(Routes.COMPLIANCE) {
                    ComplianceScreen(onBack = { nav.popBackStack() }, onAgree = {
                        showCompliance = false
                        if (nav.previousBackStackEntry != null) nav.popBackStack()
                    })
                }
            }

            if (showSheet) {
                // 用普通 Dialog 替代 ModalBottomSheet：
                // BottomSheet 的 scrim 遮罩在关闭动画竞态后会残留在组合树中，
                // 拦截全部触摸事件，表现为“假死”（点不动/滑不动/手势失效/必须大退）。
                // Dialog 关闭即窗口销毁，绝无残留。
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = {
                        mainVm.dismissPostCallPrompt()
                        followUpSheetFor = null
                    },
                    properties = androidx.compose.ui.window.DialogProperties(
                        dismissOnClickOutside = true,
                        dismissOnBackPress = true
                    )
                ) {
                    // 标准宽度 Dialog（平台默认窗口尺寸）：
                    // usePlatformDefaultWidth=false 的全屏窗口模式在 Compose 1.6 +
                    // Android 15/16 上存在触摸派发 bug（窗口拦截全部输入但内容不可见），
                    // 是"涟漪有反馈但页面不动、随后全局假死"的直接来源。
                    Column(
                        Modifier
                            .background(BgElev)
                            .border(1.dp, Divider)
                            .verticalScroll(rememberScrollState())
                            .imePadding()
                            .padding(16.dp)
                    ) {
                    val prompt = postCallPrompt
                    when {
                        prompt != null -> FollowUpDialog(
                            customerId = prompt.customerId,
                            prefillPhone = prompt.phone,
                            prefillName = prompt.customerName.takeUnless { it == "未知号码" } ?: "",
                            prefillDurationSec = prompt.durationSec,
                            allowUnknown = prompt.customerId == 0L,
                            onDismiss = { mainVm.dismissPostCallPrompt() },
                            onSaved = { mainVm.dismissPostCallPrompt() },
                            saveDirect = { a, b, c, d, e -> mainVm.savePostCallFollowUp(a, b, c, d, e) }
                        )
                        followUpSheetFor != null -> FollowUpDialog(
                            customerId = followUpSheetFor!!,
                            onDismiss = { followUpSheetFor = null },
                            onSaved = { followUpSheetFor = null }
                        )
                    }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
            ToastHost()
        }
    }
}

@Composable
private fun BottomBar(current: String?, onNav: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(BgElev)
            .border(0.dp, Divider)
            .navigationBarsPadding()  // API 36 强制 edge-to-edge，避免底栏被系统导航条遮挡
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            Routes.DASHBOARD to (Icons.Filled.Dashboard to "工作台"),
            Routes.CUSTOMER_LIST to (Icons.Filled.List to "名单"),
            Routes.DIAL_QUEUE to (Icons.Filled.PhoneInTalk to "队列"),
            Routes.IMPORT_EXPORT to (Icons.Filled.Storage to "数据"),
            Routes.SETTINGS to (Icons.Filled.Settings to "设置"),
        ).forEach { (r, pair) ->
            val (icon, label) = pair
            val active = current == r
            val color = if (active) Accent else TextMuted
            Column(
                Modifier
                    .weight(1f)
                    .height(60.dp)
                    .then(if (active) Modifier.background(Accent.copy(alpha = 0.08f)) else Modifier)
                    .clickable { onNav(r) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    color = color,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
