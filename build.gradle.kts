plugins {
    id("java")
    id("application")
    id("org.beryx.runtime") version "2.0.1"
}

group = "com.hiveworkshop"
version = "1.0.0"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("http://maven.nikr.net/"); isAllowInsecureProtocol = true  }
}

dependencies {
    implementation(files("libs/modelstudio-0.05.jar"))
    implementation(files("libs/JCASC.jar"))
    implementation(files("libs/blp-iio-plugin.jar"))
    implementation("com.github.inwc3:JMPQ3:1.7.14")
    implementation("org.json:json:20240303")
    implementation("com.formdev:flatlaf:3.4")
    implementation("net.nikr:dds:1.0.0")

    implementation(platform("org.lwjgl:lwjgl-bom:3.3.6"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-opengl")
    runtimeOnly("org.lwjgl:lwjgl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-windows")
    implementation("org.lwjglx:lwjgl3-awt:0.2.3") {
        exclude(group = "org.lwjgl", module = "lwjgl")
        exclude(group = "org.lwjgl", module = "lwjgl-opengl")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.hiveworkshop.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

runtime {
    options.addAll("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages")

    modules.addAll(
        "java.desktop",
        "java.datatransfer",
        "java.logging",
        "jdk.unsupported"
    )

    jpackage {
        imageName = "WC3ModelExplorer-${project.version}"
        installerName = "WC3ModelExplorer"
        appVersion = project.version.toString()
        val os = org.gradle.internal.os.OperatingSystem.current()
        if (os.isWindows) {
            imageOptions.addAll(listOf("--icon", "src/main/resources/images/app-icon.ico"))
            installerOptions.addAll(listOf(
                "--win-dir-chooser",
                "--win-shortcut",
                "--win-menu",
                "--win-per-user-install"
            ))
            installerType = "exe"
        } else if (os.isMacOsX) {
            installerType = "dmg"
        } else {
            installerType = "deb"
        }
        jvmArgs.addAll(listOf("--enable-native-access=ALL-UNNAMED"))
    }
}

tasks.processResources {
    filesMatching("version.properties") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
