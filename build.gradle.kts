import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.android.library") version "8.2.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.5"
    id("org.jetbrains.dokka") version "1.9.20"
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"

    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
}

val detektReportMergeSarif by tasks.registering(ReportMergeTask::class) {
    output = layout.buildDirectory.file("reports/detekt/merge.sarif.json")
}

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "1.8"
        reports {
            xml.required = true
            html.required = true
            txt.required = true
            sarif.required = true
            md.required = true
            basePath = rootDir.absolutePath
            finalizedBy(detektReportMergeSarif)
        }
    }

    detektReportMergeSarif {
        input.from(tasks.withType<Detekt>().map { it.sarifReportFile })
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://your-server.com/staging"))
            snapshotRepositoryUrl.set(uri("https://your-server.com/snapshots"))
            username.set("your-username") // defaults to project.properties["myNexusUsername"]
            password.set("your-password") // defaults to project.properties["myNexusPassword"]
        }
    }
}