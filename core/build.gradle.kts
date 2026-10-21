plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    implementation(project(":data"))
    api("com.github.kotlin-telegram-bot:kotlin-telegram-bot:6.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.10.1")
}

kotlin { jvmToolchain(17) }


tasks.withType<Test>().configureEach { useJUnit() }
