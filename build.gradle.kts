plugins {
    id("java")
    id("application")
}

group = "org.example"
version = "1.0-SNAPSHOT"

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
    mainClass = "org.example.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
}
