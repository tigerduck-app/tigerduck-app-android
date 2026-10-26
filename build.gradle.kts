// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.io.File
import java.nio.file.Paths

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

// F-Droid buildserver runs `unset CI` before building, so also check for /home/vagrant
val isCI = System.getenv("CI") != null || System.getProperty("user.home") == "/home/vagrant"
if (!isCI) {
    // One directory per checkout, at the checkout's own path below the home
    // directory: worktrees sharing one overwrote each other's generated code,
    // and a branch then compiled against another branch's Hilt bindings. The
    // path itself, not a name made from it, so no two checkouts share one.
    // debug/_lib.sh finds it the same way.
    val home = File(System.getProperty("user.home")).canonicalFile.toPath()
    val checkout = rootDir.canonicalFile.toPath()
    val checkoutKey = if (checkout.startsWith(home)) {
        home.relativize(checkout)
    } else {
        // Outside it, the whole path less its root, keeping a drive's letter.
        // Never absolute: resolve() would return an absolute path as it is,
        // and each module's build directory would land on its own sources.
        val drive = checkout.root.toString().filter(Char::isLetterOrDigit)
        checkout.root.relativize(checkout).let { if (drive.isEmpty()) it else Paths.get(drive).resolve(it) }
    }
    val localBuildRoot = home.resolve(".tigerduck-build/checkouts").resolve(checkoutKey).toFile()
    rootProject.layout.buildDirectory.set(localBuildRoot.resolve(rootProject.name))
    // By path rather than name, which is only the last part of it: :core:strings
    // lands in core/strings, and no two modules of one name can meet in a directory.
    subprojects {
        layout.buildDirectory.set(localBuildRoot.resolve(path.removePrefix(":").replace(':', '/')))
    }
}

