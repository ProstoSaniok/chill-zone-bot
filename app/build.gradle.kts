plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    testImplementation(kotlin("test"))
}

application { mainClass.set("app.MainKt") }

tasks.named<JavaExec>("run") {
    // Resolve .env from the project root for both local and Docker development runs.
    workingDir(rootProject.projectDir)
}

kotlin { jvmToolchain(17) }

tasks.withType<Copy>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

tasks.withType<Sync>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }


tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("compileTestKotlin") {
    enabled = false
}

tasks.named("test") {
    enabled = false
}
