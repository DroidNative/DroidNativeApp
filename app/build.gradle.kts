import java.awt.Image
import java.util.Properties
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Launcher icons rendered from docs/droidnativelogo.png (transparent
 * background) at every density: ic_launcher.png for Android 7.x and
 * ic_launcher_foreground.png, the layer of the adaptive icon of Android 8+
 * (res/mipmap-anydpi-v26/ic_launcher.xml).
 */
abstract class GenerateLauncherIcons : DefaultTask() {
    @get:InputFile
    abstract val logo: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val src = ImageIO.read(logo.get().asFile)
        val densities = mapOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)
        for ((name, factor) in densities) {
            val dir = outputDir.get().dir("mipmap-$name").asFile
            dir.mkdirs()
            // Legacy icon: 48 dp, the logo nearly edge to edge
            write(src, (48 * factor).toInt(), 44f / 48f, File(dir, "ic_launcher.png"))
            // Adaptive foreground: 108 dp canvas; launchers mask it down to
            // as little as a 66 dp circle, so the logo stays well inside
            write(src, (108 * factor).toInt(), 56f / 108f, File(dir, "ic_launcher_foreground.png"))
        }
    }

    private fun write(src: BufferedImage, size: Int, fill: Float, file: File) {
        val inner = (size * fill).toInt()
        // Area averaging keeps thin lines (antennas) when shrinking 2000 px to 48
        val scaled = src.getScaledInstance(inner, inner, Image.SCALE_AREA_AVERAGING)
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(scaled, (size - inner) / 2, (size - inner) / 2, null)
        g.dispose()
        ImageIO.write(out, "png", file)
    }
}

/**
 * Trusted signing key for test builds: DN_XBPS_KEY names a <fingerprint>.plist
 * (xbps-repository: ./xbps.sh keygen), shipped next to the committed keys in
 * assets/xbps-keys/ (env/GuestSetup.kt installs them into the guest).
 */
abstract class ExtraXbpsKey : DefaultTask() {
    @get:InputFiles
    abstract val keys: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().dir("xbps-keys").asFile
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        for (key in keys.files) key.copyTo(File(dir, key.name))
    }
}

// Package repository of the guest (xbps-repository); DN_XBPS_REPO overrides it
val xbpsRepo: String = providers.environmentVariable("DN_XBPS_REPO")
    .orElse("https://github.com/DroidNative/xbps-repository/releases/download/current").get()

val extraXbpsKey = tasks.register<ExtraXbpsKey>("extraXbpsKey") {
    providers.environmentVariable("DN_XBPS_KEY").orNull?.let { keys.from(it) }
    outputDir.set(layout.buildDirectory.dir("generated/xbpsKeys"))
}

val generateLauncherIcons = tasks.register<GenerateLauncherIcons>("generateLauncherIcons") {
    logo.set(rootProject.layout.projectDirectory.file("docs/droidnativelogo.png"))
    outputDir.set(layout.buildDirectory.dir("generated/launcherIcons"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateLauncherIcons, GenerateLauncherIcons::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(extraXbpsKey, ExtraXbpsKey::outputDir)
    }
}

// Release signing: keystore.properties in the project root (not in git) with
// storeFile, storePassword, keyAlias, keyPassword. Without it release APKs
// are built unsigned.
val releaseKeys = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "app.droidnative"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.droidnative"
        versionCode = 1
        versionName = "0.1-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "XBPS_REPO", "\"$xbpsRepo\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Sideload / F-Droid: Termux-style targetSdk 28, the last level that
        // allows executing files from the data directory (docs/architecture.md)
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            minSdk = 24
            targetSdk = 28
        }
        // Play compliant: all executables come from nativeLibraryDir
        create("play") {
            dimension = "distribution"
            minSdk = 29
            targetSdk = 37
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Executables shipped as lib*.so must be extracted to nativeLibraryDir
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.tukaani.xz)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
