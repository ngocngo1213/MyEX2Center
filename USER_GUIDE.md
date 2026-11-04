# Hướng dẫn cài đặt & kết nối EX2-Center với xe Geely EX2

> **Đọc trước:** EX2-Center là app **cấp hệ thống** (`sharedUserId=android.uid.system`) nên **chỉ chạy đúng khi được ký bằng khóa platform của đầu xe**. Bản đã kiểm thử mới chỉ chạy trên **emulator**. Việc cài lên đầu xe thật (IHU629G) và đọc/ghi VHAL **chưa được kiểm chứng**. Hãy thử khi xe **đỗ, đang ở số P**, và có người biết cách khôi phục đầu xe nếu có sự cố.

---

## 1. Chuẩn bị

| Hạng mục | Yêu cầu |
|---|---|
| Đầu xe | Geely EX2 IHU629G, Android 9 (API 28), arm64-v8a |
| Máy tính | macOS/Linux/Windows có `adb` và JDK (Android Studio JBR) |
| Kết nối xe ↔ máy tính | Một trong hai: cổng USB (chế độ debug) hoặc ADB qua Wi-Fi (xe và máy tính cùng mạng) |
| Khóa ký | `signing/platform.pk8` và `signing/platform.x509.pem` (hoặc `platform.p12`) khớp với firmware đầu xe |
| Mạng | Wi-Fi hoặc hotspot điện thoại cho Kho ứng dụng |

> [!WARNING]
> Nếu APK **không ký bằng khóa platform đúng**, Android sẽ từ chối cài (xung đột `sharedUserId`) hoặc app chạy mà không có quyền hệ thống. Không dùng khóa không thuộc về bạn. Tôi không thể cung cấp khóa này.

---

## 2. Build APK

Tại thư mục dự án:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Bản debug (để thử, ký bằng cấu hình trong build.gradle)
./gradlew assembleDebug
# Kết quả: app/build/outputs/apk/debug/app-debug.apk

