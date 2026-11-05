# Hướng dẫn cài đặt EX2 Store và MyEX2Center cho Geely EX2 — dành cho người mới bắt đầu

Tài liệu này hướng dẫn **từng bước, từ con số 0**, cách cài **EX2 Store** và **MyEX2Center** lên màn hình trung tâm của Geely EX2. Bạn không cần biết lập trình. Chỉ cần làm đúng thứ tự, đọc kỹ từng bước và chép lệnh chính xác.

- **EX2 Store** là kho ứng dụng cho xe. Store dùng để cài và **tự cập nhật** MyEX2Center cùng các app khác (SmartTube, Trình duyệt, Vietmap…).
- **MyEX2Center** là bảng điều khiển cho tài xế: chế độ lái, điều hòa, kính, áp suất lốp, nút nổi…

Bạn chỉ cần dùng máy tính **một lần duy nhất** để cài EX2 Store. Từ đó, mọi thứ còn lại làm ngay trên màn hình xe.

> [!IMPORTANT]
> **Đọc phần này trước khi bắt đầu**
> - Cả hai app là ứng dụng **hệ thống**, chạy trực tiếp trên đầu xe **IHU629G** (Android 9). MyEX2Center đọc/ghi một số chức năng của xe: điều hòa, kính, chế độ lái, phanh tái sinh, âm cảnh báo người đi bộ (AVAS).
> - Để cài được, xe phải được **mở khóa ADB** một lần (Phần 2). Việc này dùng một gói cập nhật do **cộng đồng** làm, **không phải của Geely**. Nó có thể ảnh hưởng đến **bảo hành**. Hãy cân nhắc và tự chịu trách nhiệm.
> - Mọi thao tác chỉ làm khi **xe đỗ, cần số ở P, phanh tay kéo**, đang ở nơi an toàn. **Không làm khi đang chạy.** EX2 Store cũng tự chặn việc cài/cập nhật khi xe không ở số P.
> - Các app đã được kiểm thử trên máy giả lập (emulator). Một số chức năng đọc/ghi trên xe thật **chưa được kiểm chứng đầy đủ**. Hãy thử từng chức năng khi xe đỗ.

---

## Mục lục

