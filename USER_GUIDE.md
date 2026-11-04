# Hướng dẫn cài đặt MyEX2Center cho Geely EX2 — dành cho người mới bắt đầu

Tài liệu này hướng dẫn **từng bước, từ con số 0**, để cài MyEX2Center lên màn hình trung tâm của Geely EX2. Bạn không cần biết lập trình. Chỉ cần làm đúng thứ tự, đọc kỹ từng bước và chép lệnh chính xác.

> [!IMPORTANT]
> **Đọc phần này trước khi bắt đầu**
> - MyEX2Center là ứng dụng **hệ thống** chạy trực tiếp trên màn hình xe (đầu xe **IHU629G**, Android 9). App đọc/ghi một số chức năng của xe: điều hòa, kính, chế độ lái, phanh tái sinh, âm cảnh báo người đi bộ (AVAS).
> - Để cài được, xe phải được **mở khóa ADB** một lần (Phần 2). Việc này dùng một gói cập nhật do **cộng đồng** làm, **không phải của Geely**. Nó có thể ảnh hưởng đến **bảo hành**. Hãy cân nhắc và tự chịu trách nhiệm.
> - Mọi thao tác chỉ làm khi **xe đỗ, cần số ở P, phanh tay kéo**, đang ở nơi an toàn, có Wi-Fi ổn định. **Không làm khi đang chạy.**
> - App đã được kiểm thử trên máy giả lập (emulator). Một số chức năng đọc/ghi trên xe thật **chưa được kiểm chứng đầy đủ**. Hãy thử từng chức năng khi xe đỗ.

---

## Mục lục

