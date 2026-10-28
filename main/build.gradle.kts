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
    api("io.lettuce:lettuce-core:6.3.2.RELEASE")
    compileOnly("org.springframework.boot:spring-boot:3.3.0")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    compileOnly("org.slf4j:slf4j-api:2.0.12")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    testImplementation("org.slf4j:slf4j-simple:2.0.12")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = base.archivesName.get()
        }
    }
}
