plugins {
    `java-library`
    `maven-publish`
}

group = "com.hayden"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.20.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "testgraphsdk-java"
        }
    }
}