0. [Tổng quan: bạn sẽ làm những gì](#0-tổng-quan-bạn-sẽ-làm-những-gì)
1. [Chuẩn bị](#1-chuẩn-bị)
2. [Mở khóa ADB cho xe (chỉ làm 1 lần)](#2-mở-khóa-adb-cho-xe-chỉ-làm-1-lần)
3. [Cài công cụ `adb` trên máy tính](#3-cài-công-cụ-adb-trên-máy-tính)
4. [Kết nối xe vào Wi-Fi và nối máy tính với xe](#4-kết-nối-xe-vào-wi-fi-và-nối-máy-tính-với-xe)
5. [Tải MyEX2Center và kiểm tra file](#5-tải-myex2center-và-kiểm-tra-file)
6. [Cài MyEX2Center lên xe](#6-cài-myex2center-lên-xe)
7. [Mở app lần đầu và kiểm tra](#7-mở-app-lần-đầu-và-kiểm-tra)
8. [Sau khi cài: an toàn và dọn dẹp](#8-sau-khi-cài-an-toàn-và-dọn-dẹp)
9. [Cài thêm ứng dụng bằng Kho ứng dụng](#9-cài-thêm-ứng-dụng-bằng-kho-ứng-dụng)
10. [Cập nhật MyEX2Center về sau](#10-cập-nhật-myex2center-về-sau)
11. [Sử dụng app](#11-sử-dụng-app)
12. [Gỡ cài đặt](#12-gỡ-cài-đặt)
13. [Xử lý sự cố](#13-xử-lý-sự-cố)
14. [Câu hỏi thường gặp](#14-câu-hỏi-thường-gặp)
15. [Dành cho nhà phát triển](#15-dành-cho-nhà-phát-triển)

---

## 0. Tổng quan: bạn sẽ làm những gì

| Bước | Việc cần làm | Thời gian | Làm lại khi nào? |
|---|---|---|---|
| 1 | Chuẩn bị máy tính, USB, Wi-Fi | 10 phút | – |
| 2 | Mở khóa ADB cho xe bằng USB | 20–30 phút | **Chỉ 1 lần** (trừ khi xe cập nhật phần mềm chính hãng) |
| 3 | Cài `adb` trên máy tính | 5 phút | Chỉ 1 lần |
| 4 | Nối xe vào Wi-Fi, nối máy tính với xe | 10 phút | Mỗi lần cần cài bằng máy tính |
| 5–6 | Tải và cài MyEX2Center | 5 phút | Chỉ lần đầu |
| 7 | Mở app, kiểm tra | 5 phút | – |

Sau lần cài đầu tiên, các bản cập nhật MyEX2Center **cài thẳng trong app** (Kho ứng dụng), không cần máy tính nữa.

---

## 1. Chuẩn bị

### 1.1. Bạn cần có

| Thứ cần có | Ghi chú |
|---|---|
| Xe Geely EX2, đầu xe **IHU629G** | Android 9. Xem phiên bản ở mục 1.2. |
| **Máy tính** macOS hoặc Windows | Có quyền cài phần mềm. |
| **USB** (thẻ nhớ USB) 4–32 GB | **Toàn bộ dữ liệu trên USB sẽ bị xóa** khi định dạng. Dùng USB trống. |
| **Wi-Fi 2,4 GHz** | Đầu xe **không thấy Wi-Fi 5 GHz**. Dễ nhất là phát **hotspot từ điện thoại** ở băng tần 2,4 GHz. |
| Khoảng **1 giờ**, xe đỗ ở nơi an toàn | Không tắt xe giữa chừng. |

### 1.2. Kiểm tra phiên bản phần mềm của xe

Trên màn hình xe, mở **Xe của tôi → Phiên bản** (tên menu có thể hơi khác tùy ngôn ngữ, ví dụ *My car → Version*). Ghi lại số phiên bản, thường là **1111** hoặc **1114**. Bạn sẽ cần con số này ở Phần 2.

### 1.3. Chuẩn bị hotspot điện thoại (khuyên dùng)

Trên điện thoại, mở **Điểm phát sóng cá nhân / Hotspot**:
- **Băng tần: 2,4 GHz** (trên iPhone, bật *Tối đa hóa tương thích*).
- **Bảo mật: WPA2** (không dùng WPA3).
- **Tên và mật khẩu chỉ dùng chữ cái và số không dấu**, ví dụ `EX2-Hotspot` / `matkhau123`.

Máy tính và xe đều phải kết nối vào **cùng** hotspot này.

---

## 2. Mở khóa ADB cho xe (chỉ làm 1 lần)

**ADB** là "cổng kỹ thuật" cho phép máy tính cài ứng dụng lên xe. Mặc định nó bị khóa. Phần này mở khóa bằng một gói cập nhật đặt trên USB.

> [!WARNING]
> - Gói mở khóa do cộng đồng tạo (tác giả kênh **Jean na Estrada**, video gốc: https://youtu.be/T-77g9hn5LU). Hướng dẫn chi tiết bằng tiếng Bồ Đào Nha: dự án **drive_assist** (https://github.com/SuspiciousRepair/drive_assist), file `docs/GUIA-ADB-IHU629G.md`.
> - **Chỉ dùng gói đúng với phiên bản của xe bạn** (1111 hoặc 1114). Gói sai phiên bản sẽ không chạy.
> - Đã mở khóa thì bỏ qua Phần 2, sang thẳng Phần 3.

### 2.1. Tải gói mở khóa

1. Mở thư mục Google Drive **Geely Ex2 – Jean na Estrada**: https://drive.google.com/drive/folders/1RPzQlNtSc0YC_rpFQf_IIgdB6WvejiLl
2. Vào thư mục đúng phiên bản của bạn:
   - `Desbloqueio versao 1111` cho phiên bản **1111**
   - `Desbloqueio 1114` cho phiên bản **1114**
3. Trong đó chỉ có **một file .zip**. Tải về rồi **giải nén một lần**. Bạn sẽ được một thư mục có tên dài, ví dụ:
   - 1111: `3C6025_SW0E22H0128H111100000_user_995`
   - 1114: `3C6025_SW0E22H0306H111400000_user_995`
4. **Dừng ở đây.** Bên trong có `OS/update.zip`. **Không giải nén `update.zip`** và **không đổi tên** thư mục. Tên thư mục chính là thứ xe dùng để tìm gói.

### 2.2. Định dạng USB thành FAT32

> [!CAUTION]
> Định dạng sẽ **xóa sạch** USB. Chọn **đúng** USB, đừng chọn nhầm ổ cứng của máy.

**Trên macOS:**
1. Cắm USB, mở ứng dụng **Terminal** (nhấn ⌘ + Space, gõ `Terminal`, Enter).
2. Xem danh sách ổ ngoài:
   ```bash
   diskutil list external
   ```
   Tìm dòng có dung lượng đúng bằng USB của bạn, ví dụ `/dev/disk4`. Số của bạn có thể khác (`disk2`, `disk5`…).
3. Định dạng. Thay `disk4` bằng số bạn vừa tìm được:
   ```bash
   diskutil eraseDisk FAT32 ATUALIZA MBRFormat /dev/disk4
   ```
   USB sẽ hiện với tên **ATUALIZA**.

**Trên Windows:**
1. Cắm USB, mở **This PC / Máy tính này**.
2. Chuột phải vào ổ USB → **Format…**
3. **File system: FAT32**, *Volume label*: `ATUALIZA`, bấm **Start**.
   - Nếu không thấy lựa chọn FAT32 (USB lớn hơn 32 GB), hãy dùng USB nhỏ hơn.

### 2.3. Chép gói vào USB

Chép **cả thư mục tên dài** (không phải chỉ thư mục `OS`) vào **thư mục gốc** của USB. Kết quả phải đúng như sau:

```
ATUALIZA (USB)
└── 3C6025_SW0E22H0128H111100000_user_995      ← đúng tên như khi giải nén
    └── OS
        └── update.zip                          ← vẫn là file .zip
```

**Riêng macOS:** chạy thêm các lệnh sau để xóa file ẩn của Mac, kiểm tra bản chép, rồi tháo USB an toàn (thay tên thư mục và `disk4` cho đúng):
```bash
cp -R "3C6025_SW0E22H0128H111100000_user_995" /Volumes/ATUALIZA/
dot_clean -m /Volumes/ATUALIZA
find /Volumes/ATUALIZA -name "._*" -delete
find /Volumes/ATUALIZA -name ".DS_Store" -delete
shasum -a 256 "3C6025_SW0E22H0128H111100000_user_995/OS/update.zip" "/Volumes/ATUALIZA/3C6025_SW0E22H0128H111100000_user_995/OS/update.zip"
diskutil eject /dev/disk4
```
Hai dãy mã SHA-256 in ra phải **giống hệt nhau**.

**Windows:** kéo thả thư mục vào USB, rồi bấm **Safely Remove / Tháo an toàn**.

### 2.4. Nạp gói trên xe

1. Bật xe (chế độ sẵn sàng/ON), **cần số P**. **Tắt Bluetooth** trên màn hình xe.
2. Cắm USB vào cổng USB của xe. Đợi thông báo **"đã cắm thiết bị"**.
3. Mở **bàn phím gọi điện (Điện thoại)** trên màn hình xe và nhập **mã động**:

   **`#*` + (tháng + 10) + ngày + giờ (dạng 12 giờ)**. Mỗi phần **2 chữ số**, dùng ngày giờ đang hiện trên xe.

   | Ví dụ thời gian trên xe | Tính | Mã cần nhập |
   |---|---|---|
   | 02/08, 21 giờ | tháng 8 + 10 = **18**, ngày **02**, 21h = **09** giờ chiều | `#*180209` |
   | 15/11, 10 giờ sáng | 11 + 10 = **21**, ngày **15**, giờ **10** | `#*211510` |
   | 04/01, 13 giờ | 1 + 10 = **11**, ngày **04**, 13h = **01** | `#*110401` |

   > Giờ luôn là **phần cuối** và theo dạng **12 giờ**: 13h → 01, 21h → 09, 12h trưa → 12.
4. Menu ẩn hiện ra (chữ Trung Quốc). Chạm vào ô **`U盘升级`** (*cập nhật từ USB*), thường ở **hàng thứ 4, cột phải**. Hãy tìm đúng chữ, đừng dựa vào vị trí.
5. Xe tự khởi động lại vào màn hình *Android Recovery* và nạp gói.
6. Chọn **`Reboot system now`** (dòng đầu tiên, đã được tô sáng sẵn).

**Thành công nếu bạn thấy các dòng:**
```
PT  ativado
Done = ADB enabled - intentional stop, nothing wiped
```
Dòng báo lỗi `E:Error in /update/update.zip (status 7)` / `Installation aborted` ở cuối là **bình thường**: gói cố ý dừng sau khi bật ADB và **không xóa gì**.

Rút USB ra. Phần 2 xong.

---

## 3. Cài công cụ `adb` trên máy tính

`adb` là chương trình miễn phí của Google để máy tính "nói chuyện" với thiết bị Android.

### macOS

**Cách 1 (nếu đã có Homebrew):**
```bash
brew install android-platform-tools
```

**Cách 2 (không cần Homebrew):**
1. Tải **SDK Platform-Tools for Mac** tại https://developer.android.com/tools/releases/platform-tools
2. Giải nén vào thư mục **Downloads**, bạn sẽ có thư mục `platform-tools`.
3. Trong Terminal:
   ```bash
   cd ~/Downloads/platform-tools
   ./adb version
   ```
   Ở cách này, mọi lệnh `adb` trong tài liệu bạn gõ thành **`./adb`** và phải đứng trong thư mục `platform-tools`.

### Windows

1. Tải **SDK Platform-Tools for Windows** tại https://developer.android.com/tools/releases/platform-tools
2. Giải nén vào `C:\platform-tools`.
3. Mở thư mục `C:\platform-tools`, bấm vào thanh địa chỉ, gõ `powershell` rồi Enter.
4. Kiểm tra:
   ```powershell
   .\adb version
   ```
   Trên Windows, mọi lệnh `adb` trong tài liệu bạn gõ thành **`.\adb`**.

Nếu thấy dòng `Android Debug Bridge version …` là đã cài xong.

---

## 4. Kết nối xe vào Wi-Fi và nối máy tính với xe

### 4.1. Cho xe vào Wi-Fi

Màn hình Wi-Fi của xe nằm trong **menu ẩn**:
1. Mở **bàn phím gọi điện** trên xe, nhập lại **mã động** (mục 2.4). **Phải tính lại**, vì giờ có thể đã đổi.
2. Chạm vào ô **`无线网络`** (*mạng không dây*), thường ở **góc dưới bên phải**.
3. Bật Wi-Fi, chọn hotspot 2,4 GHz của bạn, nhập mật khẩu.

Nối **máy tính** vào **cùng** hotspot đó.

### 4.2. Tìm địa chỉ IP của xe

Dùng một trong các cách sau:
- Ngay trên màn hình chẩn đoán của menu ẩn, cột trái, dòng **`Wi-Fi IP`**.
- Trên điện thoại phát hotspot: danh sách **thiết bị đã kết nối** (đầu xe có thể hiện tên giống TV box, ví dụ `SDMC Android TV Box`).
- Trên trang quản trị router (nếu dùng Wi-Fi nhà).

IP có dạng `192.168.x.x` hoặc `172.20.10.x`. Nếu thấy `0.0.0.0`: quên mạng trên xe rồi kết nối lại, hoặc tắt/bật lại hotspot.

### 4.3. Nối máy tính với xe

Thay `192.168.1.50` bằng IP của xe bạn:
```bash
adb connect 192.168.1.50:5555
adb devices -l
```
Kết quả đúng:
```
192.168.1.50:5555   device product:IHU629G model:IHU629G device:IHU629G
```

- `failed to connect` / `timed out`: xe và máy tính không cùng mạng, sai IP, hoặc ADB chưa được mở khóa (làm lại Phần 2).
- `offline` / `unauthorized`: chạy `adb disconnect` rồi `adb connect …` lại.

> Từ bước này đến khi cài xong, **đừng tắt xe** và **đừng đổi mạng** trên máy tính. Nếu mất kết nối, chạy lại lệnh `adb connect`.

---

## 5. Tải MyEX2Center và kiểm tra file

1. Mở trang phát hành chính thức: **https://github.com/ngocngo1213/MyEX2Center/releases**
2. Chọn bản **mới nhất** (trên cùng, có nhãn *Latest*). Ở mục **Assets**, tải file **`MyEX2Center-<phiên bản>.apk`**. Ví dụ dưới đây dùng **v1.2.0**; nếu có bản mới hơn, hãy thay số phiên bản trong các lệnh.
3. **Kiểm tra file không bị lỗi hay bị sửa** bằng mã SHA-256 ghi ở phần mô tả của bản phát hành.

   **macOS** (trong Terminal, đứng ở thư mục chứa file, thường là `~/Downloads`):
   ```bash
   shasum -a 256 MyEX2Center-1.2.0.apk
   ```
   **Windows** (PowerShell):
   ```powershell
   certutil -hashfile MyEX2Center-1.2.0.apk SHA256
   ```
   Kết quả phải **trùng khớp** với mã SHA-256 ghi trên trang Release của đúng bản bạn tải. Ví dụ với v1.2.0:
   ```
   31d1ce90d569d99308667e93d3d5d6812a3b66797baeba509b4d29ca06a250dd
   ```
   Không trùng → **không cài**, tải lại.

> [!NOTE]
> MyEX2Center là app hệ thống (`android.uid.system`), được ký bằng khóa *platform* (test-key AOSP) mà đầu xe IHU629G sử dụng. Nếu firmware của bạn dùng khóa khác, bước cài sẽ báo lỗi `SHARED_USER` (xem Phần 13). Bạn chỉ cần tải APK trên trang Release, không cần khóa nào.

---

## 6. Cài MyEX2Center lên xe

Xe vẫn ở **P**, máy tính vẫn đang nối với xe (mục 4.3). Đứng trong thư mục chứa file APK:

```bash
adb install -r MyEX2Center-1.2.0.apk
```

Chờ đến khi thấy **`Success`** (file khoảng 6–7 MB, mất vài giây đến một phút qua Wi-Fi).

Kiểm tra app đã chạy với quyền hệ thống:
```bash
adb shell dumpsys package com.mipago.ex2center | grep -E "userId=|versionName"
```
Kết quả đúng có **`userId=1000`** và `versionName=1.2.0` (hoặc bản bạn vừa cài).

> App hệ thống **không hiện** trong lệnh `adb shell pm list packages -3`. Đó là bình thường, không phải lỗi.

**Cách khác (nếu xe đã cho cài app từ USB):** chép file APK vào USB FAT32, mở **Trình quản lý tệp** trên xe, chạm vào file và làm theo hướng dẫn trên màn hình.

---

## 7. Mở app lần đầu và kiểm tra

1. Mở **MyEX2Center** trong danh sách ứng dụng trên xe (biểu tượng xe EX2 màu trắng), hoặc chạy từ máy tính:
   ```bash
   adb shell am start -n com.mipago.ex2center/.ui.MainActivity
   ```
2. App tự chạy **dịch vụ tự động hóa** (có thông báo thường trực hình chiếc xe) và **nút nổi** tròn màu xanh. Cả hai tự bật lại khi xe khởi động.
3. Nếu **nút nổi** hoặc **Màn hình đêm** không hiện, cấp quyền hiển thị trên ứng dụng khác:
   ```bash
   adb shell appops set com.mipago.ex2center SYSTEM_ALERT_WINDOW allow
   ```
4. **Kiểm tra app đã đọc được dữ liệu xe:**

   | Bạn thấy | Ý nghĩa |
   |---|---|
   | Số P/R/N/D sáng, tốc độ, % pin, km còn lại, nhiệt độ điều hòa là **số thật** | ✅ App đã kết nối với xe |
   | Toàn **`--`** và dòng "Chưa kết nối xe" | ❌ Chưa đọc được dữ liệu, xem Phần 13 |
   | Một vài ô vẫn `--` (ví dụ áp suất lốp) | Xe không cung cấp dữ liệu đó. App **không hiện số giả**. |

5. **Thử từng chức năng khi xe đỗ ở P**, mỗi lần một chức năng, và đối chiếu với xe thật:
   - Đổi **Chế độ lái** (Tiết kiệm / Thoải mái / Thể thao), rồi **Phanh tái sinh**.
   - Chỉnh **nhiệt độ** hoặc **quạt gió**, so với màn hình điều hòa gốc.
   - Bấm **Hé thoáng (15%)** rồi **Đóng kín (0%)**, quan sát kính.
   - Nút đang chờ xe xác nhận sẽ **mờ nhẹ**. Nếu xe không nhận lệnh, app báo **"Xe không nhận lệnh: …"** và trả về trạng thái thật.

---

## 8. Sau khi cài: an toàn và dọn dẹp

> [!WARNING]
> Khi đã mở khóa, **bất kỳ ai cùng mạng Wi-Fi với xe** đều có thể truy cập ADB của xe với quyền cao nhất.
> - Chỉ cho xe kết nối vào **hotspot của bạn hoặc Wi-Fi nhà có mật khẩu**. **Không** kết nối Wi-Fi công cộng (quán cà phê, trạm sạc…).
> - Ngắt kết nối khi xong việc:
>   ```bash
>   adb disconnect
>   ```
> - Chỉ cài APK từ nguồn bạn tin tưởng.

Nên tránh khởi động lại đầu xe bằng lệnh `adb reboot`, vì nó gây tiếng "bụp" lớn qua loa xe. Muốn khởi động lại màn hình, dùng nút **Khởi động lại** trong nút nổi. Nút này chỉ hoạt động khi xe ở **P** và đứng yên.

---

## 9. Cài thêm ứng dụng bằng Kho ứng dụng

Từ đây bạn **không cần máy tính nữa**.

1. Xe ở **P**, đã kết nối Wi-Fi.
2. Trên Dashboard, bấm biểu tượng **Kho ứng dụng** (hình cửa hàng) ở thanh trên.
3. Danh sách hiện có:

   | Ứng dụng | Cách cài |
   |---|---|
   | **MyEX2Center** | Nút **Cập nhật hệ thống** khi có bản mới |
   | **SmartTube** (YouTube không quảng cáo) | Bấm **Cài đặt**: tự tải từ trang chính thức, kiểm tra SHA-256 rồi cài |
   | **Trình duyệt Web** | Bấm **Cài đặt** |
   | **VIETMAP LIVE** | **Cài từ USB / Máy**: chép file `.xapk` vào USB, cắm vào xe, bấm nút |

4. Khi đang tải, nút đổi thành **"Hủy tải (x%)"**. Nếu bị hủy hoặc mất mạng, lần sau app **tải tiếp** phần còn lại.
5. Cài xong, nút chuyển thành **Mở**, kèm nút **thùng rác** để gỡ (có hỏi xác nhận).

**Lưu ý về VIETMAP LIVE (.xapk):** gói hiện tại chỉ có bản cho CPU `armeabi-v7a`. Kiểm tra đầu xe có hỗ trợ không:
```bash
adb shell getprop ro.product.cpu.abilist
```
Nếu kết quả **không có** `armeabi-v7a`, app sẽ báo *"Không tương thích CPU"* và không cài. Khi đó cần tìm bản Vietmap có `arm64-v8a`.

---

## 10. Cập nhật MyEX2Center về sau

- **Trong app (khuyên dùng):** Kho ứng dụng → **MyEX2Center** → **Cập nhật hệ thống**. App tự đóng, cài bản mới, rồi mở lại và báo *"MyEX2Center đã được cập nhật lên vX"*. Chỉ cập nhật được khi xe ở **P**.
- **Bằng máy tính:** tải APK mới từ trang Release, kiểm tra SHA-256, rồi chạy `adb install -r MyEX2Center-<phiên bản>.apk`.

Cài đè **giữ nguyên** cài đặt của bạn: tên xe, ảnh xe, giao diện, chế độ lái đã ghi nhớ.

---

## 11. Sử dụng app

> [!NOTE]
> Phần này mô tả giao diện **từ bản 1.2.0**: Cài đặt mới, giao diện Sáng/Tối, hồ sơ xe, công tắc "Ghi nhớ", hình xe nhìn từ trên xuống, bộ icon mới. Nếu đang dùng **v1.1.0**, hãy cập nhật lên bản mới nhất (Phần 10).

### Thanh trên cùng
- **Hồ sơ xe** (ảnh + tên): chạm vào để mở Cài đặt.
- Giờ, nhiệt độ ngoài trời, điện áp 12V.
- **Ô trạng thái**:
  - Xanh *"Tự động hóa: Đang bật"*.
  - Cam *"Chế độ Bảo dưỡng"*.
  - *"Crash Guard: Đã ngắt"*: tự động hóa đã tạm dừng vì gặp lỗi liên tiếp. Bấm vào để **Đặt lại**.
- Wi-Fi (tên mạng và IP), **Kho ứng dụng**, **Màn hình đêm** (chạm bất kỳ đâu để thoát; tự tắt khi vào số R), **Cài đặt**.

### Cột trái: lái xe
- Cần số **P R N D**, **biển giới hạn tốc độ** cạnh tốc độ (chạm để mở Vietmap), % pin, km còn lại.
- **Chế độ lái** và **Phanh tái sinh**. Mỗi nhóm có công tắc **"Ghi nhớ"**: app nhớ lựa chọn cuối cùng (kể cả khi bạn chọn trên màn hình gốc của xe) và **tự đặt lại sau khi tắt rồi mở xe**.
- Phím tắt: **AVAS** (âm cảnh báo người đi bộ), **Camera 360**, **Bảo dưỡng** (Valet: tạm dừng mọi tự động hóa khi gửi xe hoặc rửa xe).

### Cột giữa
- **Chạy đôi: Vietmap + YouTube** (chia đôi màn hình, có thanh điều khiển nổi để đổi chỗ hai app, bật/tắt A/C, Màn hình đêm, đóng chia đôi).
- **Nhạc**: bài đang phát, lùi / phát–dừng / tiếp, âm lượng.
- **Điều hòa**: A/C, Gió trong, **Làm mát nhanh** (A/C + 17°C + quạt 7 + gió mặt + gió trong), nhiệt độ (17–32°C), quạt gió (1–8), gió mặt, gió chân, sấy kính trước, sấy kính sau.

### Cột phải
- Hình xe nhìn từ trên xuống: **áp suất và nhiệt độ từng lốp** (vạch bánh xe xanh là bình thường, vàng là lệch khoảng, đỏ là nguy hiểm, xám là chưa có dữ liệu) và **độ mở từng kính**.
- **Đóng kín / Hé thoáng / Hạ hết kính**. *Hạ hết* chỉ dùng được khi xe chạy dưới 30 km/h.

### Nút nổi (hình tròn xanh)
Kéo để di chuyển (app nhớ vị trí). Chạm để mở bảng nhanh gồm: A/C, nhiệt độ, quạt, **Màn hình đêm**, **Khởi động lại** màn hình (chỉ khi xe ở P, có hỏi xác nhận).

### Cài đặt
| Mục | Nội dung |
|---|---|
| **Hồ sơ xe** | Đổi tên xe, chọn ảnh xe từ USB/máy, dùng lại ảnh mặc định |
| **Giao diện** | **Theo hệ thống / Sáng / Tối** |
| **Kết nối** | Bật/tắt **Wi-Fi** (hiện tên mạng ● IP; tắt Wi-Fi phải xác nhận), **Đèn viền nội thất** |
| **Lái xe & Tự động hóa** | Ghi nhớ chế độ lái, Ghi nhớ phanh tái sinh, Tự động tắt âm AVAS, Chế độ Bảo dưỡng, Crash Guard |
| **Hệ thống** | Nút nổi, Kho ứng dụng, phiên bản app |

### Các cơ chế an toàn có sẵn
- **Chặn khi không ở số P:** khởi động lại màn hình, cài/gỡ ứng dụng.
- **Chặn khi chạy nhanh:** "Hạ hết kính" chỉ dùng được dưới 30 km/h.
- **Màn hình đêm** tự tắt khi vào số R, để không che camera lùi.
- **Không có nút ESC, HDC, Auto Hold, mở cốp:** các chức năng này **chưa có mã điều khiển đã kiểm chứng** cho EX2, nên app không hiện để tránh gửi lệnh sai.

> [!CAUTION]
> Tắt âm AVAS ảnh hưởng đến an toàn của người đi bộ. Chỉ dùng khi hiểu rõ và đúng quy định nơi bạn lái. **Không thao tác trên màn hình khi xe đang chạy.**

---

## 12. Gỡ cài đặt

Bằng máy tính (đã `adb connect` như mục 4.3):
```bash
adb uninstall com.mipago.ex2center
```
Việc này xóa app và mọi cài đặt của app. Các ứng dụng khác đã cài qua Kho (SmartTube, Vietmap…) **vẫn còn**. Gỡ từng app trong danh sách ứng dụng của xe, hoặc dùng `adb uninstall <tên gói>`.

---

## 13. Xử lý sự cố

### Mở khóa ADB (Phần 2)
| Triệu chứng | Cách xử lý |
|---|---|
| Xe báo *"ensure udisk plugged and update.zip exists…"* | Hầu như luôn do **sai cấu trúc thư mục**: đã giải nén `update.zip`, chỉ chép thư mục `OS`, hoặc đổi tên thư mục chính. Làm lại mục 2.1–2.3. |
| Nhập mã `#*…` không hiện menu | Tính lại theo **ngày giờ hiện trên xe**, mỗi phần 2 chữ số, giờ theo dạng 12h và đặt cuối. |
| Không thấy ô `U盘升级` | Tìm theo chữ, không theo vị trí; ô có thể nằm ở hàng khác. |
| USB không được nhận | Dùng USB khác. Định dạng không phải nguyên nhân (FAT32 là được). |

### Kết nối ADB (Phần 4)
| Triệu chứng | Cách xử lý |
|---|---|
| Xe không thấy Wi-Fi của bạn | Wi-Fi đang ở 5 GHz. Chuyển hotspot sang **2,4 GHz**, bảo mật WPA2, tên không dấu. |
| IP hiện `0.0.0.0` | Quên mạng trên xe, kết nối lại; tắt/bật hotspot. |
| `adb connect` báo lỗi / hết thời gian chờ | Máy tính và xe chưa cùng mạng, sai IP, hoặc ADB chưa mở khóa. |
| `adb: command not found` | Chưa cài `adb` (Phần 3), hoặc cần gõ `./adb` (Mac) / `.\adb` (Windows) trong thư mục `platform-tools`. |

### Cài app (Phần 6)
| Thông báo | Nguyên nhân | Cách xử lý |
|---|---|---|
| `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` hoặc `…NO_CERTIFICATES` | Firmware của xe dùng khóa *platform* khác với khóa ký app | Bản APK này không dùng được cho xe đó. Báo lỗi trên GitHub kèm phiên bản firmware. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Đã có một bản ký khác | `adb uninstall com.mipago.ex2center` rồi cài lại (mất cài đặt cũ). |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Đang cài bản cũ hơn bản đã có | Dùng bản mới nhất. |
| Cài không có thông báo / bị ngắt giữa chừng | Wi-Fi chập chờn | `adb disconnect`, `adb connect …` lại, kiểm tra (mục 6) trước khi cài lại. |
| `Success` nhưng không thấy trong `pm list packages -3` | Bình thường với app hệ thống | Kiểm tra bằng lệnh `dumpsys` ở mục 6. |

### Khi dùng app
| Triệu chứng | Cách xử lý |
|---|---|
| Dashboard toàn `--`, "Chưa kết nối xe" | Kiểm tra `userId=1000` (mục 6). Mở lại app. Xem log: `adb logcat -s CarApiHelper CarAutomationService MainActivity` |
| Bấm nút, báo "Xe không nhận lệnh" | Xe đang tắt / chưa sẵn sàng, hoặc chức năng không có trên xe bạn. Thử lại khi xe ON, số P. |
| Không thấy nút nổi | Cài đặt → Hệ thống → bật **Nút nổi**; hoặc cấp quyền như mục 7, bước 3. |
| Pill báo "Crash Guard: Đã ngắt" | Bấm vào pill → Đặt lại; hoặc Cài đặt → Crash Guard. Nếu lặp lại, gửi log cho nhà phát triển. |
| Chế độ lái không tự khôi phục sau khi mở xe | Kiểm tra công tắc **Ghi nhớ** đang bật và **Chế độ Bảo dưỡng** đang tắt. |
| Kho ứng dụng không tải được | Kiểm tra Wi-Fi; với file lớn, bấm lại để tải tiếp. Thông báo "thiếu SHA-256" nghĩa là mục đó chưa phát hành an toàn qua mạng. |
| Vietmap báo "Không tương thích CPU" | Xem mục 9 (cần bản có `arm64-v8a`). |

**Gửi log khi cần hỗ trợ:**
```bash
adb logcat -d > ex2center-log.txt
```
Gửi file `ex2center-log.txt` kèm mô tả lỗi tại **https://github.com/ngocngo1213/MyEX2Center/issues**. Kiểm tra trong log không có thông tin cá nhân trước khi gửi.

---

## 14. Câu hỏi thường gặp

**Có cần mở khóa lại mỗi lần cài không?**
Không. Chỉ làm một lần. Nếu xe được cập nhật phần mềm chính hãng, có thể phải mở khóa lại.

**App có làm mất dữ liệu xe không?**
Không. Gói mở khóa *"nothing wiped"*, và app không xóa dữ liệu hệ thống. Gỡ app chỉ xóa dữ liệu của chính app.

**Không có máy tính có cài được không?**
Lần đầu **cần máy tính** để cài qua ADB. Nếu xe đã cho cài app từ USB, có thể thử cách cài bằng Trình quản lý tệp (cuối Phần 6). Sau đó mọi cập nhật làm trong app.

**Sao một số ô luôn hiện `--`?**
App chỉ hiện **dữ liệu thật** từ xe. Thông tin nào xe không cung cấp (ví dụ áp suất lốp trên một số phiên bản) sẽ là `--`.

**Có cốp trước / ESC / Auto Hold không?**
Chưa. Các chức năng này chưa có mã điều khiển đã được kiểm chứng trên EX2. App sẽ bổ sung khi có mã chính xác.

---

## 15. Dành cho nhà phát triển

Mã nguồn: https://github.com/ngocngo1213/MyEX2Center. Đặc tả kỹ thuật: [SPEC.md](SPEC.md).

**Build:**
```bash
# Gradle 8.11 cần JDK 17–21 (JBR 25 của Android Studio mới quá mới)
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
./gradlew :app:assembleDebug :store:assembleDebug   # bản debug (cài emulator: adb install -r -t)
./scripts/build-release.sh                          # 2 APK release ký platform vào dist/ + SHA-256
```
- Repo gồm 3 module: `:core` (thư viện chung), `:app` (MyEX2Center), `:store` (EX2 Store). Xem [SPEC.md](SPEC.md) mục 6.
- Thư mục `signing/` (khóa platform) **không có trong repo** và không bao giờ được đưa lên GitHub.
- Kiểm tra chữ ký: `apksigner verify --print-certs app-release.apk`.

**Phát hành bản mới:**
1. Tăng `versionCode` / `versionName` trong `app/build.gradle` (MyEX2Center) và/hoặc `store/build.gradle` (EX2 Store).
2. `./scripts/build-release.sh` → `dist/MyEX2Center-<phiên bản>.apk`, `dist/EX2Store-<phiên bản>.apk`.
3. Tạo Release trên GitHub: tag `v<phiên bản>` cho MyEX2Center, `store-v<phiên bản>` cho EX2 Store, đính kèm APK.
4. `python3 -I scripts/update-catalog.py` — tự điền phiên bản, link, SHA-256 vào `apps.json` và ký thành `apps.json.sig` (cần `signing/catalog-signing-key.pem`). Commit **cả hai file** và push. EX2 Store trên xe sẽ tự báo có bản mới (khi xe ở số P).
5. Sửa tay `apps.json` thì phải ký lại: `python3 -I scripts/update-catalog.py --sign-only`. Catalog sai chữ ký sẽ bị Store bỏ qua.

> Sao lưu thư mục `signing/` ra nơi an toàn (USB/ổ cứng riêng). Mất khóa platform thì không cập nhật được app trên xe; mất khóa ký catalog thì phải phát hành Store mới.

**Mẹo thử trên xe:**
- Thử lại sự kiện khởi động mà không cần reboot:
  ```bash
  adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.mipago.ex2center/.service.BootReceiver
  ```
- Xem log của app:
  ```bash
  adb logcat -s CarApiHelper CarAutomationService PackageInstallerHelper AppRepository InstallerReceiver UpdateCheck UpdateScheduler
  ```
