package app.privatetunnel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

private val BG = Color(0xFF0B1220)
private val CARD = Color(0xFF131C2E)
private val CARD2 = Color(0xFF1B2740)
private val ACCENT = Color(0xFF3B82F6)
private val OK = Color(0xFF22C55E)
private val WARN = Color(0xFFF59E0B)
private val ERR = Color(0xFFEF4444)
private val TXT = Color(0xFFE6EDF7)
private val MUTED = Color(0xFF8A97AD)

class MainActivity : ComponentActivity() {
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startTunnel()
    }

    fun connect() {
        val i = VpnService.prepare(this)
        if (i != null) vpnLauncher.launch(i) else startTunnel()
    }
    private fun startTunnel() { startForegroundService(Intent(this, TunnelService::class.java).setAction("start")) }
    fun disconnect() { startService(Intent(this, TunnelService::class.java).setAction("stop")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ff = try { FontFamily(Typeface.createFromAsset(assets, "fonts/Vazirmatn-Regular.ttf")) }
                 catch (e: Throwable) { FontFamily.Default }
        setContent {
            val t = Typography()
            val typo = t.copy(
                displayLarge = t.displayLarge.copy(fontFamily = ff), displayMedium = t.displayMedium.copy(fontFamily = ff),
                displaySmall = t.displaySmall.copy(fontFamily = ff), headlineLarge = t.headlineLarge.copy(fontFamily = ff),
                headlineMedium = t.headlineMedium.copy(fontFamily = ff), headlineSmall = t.headlineSmall.copy(fontFamily = ff),
                titleLarge = t.titleLarge.copy(fontFamily = ff), titleMedium = t.titleMedium.copy(fontFamily = ff),
                titleSmall = t.titleSmall.copy(fontFamily = ff), bodyLarge = t.bodyLarge.copy(fontFamily = ff),
                bodyMedium = t.bodyMedium.copy(fontFamily = ff), bodySmall = t.bodySmall.copy(fontFamily = ff),
                labelLarge = t.labelLarge.copy(fontFamily = ff), labelMedium = t.labelMedium.copy(fontFamily = ff),
                labelSmall = t.labelSmall.copy(fontFamily = ff))
            MaterialTheme(
                colorScheme = darkColorScheme(primary = ACCENT, background = BG, surface = BG, onSurface = TXT,
                    onBackground = TXT, surfaceVariant = CARD, secondaryContainer = CARD2, onSecondaryContainer = TXT),
                typography = typo
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = BG) { App(this) }
                }
            }
        }
    }
}

@Composable
fun App(act: MainActivity) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!Store.testDone(ctx)) { tab = 1; NetTest.run(ctx) }   // first launch: test the network first
    }
    val items = listOf("اتصال" to Icons.Filled.Home, "تست" to Icons.Filled.Search, "کانفیگ‌ها" to Icons.Filled.Star,
        "لاگ" to Icons.Filled.Info, "تنظیمات" to Icons.Filled.Settings)
    Scaffold(containerColor = BG, bottomBar = {
        NavigationBar(containerColor = CARD) {
            items.forEachIndexed { i, (t, ic) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(ic, null) }, label = { Text(t, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = CARD2, selectedIconColor = ACCENT,
                        selectedTextColor = ACCENT, unselectedIconColor = MUTED, unselectedTextColor = MUTED))
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            when (tab) { 0 -> HomeTab(act); 1 -> TestTab(); 2 -> ConfigsTab(); 3 -> LogsTab(); else -> SettingsTab() }
        }
    }
}

