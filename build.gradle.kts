// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

if (System.getenv("CI") == null) {
    val localBuildRoot = file("${System.getProperty("user.home")}/.tigerduck-build")
    rootProject.layout.buildDirectory.set(localBuildRoot.resolve(rootProject.name))
    subprojects {
        layout.buildDirectory.set(localBuildRoot.resolve(name))
    }
}

