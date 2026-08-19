import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin
import java.util.Properties

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun findGoExecutable(): File? {
    val configuredExecutable = localProperties.getProperty("go.executable")
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?.takeIf(File::isFile)
    if (configuredExecutable != null) return configuredExecutable

    val configuredDir = localProperties.getProperty("go.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
    val configuredDirExecutable = configuredDir
        ?.resolve("bin/go.exe")
        ?.takeIf(File::isFile)
    if (configuredDirExecutable != null) return configuredDirExecutable

    return listOf(
        file("C:/Go/go/bin/go.exe"),
        file("C:/Go/bin/go.exe"),
    ).firstOrNull(File::isFile)
}

fun findNdkClang(abi: String, minSdk: Int, cpp: Boolean = false): File? {
    val sdkDir = localProperties.getProperty("sdk.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?: return null

    val targetPrefix = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "armv7a-linux-androideabi"
        "x86" -> "i686-linux-android"
        "x86_64" -> "x86_64-linux-android"
        else -> return null
    }

    val ndkDir = sdkDir.resolve("ndk/26.1.10909125")
    val llvmDir = ndkDir.resolve("toolchains/llvm/prebuilt/windows-x86_64")
    val binDir = llvmDir.resolve("bin")

    val suffix = if (cpp) "clang++.cmd" else "clang.cmd"

    return binDir
        .resolve("$targetPrefix$minSdk-$suffix")
        .takeIf(File::isFile)
}

golang {
    sourceSets {
        create("alpha") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    namespace = "com.github.kr328.clash.core"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    flavorDimensions += "feature"

    productFlavors {
        create("alpha") {
            dimension = "feature"
            isDefault = true
        }
        create("meta") {
            dimension = "feature"
        }
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
}

afterEvaluate {
    val goExecutable = findGoExecutable()
    val minSdk = android.defaultConfig.minSdk ?: 24

    tasks.withType(GolangBuildTask::class.java).forEach {
        it.inputs.dir(golangSource)

        if (goExecutable != null) {
            it.executable(goExecutable.absolutePath)
        }

        val abi = abis.firstNotNullOfOrNull { (abiName, goAbi) ->
            abiName.takeIf { abiTaskName ->
                it.name.endsWith(goAbi)
            }
        }

        val cc = abi?.let { abiName ->
            findNdkClang(abiName, minSdk, cpp = false)
        }

        val cxx = abi?.let { abiName ->
            findNdkClang(abiName, minSdk, cpp = true)
        }

        if (cc != null) {
            it.environment("CC", cc.absolutePath)
        }

        if (cxx != null) {
            it.environment("CXX", cxx.absolutePath)
        }

        when (abi) {
            "arm64-v8a" -> {
                val llvmDir = cc?.parentFile?.parentFile

                it.environment("GOOS", "android")
                it.environment("GOARCH", "arm64")
                it.environment("CGO_ENABLED", "1")

                if (llvmDir != null) {
                    val sysroot = llvmDir.resolve("sysroot").absolutePath

                    it.environment(
                        "CGO_CFLAGS",
                        "--target=aarch64-linux-android$minSdk --sysroot=$sysroot"
                    )

                    it.environment(
                        "CGO_CPPFLAGS",
                        "--target=aarch64-linux-android$minSdk --sysroot=$sysroot"
                    )

                    it.environment("CGO_LDFLAGS", "")
                }
            }
        }
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86" to "X86", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
                println("Set up dependency: $name -> externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}
