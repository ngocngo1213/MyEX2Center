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

## 2. Design System — "Tactile Cockpit" (từ 1.1.0)
Bề mặt nhám như vật liệu thật, **một** màu nhấn ấm cho trạng thái "đang bật", không gradient phát sáng / kính mờ / emoji.

| Token (Dashboard `dash_*`, app `core`) | Tối | Sáng | Dùng cho |
|---|---|---|---|
| `dash_bg` | `#0E1114` | `#E6EAEE` | Nền |
| `dash_card` | `#161B20` | `#F7F8FA` | Thẻ: viền sáng 1px trên + viền tối 1px dưới (`dash_card.xml`) |
| `dash_well` | `#0C1013` | `#E1E5E9` | Nút tắt: lõm (`dash_btn.xml`, ripple) |
| `dash_accent` = `accent_primary` | `#F2A93B` | `#F2A93B` | Nút bật, cần số đang chọn, công tắc |
| `dash_on_accent` = `text_on_accent` | `#1B1205` | `#1B1205` | Chữ/icon trên màu nhấn (chữ trắng trên hổ phách không đủ tương phản) |
| `accent_secondary` | `#FF7A3D` | `#E8590C` | Cảnh báo (cam, khác màu nhấn) |
| `dash_cool` | `#6CB6FF` | `#1F6FC2` | Mức quạt |

**Quy tắc:**
- Chữ tối thiểu **18sp** (nhãn thẻ 18sp chữ hoa giãn 8%); giá trị 26–60sp; tốc độ 176sp; chữ số dạng `tnum`.
- Nút điều khiển ≥ 76dp (nút icon 84dp, icon ~44dp); khe ≥ 10–12dp. Nút bật đổi **cả** nền, chữ và icon (không chỉ màu).
- Phản hồi chạm ngay khung đầu: nút lún 0.96 (`animator/dash_press.xml`) + ripple; không chờ xe.
- Trạng thái: chưa biết (mờ 0.6, `--`) · đang gửi (mờ 0.7) · bật (màu nhấn) · lỗi (toast "Xe không nhận lệnh: …").
- Icon điều hòa và cốp lấy từ app gốc của xe (`GeelyAutoClimate`, `GeelyAutoSettings` › phím tắt) để người lái nhận ra ngay; ảnh xe nhìn từ trên xuống lấy từ Cài đặt › Điều khiển của xe (`img_car_top`, bản sáng và tối).
- Bố cục **Đi đêm tối giản**: luôn giao diện tối + phủ tối đồng đều 18% (giữ nguyên tỉ lệ tương phản).
- Kho ứng dụng: lưới 3 cột, chữ ≥ 18sp, nút 56dp.

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
- Điều hòa nhanh (A/C, nhiệt độ, quạt), **Màn hình đêm (Blackout)** (chạm để thoát). Blackout do `FloatingControlService` sở hữu duy nhất (Dashboard gửi `ACTION_TRIGGER_BLACKOUT`), **tự tắt khi vào số R hoặc mất kết nối VHAL** (kiểm tra mỗi 500ms) để không che camera lùi. Nút nổi chỉ hiện khi `key_floating_bubble_enabled` bật; khi nút nổi tắt, service chỉ chạy trong lúc có Blackout / thanh Chạy đôi và **tự `stopSelf()`** khi cả hai đóng (`OverlayViewManager.onOverlayClosed`, `hasVisibleOverlay`).
- **Khởi động lại:** chỉ cho phép ở số P và xe đứng yên (`ParkGate`, kiểm tra cả trước và sau hộp thoại). `confirmAndReboot()` hiện hộp thoại xác nhận overlay (`TYPE_APPLICATION_OVERLAY`) với chuỗi `confirm_reboot_title`/`confirm_reboot_msg`; chỉ reboot khi xác nhận (`executeReboot()`).

### Module 3: Wi-Fi App Catalog & Installer — EX2 Store `StoreActivity.kt` (module `:store`), `AppRepository.kt`, `PackageInstallerHelper.kt` (`:core`)