# Bản release rồi ký bằng khóa platform
./gradlew assembleRelease signWithPlatformKey
# Kết quả: app/build/outputs/apk/release/EX2Center-geely-platform-signed.apk
```

Tác vụ `signWithPlatformKey` cần lệnh `apksigner` (có trong Android SDK build-tools) và hai file khóa trong thư mục `signing/`.

---

## 3. Cài đặt lên xe

### Cách A: ADB qua USB
1. Bật chế độ nhà phát triển / USB debugging trên đầu xe (tùy firmware, có thể cần menu ẩn của nhà sản xuất).
2. Cắm cáp USB từ máy tính vào cổng dữ liệu của xe.
3. Kiểm tra kết nối:
   ```bash
   adb devices
   ```
   Thiết bị phải ở trạng thái `device` (nếu `unauthorized`, chấp nhận hộp thoại trên màn hình xe).
4. Cài đặt:
   ```bash
   adb install -r EX2Center-geely-platform-signed.apk
   ```
   Với bản debug thêm cờ `-t`: `adb install -r -t app-debug.apk`.

### Cách B: ADB qua Wi-Fi
1. Nối xe và máy tính vào **cùng mạng** (đầu xe hiện SSID và IP ở góc phải thanh trên của app, hoặc xem trong cài đặt Wi-Fi).
2. Bật ADB mạng trên đầu xe (nếu firmware cho phép), rồi:
   ```bash
   adb connect <IP_ĐẦU_XE>:5555
   adb devices
   adb install -r EX2Center-geely-platform-signed.apk
   ```

### Cách C: USB / thẻ nhớ
Copy APK vào USB, mở trình quản lý file trên xe và cài. Cách này tùy firmware và thường hiện hộp thoại xác nhận.

### Khi cài lỗi
| Thông báo | Nguyên nhân thường gặp | Cách xử lý |
|---|---|---|
| `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` | Khóa ký không khớp platform | Ký lại bằng đúng khóa |
| `INSTALL_FAILED_TEST_ONLY` | APK debug | Thêm `-t` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Đã có bản ký khác | Gỡ bản cũ rồi cài lại (mất dữ liệu app) |
| `adb: cannot connect` | Sai IP, khác mạng, ADB chưa bật | Kiểm tra lại bước kết nối |

---

## 4. Lần chạy đầu tiên

1. Mở app **MyEX2Center** (biểu tượng xe Geely EX2).
2. Cấp quyền khi được hỏi:
   - **Hiển thị trên ứng dụng khác** (`SYSTEM_ALERT_WINDOW`) cho nút nổi, Màn hình đêm và hộp thoại xác nhận Khởi động lại.
   - **Bộ nhớ** (đọc file) cho chức năng "Cài từ USB / Máy" trong Kho ứng dụng.
3. App tự khởi chạy dịch vụ tự động hóa `CarAutomationService` (hiện thông báo thường trực) và tự chạy lại khi xe khởi động (`BOOT_COMPLETED`).
4. Nếu muốn dùng như launcher, đặt MyEX2Center làm ứng dụng Home mặc định theo menu của đầu xe (tùy firmware).

---

## 5. Kiểm tra kết nối với xe

Dashboard tự đọc dữ liệu xe mỗi 3 giây. Hãy nhìn các chỉ báo sau:

| Trạng thái | Biểu hiện | Ý nghĩa |
|---|---|---|
| **Đã kết nối** | Tốc độ, số P/R/N/D, %pin, km, TPMS, nhiệt độ ngoài, điện áp 12V hiển thị số thật | App đã đọc được VHAL |
| **Chưa kết nối** | `--`, `-- km`, `--%`, `-- kPa`, `-- V`, chữ "Chưa kết nối xe" | Không đọc được tín hiệu. App cố ý **không hiện số giả** |
| Tính năng không hỗ trợ | Auto Hold / HDC / ESC bị làm mờ, ghi `(N/A)`, không bấm được | Xe không có hoặc không mở thuộc tính đó |

Thử nhanh khi xe **đỗ, số P**:
1. Đổi **Chế độ lái** hoặc **Regen**, kiểm tra xe có đổi theo không.
2. Chỉnh **nhiệt độ** hoặc **quạt gió**, đối chiếu với màn hình điều hòa gốc.
3. Bấm **Hé thoáng kính (15%)** rồi **Đóng kín (0%)**, quan sát kính thật.

> [!NOTE]
> Nút Active sáng (xanh) trong app phản ánh lệnh đã gửi. Hãy luôn đối chiếu với trạng thái thực tế của xe.

### Nếu hiện `--` mãi
- App chưa chạy với quyền hệ thống: kiểm tra khóa ký và `sharedUserId`.
- Firmware dùng mã thuộc tính VHAL khác: xem log để biết chi tiết.
  ```bash
  adb logcat -s CarApiHelper CarAutomationService MainActivity
  ```
  Dòng `ClassNotFoundException: android.car.Car` nghĩa là thiết bị đang chạy **không phải đầu xe** (ví dụ emulator thông thường).
- Thử khởi động lại app hoặc đầu xe.

---

## 6. Sử dụng các tính năng chính

**Thanh trên cùng**
- **Pill Tự động hóa:** bấm để bật/tắt **Chế độ Bảo dưỡng (Valet)**: tạm dừng mọi can thiệp tự động khi đi rửa xe/bảo dưỡng.
- Nếu pill báo **"⚠️ Crash Guard: Đã ngắt"**: hệ thống tự dừng sau 2 lỗi liên tiếp trong 60 giây. Bấm vào pill → **Đặt lại (Reset)** để chạy lại tự động hóa.
- Nút **Kho ứng dụng**, **Màn hình đêm** (chạm bất kỳ đâu để thoát), **Cài đặt**.

**Cột trái (tài xế):** chế độ lái, phanh tái sinh, Tắt âm AVAS, Auto Hold, HDC, ESC, Camera 360, Bảo dưỡng.

**Cột giữa:** **Chạy Đôi** Vietmap + YouTube (chia đôi màn hình), media, điều hòa, **⚡ Làm mát nhanh** (A/C, 17°C, quạt 7, gió mặt, lấy gió trong).

**Cột phải:** áp suất lốp, vị trí 4 kính, đóng/hé/hạ kính, mở cốp.

**Tự động khi vào số D:** tắt AVAS và khôi phục chế độ lái/regen đã lưu (bật/tắt trong **Cài đặt**).

> [!CAUTION]
> Tắt âm AVAS và ESC OFF ảnh hưởng an toàn người đi bộ và ổn định xe. Chỉ dùng khi hiểu rõ và đúng quy định giao thông tại nơi bạn lái. Không thao tác trên màn hình khi xe đang chạy.

---

## 7. Cài ứng dụng qua Kho ứng dụng (Wi-Fi)

1. Kết nối Wi-Fi (hotspot điện thoại cũng được). SSID và IP hiện ở góc trên phải.
2. Bấm biểu tượng **Kho ứng dụng** ở thanh trên.
3. Danh sách gồm 6 app: Vietmap Live, SmartTube, YouTube ReVanced/YT Music, Google Maps, VTV Go, Trình duyệt Web.
4. Bấm **Cài đặt** → app tải APK (**chỉ chấp nhận `https://`**), kiểm tra **mã băm SHA-256**, rồi cài.
5. Sau khi cài, nút chuyển thành **Mở** và biểu tượng thùng rác (**Gỡ cài đặt**, có hỏi xác nhận).
6. **Không có mạng:** danh mục lấy từ bản dự phòng trong app (mở trong vài giây).

**Cài từ USB / Máy:** bấm "Cài từ USB / Máy" trên thanh trên của Kho ứng dụng, chọn file `.apk` (hoặc dùng các APK trong `catalog_apks/`).

| Lỗi | Cách xử lý |
|---|---|
| `HTTP 404` | Link APK đã đổi/hết hạn: kiểm tra lại `apkUrl` trong `apps.json` |
| Hash không khớp | File bị đổi hoặc sai `sha256` trong `apps.json`: không cài, cập nhật đúng hash |
| `INSTALL_FAILED_INVALID_APK` | File tải dở hoặc sai package: tải lại |
| Báo thiếu quyền/không cài ngầm được | App chưa chạy với quyền hệ thống |

---

## 8. Gỡ cài đặt / khôi phục
- Gỡ app: `adb uninstall com.mipago.ex2center` hoặc trong cài đặt ứng dụng của xe.
- Nếu giao diện đầu xe lỗi sau khi cài: bật **Bảo dưỡng (Valet)**, dừng tự động hóa, hoặc gỡ app và khởi động lại đầu xe.

---

## 9. Hỏi đáp nhanh
- **App có chạy trên điện thoại thường không?** Mở được giao diện nhưng sẽ hiện `--` và `(N/A)` vì không có VHAL xe.
- **Auto Hold/HDC/ESC luôn `(N/A)`?** Mã thuộc tính trong code chỉ là giả định, cần xác nhận trên xe thật. Gửi log `adb logcat` để chỉnh.
- **Cần Internet không?** Chỉ cho Kho ứng dụng. Điều khiển xe hoạt động ngoại tuyến.
