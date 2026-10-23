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
}

dependencies {
    implementation(project(":main"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = base.archivesName.get()
        }
    }
}
