plugins {
    java
}

group = "net.watones"
version = "1.1.4"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")

    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
    implementation("com.mysql:mysql-connector-j:9.6.0")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

configurations.implementation {
    exclude(group = "org.slf4j", module = "slf4j-api")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    jar {
        enabled = true
        archiveFileName.set("NovaGems-${project.version}.jar")
    }
    processResources {
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }
    test { useJUnitPlatform() }
}