Màn hình Kho chỉ có trong EX2 Store; MyEX2Center không có Kho tích hợp.
- Lưới 3 cột, 9 mục:
  1. **MyEX2Center** (`com.mipago.ex2center`, `self: true`) — tự cập nhật qua GitHub Releases (tag `v<phiên bản>`); không có nút gỡ; app tự khởi động lại, `BootReceiver` (`MY_PACKAGE_REPLACED`) xác nhận.
  2. **EX2 Store** (`com.mipago.ex2store`) — tự cập nhật (tag `store-v<phiên bản>`).
  3. **VIETMAP LIVE** (`vn.vietmap.live` 3.4.4, gói `.apks` APKCube có split `arm64_v8a`; `base.apk` trùng từng byte với bản APKPure) — không tự host (`apkUrl` rỗng) → "Cài từ USB / Máy".
  4. **SmartTube** (`org.smarttube.stable` 32.68, arm64, `yuliskov/SmartTube`).
  5. **NewPipe** 0.29.1, 6. **PipePipe** 5.5.0 (arm64), 7. **OuterTune** 0.10.1, 8. **Kreate** 2.2.4 (arm64) — đều từ GitHub Releases chính thức của từng dự án, không cần Google Play Services.
  9. **Trình duyệt Web** (`com.aaex.carbrowser` 2.3).
- **Catalog:** `apps.json` từ repo của dự án (`raw.githubusercontent.com/<owner>/MyEX2Center/main/apps.json`), fallback `assets/apps.json`. Không còn dùng repo bên thứ ba. **Catalog có chữ ký:** catalog từ xa chỉ được dùng khi `apps.json.sig` (ECDSA P-256 / SHA-256, base64 DER) khớp khóa công khai nhúng trong `CatalogVerifier`; bản đã xác minh được lưu ở `filesDir/catalog_verified.json`. Thứ tự: catalog từ xa đã ký → bản đã lưu → bản đóng gói. Catalog không chữ ký / bị sửa bị bỏ qua. Catalog đã ký được **gộp** với bản đóng gói (`mergeWithBundled`): mục từ xa luôn thắng, trừ khi mục đóng gói có phiên bản cao hơn **và** SHA-256 thật; app chỉ có trong bản đóng gói vẫn hiện (để thêm app mới trước khi đẩy catalog). Mục MyEX2Center / EX2 Store trong bản đóng gói luôn để SHA giả (`__SELF_SHA256__` / `__STORE_SHA256__`) vì APK không thể chứa hash của chính nó. Trường: `name, packageName, versionCode (long), versionName, sizeMb, type (apk|xapk|apks), batteryWhitelist (bool), abis, minSdk, requiresPark, self, apkUrl, browserUrl, iconResName, description, sha256`.
- **Bảo mật tải:** chỉ HTTPS (kể cả sau redirect); **bắt buộc `sha256`** hợp lệ cho mọi mục tải qua mạng; catalog không được trỏ tới đường dẫn file trên máy.
- **Tải file lớn (Bước 7):** lưu vào `filesDir/downloads/<sha16>.<ext>.part`, **tải tiếp bằng HTTP Range** khi bị hủy/mất mạng (partial gắn với đúng mã SHA nên không bao giờ ghép nhầm file). Redirect xử lý thủ công, từng bước phải là HTTPS. Kiểm tra bộ nhớ trống trước khi tải (phần còn lại + bản sao của trình cài + 50 MB). Timeout kết nối 15s / đọc 30s. Tiến độ gửi tối đa 4 lần/giây. Nút cài khi đang tải thành **"Hủy tải (x%)"**; rời màn hình Kho → tự hủy, giữ partial. Xong → kiểm SHA-256 → đổi tên thành file hoàn chỉnh → cài → xóa file. `cleanupDownloads()` dọn partial không còn trong catalog và file cũ trong cache. Catalog từ xa lỗi/rỗng → dùng bản đóng gói.
- **Hiệu năng Kho (Bước 8):** tiến độ chỉ cập nhật đúng thẻ đang tải (`notifyItemChanged` + payload), không vẽ lại cả lưới; mỗi lần bind gỡ listener cũ. Icon: icon app đã cài được cache theo package, icon từ URL qua `LruCache` 4 MB + 2 luồng, gắn `tag` để ViewHolder tái sử dụng không hiện nhầm icon, không tải trùng. Quét USB/Download (đọc manifest APK/XAPK) luôn chạy ở luồng nền; chỉ khớp **đúng package name**, không còn khớp theo tên file. Callback bất đồng bộ bỏ qua khi màn hình đã đóng.
- **Bộ cài (`PackageInstallerHelper`):** tự nhận `.apk` hoặc gói split `.xapk`/`.apks` (zip chứa base + `config.*.apk`). Với XAPK: đọc `manifest.json` (gói `.apks` không có file này → đọc `AndroidManifest.xml` nhị phân của `base.apk`), chọn đúng 1 split ABI theo `Build.SUPPORTED_ABIS`, giữ mọi split mật độ/ngôn ngữ, stream từng entry thẳng từ zip vào **một** `PackageInstaller.Session` (không giải nén), `setSize()` = tổng kích thước. Kiểm tra trước khi commit: đúng package, `minSdk`, ABI (báo rõ "Không tương thích CPU"), bộ nhớ trống ≥ 2× dung lượng. Lỗi → `abandon()` session. Cài tuần tự trên 1 luồng nền, chờ kết quả tối đa 180s.
- **Receiver:** callback theo extra `request_key` (`s:<session>` / `u:<package>`) — sửa lỗi callback gỡ cài đặt không bao giờ chạy. `STATUS_PENDING_USER_ACTION` → mở hộp thoại hệ thống (fallback khi không cài ngầm được).
- **Cài từ USB/máy:** quét `.apk/.xapk/.apks`, khớp theo package trong manifest; file chọn qua trình chọn được sao chép tạm vào cache và xóa sau khi cài (không bao giờ xóa file của người dùng trên USB). Nút cài bị khóa khi đang tải/cài.
- **Release:** `./gradlew assembleRelease` ký bằng platform key (lint `ExpiredTargetSdkVersion` tắt vì không phát hành qua Google Play). Khóa ký, `reference/`, `catalog_apks/`, `pylib/` và mọi APK bị loại khỏi git (`.gitignore`).

