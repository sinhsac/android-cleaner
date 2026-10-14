plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sacls.foldercleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sacls.foldercleaner"
        minSdk = 24
        // Trước đây để 28 nhằm dùng legacy storage, với giả định app target thấp vẫn đọc
        // được Android/data của app khác. Giả định đó sai: đã đo trên SM-A507FN/Android 11,
        // process app chỉ thuộc group 9997 (everybody) chứ không có 1015 (sdcard_rw), mà
        // Android/data là drwxrwx--x root:sdcard_rw -> chỉ đi xuyên qua được, không liệt kê.
        // Truy cập đi qua SAF nên không phụ thuộc targetSdk; giữ 30 cho đúng scoped storage.
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