@Composable
fun PowerButton(state: Int, onClick: () -> Unit) {
    val color = when (state) { 2 -> OK; 1 -> WARN; else -> ACCENT }
    val inf = rememberInfiniteTransition(label = "p")
    val pulse by inf.animateFloat(0.92f, 1.08f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "r")
    Box(Modifier.size(230.dp).clickable(interactionSource = remember { MutableInteractionSource() },
        indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center; val r = size.minDimension / 2
            val g = if (state == 0) 1f else pulse
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.40f), Color.Transparent), center = c, radius = r * g), radius = r * g)
            drawCircle(color.copy(alpha = 0.14f), radius = r * 0.74f)
            drawCircle(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.65f))), radius = r * 0.58f)
            if (state == 1) drawArc(Color.White, rot, 100f, false,
                topLeft = Offset(c.x - r * 0.67f, c.y - r * 0.67f), size = Size(r * 1.34f, r * 1.34f),
                style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            val pr = r * 0.2f
            drawArc(Color.White, -60f, 300f, false, topLeft = Offset(c.x - pr, c.y - pr + r * 0.03f),
                size = Size(pr * 2, pr * 2), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            drawLine(Color.White, Offset(c.x, c.y - pr * 1.2f), Offset(c.x, c.y - pr * 0.05f),
                strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun Card2(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CARD).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

private fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

@Composable
fun LtrText(text: String, color: Color = MUTED, size: Int = 12, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(text, modifier, color = color, fontSize = size.sp, maxLines = maxLines,
        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
}

private val MODES = listOf(
    Triple("auto", "خودکار (پیشنهادی)", "خودش Psiphon، WARP و Gool را یکی‌یکی امتحان می‌کند و اولین روش سالم را نگه می‌دارد"),
    Triple("psiphon", "Psiphon", "برای فیلترینگ سخت؛ کشور خروجی از تنظیمات"),
    Triple("warp", "WARP", "تونل ساده و سریع Cloudflare"),
    Triple("gool", "Gool", "WARP دوبل؛ IP متفاوت"),
    Triple("v2ray", "کانفیگ V2Ray", "یکی از کانفیگ‌ها را در تب «کانفیگ‌ها» انتخاب کن")
)

@Composable
fun HomeTab(act: MainActivity) {
    val ctx = LocalContext.current
    val running by TunnelService.running.collectAsState()
    val busy by TunnelService.busy.collectAsState()
    val status by TunnelService.status.collectAsState()
    val method by TunnelService.method.collectAsState()
    val since by TunnelService.since.collectAsState()
    val traffic by TunnelService.traffic.collectAsState()
    var mode by remember { mutableStateOf(Store.mode(ctx)) }
    var via by remember { mutableStateOf(Store.via(ctx)) }
    var adv by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }
    val locked = running || busy

    val state = if (running) 2 else if (busy) 1 else 0
    val sec = if (running && since > 0) (now - since) / 1000 else 0
    val uptime = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Private Tunnel", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        PowerButton(state) { if (locked) act.disconnect() else act.connect() }
        Text(when (state) { 2 -> "متصل"; 1 -> "در حال اتصال..."; else -> "قطع" },
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = when (state) { 2 -> OK; 1 -> WARN; else -> TXT })
        if (state == 2) {
            Text("با $method  ·  $uptime", color = MUTED)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("↑ " + fmt(traffic.first), color = ACCENT, fontWeight = FontWeight.Bold)
                Text("↓ " + fmt(traffic.second), color = OK, fontWeight = FontWeight.Bold)
            }
        } else Text(status.ifEmpty { "دکمه را لمس کن" }, color = if (status.startsWith("خطا")) ERR else MUTED,
            textAlign = TextAlign.Center, fontSize = 14.sp)

        Card2 {
            Text("روش اتصال", fontWeight = FontWeight.Bold)
            MODES.forEach { (k, title, desc) ->
                val on = mode == k
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) CARD2 else Color.Transparent)
                    .clickable(enabled = !locked) { mode = k; Store.setMode(ctx, k) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = on, onClick = null, enabled = !locked)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(desc, color = MUTED, fontSize = 12.sp)
                    }
                }
            }
            TextButton(onClick = { adv = !adv }) { Text(if (adv) "بستن گزینه‌های پیشرفته" else "گزینه‌های پیشرفته") }
            if (adv) {
                Text("کانفیگ V2Ray اول از داخل چه تونلی رد شود؟", color = MUTED, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "مستقیم", "warp" to "WARP", "gool" to "Gool").forEach { (k, v) ->
                        FilterChip(selected = via == k, enabled = !locked,
                            onClick = { via = k; Store.setVia(ctx, k) }, label = { Text(v) })
                    }
                }
                Text("ترکیب Gool با Psiphon توسط هسته پشتیبانی نمی‌شود.", color = MUTED, fontSize = 11.sp)
            }
        }

        if (running && Store.hotspot(ctx)) {
            val addrs = TunnelService.hotspotAddresses(Store.port(ctx))
            Card2 {
                Text("اشتراک با لپ‌تاپ", fontWeight = FontWeight.Bold)
                Text("در v2rayN یک سرور SOCKS5 با این آدرس بساز:", color = MUTED, fontSize = 12.sp)
                addrs.forEach { LtrText(it, OK, 20) }
                if (addrs.isEmpty()) Text("آدرسی پیدا نشد. هات‌اسپات را روشن کن.", color = WARN)
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, v: Boolean, enabled: Boolean = true, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) TXT else MUTED)
        Switch(checked = v, enabled = enabled, onCheckedChange = on)
    }
}