### Module 4: Split-Screen Launcher — `SplitScreenLauncher.kt`
- Một chạm: Vietmap Live (trái) + SmartTube/YouTube (phải) bằng split-screen Android 9.

### Module 5: Dashboard — `MainActivity.kt`, `ui/dash/*`, `layout/widget_*.xml`
**Thanh trạng thái (64dp):** giờ, nhiệt độ ngoài, P R N D, ô cảnh báo (chỉ hiện khi Crash Guard đã ngắt / Valet bật), Wi-Fi (chạm → màn Wi-Fi của xe `com.flyme.auto.intent.action.ACTION_WIFI_SETTINGS`), **Chỉnh bố cục**, Blackout, Cài đặt. Đã bỏ: ắc quy 12V, nút Kho ứng dụng, pill Tự động hóa, áp suất lốp (xe không cấp dữ liệu).

**Lưới Bento 6×3** (`BentoGridLayout`): ô ~299×248dp, khe 16dp, luôn lấp 100% vùng app 1920×896; mỗi widget đo đúng một lần với kích thước cố định nên cập nhật chữ không làm cả màn hình đo lại.

| Widget | Cỡ | Nội dung |
|---|---|---|
| Lái xe `DRIVE` | 2×2 | tốc độ, biển giới hạn (chỉ khi có dữ liệu thật), % pin, km |
| Điều hòa `CLIMATE` / `CLIMATE_COMPACT` | 2×2 / 2×1 | −/+ nhiệt độ (thang 17–32), −/+ quạt (1–8); nút icon A/C, gió trong, gió ngoài, làm mát nhanh, 3 hướng gió (mặt 1 / mặt+chân 3 / chân 2), sấy trước/sau |
| Xe · Cốp · Kính `CAR` | 2×2 | ảnh xe thật, kính TT/TP/ST/SP + %, cốp trước (chỉ ở P, xác nhận), kính Đóng/10/50/100 (50/100 khoá khi ≥ 30 km/h hoặc không đọc được tốc độ) |
| Nhạc `MEDIA` / `MEDIA_LARGE` | 2×1 / 2×2 | bài + app đang phát, trước/phát/sau, âm lượng (theo kịp vô lăng) |
| Chế độ lái `DRIVE_MODE` | 2×1 | Eco/Comfort/Sport + phanh tái sinh |
| Phím tắt `SHORTCUTS` | 2×1 | Tắt âm AVAS, Auto Hold, Đèn nội thất, Sạc không dây, Chạy đôi, Valet |
| Pin `BATTERY`, Chạy đôi `SPLIT` | 2×1 | |
| Ô 1×1 `TILE` | 1×1 | Tắt màn hình, Đèn nội thất, Sấy kính, Auto Hold, Valet, AVAS, Sạc không dây, Mở cốp trước |

**Bố cục** (`DashPresets.kt`): 3 preset (Lái xe hàng ngày · Đi đêm tối giản · Đỗ xe / Cắm trại — preset đỗ xe không có chạy đôi). Bố cục đang dùng lưu JSON có phiên bản (`prefs dashboard/layout_v1`); dữ liệu sai (widget lạ, ra ngoài lưới, chồng lấn, trùng) → về "Lái xe hàng ngày". Chọn preset trong Cài đặt › Giao diện.

