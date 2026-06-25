plugins {
    java
    id("io.github.goooler.shadow") version "8.1.8"
    id("com.xbaimiao.easylib") version ("1.1.6")
    kotlin("jvm") version "1.9.20"
}

group = "com.dongzh1.sourceforge"
version = "1.0.0"

easylib {
    env {
        mainClassName = "com.dongzh1.sourceforge.SourceForge"
        pluginName = "SourceForge"
        pluginUpdateInfo = "源质锻造系统"
        kotlinVersion = "1.9.20"
    }
    version = "3.9.0"

    library("com.github.cryptomorin:XSeries:9.9.0", true) {
        relocate("com.cryptomorin.xseries", "${project.group}.shadow.xseries")
    }
    // Kryo 5：源质锻炉多方块作业的按世界持久化（cloud=false -> implementation + shadow 重定位）
    library("com.esotericsoftware.kryo:kryo5:5.6.2", false) {
        relocate("com.esotericsoftware", "${project.group}.shadow.kryo")
        relocate("org.objenesis", "${project.group}.shadow.objenesis")
    }
//    library("de.tr7zw:item-nbt-api:2.12.3", true){
//        relocate("de.tr7zw.changeme.nbtapi", "${project.group}.shadow.itemnbtapi")
//        repo("https://repo.codemc.org/repository/maven-public/")
//    }
//    library("redis.clients:jedis:5.0.1", true) {
//        relocate("redis.clients.jedis", "${project.group}.shadow.redis")
//    }
//    // jedis需要
//    library("org.apache.commons:commons-pool2:2.12.0", true){
//        relocate("org.apache.commons.pool2", "${project.group}.shadow.pool2")
//    }
//    library("com.zaxxer:HikariCP:4.0.3", true) {
//        relocate("com.zaxxer.hikari", "${project.group}.shadow.hikari")
//    }

    relocate("com.xbaimiao.easylib", "${project.group}.easylib", false)
    relocate("kotlin", "${project.group}.shadow.kotlin", true)
    relocate("kotlinx", "${project.group}.shadow.kotlinx", true)
}

repositories {
    mavenLocal()
    mavenCentral()
    // auto-inject
    easylib.library.mapNotNull { it.repo }.toSet().forEach { uri -> maven(uri) }

    maven("https://maven.xbaimiao.com/repository/maven-public/")
    maven("https://papermc.io/repo/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    easylib.library.forEach {
        if (it.cloud) {
            compileOnly(it.id)
        } else {
            implementation(it.id)
        }
    }

    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // 必须与服务器部署的 CraftEngine 版本一致（v26.6）。旧的 0.0.66 API 与 26.6 不兼容，
    // 运行时 ImmutableBlockState.owner()/value()/id() 会抛 NoSuchMethodError 被 runCatching 吞掉 → blockId 返回 null。
    compileOnly("net.momirealms:craft-engine-core:26.6.3")
    compileOnly("net.momirealms:craft-engine-bukkit:26.6.3")
    // 仅取 API 接口；isTransitive=false 避免带入更高版本 kotlin-stdlib 覆盖本项目 1.9
    compileOnly("io.github.toxicity188:BetterHud-standard-api:1.14.1") { isTransitive = false }
    compileOnly("io.github.toxicity188:BetterHud-bukkit-api:1.14.1") { isTransitive = false }
    // HudPlayer 继承 kr.toxicity.command.BetterCommandSource；调用其成员(pointers())需此接口在编译期可见
    compileOnly("io.github.toxicity188:BetterCommand:1.4.3") { isTransitive = false }
    // PacketEvents：Phase 2 发包悬浮符号特效用（运行期由服务器 PacketEvents 插件提供，故 compileOnly 不打包）。
    // 2.11.0+ 支持 MC 1.21.11；与服务器装的 PacketEvents 版本保持一致即可。
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.2")
    // GraalJS（SF 技能脚本引擎）：shade 进 jar，运行期不依赖服务器 JDK 自带 JS。
    // 排除 Truffle 优化运行时/编译器：它要 GraalVM 的 JVMCI（普通 JDK 没有 IS_BUILDING_NATIVE_IMAGE 字段会崩），
    // 排除后回退到 DefaultTruffleRuntime 纯解释器，任何 JDK 都能跑（脚本稍慢，对技能逻辑无所谓）。
    // Truffle 依赖 ServiceLoader → shadowJar 需 mergeServiceFiles()。
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js-community:24.1.2") {
        exclude(group = "org.graalvm.truffle", module = "truffle-runtime")
        exclude(group = "org.graalvm.truffle", module = "truffle-compiler")
        exclude(group = "org.graalvm.truffle", module = "truffle-enterprise")
        exclude(group = "org.graalvm.compiler", module = "compiler")
    }
    compileOnly(fileTree("libs"))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }
    shadowJar {
        dependencies {
            easylib.library.forEach {
                if (it.cloud) {
                    exclude(dependency(it.id))
                }
            }
            exclude(dependency("org.slf4j:"))
            exclude(dependency("org.jetbrains:annotations:"))
            exclude(dependency("com.google.code.gson:gson:"))
            exclude(dependency("org.jetbrains.kotlin:"))
            exclude(dependency("org.jetbrains.kotlinx:"))
        }
        archiveClassifier.set("")
        easylib.getAllRelocate().forEach {
            relocate(it.pattern, it.replacement)
        }
        mergeServiceFiles()   // Truffle/GraalJS 靠 META-INF/services 发现语言引擎，必须合并

        // 不再使用 minimize：它的可达性静态分析对 Kotlin 不稳定，曾间歇性把本项目自有类（如 util/Text）
        // 当作“无用类”剔除，导致运行时 NoClassDefFoundError。换来的体积收益很小，不值得这个风险。
    }
    processResources {
        expand("version" to project.version)
        val relocateAnchor = "relocate: # inject"
        filter { line ->
            var replace = line
            if (line.contains(relocateAnchor)) {
                replace = line.replace(relocateAnchor,
                    "relocate: \r\n" + easylib.getAllRelocate().filter { it.cloud }
                        .joinToString("\r\n") { "  - \"${it.pattern}!${it.replacement}\"" })
            }
            if (line.contains("main: # inject")) {
                replace = "main: ${easylib.env.mainClassName}"
            }
            if (line.contains("name: # inject")) {
                replace = "name: ${easylib.env.pluginName}"
            }
            if (line.contains("update-info: # inject")) {
                replace = "update-info: \"${easylib.env.pluginUpdateInfo}\""
            }
            if (line.contains("kotlin-version: # inject")) {
                replace = "kotlin-version: \"${easylib.env.kotlinVersion}\""
            }
            if (line.contains("depend-list: # inject")) {
                replace = "depend-list: \r\n" + easylib.library.filter { it.cloud }.joinToString("\r\n") {
                    if (it.repo == null) {
                        "  - \"${it.id}\""
                    } else {
                        "  - \"${it.id}<<repo>>${it.repo}\""
                    }
                }
            }
            replace
        }
    }

}
