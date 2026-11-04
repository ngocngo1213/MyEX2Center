# Technical Specification: Geely EX2 Custom In-Car System & Companion Application (EX2-Center)

> Trạng thái: cập nhật theo code hiện tại. Mục "Implemented" = đã code và build thành công; "Verified" = đã chạy thử trên emulator (`emulator-5554`). Chưa thử trên xe thật.

## 1. Project Overview & Target Environment
- **Project Name:** EX2-Center (Custom System Tool & Launcher for Geely EX2)
- **Target Hardware:** Geely EX2 head unit (IHU629G, `arm64-v8a`).
- **OS:** Android 9.0 (Pie), API 28 (min/target/compile SDK 28).
- **Privilege Level:** System app (`android:sharedUserId="android.uid.system"`), ký bằng platform key Geely (`platform.pk8`, `platform.x509.pem`).
- **Language:** Kotlin (tương thích API 28). UI hoàn toàn tiếng Việt.
- **References:** `drive_assist`, `CentralEXAuto` (thư mục `reference/`).
- **Branding:** icon launcher + ảnh xe lấy từ `my_ex2_icon.png` (biển số "Geely Ex2"); `img_my_ex2.png` dùng ở header và thẻ xe Dashboard.

---

## 2. Design System — "Cyber Emerald & Obsidian"
| Token | Màu | Dùng cho |
|---|---|---|
| `bg_primary` | `#090D14` | Nền chính |
| `card_surface` | `#121824` (viền `#1F293D` 1dp, bo 20dp) | Thẻ |
| `accent_primary` | `#10B981` | Nút Active, gear đang chọn |
| `accent_secondary` | `#F59E0B` | Cảnh báo, Valet, ESC OFF, "Làm mát nhanh" |
| `accent_danger` | `#EF4444` | Nguy hiểm/xóa, 12V < 12.0V |

**Quy tắc Driver-Centric** (màn 1920×1080, 1dp = 1px, nhìn từ ~75 cm):
- Cỡ chữ tối thiểu **14sp**; nhãn nút **16sp**; giá trị chính 22–48sp. Không dùng 9–13sp.
- Vùng chạm: header 52dp (thanh header 60dp), chế độ lái / Regen 60dp, media 56dp, điều hòa (hàng nút, +/-) 56dp, hàng kính 64dp. Nút nổi 64dp.
- Gear đang chọn nền Emerald, chữ trắng; nút Active luôn chữ trắng.
- **4 trạng thái nút điều khiển xe:** chưa biết (mờ 0.6, hiện `--`) · đang gửi (mờ 0.7 tới khi xe xác nhận) · active (Emerald, chữ trắng) · lỗi (toast "Xe không nhận lệnh: …", trở về trạng thái thật). Bị chặn do không ở số P → toast lý do.
- Ô TPMS bị ẩn khi xe không cung cấp dữ liệu lốp (không hiện `-- kPa` cố định).
- Nút nổi mặc định ở mép phải, phần dưới màn hình (không đè cần số/HUD); vị trí kéo được ghi nhớ.
- Màn hình đêm hiện gợi ý "Chạm bất kỳ đâu để thoát" 3 giây rồi mờ dần.
- Kho ứng dụng: lưới **2 cột** thẻ lớn, chữ 14–20sp, nút 56dp.

---

## 3. Core Modules & Architecture

### Module 1: Foreground Service & Automation — `CarAutomationService.kt`
- Vòng lặp 4s trên `HandlerThread` riêng (không gọi VHAL trên main thread). Chỉ có một điểm lên lịch (`scheduleTick` luôn gỡ tick cũ) nên vòng lặp không thể bị nhân đôi.
- Trigger: boot, `MY_PACKAGE_REPLACED` (sau tự cập nhật), `SCREEN_ON` (head unit thức dậy sau suspend → reset và áp lại).
- **AVAS** (`KEY_AUTO_AVAS`): áp lại **mỗi tick**, mọi số (xe tự bật lại AVAS khi thức — theo reference).
- **Drive Mode / Regen** (`KEY_PREFERRED_*`): giữ theo cài đặt khi ở **số P** và áp một lần khi **vào D**; chế độ người lái chọn trong lúc chạy được tôn trọng tới lần dừng sau.
- Mỗi lệnh ghi được xác nhận bằng đọc lại ở tick sau; sau 3 lần xe vẫn không nhận thì tạm dừng tính năng đó tới lần thức / về P (không "giằng co" với xe). `setAvasMode()` trả `true` chỉ khi đọc lại khớp.
- Đọc số thất bại 5 tick liên tiếp → `reconnect()` (binder chết sau suspend).
- **Valet / Bảo dưỡng** (`KEY_VALET_MODE`): khi bật, vòng lặp bỏ qua mọi can thiệp VHAL.
- **Crash Guard:** 2 exception ngoài dự kiến (lỗi code) trong 60s → dừng vòng lặp, ghi `KEY_GUARD_HALTED = true`. Xe từ chối lệnh ghi không làm ngắt Guard (xử lý bằng cơ chế tạm dừng theo tính năng ở trên).
  - Reset từ UI: bấm Pill Tự động hóa trên Dashboard → hộp thoại xác nhận → gửi `ACTION_RESET_GUARD` tới service và xóa cờ.

