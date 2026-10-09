plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Kotlin 版本下限 2.4.20：低于它（包括 2.4.20 的 Beta / RC）直接拒绝编译。
// Neton 各仓走同一条 Kotlin 线 —— 混用时 K/N klib 与 KSP 产物会以很难看懂的方式失败。
run {
    val required = KotlinVersion(2, 4, 20)
    val actual = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)
    val parts = actual.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val parsed = KotlinVersion(parts[0], parts[1], parts[2])
    require(parsed > required || (parsed == required && '-' !in actual)) {
        "Kotlin $actual is below the required minimum $required. Upgrade the Kotlin Gradle plugin."
    }
}

group = "com.netonstream.app"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        // 锚定到**本模块自身目录**：rootProject 在复合构建里会变成宿主工程
        // （privchat-application），那时 ../neton 会指向不存在的路径，
        // 链接期报 `library 'env' not found`。
        val coreInterop = project.file("../neton/neton-core/build/nativeInterop/${target.name}").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("com.netonstream.app:module-system")
                implementation("com.netonstream.app:module-infra")
                // group 是 com.netonstream（见 geolite4k/build.gradle.kts:10）。写成
                // com.netonframework 会让 includeBuild 的坐标替换失配，Gradle 转而去
                // 远程仓库找一个不存在的制品；版本也交给复合构建，不硬编。
                implementation("com.netonstream:geolite4k")
                // MEMBER-IDENTITY-ADAPTER(M1): 去掉 privchat:client/hook —— member 脱离 privchat 直接依赖。
                implementation("com.netonstream:neton-core")
                implementation("com.netonstream:neton-routing")
                implementation("com.netonstream:neton-security")
                implementation("com.netonstream:neton-http")
                implementation("com.netonstream:neton-database")
                implementation("com.netonstream:neton-logging")
                implementation("com.netonstream:neton-validation")
                implementation("com.netonstream:neton-redis")
                implementation("com.netonstream:neton-cache")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

dependencies {
    add("kspMacosArm64", "com.netonstream:neton-ksp")
    add("kspLinuxX64", "com.netonstream:neton-ksp")
    add("kspLinuxArm64", "com.netonstream:neton-ksp")
    add("kspMingwX64", "com.netonstream:neton-ksp")
}

ksp {
    arg("neton.moduleId", "member")
}

// Ensure Kotlin compilation sees KSP-generated commonMain sources.
// Required because facade tables delegate to generated XxxTableImpl —
// K2 metadata compilation needs them at the commonMain level.
afterEvaluate {
    // 把 macosArm64 KSP 输出加到 commonMain，让所有 target 共享（避免每 target 重复生成）。
    val kspOut = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
    kotlin.sourceSets.named("commonMain") {
        kotlin.srcDir(kspOut)
    }
    // 各 target 的 srcDirs 默认会自动包含自己 target 的 generated/ksp 目录；
    // commonMain 已经包含 macosArm64 的输出，target 自己的 KSP 输出会与 commonMain 冲突
    // （PrivchatModuleInitializer 等 object 重定义）。统一过滤掉 generated/ksp，
    // 让所有 target 只通过 commonMain 看到 macosArm64 的 KSP 输出一份。
    listOf("macosArm64Main", "linuxX64Main", "linuxArm64Main", "mingwX64Main").forEach { name ->
        kotlin.sourceSets.findByName(name)?.let { ss ->
            val filtered = ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }
            if (filtered.size < ss.kotlin.srcDirs.size) ss.kotlin.setSrcDirs(filtered)
        }
    }
}

tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
// 非 macosArm64 的 KSP 任务也读 macosArm64 的 generated/ksp 输出（commonMain 共享），
// 必须显式声明依赖，否则 Gradle 8 严格模式把 ./gradlew build 判为 race + FAILED。
tasks.matching { it.name.matches(Regex("kspKotlin(LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}

// Embed SQL migration resources into binary (no runtime file IO).
extra["neton.migration.moduleId"] = "member"
extra["neton.migration.dialects"] = listOf("postgresql")
extra["neton.migration.sqlSourceDir"] = file("sql")
apply(from = "../neton/scripts/embed-migration-resources.gradle.kts")