**Chỉnh bố cục (chỉ ở P):** nút "Chỉnh bố cục" (khoá khi không ở P). Lớp `EditOverlay` nhận mọi chạm (nút widget không thể bấm nhầm): viền nét đứt, "−" ẩn, "⇲" đổi cỡ, kéo để di chuyển (ô trống → chuyển; widget dưới vừa chỗ cũ → đổi chỗ; không thì đẩy sang chỗ trống; không được → bật về). Thanh trên: 3 preset, + Thêm widget (khay widget đang ẩn), Hoàn tác (30 bước), Về mặc định, Xong. Xe rời P hoặc mất tín hiệu → tự lưu và khoá ngay.

**Dữ liệu xe:** `car/CarStateHub.kt` — một luồng đọc VHAL cho cả app (Dashboard + thanh dưới), chỉ đọc nhóm tín hiệu đang có người đăng ký, một callback thay đổi VHAL cho cả tiến trình, poll theo chu kỳ ngắn nhất được yêu cầu (Dashboard 3s, thanh dưới 2s). Ghi qua `CarCommands` (một luồng, đọc lại sau 1.2s). Dashboard chỉ đổi nền/icon khi trạng thái thật sự đổi: khi đứng yên ~1 khung hình / 30s (14ms).

**"Ghi nhớ" chế độ lái / phanh tái sinh** (`KEY_AUTO_DRIVE_MODE`, `KEY_AUTO_REGEN_MODE`): trong 20s sau khi service khởi động hoặc head unit thức dậy, chế độ đã lưu được ghi lại vào xe (đọc lại xác nhận, tối đa 3 lần); sau đó chỉ theo dõi — lựa chọn của người lái (trên Dashboard hoặc màn hình gốc của xe) ổn định qua 2 tick sẽ được lưu. Không còn ép chế độ khi đang lái.

**Cài đặt (`SettingsActivity`):** 2 cột thẻ, mọi tùy chọn là công tắc chỉ phản ứng khi chạm (không bao giờ ghi lại khi đọc trạng thái).
- Hồ sơ xe: đổi tên (≤ 32 ký tự, "Mặc định"), chọn ảnh (thu nhỏ ≤ 512px, lưu `filesDir/car_photo.jpg`), dùng ảnh mặc định (`utils/CarProfile.kt`).
- Giao diện: Theo hệ thống / Sáng / Tối (`utils/ThemeManager.kt`; màu sáng `values/`, tối `values-night/`; áp cho Activity và overlay; thanh trạng thái đổi theo).
- Kết nối: Wi-Fi (SSID ● IP, tắt phải xác nhận), Đèn viền nội thất (VHAL `557885013` boolean, đã kiểm chứng; ghi rồi đọc lại).
- Lái xe & Tự động hóa: ghi nhớ chế độ lái, ghi nhớ phanh tái sinh, tự động tắt âm AVAS, Valet, Crash Guard (trạng thái + đặt lại).
- Hệ thống: nút nổi, Kho ứng dụng, phiên bản.
- **Cốp trước:** chưa có mã VHAL đã kiểm chứng trong `reference/` → chưa thêm nút.

**Quy tắc dữ liệu thật (không fake state):**
- Không có dữ liệu VHAL → hiển thị `--` (`-- km`, `--%`, `--°C`, `-- kPa`, `-- V • Ắc quy`, "Chưa kết nối xe"); không dùng giá trị cứng.
- **Kính:** không cập nhật UI lạc quan; sau lệnh đọc lại VHAL sau 1.5s và 5s. Xe không nhận lệnh → toast báo lỗi.
- **`ParkGate`** (`utils/ParkGate.kt`): cổng an toàn dùng chung, đọc VHAL ở luồng nền. `checkParked()` = số P + tốc độ ≤ 1 km/h; `checkSlowerThan(km/h)`. Trên đầu xe thật, không đọc được số/tốc độ → **chặn**. Chỉ thiết bị không có `android.car.Car` (emulator) được cho qua. Áp dụng cho: Reboot, cài/cập nhật/gỡ ứng dụng (kiểm lại ngay trước khi cài, sau khi tải xong), Hạ hết kính.

