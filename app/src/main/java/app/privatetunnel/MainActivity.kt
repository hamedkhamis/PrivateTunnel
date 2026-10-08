package app.privatetunnel

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class MainActivity : ComponentActivity() {
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startTunnel()
    }

    fun connect() {
        val i = VpnService.prepare(this)
        if (i != null) vpnLauncher.launch(i) else startTunnel()
    }

    private fun startTunnel() {
        startForegroundService(Intent(this, TunnelService::class.java).setAction("start"))
    }

    fun disconnect() {
        startService(Intent(this, TunnelService::class.java).setAction("stop"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App(this) } } }
    }
}

private val MODES = listOf(
    "psiphon" to "Psiphon", "gool" to "Gool", "warp" to "WARP",
    "masque" to "Masque", "proxy" to "پروکسی/V2Ray"
)

@Composable
fun App(act: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("اتصال", "پروفایل‌ها", "لاگ", "تنظیمات")
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text(listOf("🔌", "📋", "📜", "⚙️")[i]) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            when (tab) {
                0 -> HomeTab(act)
                1 -> ProfilesTab()
                2 -> LogsTab()
                else -> SettingsTab()
            }
        }
    }
}

@Composable
fun HomeTab(act: MainActivity) {
    val ctx = LocalContext.current
    val running by TunnelService.running.collectAsState()
    val busy by TunnelService.busy.collectAsState()
    var mode by remember { mutableStateOf(Store.mode(ctx)) }
    val prof = Store.selectedProfile(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Private Tunnel", fontSize = 26.sp)
        Text(if (running) "🟢 متصل" else if (busy) "🟡 در حال اتصال..." else "🔴 قطع", fontSize = 18.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODES.forEach { (k, v) ->
                FilterChip(selected = mode == k, enabled = !running && !busy,
                    onClick = { mode = k; Store.setMode(ctx, k) }, label = { Text(v) })
            }
        }
        if (mode == "proxy") Text("پروفایل: " + (prof?.name ?: "انتخاب نشده (برو به تب پروفایل‌ها)"))
        if (mode == "psiphon") Text("کشور خروجی: " + Store.country(ctx))
        Button(
            onClick = { if (running) act.disconnect() else act.connect() },
            enabled = !busy || running,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(if (running) "قطع اتصال" else "اتصال", fontSize = 20.sp) }

        if (running && Store.hotspot(ctx)) {
            val addrs = TunnelService.hotspotAddresses(Store.port(ctx))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("اشتراک‌گذاری هات‌اسپات فعال است. روی لپ‌تاپ پراکسی SOCKS5 یا HTTP بگذار:")
                    addrs.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 18.sp) }
                    if (addrs.isEmpty()) Text("آدرس پیدا نشد، هات‌اسپات را روشن کن.")
                }
            }
        }
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

    fun add(newOnes: List<Profile>) {
        list = list + newOnes; Store.saveProfiles(ctx, list)
        msg = "${newOnes.size} پروفایل اضافه شد"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                scope.launch {
                    val body = if (text.startsWith("http")) withContext(Dispatchers.IO) { fetchSub(text) } else text
                    val found = body.lines().map { it.trim() }
                        .filter { it.isNotEmpty() && UriParser.parse(it) != null }
                        .map { Profile(UriParser.name(it), it) }
                    if (found.isEmpty() && text.startsWith("{") && UriParser.parse(text) != null)
                        add(listOf(Profile(UriParser.name(text), text)))
                    else if (found.isEmpty()) msg = "چیزی قابل‌استفاده در کلیپ‌بورد نبود"
                    else add(found)
                }
            }) { Text("افزودن از کلیپ‌بورد") }
            OutlinedButton(onClick = {
                scope.launch {
                    list.forEachIndexed { i, p ->
                        launch { ping[i] = withContext(Dispatchers.IO) { tcpPing(p) } }
                    }
                }
            }) { Text("تست پینگ") }
        }
        if (msg.isNotEmpty()) Text(msg)
        Text("لینک vless/vmess/trojan/ss/hy2/tuic، لینک ساب، یا JSON خروجی sing-box", fontSize = 12.sp)
        LazyColumn {
            itemsIndexed(list) { i, p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel == i, onClick = { sel = i; Store.setSelected(ctx, i) })
                    Column(Modifier.weight(1f)) {
                        Text(p.name, maxLines = 1)
                        Text(ping[i] ?: "", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        list = list.toMutableList().also { it.removeAt(i) }
                        Store.saveProfiles(ctx, list)
                        sel = 0; Store.setSelected(ctx, 0)
                    }) { Text("حذف") }
                }
            }
        }
    }
}

private fun fetchSub(url: String): String = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 15000
    c.setRequestProperty("User-Agent", "v2rayN/6.0")
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
    val logs by TunnelService.logs.collectAsState()
    LazyColumn(reverseLayout = true) {
        items(logs.reversed().size) { i ->
            Text(logs.reversed()[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val running by TunnelService.running.collectAsState()
    var hotspot by remember { mutableStateOf(Store.hotspot(ctx)) }
    var chain by remember { mutableStateOf(Store.chain(ctx)) }
    var scan by remember { mutableStateOf(Store.scan(ctx)) }
    var port by remember { mutableStateOf(Store.port(ctx).toString()) }
    var country by remember { mutableStateOf(Store.country(ctx)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (running) Text("برای تغییر تنظیمات اول قطع کن")
        SwitchRow("اشتراک با هات‌اسپات (لپ‌تاپ و ...)", hotspot) { hotspot = it; Store.setHotspot(ctx, it) }
        SwitchRow("زنجیره: پروکسی از داخل WARP", chain) { chain = it; Store.setChain(ctx, it) }
        SwitchRow("اسکن خودکار endpoint سالم", scan) { scan = it; Store.setScan(ctx, it) }
        OutlinedTextField(value = port, onValueChange = {
            port = it.filter(Char::isDigit).take(5)
            port.toIntOrNull()?.let { p -> if (p in 1024..65530) Store.setPort(ctx, p) }
        }, label = { Text("پورت (SOCKS5 و HTTP روی همین پورت)") }, singleLine = true)
        OutlinedTextField(value = country, onValueChange = {
            country = it.uppercase().take(2); Store.setCountry(ctx, country)
        }, label = { Text("کشور خروجی Psiphon (مثلاً US, DE, NL)") }, singleLine = true)
    }
}

@Composable
fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = v, onCheckedChange = on)
    }
}