@Composable
fun ProfilesTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(Store.profiles(ctx)) }
    var sel by remember { mutableIntStateOf(Store.selected(ctx)) }
    var msg by remember { mutableStateOf("") }
    val ping = remember { mutableStateMapOf<Int, String>() }

    fun add(n: List<Profile>) { list = list + n; Store.saveProfiles(ctx, list); msg = "${n.size} پروفایل اضافه شد" }

    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("پروفایل‌ها", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                scope.launch {
                    val body = if (text.startsWith("http")) withContext(Dispatchers.IO) { fetchSub(text) } else text
                    val found = body.lines().map { it.trim() }.filter { it.isNotEmpty() && UriParser.parse(it) != null }
                        .map { Profile(UriParser.name(it), it) }
                    if (found.isEmpty() && text.startsWith("{") && UriParser.parse(text) != null)
                        add(listOf(Profile(UriParser.name(text), text)))
                    else if (found.isEmpty()) msg = "چیز قابل‌استفاده‌ای در کلیپ‌بورد نبود" else add(found)
                }
            }) { Text("افزودن از کلیپ‌بورد") }
            OutlinedButton(onClick = {
                list.forEachIndexed { i, p -> scope.launch { ping[i] = withContext(Dispatchers.IO) { tcpPing(p) } } }
            }) { Text("تست پینگ") }
        }
        if (msg.isNotEmpty()) Text(msg, color = OK, fontSize = 13.sp)
        Text("لینک vless / vmess / trojan / ss / hy2 / tuic، لینک ساب، یا JSON خروجی sing-box", color = MUTED, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list) { i, p ->
                val on = sel == i
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) CARD2 else CARD)
                    .border(if (on) 1.5.dp else 0.dp, if (on) ACCENT else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { sel = i; Store.setSelected(ctx, i) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(p.uri.substringBefore("://").uppercase() + (ping[i]?.let { "  ·  $it" } ?: ""),
                            color = MUTED, fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        list = list.toMutableList().also { it.removeAt(i) }
                        Store.saveProfiles(ctx, list); sel = 0; Store.setSelected(ctx, 0)
                    }) { Icon(Icons.Filled.Delete, null, tint = ERR) }
                }
            }
        }
    }
}

private fun fetchSub(url: String): String = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "v2rayN/6.0")
    val t = c.inputStream.bufferedReader().readText().trim()
    if (t.contains("://") || t.startsWith("{")) t
    else String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8)
} catch (e: Exception) { "" }

private fun tcpPing(p: Profile): String = try {
    val o = UriParser.parse(p.uri)!!
    val t0 = System.currentTimeMillis()
    Socket().use { it.connect(InetSocketAddress(o.getString("server"), o.getInt("server_port")), 3000) }
    "${System.currentTimeMillis() - t0} ms"
} catch (e: Exception) { "ناموفق" }

