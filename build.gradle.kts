import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    group = "com.github.onyou668.video-mobile-player"
    version = providers.gradleProperty("version").orNull
        ?: System.getenv("VERSION")
        ?: "1.0.0"

    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("maven-publish")

        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        artifactId = project.name
                    }
                }
            }
        }
    }
}