**Đồng bộ trạng thái điều khiển (Bước 4):**
- Mỗi lần poll đọc `ControlSnapshot` từ VHAL: A/C, Gió trong, sấy trước/sau, nhiệt độ (làm tròn 1°C), quạt, hướng gió (bitmask 1/2/3/4/6), Drive, Regen, AVAS, Đèn viền. Không có giá trị khởi tạo giả định; chưa đọc được → `--` và nút mờ (alpha 0.6).
- `PendingWrites` (`utils/PendingWrites.kt`) chống nhấp nháy: khi bấm, giá trị mong muốn hiển thị ngay và được giữ trong tối đa 4.5s; xe báo đúng giá trị → xác nhận; hết hạn mà xe vẫn khác → hiện giá trị thật của xe + toast "Xe không nhận lệnh: …". Lệnh bị từ chối ngay (ghi lỗi) → trả về trạng thái thật ngay.
- Thanh trượt: khi đang kéo không bị poll ghi đè; ghi debounce 250ms và ghi lần cuối khi thả tay. Nút +/- dựa trên giá trị thật; chưa đọc được → toast, không ghi giá trị đoán.
- Sự kiện VHAL: `CarApiHelper.watch()` đăng ký `SENSOR_RATE_ONCHANGE` cho số, HVAC, Drive/Regen, kính → refresh sau 200ms; poll 3s giữ làm dự phòng. Sau mỗi lệnh ghi refresh thêm sau 1.2s.
- Nút AVAS thể hiện trạng thái mong muốn và đồng bộ `KEY_AUTO_AVAS` (tắt âm → service giữ tắt; bật âm → service ngừng tắt) để không giằng co với service.
- Media: tiêu đề/ứng dụng và trạng thái phát lấy từ `MediaSessionManager` (`MEDIA_CONTENT_CONTROL`, cấp cho system uid), fallback `AudioManager.isMusicActive`.
- Wi-Fi (`utils/WifiStatus.kt`): SSID/IP thật qua `ConnectivityManager` + `WifiManager`; không có → "Không có Wi-Fi" / `--`. Kho: dung lượng/kích thước không rõ → `--`.
- Overlay: mọi lệnh đọc/ghi VHAL của panel nổi và thanh chia đôi chạy trên executor nền.

**Threading:** đọc VHAL trên luồng `car-state-hub`, ghi trên `car-commands`, thông tin media trên `mediaExecutor`; đăng ký Hub ở `onResume`, hủy ở `onPause`.

### `CarApiHelper.kt` (VHAL)
- Singleton, một kết nối `Car` dùng chung cho cả tiến trình. Thành phần đăng ký bằng `acquire(context, owner)` / `release(owner)` (holders: `dashboard`, `automation-service`, `floating-service`, `park-gate`); chỉ đóng kết nối khi holder cuối cùng release → đóng Dashboard không cắt VHAL của service.
- `connect()` an toàn khi gọi lặp: gộp các lần gọi đồng thời, bỏ lần kết nối treo sau 10s, giải phóng `Car` cũ trước khi `createCar` mới; callback của kết nối cũ bị bỏ qua (bộ đếm `generation`). `car`/`cpm` là `@Volatile`. `reconnect()` cho binder chết.
- `ParkGate` tự kết nối và chờ tối đa 3s nếu chưa sẵn sàng.
- Các hàm đọc trả `null` khi không có dữ liệu (`TpmsData` nullable + `isValid`; `read12vBatteryVoltage()` → `Float?`).
- API: chế độ lái, Regen, số, tốc độ, giới hạn tốc độ (chỉ đọc), pin, quãng đường, nhiệt độ ngoài trời, HVAC, kính, đèn viền, TPMS, AVAS (reflection `CarAudioManager`), Quick Cool macro (thread riêng). Không có API ADAS (AEB/HDC/ESC/Auto Hold) — chưa kiểm chứng, ghi vào khối ADAS là không an toàn.
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

Một repo, 3 module Gradle (MyEX2Center và EX2 Store là 2 app riêng):

```text
MyEX2Center/
├── core/        # thư viện dùng chung (namespace com.mipago.ex2center.core)
│   ├── libs/car-stubs.jar
│   └── src/main/
│       ├── java/com/mipago/ex2center/
│       │   ├── utils/   CarApiHelper, ParkGate, PackageInstallerHelper(+StatusReceiver),
│       │   │            LocalApkFinder, CatalogVerifier, ThemeManager, UiStyle, WifiStatus
│       │   ├── model/   AppInfo
│       │   ├── repository/ AppRepository (catalog + tải resumable)
│       ├── res/         theme Sáng/Tối, dimens 8pt, font, bộ icon
│       └── assets/apps.json (catalog dự phòng)
├── app/         # MyEX2Center  (com.mipago.ex2center): Dashboard, VHAL, dịch vụ, nút nổi, Cài đặt
├── store/       # EX2 Store    (com.mipago.ex2store): StoreActivity (màn hình Kho), job kiểm tra cập nhật
├── apps.json    # catalog phát hành (raw.githubusercontent.com/ngocngo1213/MyEX2Center/main/apps.json)
├── scripts/build-release.sh   # build + ký platform 2 APK vào dist/, in SHA-256
├── scripts/update-catalog.py  # điền phiên bản/link/SHA-256 từ dist/ vào apps.json và ký → apps.json.sig
└── .github/workflows/build.yml # CI: build debug 2 app, kiểm tra JSON catalog (không ký)
```

