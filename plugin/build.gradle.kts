plugins {
    java
    `maven-publish`
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.1.2"
}

base {
    archivesName.set("trackcache-plugin")
}

lavalinkPlugin {
    name = "trackcache-plugin"
    apiVersion = "4.0.8"
    serverVersion = "4.0.8"
    configurePublishing = false
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    jar {
        dependsOn(":main:jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    }
    test {
        useJUnitPlatform()
    }
}

dependencies {
    implementation(project(":main"))
    testImplementation("dev.arbjerg.lavalink:plugin-api:4.0.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework:spring-test:6.1.8")
    testImplementation("org.springframework.boot:spring-boot-starter-web:3.3.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = base.archivesName.get()
        }
    }
}
