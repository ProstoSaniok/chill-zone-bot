import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    base
    kotlin("jvm") version "1.9.22" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        version.set("1.3.1")
        ignoreFailures.set(false)
        baseline.set(rootProject.file("config/quality/baselines/ktlint-${project.name}.xml"))
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
            reporter(ReporterType.HTML)
        }
        filter {
            exclude("**/build/**", "**/.gradle/**")
        }
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<DetektExtension> {
        toolVersion = "1.23.8"
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/quality/detekt.yml"))
        baseline = rootProject.file("config/quality/baselines/detekt-${project.name}.xml")
        // Include every source set, including app tests whose execution is disabled.
        source.setFrom(fileTree("src") { include("**/*.kt", "**/*.kts") })
        basePath = rootProject.projectDir.absolutePath
        ignoreFailures = false
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
        }
    }
}

val lint by tasks.registering {
    group = "verification"
    description = "Checks Kotlin style and code quality in every module and Gradle script."
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })
    dependsOn(subprojects.map { "${it.path}:detekt" })
}

tasks.named("check") {
    dependsOn(lint)
}