- Cả hai app ký cùng khóa platform, bản release có `sharedUserId=android.uid.system`.
- Nút **Kho ứng dụng** (Dashboard, Cài đặt) gọi `StoreLauncher.open()` (module `:app`): mở EX2 Store nếu đã cài. Nếu chưa có, hiện hộp thoại **Cài EX2 Store**: *Tải và cài* (chỉ từ catalog đã xác minh chữ ký — từ mạng hoặc bản đã lưu, kiểm SHA-256, cài ngầm, chỉ khi ở số P) hoặc *Cài từ USB* (tìm `EX2Store-*.apk` trên USB/Download theo đúng package). Cài xong tự mở Store. MyEX2Center chỉ giữ `INSTALL_PACKAGES` cho việc này (đã bỏ `DELETE_PACKAGES`).
- Trong Store, MyEX2Center là một app thường (cập nhật không làm tắt Store); chỉ EX2 Store tự cập nhật chính nó (`StoreUpdateReceiver` xác nhận qua `MY_PACKAGE_REPLACED`).
- Khóa ký **không** có trong repo hay CI; ký bằng `scripts/build-release.sh` trên máy có `signing/platform.p12`. Khóa ký catalog `signing/catalog-signing-key.pem` cũng chỉ nằm trên máy phát hành — mất khóa này phải phát hành Store mới với khóa mới.
- **Tự kiểm tra cập nhật (EX2 Store):** `UpdateScheduler` đặt job định kỳ 3 giờ (khi có mạng, giữ qua khởi động lại) + 1 lần chạy 2 phút sau khi xe khởi động / Store được cập nhật. `UpdateCheckJobService` chỉ tin catalog từ xa đã xác minh chữ ký; có bản mới thì hiện **một** thông báo, chỉ khi xe ở số P (chưa ở P → thử lại sau 10 phút); cùng một nhóm bản cập nhật chỉ báo lại sau 24 giờ. Chạm thông báo → mở Store.
- EX2 Store có mục riêng trong catalog (tag release `store-v<phiên bản>`) để tự cập nhật; MyEX2Center dùng tag `v<phiên bản>`.

## 7. Verification Status
- **Verified (emulator, Android API 36 tablet 1920×1080):** build thành công; Dashboard hiển thị `--` khi không có VHAL; Valet bật/tắt đổi Pill + nút; EX2 Store lưới 3 cột, 9 app, gộp catalog `5 remote, 9 total`; 4 app mới tải về khớp SHA-256.
- **Chưa verify:** đọc/ghi VHAL thật (emulator không có `android.car.Car`), Crash Guard trip/reset end-to-end, hộp thoại Reboot, cài đặt APK qua Wi-Fi với hash thật, hành vi trên head unit IHU629G.

## 8. Known Limitations / TODO
- Auto Hold / HDC / ESC / Mở cốp: đã gỡ khỏi code và ẩn khỏi UI cho tới khi dò được mã VHAL thật trên xe (so sánh ảnh chụp thuộc tính trước/sau khi bật trên màn hình gốc).
- TPMS dùng mã AOSP `TIRE_PRESSURE` `392168201` (area 1/2/4/8, kPa) — chưa xác nhận trên EX2; nhiệt độ lốp chưa có mã.
- Một số mục `apps.json` (Vietmap, Google Maps, VTV Go) trỏ link release-friendly nhưng chưa có `sha256` đã kiểm chứng; chưa có sha256 thì cần bổ sung trước khi phát hành.
- Kính dùng mã đã kiểm chứng `WINDOW_POS` `322964416`, area `16/64/256/1024` (field-catalog §8).
- Biển tốc độ đọc (chỉ đọc) 3 mã ứng viên theo thứ tự: biển do camera ADAS nhận diện `0x2140B029`, giới hạn của bản đồ gốc `0x2140303A`, tốc độ dẫn đường `0x2140A405` (lấy từ `reference/drive_assist/.../DashRecorder.java`, **chưa có trong field-catalog — chưa kiểm chứng**). Chỉ nhận giá trị 5–200 km/h, còn lại hiện `--`. Nguồn đang dùng được ghi log `Speed limit source:` mỗi khi đổi, để test trên xe. Điện áp 12V vẫn đọc từ `BatteryManager` của head unit, chưa phải ắc quy xe.
- Camera 360 phụ thuộc package `com.flyme.auto.camera`; chưa xác nhận trên xe.
- Wi-Fi IP fallback `172.20.10.12` vẫn là giá trị mặc định khi không đọc được; cân nhắc đổi sang `--`.
</Content>
</invoke>

