plugins {
    `java-library`
    `maven-publish`
}

base {
    archivesName.set("trackcache-main")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

tasks.jar {
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-redis:3.2.5")
    api("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    api("org.slf4j:slf4j-api:2.0.12")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = base.archivesName.get()
        }
    }
}
