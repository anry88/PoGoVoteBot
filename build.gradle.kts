plugins {
    kotlin("jvm") version "1.7.21"
    application
}

group = "com.abrazhnikov"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.telegram:telegrambots:6.1.0")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")
}

application {
    mainClass.set("MainKt")
}