### Giao diện chung
- Chữ/icon trên nền xanh hoặc màu nhấn dùng `@color/text_on_accent` (#FFFFFF ở cả Sáng và Tối).
- Mọi nền nút `bg_btn_*` / `bg_pill_*` là `<ripple>` bọc shape cũ (`ripple_on_accent` / `ripple_neutral`) để có phản hồi khi chạm.
- Ô số P/R/N/D 48dp, chữ 20sp. Chữ khởi tạo trong XML là `--` (không hiện số giả ở khung hình đầu).
- `assets/apps.json` (catalog dự phòng) chứa các bản đã phát hành của MyEX2Center / EX2 Store kèm SHA-256 thật, đồng bộ với `apps.json` gốc.

## 9. Kết quả trên xe thật (IHU629G, firmware 1114, 06/11/2026)
- Màn hình 1920×1080 @160dpi nhưng **vùng app chỉ cao 896px** (`mStable=[0,72][1920,968]`: thanh trạng thái 72px, thanh dock dưới 112px). Cột trái: card HUD và Chế độ lái `wrap_content`, card Phím tắt lấy phần còn lại; card Chạy đôi `wrap_content`, card Điều hòa lấy phần còn lại.
- **Hiệu năng:** một lượt measure/layout của Dashboard tốn ~2,8 s trên CPU đầu xe (weight lồng nhau + `baselineAligned`). `TextView.setText()` trên view `wrap_content` luôn ép layout, nên đồng hồ 1 s và telemetry 3 s làm app treo gần như liên tục. Sửa: `setTextIfChanged()` (`ui/ViewExt.kt`) cho mọi chữ cập nhật, `baselineAligned="false"` ở mọi hàng ngang có weight, spacer `0dp`+weight, bỏ lớp bọc lồng trong card điều hòa, ô tốc độ rộng cố định 136dp, `setTextSize` chỉ khi đổi. Đo trên xe: khung hình khi đứng yên 17–23 ms (trước 2.800 ms); một lượt layout khi giá trị đổi còn ~100–150 ms.
- **Đã đọc/ghi được:** số, tốc độ, quãng đường, % pin, nhiệt độ ngoài, chế độ lái, Regen, A/C, gió trong, nhiệt độ, quạt, hướng gió, AVAS, đèn viền (adapter ECARX ánh xạ mã AOSP sang mã vendor, ví dụ `HVAC_AC_ON`→557846563, `PHEV_DRV_MODE_SET` 557850837).
- **Áp suất lốp:** `TIRE_PRESSURE` được ánh xạ sang `TPMSPRESSURE_FL/FR/RL/RR` (557884502–505) nhưng trả `status=3`, giá trị 0 khi đỗ → UI "Chưa có dữ liệu lốp từ xe". Cần kiểm tra lại khi xe đang chạy.
- **Kính:** đọc `WINDOW_POS` trả `status=3` kèm log `checkWindowStatus: (WINDOW_POS) is not in Process: com.baidu.iov.sal` — có thể chỉ tiến trình hệ thống của Baidu được điều khiển kính; cần xác nhận lệnh ghi có tác dụng.
- **Biển tốc độ:** 3 mã tồn tại trên xe với tên `DHU_ROAD_CAMERA_LIMIT_SPEED` (557887529), `AP_NAVI_SPEED_LIMIT_VALUE` (557854778), `NAVSPEEDLIMIT` (557884421); `status=3` khi đỗ.
- **Camera:** app camera vòm của xe là `com.ecarx.parking/.MainAvmActivity` (nút "Camera 360" mở app này).
- **VIETMAP LIVE crash khi mở:** app gọi `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, màn hình này **không có** trên Flyme Auto → `ActivityNotFoundException`. Sửa: mục catalog có `"batteryWhitelist": true`; sau mỗi lần nạp catalog (tức là sau mỗi lần cài và mỗi lần mở Store), EX2 Store thêm các app đã cài có cờ này vào whitelist pin qua `IDeviceIdleController.addPowerSaveWhitelistApp` (`PowerWhitelist`, uid system + `DEVICE_POWER`). Khi gộp catalog, cờ này lấy từ bản từ xa **hoặc** bản đóng gói. Đã kiểm tra trên xe: bỏ whitelist → mở Store → `Battery whitelist vn.vietmap.live -> true`, Vietmap chạy được.
- **Lớp chuyển đổi ECARX:** `CarPropertyManager` trên IHU629G đi qua adapter (`getAdaptValue` / `getProperValue`): app đọc/ghi **giá trị chức năng**, adapter tự đổi sang giá trị thô của VHAL. Luôn ghi theo đúng cách app gốc của xe làm (xem log `car setProperty getProperValue … adapterValue = X properValue = Y`).
- **Nhóm 1 (Dashboard + Cài đặt):**
  - **Auto Hold** (`DRIVE_AUTO_HOLD` 0x2140208B): đọc boolean; ghi `setIntProperty(…, 1)` = Bật, `0` = Tắt (adapter → thô 1 / 2). ESC tự nhớ qua các lần khởi động nên không có "Ghi nhớ".
  - **Sạc không dây** (`BODY_WIRELESS_CHARGING_ON` 0x214020C7, area 5): đọc/ghi boolean true = Bật (thô bị đảo, adapter xử lý).
  - **Chẩn đoán phanh & ESC** (chỉ đọc): `ESC_ABSFAILED`, `ESC_ESPFAILED`, `ESC_TCSFAILED`, `ESC_EBDFAILED`, `ESC_EPBERRORSTATUS`, `IPKWARN_ESC_FAIL`; 0 = bình thường. Trên xe 06/11: 6/6 bình thường.
  - **Giới hạn âm lượng khi bật xe** (`KEY_SAFE_VOLUME`, mặc định tắt, mức 20–60 %, mặc định 40 %): `CarAutomationService` chỉ **hạ** `STREAM_MUSIC` về mức đó khi service khởi động và mỗi lần màn hình thức dậy.
  - `DISCHARGING_SOC_SET` (0x2140A6DC) là ngưỡng pin tối thiểu khi **xả điện V2L**, không phải giới hạn sạc — không đưa vào app.
- **Nhóm 2 (Cài đặt › Xe & tiện nghi)**, dò từ log app Cài đặt gốc của xe 06/11:
  - **Đèn chào khi mở cửa** (`DOOR_CONTROL_LIGHT_SWITCH` 0x2140A152): boolean chức năng true = Bật (thô 1 / 2).
  - **Mở cốp trước** (`DOOR_MOVE` 0x16400B01, area 0x10000000, true): chỉ ở số P (`ParkGate`) + hộp thoại xác nhận; đóng bằng tay.
  - **Mở camera 360 khi vào số D** (`KEY_CAMERA_ON_DRIVE`, mặc định tắt): `CarAutomationService` thấy P → D và tốc độ ≤ 5 km/h thì mở `com.ecarx.parking`.
  - Thấy nhưng **không** đưa vào app (ADAS / an toàn): `DRIVE_ESC_ON` (ESC Sport, 0x21402001), `ADAS_HDC_CONTROL_ON` (HDC, 0x21404000). App Điều hòa gốc dùng `HVAC_DEFROSTER` 0x23401024 area 1 (trước) / 2 (sau) dạng nút bấm-đảo.
  - Không thấy trong Cài đặt gốc của xe: gập gương tự động, đèn dẫn về nhà, tự khóa khi rời xe, gạt mưa sau, chiều cao đèn pha, âm lượng AVAS.
- **Nhiệt độ & quạt trên thanh dưới** (`DockClimateOverlay`, `KEY_DOCK_CLIMATE_ENABLED`, mặc định bật, công tắc trong Cài đặt › Hệ thống): bảng chỉnh nhanh "[−] 24°C [+]   [−] Quạt 2 [+]" phủ lên giữa thanh dưới gốc (`CarNavigationBar`, x 890..1290/1920, cao 112/1080, nền đục `#12161A` cùng màu thanh). Cửa sổ `TYPE_NAVIGATION_BAR_PANEL` (2024, uid system) + `NOT_FOCUSABLE|NOT_TOUCH_MODAL` → chạm ngoài bảng vẫn vào thanh của xe. Nút −/+ ghi nhiệt độ (17–32) / quạt (1–8; quạt tắt thì "−" không bật quạt) trên luồng riêng, hiện giá trị mới ngay và giữ 2,5 s trước khi đọc lại; chạm vào số nhiệt độ/quạt mở màn hình điều hòa gốc (`com.flyme.auto.hvac.action.ACTION_CLIMATE_HOME`). Đọc mỗi 2 s; ẩn khi hệ thống ẩn thanh điều hướng và khi Màn hình đêm. Do `FloatingControlService` giữ; service chạy khi nút nổi **hoặc** nhãn này bật (`isWanted`), khởi động từ `BootReceiver` và Dashboard.