### Module 2: Floating Overlay — `FloatingControlService.kt`, `OverlayViewManager.kt`
- Cần `SYSTEM_ALERT_WINDOW`. Nút nổi bật/tắt qua Cài đặt (`key_floating_bubble_enabled`).
- Điều hòa nhanh (A/C, nhiệt độ, quạt), **Màn hình đêm (Blackout)** (chạm để thoát). Blackout do `FloatingControlService` sở hữu duy nhất (Dashboard gửi `ACTION_TRIGGER_BLACKOUT`), **tự tắt khi vào số R hoặc mất kết nối VHAL** (kiểm tra mỗi 500ms) để không che camera lùi. Nút nổi chỉ hiện khi `key_floating_bubble_enabled` bật.
- **Khởi động lại:** chỉ cho phép ở số P và xe đứng yên (`ParkGate`, kiểm tra cả trước và sau hộp thoại). `confirmAndReboot()` hiện hộp thoại xác nhận overlay (`TYPE_APPLICATION_OVERLAY`) với chuỗi `confirm_reboot_title`/`confirm_reboot_msg`; chỉ reboot khi xác nhận (`executeReboot()`).

### Module 3: Wi-Fi App Catalog & Installer — `AppCatalogActivity.kt`, `AppRepository.kt`, `PackageInstallerHelper.kt`
- Lưới 2 cột, 4 mục:
  1. **MyEX2Center** (`com.mipago.ex2center`, `self: true`) — tự cập nhật qua GitHub Releases; không có nút gỡ; app tự khởi động lại, `BootReceiver` (`MY_PACKAGE_REPLACED`) xác nhận.
  2. **VIETMAP LIVE** (`vn.vietmap.live`, `.xapk`, chỉ có split `armeabi-v7a`) — chưa phát hành qua mạng (`apkUrl` rỗng, không tự host bản APKPure) → nút "Cài từ USB / Máy".
  3. **SmartTube** (`org.smarttube.stable` 32.68, bản arm64 chính thức từ `yuliskov/SmartTube`, MIT).
  4. **Trình duyệt Web** (`com.aaex.carbrowser` 2.3).
  - Đã gỡ tạm thời: Google Maps, VTV Go, YouTube ReVanced / YT Music.