0. [Tổng quan: bạn sẽ làm những gì](#0-tổng-quan-bạn-sẽ-làm-những-gì)
1. [Chuẩn bị](#1-chuẩn-bị)
2. [Mở khóa ADB cho xe (chỉ làm 1 lần)](#2-mở-khóa-adb-cho-xe-chỉ-làm-1-lần)
3. [Tải EX2 Store và MyEX2Center vào USB](#3-tải-ex2-store-và-myex2center-vào-usb)
4. [Cài EX2 Store lên xe (chỉ làm 1 lần)](#4-cài-ex2-store-lên-xe-chỉ-làm-1-lần)
5. [Cài MyEX2Center từ EX2 Store](#5-cài-myex2center-từ-ex2-store)
6. [Mở MyEX2Center lần đầu và kiểm tra](#6-mở-myex2center-lần-đầu-và-kiểm-tra)
7. [Sau khi cài: an toàn và dọn dẹp](#7-sau-khi-cài-an-toàn-và-dọn-dẹp)
8. [Cập nhật về sau: tự động báo trong xe](#8-cập-nhật-về-sau-tự-động-báo-trong-xe)
9. [Cài thêm ứng dụng bằng EX2 Store](#9-cài-thêm-ứng-dụng-bằng-ex2-store)
10. [Đang dùng MyEX2Center 1.2.0?](#10-đang-dùng-myex2center-120)
11. [Sử dụng MyEX2Center](#11-sử-dụng-myex2center)
12. [Gỡ cài đặt](#12-gỡ-cài-đặt)
13. [Xử lý sự cố](#13-xử-lý-sự-cố)
14. [Câu hỏi thường gặp](#14-câu-hỏi-thường-gặp)
15. [Dành cho nhà phát triển](#15-dành-cho-nhà-phát-triển)

---

## 0. Tổng quan: bạn sẽ làm những gì

```
 Mở khóa ADB (USB)  →  Cài EX2 Store (máy tính, 1 lệnh)  →  Mở EX2 Store trên xe  →  Cài MyEX2Center
      1 lần                     1 lần                                                       │
                                                                                            ▼
                                       Có bản mới trên GitHub → EX2 Store báo khi xe ở số P → bấm Cập nhật
```

| Bước | Việc cần làm | Thời gian | Làm lại khi nào? |
|---|---|---|---|
| 1 | Chuẩn bị máy tính, USB, Wi-Fi | 10 phút | – |
| 2 | Mở khóa ADB cho xe bằng USB | 20–30 phút | **Chỉ 1 lần** (trừ khi xe cập nhật phần mềm chính hãng) |
| 3 | Tải EX2 Store và MyEX2Center vào USB | 5 phút | – |
| 4 | Cài EX2 Store bằng máy tính | 15 phút | **Chỉ 1 lần** |
| 5–6 | Cài MyEX2Center **ngay trên xe**, mở và kiểm tra | 5 phút | – |
| 8 | Cập nhật | 1 phút | Khi EX2 Store báo có bản mới |

---

## 1. Chuẩn bị

### 1.1. Bạn cần có

| Thứ cần có | Ghi chú |
|---|---|
| Xe Geely EX2, đầu xe **IHU629G** | Android 9. Xem phiên bản ở mục 1.2. |
| **Máy tính** macOS hoặc Windows | Chỉ cần cho lần cài EX2 Store đầu tiên. Có quyền cài phần mềm. |
| **USB** (thẻ nhớ USB) 4–32 GB | Dùng để mở khóa ADB và chứa file cài. **Toàn bộ dữ liệu trên USB sẽ bị xóa** khi định dạng. Dùng USB trống. |
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
> - Nếu xe của bạn **đã có MyEX2Center 1.2.0**, ADB đã được mở khóa từ trước: xem thẳng [Phần 10](#10-đang-dùng-myex2center-120).

### 2.1. Tải gói mở khóa

> [!WARNING]
> **Link Google Drive gốc đã ngừng hoạt động** (kiểm tra ngày 05/11/2026: trả về lỗi 404). Tài liệu drive_assist cũng vẫn trỏ vào đúng link này. Video hướng dẫn gốc vẫn xem được, nhưng phần mô tả **không có link tải mới**. Hiện dự án này **chưa có nguồn thay thế đã kiểm chứng**, nên không đăng lại gói mở khóa.
>
> Cách lấy gói:
> - Hỏi trong các nhóm cộng đồng Geely EX2 (Facebook / Zalo). Nhiều người đã mở khóa vẫn còn giữ file.
> - Hoặc liên hệ tác giả qua email ghi trong mô tả video: `contato.jeannaestrada@gmail.com`.
>
> Chỉ dùng gói có **đúng tên thư mục** như bên dưới, và **đúng phiên bản xe** (1111 / 1114). Không dùng gói `update.zip` lạ không rõ nguồn: gói này chạy trên xe với quyền cao nhất.
> Có link mới đã kiểm chứng, hãy báo tại https://github.com/ngocngo1213/MyEX2Center/issues để cập nhật tài liệu.

1. Lấy gói mở khóa (thư mục gốc từng có tên **Geely Ex2 – Jean na Estrada**, link cũ: `https://drive.google.com/drive/folders/1RPzQlNtSc0YC_rpFQf_IIgdB6WvejiLl`).
2. Gói được chia theo phiên bản (tên thư mục gốc):
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

## 3. Tải EX2 Store và MyEX2Center vào USB

Dùng lại USB của Phần 2 (định dạng FAT32). Gói mở khóa trên USB không cần nữa, bạn có thể xóa.

1. Mở trang phát hành chính thức: **https://github.com/ngocngo1213/MyEX2Center/releases**
2. Tải **hai file** ở mục **Assets** của bản mới nhất:

   | File | Ở bản phát hành | Dùng để |
   |---|---|---|
   | **`EX2Store-<phiên bản>.apk`** | tag `store-v…` (ví dụ `store-v1.0.0`) | Cài kho ứng dụng (Phần 4) |
   | **`MyEX2Center-<phiên bản>.apk`** | tag `v…` (ví dụ `v1.3.0`) | Cài từ USB khi xe chưa có Wi-Fi (Phần 5, cách B) |

   Các ví dụ dưới đây dùng **EX2 Store 1.0.0** và **MyEX2Center 1.3.0**. Nếu có bản mới hơn, hãy thay số phiên bản trong các lệnh.
3. **Kiểm tra file không bị lỗi hay bị sửa** bằng mã SHA-256 ghi trong phần mô tả của từng bản phát hành.

   **macOS** (Terminal, đứng ở thư mục chứa file, thường là `~/Downloads`):
   ```bash
   shasum -a 256 EX2Store-1.0.0.apk MyEX2Center-1.3.0.apk
   ```
   **Windows** (PowerShell):
   ```powershell
   certutil -hashfile EX2Store-1.0.0.apk SHA256
   certutil -hashfile MyEX2Center-1.3.0.apk SHA256
   ```
   Kết quả phải **trùng khớp** với mã trên trang Release. Không trùng → **không cài**, tải lại.
4. Chép cả hai file vào **thư mục gốc** của USB, rồi tháo USB an toàn. Trên macOS, chạy thêm lệnh sau để xóa file ẩn của Mac trước khi tháo:
   ```bash
   dot_clean -m /Volumes/ATUALIZA
   ```

> [!NOTE]
> Hai app là app hệ thống (`android.uid.system`), được ký bằng khóa *platform* (test-key AOSP) mà đầu xe IHU629G sử dụng. Nếu firmware của bạn dùng khóa khác, bước cài sẽ báo lỗi `SHARED_USER` (xem Phần 13). Bạn chỉ cần tải APK trên trang Release, không cần khóa nào.

---

## 4. Cài EX2 Store lên xe (chỉ làm 1 lần)

Đây là lần **duy nhất** cần máy tính. Máy tính cài EX2 Store qua ADB, qua mạng Wi-Fi.

### 4.1. Cài công cụ `adb` trên máy tính

`adb` là chương trình miễn phí của Google để máy tính "nói chuyện" với thiết bị Android.

**macOS**

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

**Windows**

1. Tải **SDK Platform-Tools for Windows** tại https://developer.android.com/tools/releases/platform-tools
2. Giải nén vào `C:\platform-tools`.
3. Mở thư mục `C:\platform-tools`, bấm vào thanh địa chỉ, gõ `powershell` rồi Enter.
4. Kiểm tra:
   ```powershell
   .\adb version
   ```
   Trên Windows, mọi lệnh `adb` trong tài liệu bạn gõ thành **`.\adb`**.

Nếu thấy dòng `Android Debug Bridge version …` là đã cài xong.

### 4.2. Cho xe vào Wi-Fi

Màn hình Wi-Fi của xe nằm trong **menu ẩn**:
1. Mở **bàn phím gọi điện** trên xe, nhập lại **mã động** (mục 2.4). **Phải tính lại**, vì giờ có thể đã đổi.
2. Chạm vào ô **`无线网络`** (*mạng không dây*), thường ở **góc dưới bên phải**.
3. Bật Wi-Fi, chọn hotspot 2,4 GHz của bạn, nhập mật khẩu.

Nối **máy tính** vào **cùng** hotspot đó.

### 4.3. Tìm địa chỉ IP của xe

Dùng một trong các cách sau:
- Ngay trên màn hình chẩn đoán của menu ẩn, cột trái, dòng **`Wi-Fi IP`**.
- Trên điện thoại phát hotspot: danh sách **thiết bị đã kết nối** (đầu xe có thể hiện tên giống TV box, ví dụ `SDMC Android TV Box`).
- Trên trang quản trị router (nếu dùng Wi-Fi nhà).

IP có dạng `192.168.x.x` hoặc `172.20.10.x`. Nếu thấy `0.0.0.0`: quên mạng trên xe rồi kết nối lại, hoặc tắt/bật lại hotspot.

### 4.4. Nối máy tính với xe

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

### 4.5. Cài EX2 Store

Xe vẫn ở **P**, máy tính vẫn đang nối với xe (mục 4.4). Đứng trong thư mục chứa file `EX2Store-1.0.0.apk` (thường là `~/Downloads`; file cũng có thể nằm trên USB):

```bash
adb install -r EX2Store-1.0.0.apk
```

Chờ đến khi thấy **`Success`** (file khoảng 5 MB).

Kiểm tra Store đã chạy với quyền hệ thống:
```bash
adb shell dumpsys package com.mipago.ex2store | grep -E "userId=|versionName"
```
Kết quả đúng có **`userId=1000`** và `versionName=1.0.0` (hoặc bản bạn vừa cài).

> App hệ thống **không hiện** trong lệnh `adb shell pm list packages -3`. Đó là bình thường, không phải lỗi.

Xong bước này, bạn có thể ngắt máy tính (`adb disconnect`). Từ đây mọi việc làm trên màn hình xe.

**Cách khác, không cần máy tính (chưa kiểm chứng):** nếu đầu xe của bạn có **Trình quản lý tệp** cho phép cài APK, cắm USB vào xe, mở file `EX2Store-1.0.0.apk` và làm theo hướng dẫn trên màn hình. Nếu không có hoặc báo lỗi, hãy dùng máy tính như trên.

---

## 5. Cài MyEX2Center từ EX2 Store

1. Mở **EX2 Store** trong danh sách ứng dụng của xe (biểu tượng cửa hàng trắng trên nền xanh lá).
2. Xe ở **P**. Chọn một trong hai cách:

   **A. Qua Wi-Fi (khuyên dùng):** xe đã vào Wi-Fi (mục 4.2). Trên thẻ **MyEX2Center**, bấm **Cài đặt**. Store tự tải bản chính thức, kiểm tra SHA-256 rồi cài. Khi đang tải, nút đổi thành **"Hủy tải (x%)"**. Nếu bị gián đoạn, lần sau Store **tải tiếp** phần còn lại.

   **B. Từ USB (không cần mạng):** cắm USB có file `MyEX2Center-1.3.0.apk` (Phần 3). Bấm **Cài từ USB / Máy** ở thanh trên của Store, rồi chọn đúng file MyEX2Center.

3. Cài xong, thẻ MyEX2Center hiện **Đã cài**, kèm nút **Mở**.

### Kiểm tra (nếu còn nối máy tính)

```bash
adb shell dumpsys package com.mipago.ex2center | grep -E "userId=|versionName"
```
Kết quả đúng có **`userId=1000`** và `versionName=1.3.0`.

---

## 6. Mở MyEX2Center lần đầu và kiểm tra

1. Bấm **Mở** trong EX2 Store, hoặc mở **MyEX2Center** trong danh sách ứng dụng (biểu tượng xe EX2 màu trắng).
2. App tự chạy **dịch vụ tự động hóa** (có thông báo thường trực hình chiếc xe) và **nút nổi** tròn màu xanh. Cả hai tự bật lại khi xe khởi động.
3. Nếu **nút nổi** hoặc **Màn hình đêm** không hiện, cấp quyền hiển thị trên ứng dụng khác (cần máy tính, mục 4.4):
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

## 7. Sau khi cài: an toàn và dọn dẹp

> [!WARNING]
> Khi đã mở khóa, **bất kỳ ai cùng mạng Wi-Fi với xe** đều có thể truy cập ADB của xe với quyền cao nhất.
> - Chỉ cho xe kết nối vào **hotspot của bạn hoặc Wi-Fi nhà có mật khẩu**. **Không** kết nối Wi-Fi công cộng (quán cà phê, trạm sạc…).
> - Ngắt kết nối máy tính khi xong việc:
>   ```bash
>   adb disconnect
>   ```
> - Chỉ cài APK từ nguồn bạn tin tưởng.

Nên tránh khởi động lại đầu xe bằng lệnh `adb reboot`, vì nó gây tiếng "bụp" lớn qua loa xe. Muốn khởi động lại màn hình, dùng nút **Khởi động lại** trong nút nổi. Nút này chỉ hoạt động khi xe ở **P** và đứng yên.

---

## 8. Cập nhật về sau: tự động báo trong xe

Bạn **không cần làm gì** để biết có bản mới:
- Khi xe có Wi-Fi, EX2 Store tự kiểm tra **khoảng 3 giờ một lần**, và thêm một lần ngay sau khi xe khởi động.
- Có bản mới của MyEX2Center, EX2 Store hay app khác đã cài, xe hiện **một thông báo** *"Có bản cập nhật…"*. Thông báo **chỉ hiện khi xe ở số P**, không làm phiền khi đang chạy. Cùng một bản cập nhật chỉ được nhắc lại sau 24 giờ.
- EX2 Store chỉ tin danh sách ứng dụng có **chữ ký số** của dự án. Danh sách bị sửa hoặc giả mạo sẽ bị bỏ qua.

**Để cập nhật:** chạm vào thông báo, hoặc mở EX2 Store. Trên thẻ app có bản mới, bấm **Cập nhật** (xe ở **P**).
- **MyEX2Center** đang mở sẽ tự đóng, cài bản mới, rồi mở lại được ngay.
- **EX2 Store** tự cập nhật chính nó: thẻ EX2 Store hiện **Cập nhật hệ thống**. Store đóng lại trong giây lát, sau đó báo *"EX2 Store đã được cập nhật lên v…"*.
- Không có mạng: chép file APK mới vào USB, rồi dùng **Cài từ USB / Máy** trong Store.

Cài đè **giữ nguyên** cài đặt của bạn: tên xe, ảnh xe, giao diện, chế độ lái đã ghi nhớ.

---

## 9. Cài thêm ứng dụng bằng EX2 Store

1. Xe ở **P**, đã kết nối Wi-Fi.
2. Mở **EX2 Store** (hoặc bấm biểu tượng **Kho ứng dụng** hình cửa hàng ở thanh trên của MyEX2Center).
3. Danh sách hiện có:

   | Ứng dụng | Cách cài |
   |---|---|
   | **MyEX2Center** | **Cài đặt** / **Cập nhật** |
   | **EX2 Store** | **Cập nhật hệ thống** khi có bản mới |
   | **SmartTube** (YouTube không quảng cáo) | Bấm **Cài đặt**: tự tải từ trang chính thức, kiểm tra SHA-256 rồi cài |
   | **Trình duyệt Web** | Bấm **Cài đặt** |
   | **VIETMAP LIVE** | **Cài từ USB / Máy**: chép file `.xapk` vào USB, cắm vào xe, bấm nút |

4. Cài xong, nút chuyển thành **Mở**, kèm nút **thùng rác** để gỡ (có hỏi xác nhận).

**Lưu ý về VIETMAP LIVE (.xapk):** gói hiện tại chỉ có bản cho CPU `armeabi-v7a`. Nếu đầu xe không hỗ trợ, Store sẽ báo *"Không tương thích CPU"* và không cài. Khi đó cần tìm bản Vietmap có `arm64-v8a`. Kiểm tra bằng máy tính:
```bash
adb shell getprop ro.product.cpu.abilist
```

---

## 10. Đang dùng MyEX2Center 1.2.0?

Từ bản **1.3.0**, Kho ứng dụng tích hợp trong MyEX2Center được thay bằng app riêng **EX2 Store**. Xe đã cài 1.2.0 thì ADB đã được mở khóa, nên **không cần máy tính**. Chọn một trong hai cách (xe ở **P**, có Wi-Fi):

**Cách 1:** mở MyEX2Center 1.2.0 → **Kho ứng dụng** → thẻ **EX2 Store** → **Cài đặt**. Sau đó mở EX2 Store và cập nhật MyEX2Center lên 1.3.0.

**Cách 2:** trong Kho của 1.2.0, cập nhật **MyEX2Center** lên 1.3.0 trước (**Cập nhật hệ thống**). Sau đó bấm **Kho ứng dụng**. App báo xe chưa có EX2 Store và hiện hộp thoại **Cài EX2 Store**:
- **Tải và cài:** tự tải EX2 Store chính thức (đã kiểm tra chữ ký và SHA-256) rồi cài, cài xong tự mở Store.
- **Cài từ USB:** dùng file `EX2Store-<phiên bản>.apk` chép vào USB (Phần 3).

Cài đặt cũ của MyEX2Center (tên xe, ảnh, giao diện, ghi nhớ…) **được giữ nguyên**.

---

## 11. Sử dụng MyEX2Center

> [!NOTE]
> Phần này mô tả giao diện **từ bản 1.3.0**. Nút **Kho ứng dụng** (hình cửa hàng) giờ mở app **EX2 Store**; nếu xe chưa có Store, app sẽ đề nghị cài (Phần 10).

### Thanh trên cùng
- **Hồ sơ xe** (ảnh + tên): chạm vào để mở Cài đặt.
- Giờ, nhiệt độ ngoài trời, điện áp 12V.
- **Ô trạng thái**:
  - Xanh *"Tự động hóa: Đang bật"*.
  - Cam *"Chế độ Bảo dưỡng"*.
  - *"Crash Guard: Đã ngắt"*: tự động hóa đã tạm dừng vì gặp lỗi liên tiếp. Bấm vào để **Đặt lại**.
- Wi-Fi (tên mạng và IP), **Kho ứng dụng** (mở EX2 Store), **Màn hình đêm** (chạm bất kỳ đâu để thoát; tự tắt khi vào số R), **Cài đặt**.

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
| **Hệ thống** | Nút nổi, Kho ứng dụng (mở EX2 Store), phiên bản app |

### Các cơ chế an toàn có sẵn
- **Chặn khi không ở số P:** khởi động lại màn hình, cài/gỡ ứng dụng.
- **Chặn khi chạy nhanh:** "Hạ hết kính" chỉ dùng được dưới 30 km/h.
- **Màn hình đêm** tự tắt khi vào số R, để không che camera lùi.
- **Không có nút ESC, HDC, Auto Hold, mở cốp:** các chức năng này **chưa có mã điều khiển đã kiểm chứng** cho EX2, nên app không hiện để tránh gửi lệnh sai.

> [!CAUTION]
> Tắt âm AVAS ảnh hưởng đến an toàn của người đi bộ. Chỉ dùng khi hiểu rõ và đúng quy định nơi bạn lái. **Không thao tác trên màn hình khi xe đang chạy.**

---

## 12. Gỡ cài đặt

Bằng máy tính (đã `adb connect` như mục 4.4):
```bash
adb uninstall com.mipago.ex2center
adb uninstall com.mipago.ex2store
```
- Dòng đầu gỡ MyEX2Center, dòng sau gỡ EX2 Store. Mỗi lệnh xóa app và mọi cài đặt của app đó.
- Có thể gỡ MyEX2Center ngay trong EX2 Store (nút **thùng rác**), không cần máy tính.
- Các ứng dụng khác đã cài qua Store (SmartTube, Vietmap…) **vẫn còn**. Gỡ chúng trong EX2 Store, hoặc dùng `adb uninstall <tên gói>`.

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
| `adb: command not found` | Chưa cài `adb` (mục 4.1), hoặc cần gõ `./adb` (Mac) / `.\adb` (Windows) trong thư mục `platform-tools`. |

### Cài EX2 Store / MyEX2Center (Phần 4–5)
| Thông báo | Nguyên nhân | Cách xử lý |
|---|---|---|
| `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` hoặc `…NO_CERTIFICATES` | Firmware của xe dùng khóa *platform* khác với khóa ký app | Bản APK này không dùng được cho xe đó. Báo lỗi trên GitHub kèm phiên bản firmware. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Đã có một bản ký khác | `adb uninstall <tên gói>` rồi cài lại (mất cài đặt cũ). |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Đang cài bản cũ hơn bản đã có | Dùng bản mới nhất. |
| Cài không có thông báo / bị ngắt giữa chừng | Wi-Fi chập chờn | `adb disconnect`, `adb connect …` lại, rồi cài lại. |
| `Success` nhưng không thấy trong `pm list packages -3` | Bình thường với app hệ thống | Kiểm tra bằng lệnh `dumpsys` ở mục 4.5 / Phần 5. |
| Store báo *"Chỉ dùng được khi xe ở số P."* | Xe chưa ở P | Về số P rồi bấm lại. |
| Store không tải được / báo lỗi mạng | Mất Wi-Fi | Kiểm tra Wi-Fi. Với file lớn, bấm lại để **tải tiếp**. Hoặc dùng **Cài từ USB / Máy**. |
| Mục báo *"…thiếu mã SHA-256 hợp lệ…"* | Mục đó chưa được phát hành an toàn qua mạng | Dùng **Cài từ USB / Máy**. |
| Hộp thoại *"Chưa lấy được thông tin EX2 Store đã xác minh chữ ký"* (trong MyEX2Center) | Không có mạng, hoặc danh sách trên mạng không có chữ ký hợp lệ | Kiểm tra Wi-Fi rồi thử lại, hoặc chọn **Cài từ USB**. |
| Không thấy thông báo cập nhật | Xe không ở P, chưa có Wi-Fi, hoặc thông báo đã hiện trong 24 giờ qua | Mở EX2 Store và bấm nút **làm mới** (↻) để xem ngay. |

### Khi dùng app
| Triệu chứng | Cách xử lý |
|---|---|
| Dashboard toàn `--`, "Chưa kết nối xe" | Kiểm tra `userId=1000` (Phần 5). Mở lại app. Xem log: `adb logcat -s CarApiHelper CarAutomationService MainActivity` |
| Bấm nút, báo "Xe không nhận lệnh" | Xe đang tắt / chưa sẵn sàng, hoặc chức năng không có trên xe bạn. Thử lại khi xe ON, số P. |
| Không thấy nút nổi | Cài đặt → Hệ thống → bật **Nút nổi**; hoặc cấp quyền như mục 6, bước 3. |
| Pill báo "Crash Guard: Đã ngắt" | Bấm vào pill → Đặt lại; hoặc Cài đặt → Crash Guard. Nếu lặp lại, gửi log cho nhà phát triển. |
| Chế độ lái không tự khôi phục sau khi mở xe | Kiểm tra công tắc **Ghi nhớ** đang bật và **Chế độ Bảo dưỡng** đang tắt. |
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

**Sau lần đầu còn cần máy tính không?**
Không. Máy tính chỉ dùng một lần để cài EX2 Store. MyEX2Center, các app khác và mọi bản cập nhật đều cài trong EX2 Store, qua Wi-Fi hoặc USB.

**Không có máy tính có cài được không?**
Lần đầu **cần máy tính**, trừ khi đầu xe có Trình quản lý tệp cài được APK (cuối mục 4.5, chưa kiểm chứng).

**Vì sao tách thành hai app?**
Để cập nhật MyEX2Center không làm tắt chính kho đang cài nó, và để có một nơi duy nhất báo cập nhật cho mọi app trên xe.

**EX2 Store có tự cài gì mà tôi không biết không?**
Không. Store chỉ **báo** có bản mới. Việc cài luôn do bạn bấm, và chỉ khi xe ở số P.

**App có làm mất dữ liệu xe không?**
Không. Gói mở khóa *"nothing wiped"*, và các app không xóa dữ liệu hệ thống. Gỡ app chỉ xóa dữ liệu của chính app.

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
