# Folder Cleaner

App Android tự dọn file rác (ví dụ `*.mp4`, `*.jpg` mà Kabe View để lại trong thư mục data) mà **không cần root**.

Bạn chọn thư mục một lần qua trình chọn của hệ thống (Storage Access Framework). App giữ quyền truy cập thư mục đó vĩnh viễn, rồi quét và xóa các file khớp đuôi bạn cấu hình — thủ công hoặc tự động định kỳ.

## Tính năng

- **Chọn thư mục (SAF)**: mở trình chọn hệ thống ngay tại thư mục cần dọn (kể cả `Android/data/...` mà Android 11+ chặn truy cập trực tiếp). Cấp quyền 1 lần, dùng mãi.
- **Cấu hình đuôi file**: mặc định `mp4,jpg`, sửa được (ví dụ `mp4,jpg,jpeg,mkv`).
- **Quét cả thư mục con**: bật/tắt.
- **Quét thử**: đếm số file khớp và tổng dung lượng, **không xóa** — xem trước cho yên tâm.
- **Dọn ngay**: xóa, có hộp xác nhận.
- **Dọn tự động**: WorkManager chạy định kỳ theo chu kỳ giờ bạn đặt (tối thiểu 1 giờ), hiện thông báo kết quả. Lần xóa đầu tiên đợi đủ một chu kỳ, không xóa ngay khi vừa bật công tắc.

## Cách truy cập thư mục của Kabe View (Android/data)

Thư mục Kabe View trên máy thật là:

```
/storage/emulated/0/Android/data/com.mm.android.direct.KBVISIONVIEWPlus/files/Download
```

Trên **Android 11, cách duy nhất không-root là SAF tree grant**. Cụ thể:

1. Bấm **"Chọn thư mục (SAF)"**. App mở trình chọn hệ thống **ngay tại** thư mục đang điền trong ô đường dẫn (qua `EXTRA_INITIAL_URI`).
2. Bấm **"Sử dụng thư mục này"**.

Bước 1 là mấu chốt: trình chọn của Android 11 **ẩn** `Android/data` khi bạn duyệt xuống từng cấp, nhưng nếu mở thẳng vào bên trong thì nút "Sử dụng thư mục này" vẫn cấp quyền bình thường. Grant thuộc dạng `prefix` nên từ một thư mục cấp trên vẫn quét được thư mục con. Đây đúng là cách ES File Explorer làm — nó giữ grant trên `primary:Android/data`.

Sau khi có grant, app giữ vĩnh viễn và `CleanerEngine.clean()` tự chọn SAF. Với thư mục thường (DCIM, Download...) không cần grant, app dùng `java.io.File`.

### Những cách KHÔNG hoạt động

Đã đo trực tiếp trên Samsung SM-A507FN / Android 11:

| Cách | Kết quả |
|---|---|
| `targetSdk 28` + `requestLegacyExternalStorage` | `listFiles()` trả `null` |
| `MANAGE_EXTERNAL_STORAGE` ("Truy cập tất cả tệp") | vẫn `null`, kể cả khi appop bật ở mức uid |

Lý do: process app chỉ thuộc group `9997 (everybody)`, không có `1015 (sdcard_rw)`, mà `Android/data` là `drwxrwx--x root:sdcard_rw`. App rơi vào bit "other" là `--x` — đi xuyên qua được nếu biết tên chính xác, nhưng không liệt kê và không xóa được. Không phải lỗi SELinux (logcat không có AVC denial nào).

> Nếu đổi thư mục đích: dán đường dẫn mới vào ô đường dẫn, rồi bấm "Chọn thư mục (SAF)" để cấp quyền cho thư mục đó.

## Build

Cần: JDK 17, Android SDK (API 34). Project đã kèm Gradle wrapper (Gradle 8.9).

Tạo `local.properties` trỏ tới SDK (hoặc đặt biến môi trường `ANDROID_HOME`):

```
sdk.dir=D:\\android-sdk
```

Build APK debug:

```
gradlew.bat :app:assembleDebug
```

APK nằm ở: `app/build/outputs/apk/debug/app-debug.apk`

## Build tự động trên GitHub (khuyến nghị)

Repo có sẵn workflow `.github/workflows/release.yml`:

- **Mỗi lần push** lên `master`/`main`: build APK debug và lưu dưới dạng **Artifact** (tab Actions → chọn run → mục Artifacts → `FolderCleaner-apk`).
- **Khi push tag `v*`** (ví dụ `v1.0`): build APK, sinh **QR code**, và tạo **GitHub Release** đính kèm cả APK lẫn QR. Mở trang Release trên điện thoại, hoặc quét QR, là tải + cài được ngay.

Cách phát hành một bản:

```
git tag v1.0
git push origin master --tags
```

Vào tab **Actions** chờ build xong, rồi mở **Releases** để lấy APK/QR. APK ký bằng **debug key mặc định** (đủ để cài test, không cần quản lý keystore).

## Cài lên điện thoại

Qua adb (điện thoại bật USB debugging):

```
D:\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

Hoặc copy APK vào máy và cài tay (bật "Cài từ nguồn không xác định").

## Lưu ý quyền

- App **không** xin `MANAGE_EXTERNAL_STORAGE`; nó chỉ dùng quyền trên đúng thư mục bạn cấp (an toàn hơn, và quyền đó cũng không giúp gì với `Android/data`).
- `READ_EXTERNAL_STORAGE` chỉ dùng cho các thư mục thường khi quét bằng `java.io.File`.
- Android 13+: khi bật dọn tự động, app xin quyền thông báo để báo kết quả.
- Nếu một ngày app báo "mất quyền truy cập thư mục", mở app và bấm "Chọn thư mục (SAF)" để cấp lại.

## Giới hạn kỹ thuật

- Chu kỳ tự động tối thiểu của WorkManager là 15 phút; app dùng đơn vị giờ nên luôn hợp lệ. Hệ thống có thể trễ ít phút để tiết kiệm pin — đây là hành vi bình thường của Android, không phải lỗi.
- Việc xóa là **vĩnh viễn** (không vào thùng rác). Luôn "Quét thử" trước khi "Dọn ngay" lần đầu.