- **Catalog:** `apps.json` từ repo của dự án (`raw.githubusercontent.com/<owner>/MyEX2Center/main/apps.json`), fallback `assets/apps.json`. Không còn dùng repo bên thứ ba. Trường: `name, packageName, versionCode (long), versionName, sizeMb, type (apk|xapk), abis, minSdk, requiresPark, self, apkUrl, browserUrl, iconResName, description, sha256`.
- **Bảo mật tải:** chỉ HTTPS (kể cả sau redirect); **bắt buộc `sha256`** hợp lệ cho mọi mục tải qua mạng; catalog không được trỏ tới đường dẫn file trên máy.
- **Tải file lớn (Bước 7):** lưu vào `filesDir/downloads/<sha16>.<ext>.part`, **tải tiếp bằng HTTP Range** khi bị hủy/mất mạng (partial gắn với đúng mã SHA nên không bao giờ ghép nhầm file). Redirect xử lý thủ công, từng bước phải là HTTPS. Kiểm tra bộ nhớ trống trước khi tải (phần còn lại + bản sao của trình cài + 50 MB). Timeout kết nối 15s / đọc 30s. Tiến độ gửi tối đa 4 lần/giây. Nút cài khi đang tải thành **"Hủy tải (x%)"**; rời màn hình Kho → tự hủy, giữ partial. Xong → kiểm SHA-256 → đổi tên thành file hoàn chỉnh → cài → xóa file. `cleanupDownloads()` dọn partial không còn trong catalog và file cũ trong cache. Catalog từ xa lỗi/rỗng → dùng bản đóng gói.
- **Hiệu năng Kho (Bước 8):** tiến độ chỉ cập nhật đúng thẻ đang tải (`notifyItemChanged` + payload), không vẽ lại cả lưới; mỗi lần bind gỡ listener cũ. Icon: icon app đã cài được cache theo package, icon từ URL qua `LruCache` 4 MB + 2 luồng, gắn `tag` để ViewHolder tái sử dụng không hiện nhầm icon, không tải trùng. Quét USB/Download (đọc manifest APK/XAPK) luôn chạy ở luồng nền; chỉ khớp **đúng package name**, không còn khớp theo tên file. Callback bất đồng bộ bỏ qua khi màn hình đã đóng.
- **Bộ cài (`PackageInstallerHelper`):** tự nhận `.apk` hoặc gói split `.xapk`/`.apks` (zip chứa base + `config.*.apk`). Với XAPK: đọc `manifest.json`, chọn đúng 1 split ABI theo `Build.SUPPORTED_ABIS`, giữ mọi split mật độ/ngôn ngữ, stream từng entry thẳng từ zip vào **một** `PackageInstaller.Session` (không giải nén), `setSize()` = tổng kích thước. Kiểm tra trước khi commit: đúng package, `minSdk`, ABI (báo rõ "Không tương thích CPU"), bộ nhớ trống ≥ 2× dung lượng. Lỗi → `abandon()` session. Cài tuần tự trên 1 luồng nền, chờ kết quả tối đa 180s.
- **Receiver:** callback theo extra `request_key` (`s:<session>` / `u:<package>`) — sửa lỗi callback gỡ cài đặt không bao giờ chạy. `STATUS_PENDING_USER_ACTION` → mở hộp thoại hệ thống (fallback khi không cài ngầm được).
- **Cài từ USB/máy:** quét `.apk/.xapk/.apks`, khớp theo package trong manifest; file chọn qua trình chọn được sao chép tạm vào cache và xóa sau khi cài (không bao giờ xóa file của người dùng trên USB). Nút cài bị khóa khi đang tải/cài.
- **Release:** `./gradlew assembleRelease` ký bằng platform key (lint `ExpiredTargetSdkVersion` tắt vì không phát hành qua Google Play). Khóa ký, `reference/`, `catalog_apks/`, `pylib/` và mọi APK bị loại khỏi git (`.gitignore`).

### Module 4: Split-Screen Launcher — `SplitScreenLauncher.kt`
- Một chạm: Vietmap Live (trái) + SmartTube/YouTube (phải) bằng split-screen Android 9.

### Module 5: Dashboard — `MainActivity.kt`, `activity_main.xml`
Bố cục 3 cột (32% / 38% / 30%) + thanh Header.

**Header:** logo xe, giờ `HH:mm`, nhiệt độ ngoài, điện áp 12V (đỏ nếu < 12.0V), Pill Tự động hóa (xanh = bật; cam = Valet; cam cảnh báo = Crash Guard đã ngắt, bấm để reset), Wi-Fi SSID + IP, nút Kho ứng dụng / Blackout / Cài đặt.

**Cột 1 (lái):** HUD (P R N D, tốc độ, % pin, km ước tính + progress); chế độ lái (Tiết kiệm/Thoải mái/Thể thao) và Regen (Thấp/Vừa/Cao); phím tắt: Tắt âm AVAS, Camera 360, Bảo dưỡng (Valet). Auto Hold / HDC / ESC **đã ẩn** (mã VHAL chưa được kiểm chứng, nằm trong khối ADAS thật).

**Cột 2:** nút "Chạy Đôi: Vietmap + YouTube", media (prev/play/next, volume); điều hòa (A/C, Gió trong (nút AUTO đã bỏ vì `HVAC_AUTO_ON` là NOOP trên xe), ⚡Làm mát nhanh; nhiệt độ 17–32°C; quạt 1–8; gió mặt/chân; sấy trước/sau).

**Cột 3:** mô hình xe 160×160dp, 4 ô TPMS (kPa/°C), 4 chỉ báo kính (đọc thật từ VHAL, không đọc được → `Kính: --`); Đóng/Hé (15%)/Hạ hết kính (Hạ hết chỉ khi < 30 km/h), biển tốc độ (mở Vietmap). Nút mở cốp **đã ẩn** (`DOOR_POS` chỉ đọc, chưa có mã cốp).

