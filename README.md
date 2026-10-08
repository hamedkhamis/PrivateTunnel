# Private Tunnel

اپ شخصی اندروید برای تونل، با چند هسته متن‌باز. بیلد کامل با GitHub Actions، بدون نیاز به Android Studio.

## معماری (همان معماری AetherST)
TUN (VpnService) -> hev-socks5-tunnel -> SOCKS5 محلی -> یکی از هسته‌ها:
- **Aether** (https://github.com/CluvexStudio/Aether، مجوز AGPL-3.0): MASQUE روی HTTP/2 یا HTTP/3، WireGuard، Gool و Psiphon داخل تونل WARP. اسکن endpoint و تأیید مسیر داده را خودش انجام می‌دهد.
- **sing-box**: برای کانفیگ‌های V2Ray (VLESS/Reality، VMess، Trojan، Shadowsocks، Hysteria2، TUIC) و تست جمع‌آوری کانفیگ.

هویت Cloudflare روی سرور GitHub (خارج از ایران) با `aether --register all` ساخته و داخل APK گذاشته می‌شود؛ اگر نبود، خود Aether روی گوشی ثبت‌نام می‌کند. DNS از طریق تونل حل می‌شود. هات‌اسپات: همان پورت روی 0.0.0.0.

## راه‌اندازی
1. فایل‌ها را در یک ریپوی private گیت‌هاب push کن.
2. Actions > Build APK (خودش اجرا می‌شود یا Run workflow).
3. APK در Releases است.
### امضای ثابت (مهم برای آپدیت روی همان نصب)
یک بار keystore بساز:
    keytool -genkeypair -v -keystore release.jks -alias pt -keyalg RSA -keysize 2048 -validity 36500
    base64 -w0 release.jks
در Settings > Secrets این‌ها را بگذار: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
بدون آن‌ها با کلید debug امضا می‌شود و هر بیلد باید جدا نصب شود.

## استفاده با لپ‌تاپ
در تنظیمات، اشتراک هات‌اسپات را روشن کن، متصل شو، آدرس نمایش‌داده‌شده در صفحه اتصال را در v2rayN به صورت SOCKS5 بگذار.

## نکته مهم درباره هویت WARP
ثبت‌نام Cloudflare از داخل ایران معمولاً بلاک است. به همین دلیل workflow هویت‌ها را روی سرور GitHub (خارج از ایران) می‌سازد و داخل APK می‌گذارد. پس ریپو و Releases را private نگه دار.

## تست شبکه
تب «تست» در اولین اجرا خودکار اجرا می‌شود: DNS، UDP، پورت‌های Cloudflare، SNIهای عبوری و دسترسی به سایت‌ها را می‌سنجد، خلاصه و پیشنهاد می‌دهد و می‌تواند IPهای تمیز Cloudflare را پیدا کند.

## جمع‌آوری کانفیگ آنلاین
تب «کانفیگ‌ها > جمع‌آوری آنلاین»: چند لیست عمومی را می‌گیرد، تکراری‌ها را حذف می‌کند و با sing-box (clash API) هر کانفیگ را واقعاً تست می‌کند. نتیجه را می‌شود بر اساس پینگ، سرعت دانلود، کشور و پروتکل مرتب و فیلتر کرد. اگر منابع در دسترس نباشند، لیست داخل APK (که هفتگی به‌روز می‌شود) استفاده می‌شود.

## نحوه کار WARP و Gool
WARP و Gool با WireGuard داخل sing-box اجرا می‌شوند (نه با warp-plus)، چون موتور WireGuard داخل warp-plus روی اندروید خطای bind می‌داد. اپ خودش از بین IPهای Cloudflare (IPv4 و IPv6 و پورت‌های WARP) دسته‌دسته تست واقعی می‌کند و endpoint سالم را ذخیره می‌کند. Psiphon همچنان از warp-plus استفاده می‌کند.

## اگر وصل نشد
1. هر VPN دیگری (مثلاً AetherST) را کاملاً قطع کن. اپ در این حالت اجازه اتصال نمی‌دهد، چون تست‌ها از داخل آن VPN رد می‌شوند و نتیجه‌شان بی‌معنی است.
2. تب «لاگ» را کپی کن. بخش‌های `[warp]` و `[collector]` علت دقیق شکست را می‌نویسند.
