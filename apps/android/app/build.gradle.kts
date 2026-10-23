plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val toolsRoot = providers.environmentVariable("QINGSTUDY_TOOLS")
    .orElse(rootProject.projectDir.resolve("../../.tools").absolutePath).get()

android {
    namespace = "dev.qingstudy.ime"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    ndkPath = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse("$toolsRoot/android-ndk-r27d").get()
    defaultConfig {
        applicationId = "dev.qingstudy.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "0.24.1-maintenance"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isJniDebuggable = false
            // 外部签名步骤校验固定证书；缺少私钥时仅产出 unsigned，不回退调试签名。
            signingConfig = null
        }
    }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false } }
    androidResources { noCompress += listOf("onnx", "wav") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/qingjian-assets"))
    if (project.hasProperty("punctuationLab")) {
        sourceSets["androidTest"].java.srcDir("src/punctuationLab/java")
        sourceSets["androidTest"].jniLibs.srcDir("src/punctuationLab/jniLibs")
    }
}

val upstream = rootProject.projectDir.resolve("../..")
val prepareAssets by tasks.registering(Sync::class) {
    doFirst { check(upstream.resolve("data/generated/lm.qj").isFile) { "Run python apps/android/release/prepare.py first" } }
    into(layout.buildDirectory.dir("generated/qingjian-assets"))
    from(upstream.resolve("assets/lexicon/dict.tsv")) { into("engine") }
    from(upstream.resolve("assets/lexicon/dicts")) { include("*.tsv"); into("engine/domains") }
    from(upstream.resolve("data/generated/lm.qj")) { into("engine") }
    from(upstream.resolve("assets/glossary/glossary-en.tsv")) { into("engine") }
    from(upstream.resolve("assets/lexicon/english.tsv")) { into("engine") }
    from(upstream.resolve("assets/glossary/glossary-zh.tsv")) { into("engine") }
    from(upstream.resolve("assets/levels/levels-en.tsv")) { into("engine") }
    from(upstream.resolve("assets/levels/README.md")) { into("licenses/levels") }
    from(upstream.resolve("assets/emoji")) { include("*.tsv"); into("engine") }
    from(upstream.resolve("assets/emoji/LICENSE-unicode.txt")) { into("licenses/emoji") }
    from(upstream.resolve("LICENSE")) { into("licenses") }
    from(upstream.resolve("assets/lexicon")) {
        include("README.md", "QINGJIAN.md", "**/*LICENSE*", "**/*NOTICE*", "**/*license*", "**/*COPYING*")
        into("licenses/lexicon")
    }
    from(upstream.resolve("assets/glossary/README.md")) { into("licenses/glossary") }
    from(upstream.resolve("docs/design/landscape.md")) { into("licenses") }
    from("src/main/notices") { into("licenses") }
    from("src/main/speech-licenses") { into("licenses/voice") }
}
tasks.named("preBuild") { dependsOn(prepareAssets) }

dependencies {
    if (project.hasProperty("punctuationLab")) {
        androidTestImplementation(files(upstream.resolve(".tools/text-models/punctuation/onnxruntime-android-1.24.3.aar")))
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("$toolsRoot/speech/sherpa-onnx-1.13.8.aar"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