**Quy tắc dữ liệu thật (không fake state):**
- Không có dữ liệu VHAL → hiển thị `--` (`-- km`, `--%`, `--°C`, `-- kPa`, `-- V • Ắc quy`, "Chưa kết nối xe"); không dùng giá trị cứng.
- **Kính:** không cập nhật UI lạc quan; sau lệnh đọc lại VHAL sau 1.5s và 5s. Xe không nhận lệnh → toast báo lỗi.
- **`ParkGate`** (`utils/ParkGate.kt`): cổng an toàn dùng chung, đọc VHAL ở luồng nền. `checkParked()` = số P + tốc độ ≤ 1 km/h; `checkSlowerThan(km/h)`. Trên đầu xe thật, không đọc được số/tốc độ → **chặn**. Chỉ thiết bị không có `android.car.Car` (emulator) được cho qua. Áp dụng cho: Reboot, cài/cập nhật/gỡ ứng dụng (kiểm lại ngay trước khi cài, sau khi tải xong), Hạ hết kính.

**Đồng bộ trạng thái điều khiển (Bước 4):**
- Mỗi lần poll đọc `ControlSnapshot` từ VHAL: A/C, Gió trong, sấy trước/sau, nhiệt độ (làm tròn 1°C), quạt, hướng gió (bitmask 1/2/3/4/6), Drive, Regen, AVAS. Không có giá trị khởi tạo giả định; chưa đọc được → `--` và nút mờ (alpha 0.6).
- `PendingWrites` (`utils/PendingWrites.kt`) chống nhấp nháy: khi bấm, giá trị mong muốn hiển thị ngay và được giữ trong tối đa 4.5s; xe báo đúng giá trị → xác nhận; hết hạn mà xe vẫn khác → hiện giá trị thật của xe + toast "Xe không nhận lệnh: …". Lệnh bị từ chối ngay (ghi lỗi) → trả về trạng thái thật ngay.
- Thanh trượt: khi đang kéo không bị poll ghi đè; ghi debounce 250ms và ghi lần cuối khi thả tay. Nút +/- dựa trên giá trị thật; chưa đọc được → toast, không ghi giá trị đoán.
- Sự kiện VHAL: `CarApiHelper.watch()` đăng ký `SENSOR_RATE_ONCHANGE` cho số, HVAC, Drive/Regen, kính → refresh sau 200ms; poll 3s giữ làm dự phòng. Sau mỗi lệnh ghi refresh thêm sau 1.2s.
- Nút AVAS thể hiện trạng thái mong muốn và đồng bộ `KEY_AUTO_AVAS` (tắt âm → service giữ tắt; bật âm → service ngừng tắt) để không giằng co với service.
- Media: tiêu đề/ứng dụng và trạng thái phát lấy từ `MediaSessionManager` (`MEDIA_CONTENT_CONTROL`, cấp cho system uid), fallback `AudioManager.isMusicActive`.
- Wi-Fi (`utils/WifiStatus.kt`): SSID/IP thật qua `ConnectivityManager` + `WifiManager`; không có → "Không có Wi-Fi" / `--`. Kho: dung lượng/kích thước không rõ → `--`.
- Overlay: mọi lệnh đọc/ghi VHAL của panel nổi và thanh chia đôi chạy trên executor nền.

**Threading:**
- Telemetry: `ScheduledExecutorService` (3s) đọc VHAL ở background, đẩy snapshot về `runOnUiThread`; bắt đầu ở `onResume`, hủy ở `onPause`; shutdown ở `onDestroy`.
- Ghi VHAL (nhiệt độ, quạt, chế độ lái, regen, kính, cốp, AVAS…) qua `climateExecutor` (single thread). Slider debounce 120ms; nút +/- ghi ngay.

### `CarApiHelper.kt` (VHAL)
- Singleton, một kết nối `Car` dùng chung cho cả tiến trình. Thành phần đăng ký bằng `acquire(context, owner)` / `release(owner)` (holders: `dashboard`, `automation-service`, `floating-service`, `park-gate`); chỉ đóng kết nối khi holder cuối cùng release → đóng Dashboard không cắt VHAL của service.
- `connect()` an toàn khi gọi lặp: gộp các lần gọi đồng thời, bỏ lần kết nối treo sau 10s, giải phóng `Car` cũ trước khi `createCar` mới; callback của kết nối cũ bị bỏ qua (bộ đếm `generation`). `car`/`cpm` là `@Volatile`. `reconnect()` cho binder chết.
- `ParkGate` tự kết nối và chờ tối đa 3s nếu chưa sẵn sàng.
- Các hàm đọc trả `null` khi không có dữ liệu (`TpmsData` nullable + `isValid`; `read12vBatteryVoltage()` → `Float?`).
- Có API ADAS: AutoHold/HDC/ESC (`is…Supported/read…/set…`), AEB, cửa sổ, cốp, HVAC, AVAS (reflection `CarAudioManager`), Quick Cool macro (thread riêng).
- Dùng `app/libs/car-stubs.jar` (không có `isPropertyAvailable`, chỉ `getPropertyList`/`getProperty`).