@Composable
fun LogsTab() {
    val ctx = LocalContext.current
    val logs by TunnelService.logs.collectAsState()
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("لاگ", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", logs.joinToString("\n")))
            }) { Text("کپی") }
            OutlinedButton(onClick = { TunnelService.logs.value = emptyList() }) { Text("پاک کردن") }
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(CARD).padding(10.dp)) {
            LazyColumn(reverseLayout = true) {
                val r = logs.reversed()
                items(r.size) { Text(r[it], fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED) }
            }
        }
    }
}

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val running by TunnelService.running.collectAsState()
    var hotspot by remember { mutableStateOf(Store.hotspot(ctx)) }
    var scan by remember { mutableStateOf(Store.scan(ctx)) }
    var port by remember { mutableStateOf(Store.port(ctx).toString()) }
    var country by remember { mutableStateOf(Store.country(ctx)) }
    var endpoint by remember { mutableStateOf(Store.endpoint(ctx)) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (running) Text("برای تغییر تنظیمات اول قطع کن", color = WARN)
        Card2 {
            SwitchRow("اشتراک با هات‌اسپات", hotspot, !running) { hotspot = it; Store.setHotspot(ctx, it) }
            SwitchRow("اسکن خودکار endpoint سالم", scan, !running) { scan = it; Store.setScan(ctx, it) }
        }
        Card2 {
            OutlinedTextField(value = port, onValueChange = {
                port = it.filter(Char::isDigit).take(5)
                port.toIntOrNull()?.let { p -> if (p in 1024..65530) Store.setPort(ctx, p) }
            }, label = { Text("پورت SOCKS5 و HTTP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = country, onValueChange = {
                country = it.uppercase().take(2); Store.setCountry(ctx, country)
            }, label = { Text("کشور خروجی Psiphon (US, DE, NL ...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = endpoint, onValueChange = {
                endpoint = it.trim(); Store.setEndpoint(ctx, endpoint)
            }, label = { Text("endpoint دلخواه WARP (مثلاً 162.159.192.1:2408)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }
        Card2 {
            Text("اگر WARP/Gool گیر کرد، هویت ذخیره‌شده را پاک کن تا از نو ساخته شود.", color = MUTED, fontSize = 12.sp)
            OutlinedButton(enabled = !running, onClick = {
                File(ctx.filesDir, "warp").deleteRecursively(); msg = "هویت پاک شد"
            }) { Text("ریست هویت WARP") }
            if (msg.isNotEmpty()) Text(msg, color = OK)
        }
    }
}


// ---------------------------------------------------------------- network test tab
@Composable
fun TestTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by NetTest.rows.collectAsState()
    val running by NetTest.running.collectAsState()
    val summary by NetTest.summary.collectAsState()
    val suggestion by NetTest.suggestion.collectAsState()
    val clean by NetTest.clean.collectAsState()
    val scanning by NetTest.scanning.collectAsState()
    var applied by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تست شبکه", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("ببین اینترنت فعلی چه چیزهایی را عبور می‌دهد.", color = MUTED, fontSize = 13.sp)
        Button(onClick = { applied = false; scope.launch { NetTest.run(ctx) } }, enabled = !running,
            modifier = Modifier.fillMaxWidth()) { Text(if (running) "در حال تست..." else "شروع تست") }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (summary.isNotEmpty()) Card2 {
            Text("نتیجه", fontWeight = FontWeight.Bold)
            summary.forEach { Text(it, fontSize = 14.sp) }
            suggestion?.let { m ->
                Button(onClick = { Store.setMode(ctx, m); applied = true }) {
                    Text("پیشنهاد: " + TunnelService.modeLabel(m) + " (اعمال کن)")
                }
                if (applied) Text("اعمال شد. به تب اتصال برو.", color = OK, fontSize = 12.sp)
            }
        }

        TextButton(onClick = { details = !details }) { Text(if (details) "پنهان کردن جزئیات" else "نمایش جزئیات و ابزارها") }
        if (details) {
            rows.groupBy { it.group }.forEach { (g, list) ->
                Card2 {
                    Text(g, fontWeight = FontWeight.Bold)
                    list.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(when (r.ok) { true -> "✅"; false -> "❌"; null -> "⏳" })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontSize = 13.sp)
                                LtrText(r.detail, if (r.ok == false) ERR else MUTED, 11)
                            }
                        }
                    }
                }
            }
            Card2 {
                Text("اسکن IP تمیز Cloudflare", fontWeight = FontWeight.Bold)
                Text("برای کانفیگ‌هایی که پشت CDN هستند، آدرس سریع‌تر پیدا می‌کند.", color = MUTED, fontSize = 12.sp)
                Button(onClick = { scope.launch { NetTest.scanClean() } }, enabled = !scanning) {
                    Text(if (scanning) "در حال اسکن..." else "شروع اسکن")
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clean.forEach { (ip, ms) ->
                    Row(Modifier.fillMaxWidth().clickable { cm.setPrimaryClip(ClipData.newPlainText("ip", ip)) },
                        verticalAlignment = Alignment.CenterVertically) {
                        LtrText(ip, TXT, 15, Modifier.weight(1f), 1)
                        Text("$ms ms", color = OK)
                    }
                }
                if (clean.isNotEmpty()) Text("برای کپی روی هر IP بزن.", color = MUTED, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- configs: saved + online collector
@Composable
fun ConfigsTab() {
    var sub by remember { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub, containerColor = BG, contentColor = ACCENT) {
            Tab(selected = sub == 0, onClick = { sub = 0 }) { Text("ذخیره‌شده", Modifier.padding(12.dp)) }
            Tab(selected = sub == 1, onClick = { sub = 1 }) { Text("جمع‌آوری آنلاین", Modifier.padding(12.dp)) }
        }
        Box(Modifier.weight(1f)) { if (sub == 0) ProfilesTab() else CollectorTab() }
    }
}

private fun flagOf(cc: String): String =
    if (cc.length == 2 && cc.all { it in 'A'..'Z' })
        String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    else "🏳"

@Composable
fun CollectorTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by Collector.running.collectAsState()
    val speedRunning by Collector.speedRunning.collectAsState()
    val phase by Collector.phase.collectAsState()
    val progress by Collector.progress.collectAsState()
    val alive by Collector.alive.collectAsState()
    val results by Collector.results.collectAsState()
    var sort by remember { mutableStateOf("ping") }
    var country by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    val countries = results.map { it.cc }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }
    val shown = results.filter { country.isEmpty() || it.cc == country }.let { l ->
        when (sort) {
            "speed" -> l.sortedByDescending { it.speed }
            "country" -> l.sortedWith(compareBy({ it.cc }, { it.delay }))
            else -> l.sortedBy { it.delay }
        }
    }

    fun use(c: Cfg) {
        val i = Store.addProfile(ctx, Profile(c.name, c.uri))
        Store.setSelected(ctx, i); Store.setMode(ctx, "v2ray")
        msg = "انتخاب شد. حالا به تب اتصال برو و وصل شو."
    }

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("پیدا کردن کانفیگ سالم", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("لیست‌های عمومی را می‌گیرد و هر کانفیگ را واقعاً تست می‌کند. چند دقیقه طول می‌کشد.",
            color = MUTED, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!running) Button(onClick = { msg = ""; scope.launch { Collector.run(ctx, 300) } }, enabled = !speedRunning) {
                Text("شروع")
            } else Button(onClick = { Collector.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = ERR)) {
                Text("توقف")
            }
            if (results.isNotEmpty()) OutlinedButton(onClick = { scope.launch { Collector.speedTest(ctx) } },
                enabled = !running && !speedRunning) { Text(if (speedRunning) "در حال تست..." else "تست سرعت ۱۲ تای اول") }
        }
        if (phase.isNotEmpty()) Text(phase, fontSize = 13.sp, color = if (phase.startsWith("خطا") || phase.startsWith("هیچ")) ERR else MUTED)
        if (running && progress.second > 0) {
            LinearProgressIndicator(progress = { progress.first / progress.second.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("بررسی‌شده ${progress.first} از ${progress.second}  ·  سالم: $alive", fontSize = 12.sp)
        }
        if (msg.isNotEmpty()) Text(msg, color = OK, fontSize = 12.sp)

        if (results.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("ping" to "پینگ", "speed" to "سرعت", "country" to "کشور").forEach { (k, v) ->
                    FilterChip(selected = sort == k, onClick = { sort = k }, label = { Text(v) })
                }
                Text("|", color = MUTED)
                FilterChip(selected = country.isEmpty(), onClick = { country = "" }, label = { Text("همه") })
                countries.forEach { cc ->
                    FilterChip(selected = country == cc, onClick = { country = if (country == cc) "" else cc },
                        label = { Text(flagOf(cc) + " " + results.count { it.cc == cc }) })
                }
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown.size) { i ->
                val c = shown[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CARD).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(flagOf(c.cc), fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        LtrText(c.name, TXT, 13, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(c.proto.uppercase(), color = MUTED, fontSize = 11.sp)
                            Text("${c.delay} ms", fontSize = 11.sp,
                                color = if (c.delay < 800) OK else if (c.delay < 2000) WARN else ERR)
                            if (c.speed >= 0) Text(String.format(Locale.US, "%.1f Mbps", c.speed), fontSize = 11.sp, color = ACCENT)
                        }
                    }
                    Button(onClick = { use(c) }) { Text("اتصال") }
                }
            }
        }
    }
}
