# Private Tunnel

اپ شخصی اندروید برای تونل، با چند هسته متن‌باز. بیلد کامل با GitHub Actions، بدون نیاز به Android Studio.

## معماری
TUN (VpnService) -> hev-socks5-tunnel (JNI) -> SOCKS5 محلی -> یکی از هسته‌ها:
- sing-box: VLESS/Reality, VMess, Trojan, Shadowsocks, Hysteria2, TUIC و هر outbound دیگر (ShadowTLS, AnyTLS, Naive, WireGuard) با paste کردن JSON
- warp-plus: Psiphon، Gool، WARP و Masque بدون سرور
- حالت زنجیره: پروکسی از داخل WARP

DNS به صورت mapdns از راه تونل حل می‌شود (نشت DNS ندارد). هات‌اسپات: همان پورت روی 0.0.0.0 باز می‌شود.

## راه‌اندازی
1. ریپو را روی GitHub بساز (private هم می‌شود) و همه فایل‌ها را push کن.
2. تب Actions را باز کن، workflow به نام Build APK خودش اجرا می‌شود (یا Run workflow).
3. بعد از چند دقیقه APK در Releases است. نصبش کن.
4. هر دوشنبه خودکار با آخرین نسخه هسته‌ها دوباره بیلد می‌شود.

### امضای ثابت (مهم برای آپدیت روی همان نصب)
یک بار keystore بساز:
    keytool -genkeypair -v -keystore release.jks -alias pt -keyalg RSA -keysize 2048 -validity 36500
    base64 -w0 release.jks
در Settings > Secrets این‌ها را بگذار: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
بدون آن‌ها با کلید debug امضا می‌شود و هر بیلد باید جدا نصب شود.

## استفاده با لپ‌تاپ
در تنظیمات، اشتراک هات‌اسپات را روشن کن، متصل شو، آدرس نمایش‌داده‌شده در صفحه اتصال را در v2rayN به صورت SOCKS5 بگذار.