---

## 4. Project Configuration (`build.gradle`)
- `minSdk = 28`, `targetSdk = 28`, `compileSdk = 28` (theo thiết kế gốc; build thực tế dùng Gradle 8.11.1).
- Ký platform key hậu build/release.
- Build local: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`; cài emulator cần `adb install -r -t` (debug APK là test-only).

## 5. Android Manifest Requirements
- `android:sharedUserId="android.uid.system"`.
- Permissions: `INSTALL_PACKAGES`, `DELETE_PACKAGES`, `INJECT_EVENTS`, `SYSTEM_ALERT_WINDOW`, `WRITE_SECURE_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE`, (kho ứng dụng yêu cầu thêm `READ_EXTERNAL_STORAGE` lúc chạy).

## 6. Project Directory Structure
```text
MyEX2Center/
├── SPEC.md
├── my_ex2_icon.png
├── catalog_apks/                  # APK nguồn mở tải sẵn (local install)
├── apps.json                      # catalog gốc (bản sao: app/src/main/assets/apps.json)
├── app/libs/car-stubs.jar
├── app/src/main/
│   ├── java/com/mipago/ex2center/
│   │   ├── launcher/SplitScreenLauncher.kt
│   │   ├── repository/AppRepository.kt
│   │   ├── service/{CarAutomationService,FloatingControlService}.kt
│   │   ├── ui/{MainActivity,AppCatalogActivity,OverlayViewManager}.kt
│   │   └── utils/{CarApiHelper,PackageInstallerHelper}.kt
│   ├── res/ (drawable: img_my_ex2, ic_app_*, bg_btn_*, bg_gear_active, bg_pill_*; mipmap-*: ic_launcher)
│   └── AndroidManifest.xml
├── reference/
└── build.gradle
```

## 7. Verification Status
- **Verified (emulator, Android API 36 tablet 1920×1080):** build thành công; Dashboard hiển thị `--` khi không có VHAL; Auto Hold/HDC/ESC hiển thị `(N/A)` và bị vô hiệu; Valet bật/tắt đổi Pill + nút; Kho ứng dụng mở, 3×2, logo riêng từng app, Google Maps hiển thị "Đã cài".
- **Chưa verify:** đọc/ghi VHAL thật (emulator không có `android.car.Car`), Crash Guard trip/reset end-to-end, hộp thoại Reboot, cài đặt APK qua Wi-Fi với hash thật, hành vi trên head unit IHU629G.

## 8. Known Limitations / TODO
- Auto Hold / HDC / ESC / Mở cốp: đã gỡ khỏi code và ẩn khỏi UI cho tới khi dò được mã VHAL thật trên xe (so sánh ảnh chụp thuộc tính trước/sau khi bật trên màn hình gốc).
- TPMS dùng mã AOSP `TIRE_PRESSURE` `392168201` (area 1/2/4/8, kPa) — chưa xác nhận trên EX2; nhiệt độ lốp chưa có mã.
- Một số mục `apps.json` (Vietmap, Google Maps, VTV Go) trỏ link release-friendly nhưng chưa có `sha256` đã kiểm chứng; chưa có sha256 thì cần bổ sung trước khi phát hành.
- Kính dùng mã đã kiểm chứng `WINDOW_POS` `322964416`, area `16/64/256/1024` (field-catalog §8).
- Biển tốc độ hiển thị `--` (chưa có nguồn dữ liệu giới hạn tốc độ đã kiểm chứng). Điện áp 12V vẫn đọc từ `BatteryManager` của head unit, chưa phải ắc quy xe.
- Camera 360 phụ thuộc package `com.flyme.auto.camera`; chưa xác nhận trên xe.
- Wi-Fi IP fallback `172.20.10.12` vẫn là giá trị mặc định khi không đọc được; cân nhắc đổi sang `--`.
</Content>
</invoke>
