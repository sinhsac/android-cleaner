# Folder Cleaner

App Android tự dọn file rác (ví dụ `*.mp4`, `*.jpg` mà Kabe View để lại trong thư mục data) mà **không cần root**.

Bạn chọn thư mục một lần qua trình chọn của hệ thống (Storage Access Framework). App giữ quyền truy cập thư mục đó vĩnh viễn, rồi quét và xóa các file khớp đuôi bạn cấu hình — thủ công hoặc tự động định kỳ.

## Tính năng

- **Chọn thư mục (Browse)**: mở trình chọn hệ thống, điều hướng tới đúng thư mục cần dọn (kể cả `Android/data/...` mà Android 11+ chặn truy cập trực tiếp). Chọn 1 lần, dùng mãi.
- **Cấu hình đuôi file**: mặc định `mp4,jpg`, sửa được (ví dụ `mp4,jpg,jpeg,mkv`).
- **Quét cả thư mục con**: bật/tắt.
- **Quét thử**: đếm số file khớp và tổng dung lượng, **không xóa** — xem trước cho yên tâm.
- **Dọn ngay**: xóa, có hộp xác nhận.
- **Dọn tự động**: WorkManager chạy định kỳ theo chu kỳ giờ bạn đặt (tối thiểu 1 giờ), hiện thông báo kết quả.

## Cách truy cập thư mục của Kabe View (Android/data)

Thư mục Kabe View dạng `/storage/emulated/0/Android/data/com.mm.android.direct.KB/files/Downloads`.

Trên **Android 11**, cả SAF lẫn `MANAGE_EXTERNAL_STORAGE` đều **bị chặn** truy cập `Android/data` của app khác. Cách duy nhất không-root còn mở là **bắt chước ES Explorer**: build app với `targetSdk` thấp (28) + legacy storage. Khi đó app xin `READ/WRITE_EXTERNAL_STORAGE` kiểu cũ và truy cập thẳng đường dẫn tuyệt đối bằng `java.io.File`, vào được `Android/data` như ES Explorer.

App vì vậy có **2 chế độ**:

1. **Đường dẫn tuyệt đối (mặc định, khuyến nghị)** — ô "Đường dẫn thư mục cần dọn" đã điền sẵn đường dẫn Kabe View. Bấm "Cấp quyền truy cập bộ nhớ", rồi "Quét thử" / "Dọn ngay".
2. **SAF (phụ)** — nút "Chọn thư mục (SAF)". Thường bị chặn với `Android/data` trên Android 11, chỉ dùng cho thư mục công khai.

> Nếu đổi đường dẫn, chỉ cần dán đường dẫn khác vào ô đó.

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

- App **không** xin quyền lưu trữ toàn bộ; nó chỉ dùng quyền trên đúng thư mục bạn chọn (an toàn hơn).
- Android 13+: khi bật dọn tự động, app xin quyền thông báo để báo kết quả.
- Nếu một ngày app báo "mất quyền truy cập thư mục", chỉ cần mở app và bấm Browse chọn lại.

## Giới hạn kỹ thuật

- Chu kỳ tự động tối thiểu của WorkManager là 15 phút; app dùng đơn vị giờ nên luôn hợp lệ. Hệ thống có thể trễ ít phút để tiết kiệm pin — đây là hành vi bình thường của Android, không phải lỗi.
- Việc xóa là **vĩnh viễn** (không vào thùng rác). Luôn "Quét thử" trước khi "Dọn ngay" lần đầu.
