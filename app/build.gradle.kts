import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// google-services plugin is consumed only by the `play` flavor. The JSON
// lives under src/play/ (not app root) so the plugin's per-variant scanner
// only registers a `process<Variant>GoogleServices` task for play* — fdroid
// variants stay clean and don't trip on the package-id mismatch caused by
// our `.fdroid` applicationIdSuffix. The plugin still has to be applied at
// project level, which we gate on the JSON's presence so a fresh checkout
// without it (e.g. the F-Droid buildserver) is buildable end-to-end.
val hasGoogleServices = file("src/play/google-services.json").exists() ||
        file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    // Without this, building the fdroid flavor fails because the plugin
    // walks every variant and demands a google-services.json for each. With
    // the JSON only under src/play/, fdroid* variants now warn instead of
    // erroring; play* variants still pick up the file and process it.
    extensions.configure<com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig> {
        missingGoogleServicesStrategy =
            com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
    }
}

// Pull dev push-server URL out of root-level local.properties so it never
// ends up in VCS. project.findProperty() only reads gradle.properties,
// so do it manually here.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: (project.findProperty(key) as? String) ?: default

android {
    namespace = "org.ntust.app.tigerduck"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.ntust.app.tigerduck"
        minSdk = 29
        targetSdk = 36
        versionCode = 27
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Earliest pin-set expiration across network_security_config.xml.
        // Used by TigerDuckApp to surface a logcat warning when pins are
        // within 30 days of expiry; without a runtime check, post-expiry the
        // platform silently falls back to system CA trust with no UI signal.
        // 2027-01-18T00:00:00Z = 1800230400000L epoch ms.
        buildConfigField("long", "PIN_EXPIRY_EPOCH", "1800230400000L")
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Default to the local backend, NOT production — a debug build must
            // never silently hit api.tigerduck.app (mirrors the iOS Debug
            // resolver, which defaults to localhost). 10.0.2.2 is the Android
            // emulator's host-loopback (the Mac running the backend); physical
            // devices override `pushBaseUrl` in local.properties with the Mac's
            // LAN IP. Cleartext to this host is permitted in network_security_config.
            buildConfigField(
                "String",
                "PUSH_BASE_URL",
                "\"${localProp("pushBaseUrl", "http://10.0.2.2:40000/v3")}\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            val keystoreFile = file("keystore.jks")
            if (keystoreFile.exists() && System.getenv("KEYSTORE_PASSWORD")?.isNotEmpty() == true) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField(
                "String",
                "PUSH_BASE_URL",
                "\"${
                    System.getenv("PUSH_BASE_URL")
                        ?: localProp("pushBaseUrlRelease", "https://api.tigerduck.app/v3")
                }\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Matches :shared. Without it any JVM unit test that reaches a
            // real android.util.Log call throws "not mocked", which would
            // limit migration and cache coverage to log-free code paths.
            isReturnDefaultValues = true
        }
    }

    // Two distribution channels:
    //   * play   — Google Play Store + sideload. Uses FCM for real-time push.
    //   * fdroid — F-Droid. 100% FOSS, no Google Play Services. The
    //              announcements list still works (manual refresh / open-app
    //              poll); push is simply absent. Add UnifiedPush here later
    //              if real-time delivery becomes a requirement.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // No suffix — this is the canonical applicationId.
        }
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            versionNameSuffix = "-fdroid"
        }
    }

    // No `sourceSets { main { assets.directories.add(file("name-abbr")) } }`
    // here, deliberately. That maps the whole submodule in, which ships its
    // README and scraper script to every user and puts its LICENSE on
    // assets/LICENSE — the path the app's own licence is copied to, leaving
    // the merge order to decide which licence the page shows. copyLicenseAssets
    // below copies the two JSONs and both licences under distinct names.

    // Per-app language picker hands users any locale we ship, but Play's
    // default per-language AAB splits only deliver the split matching the
    // device's system locale at install time. Result: picking any other
    // language falls back to values/ (English) because that locale's
    // strings.xml isn't on disk. Bundle every language into the base APK
    // so the picker always has resources to resolve against. The added
    // install size is small — strings.xml only, no per-language assets.
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        resources {
            // angus-mail, jakarta.mail-api, angus-activation and
            // jakarta.activation-api each ship these, and all four differ.
            // Concatenate them: picking one silently drops the other three,
            // which are the notices those projects publish to be
            // redistributed with the code.
            merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}

// Fail fast if the name-abbr submodule wasn't checked out — otherwise the
// app silently ships without abbreviation JSONs (the loader catches the
// FileNotFoundException and returns an empty map). v1.3.2 hit Play Store
// in exactly this state because the release workflows were missing
// `submodules: true` on actions/checkout.
val verifyNameAbbrSubmodule = tasks.register("verifyNameAbbrSubmodule") {
    val nameAbbrDir = rootProject.file("name-abbr")
    // Explicit contract: files the runtime loader requires by name, plus
    // the submodule's own LICENSE, which the licences page shows because
    // this data ships under MIT rather than the app's AGPL. Update this
    // list when CourseService starts loading additional JSONs.
    val requiredFiles = listOf("class-name-abbr.json", "classroom-name-abbr.json", "LICENSE")
    doLast {
        val hint =
            "Run `git submodule update --init` (or pass submodules: true to actions/checkout in CI)."
        val jsonFiles = nameAbbrDir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
        if (jsonFiles.isEmpty()) {
            throw GradleException("name-abbr submodule is empty (no JSON files in $nameAbbrDir). $hint")
        }
        val missing = requiredFiles.filterNot { nameAbbrDir.resolve(it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException("name-abbr submodule is missing required files: $missing. $hint")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(verifyNameAbbrSubmodule) }

// Assets the licences page and the abbreviation loader read at runtime,
// copied on every build so neither can drift from the file it came from.
// Each lands under its own name: two files called LICENSE competing for
// assets/LICENSE is how the app's own licence and name-abbr's used to
// collide, with only the merge order deciding which one the page showed.
abstract class CopyLicenseAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nameAbbrLicense: RegularFileProperty

    /** `class-name-abbr.json` and `classroom-name-abbr.json`, under their own names. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nameAbbrData: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get()
        appLicense.get().asFile.copyTo(out.file("tigerduck-license.txt").asFile, overwrite = true)
        nameAbbrLicense.get().asFile.copyTo(out.file("name-abbr-license.txt").asFile, overwrite = true)
        nameAbbrData.forEach { it.copyTo(out.file(it.name).asFile, overwrite = true) }
    }
}

val copyLicenseAssets = tasks.register<CopyLicenseAssets>("copyLicenseAssets") {
    dependsOn(verifyNameAbbrSubmodule)
    val nameAbbr = rootProject.layout.projectDirectory.dir("name-abbr")
    appLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
    nameAbbrLicense.set(nameAbbr.file("LICENSE"))
    nameAbbrData.from(nameAbbr.file("class-name-abbr.json"), nameAbbr.file("classroom-name-abbr.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseAssets, CopyLicenseAssets::outputDir)
    }
}

// Settings → Others → Open-source licences reads res/raw/aboutlibraries.json,
// one per flavor because play ships Firebase and Play Services and fdroid
// ships neither. Generated from each release variant's dependency graph,
// then committed. Regenerate after changing a dependency:
//   ./gradlew -PexportLicenses :app:exportLibraryDefinitionsPlayRelease :app:exportLibraryDefinitionsFdroidRelease
//
// The plugin is applied only for that command. Applied unconditionally it
// hooks every variant's resource generation, rebuilding the list and
// downloading licence texts on each build; the build itself (the F-Droid
// buildserver's included) should neither reach the network nor come out
// different from one run to the next.
if (providers.gradleProperty("exportLicenses").isPresent) {
    apply(plugin = libs.plugins.aboutlibraries.get().pluginId)
    extensions.configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
        collect {
            // Licence texts the plugin can't find on its own, keyed by the
            // hash it reports for them: Angus Mail's EPL-2.0 and EDL-1.0
            // (published under names the plugin doesn't map to SPDX) and
            // GPL-2.0 with the Classpath Exception (whose SPDX text URL
            // doesn't exist).
            configPath.set(file("aboutlibraries"))
            // BOMs only pin versions; nothing from them ships.
            includePlatform.set(false)
        }
        export {
            prettyPrint.set(true)
        }
        exports {
            create("playRelease") {
                outputFile.set(file("src/play/res/raw/aboutlibraries.json"))
            }
            create("fdroidRelease") {
                outputFile.set(file("src/fdroid/res/raw/aboutlibraries.json"))
            }
        }
    }

    registerBundledNoticesExport("play")
    registerBundledNoticesExport("fdroid")
    // The watch's list lives in the phone's play resources; see
    // wear/build.gradle.kts for why it is shown there rather than on the
    // watch. Only the play flavor gets one — :wear is play-only.
    registerBundledNoticesExport(
        name = "Wear",
        classpath = { project(":wear").configurations.getByName("releaseRuntimeClasspath") },
        libraryList = file("src/play/res/raw/aboutlibraries_wear.json"),
        output = file("src/play/res/raw/bundled_notices_wear.json"),
        exportTask = ":wear:exportLibraryDefinitionsRelease",
        // The watch shares almost every notice with the phone — both ship
        // Play Services. Reference those by the hash the phone's file
        // already carries instead of writing them a second time; the app
        // reads the two documents into one map.
        sharedWith = file("src/play/res/raw/bundled_notices.json"),
    )
}

/**
 * Notices a dependency ships *inside* its own artifact, which a POM-driven
 * list like AboutLibraries' cannot see. Two kinds:
 *
 *  - `META-INF/NOTICE*` and the like. Apache-2.0 section 4(d) requires
 *    these be redistributed; kotlinx.coroutines' arrives this way, inside
 *    androidx.concurrent.
 *  - Google's `third_party_licenses.txt` plus its `.json` offset index,
 *    which is how Play Services and Firebase carry the licences of the
 *    third-party code compiled into those closed SDKs.
 *
 * Both are extracted into `res/raw/bundled_notices.json` and committed, for
 * the same reason the library list is: the build must not reach the network
 * or vary between runs. Texts are stored once and referenced by hash — the
 * Play Services artifacts repeat the same few licences hundreds of times.
 */
fun registerBundledNoticesExport(flavor: String) {
    val variant = "${flavor}Release"
    registerBundledNoticesExport(
        name = variant.replaceFirstChar(Char::uppercase),
        classpath = { configurations.getByName("${variant}RuntimeClasspath") },
        libraryList = file("src/$flavor/res/raw/aboutlibraries.json"),
        output = file("src/$flavor/res/raw/bundled_notices.json"),
        exportTask = "exportLibraryDefinitions${variant.replaceFirstChar(Char::uppercase)}",
    )
}

fun registerBundledNoticesExport(
    name: String,
    // Resolved in the task action: AGP creates the variant configurations
    // long after this script is evaluated.
    classpath: () -> Configuration,
    libraryList: File,
    output: File,
    exportTask: String,
    sharedWith: File? = null,
) {
    val export = tasks.register("exportBundledNotices$name") {
        dependsOn(exportTask)
        // The watch's document references texts by the hashes the phone's
        // already carries, so the phone's has to exist first. A real
        // dependency rather than `mustRunAfter`: `sharedWith` is declared as
        // an input below, and Gradle rejects reading another task's output
        // without one.
        if (sharedWith != null) dependsOn("exportBundledNoticesPlayRelease")
        inputs.file(libraryList).withPathSensitivity(PathSensitivity.NONE)
        sharedWith?.let { inputs.file(it).withPathSensitivity(PathSensitivity.NONE) }
        // Declared so Gradle knows this file is *produced*, not merely
        // present. res/raw feeds merge*Resources, and with no output
        // declaration a single `-PexportLicenses assemble…` invocation could
        // have the merge read this file while this task rewrote it, silently.
        // Now that is a validation error instead of a race.
        outputs.file(output)
        // The classpath is deliberately not a declared input — resolving it
        // at configuration time would cost every build — so this task can
        // never be up to date. That is the safe direction: it re-reads the
        // dependency graph on every run and so cannot quietly go stale.
        outputs.upToDateWhen { false }
        doLast {
            val needsNotice = librariesMissingTheirCopyright(libraryList)
            val alreadyWritten = sharedWith?.let { noticeTextHashes(it) }.orEmpty()
            val texts = sortedMapOf<String, String>()
            val libraries = sortedMapOf<String, MutableList<Map<String, String>>>()
            resolveModuleArchives(classpath()).forEach { (module, archive) ->
                ZipFile(archive).use { zip ->
                    bundledNotices(zip).forEach { (name, text) ->
                        // A bundled copy of the licence the page already
                        // prints in full is noise — AndroidX ships one per
                        // artifact. Keep it only where that printed text is
                        // the SPDX template, whose `<year> <copyright
                        // holders>` line the bundled copy fills in. NOTICE
                        // files are always kept; they exist to be passed on.
                        val isLicenseCopy = name.substringBeforeLast('.').equals("LICENSE", ignoreCase = true) ||
                            name.substringBeforeLast('.').equals("LICENCE", ignoreCase = true)
                        if (isLicenseCopy && module !in needsNotice) return@forEach
                        val hash = MessageDigest.getInstance("SHA-256")
                            .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
                        if (hash !in alreadyWritten) texts[hash] = text
                        libraries.getOrPut(module) { mutableListOf() }
                            .add(mapOf("name" to name, "hash" to hash))
                    }
                }
            }
            output.writeText(
                groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(mapOf("texts" to texts, "libraries" to libraries)),
                ) + "\n",
            )
            logger.lifecycle("$name: ${libraries.size} libraries carry notices, ${texts.size} distinct texts")
        }
    }
    // The plugin creates its export tasks late, so match rather than name:
    // one `exportLibraryDefinitions…` command refreshes both files.
    val owner = if (exportTask.startsWith(":")) project(exportTask.substringBeforeLast(':')) else project
    owner.tasks.matching { it.name == exportTask.substringAfterLast(':') }
        .configureEach { finalizedBy(export) }
}

/** The hashes a previously written notices document already carries. */
@Suppress("UNCHECKED_CAST")
fun noticeTextHashes(document: File): Set<String> =
    if (!document.isFile) {
        emptySet()
    } else {
        ((groovy.json.JsonSlurper().parse(document) as Map<String, Any>)["texts"] as Map<String, String>).keys
    }

/** Every resolved module's own `.aar`/`.jar`, not the `classes.jar` AGP transforms it into. */
fun resolveModuleArchives(classpath: Configuration): Map<String, File> {
    fun view(type: String) = classpath.incoming.artifactView {
        isLenient = true
        attributes.attribute(Attribute.of("artifactType", String::class.java), type)
    }.artifacts
    val aar = view("aar")
    val jar = view("jar")
    // Leniency is required: the `aar` view has nothing to offer a pure-JAR
    // module, and project(":shared") cannot be materialised here. But it
    // also swallows genuine failures — a corrupted entry in the module
    // cache, a transient repository error, a failed artifact transform —
    // and the only symptom would be a *shorter* notices file written by a
    // green build. A notice dropped that way is one the app is legally
    // obliged to reproduce, so fail loudly rather than write it short.
    val failures = aar.failures + jar.failures
    if (failures.isNotEmpty()) {
        throw GradleException(
            "${classpath.name}: ${failures.size} artifact(s) could not be resolved, so the " +
                "notices would be written incomplete:\n" +
                failures.joinToString("\n") { "  ${it.message}" },
        )
    }
    val archives = linkedMapOf<String, File>()
    (aar.artifacts + jar.artifacts).forEach { artifact ->
        val id = artifact.id.componentIdentifier
        if (id is ModuleComponentIdentifier && artifact.file.name != "classes.jar") {
            archives.putIfAbsent("${id.group}:${id.module}", artifact.file)
        }
    }
    return archives
}

/**
 * Libraries whose licence text reaches the page as the SPDX template, with
 * the copyright line left as `<year> <copyright holders>` — MIT and the BSD
 * family. Both licences require that the real notice be reproduced, and the
 * only place it exists is inside the artifact.
 */
@Suppress("UNCHECKED_CAST")
fun librariesMissingTheirCopyright(libraryList: File): Set<String> {
    val placeholder = Regex("""<(year|copyright holders?|owner)>|\[(year|fullname)]""", RegexOption.IGNORE_CASE)
    val parsed = groovy.json.JsonSlurper().parse(libraryList) as Map<String, Any>
    val templated = (parsed["licenses"] as Map<String, Map<String, Any>>)
        .filterValues { placeholder.containsMatchIn((it["content"] as String?).orEmpty()) }
        .keys
    return (parsed["libraries"] as List<Map<String, Any>>)
        .filter { library -> (library["licenses"] as? List<String>).orEmpty().any { it in templated } }
        .map { it["uniqueId"] as String }
        .toSet()
}

// The extensions are listed rather than `\w+` so that a class named after a
// licence — `License.class`, `Notice.class` — is not read as one. Only the
// nested `classes.jar` inside an AAR is filtered out by name; a plain JAR is
// walked whole, bytecode and all.
private val legalFileName =
    Regex(
        """^(LICEN[CS]E|NOTICE|COPYING|THIRD[-_]?PARTY[-_]?NOTICES)(\.(txt|md|html?|rst))?$""",
        RegexOption.IGNORE_CASE,
    )

/** `name to text` for every notice bundled in one artifact. */
@Suppress("UNCHECKED_CAST")
fun bundledNotices(zip: ZipFile): List<Pair<String, String>> {
    val index = zip.getEntry("third_party_licenses.json")
    val blob = zip.getEntry("third_party_licenses.txt")
    if (index != null && blob != null) {
        val bytes = zip.getInputStream(blob).use { it.readBytes() }
        val entries = zip.getInputStream(index).use {
            groovy.json.JsonSlurper().parse(it) as Map<String, Map<String, Int>>
        }
        return entries.entries.sortedBy { it.key }.mapNotNull { (name, at) ->
            val start = at["start"] ?: return@mapNotNull null
            val length = at["length"] ?: return@mapNotNull null
            val text = String(bytes, start, length, Charsets.UTF_8).trim()
            if (text.isEmpty()) null else name to text
        }
    }
    return zip.entries().toList()
        .filter { !it.isDirectory && legalFileName.matches(it.name.substringAfterLast('/')) }
        .sortedBy { it.name }
        .mapNotNull { entry ->
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            if (text.isEmpty()) null else entry.name.substringAfterLast('/') to text
        }
}

dependencies {
    implementation(project(":shared"))
    "playImplementation"(libs.play.services.wearable)
    "playImplementation"(libs.play.app.update.ktx)
    // WearScheduleBridge / WearDebugClockBridge use kotlinx.coroutines.tasks.await();
    // declare it directly instead of leaning on firebase-messaging's transitive edge.
    "playImplementation"(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.jsoup)

    // School Mail — IMAP/SMTP. Used under its GPL-2.0 with Classpath
    // Exception option, which is what makes it compatible with AGPL-3.0.
    implementation(libs.angus.mail)

    // Security
    implementation(libs.security.crypto)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // QR Code
    implementation(libs.zxing.core)

    // Firebase Cloud Messaging — confined to the `play` flavor so the
    // F-Droid APK contains zero Google Play Services code. FcmService.kt
    // and FcmBootstrap.kt live under src/play/ and are not compiled when
    // building fdroid* variants.
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.messaging)
    "playImplementation"(libs.firebase.analytics)

    // In-app browser (Custom Tabs)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.appcompat)

    // Background work scheduling
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Glance (home screen widgets)
    implementation(libs.glance.appwidget)

    // Markdown rendering for announcement bodies
    implementation(libs.markdown.renderer.m3)

    // DataStore Preferences (server-push popup dedupe set)
    implementation(libs.androidx.datastore.preferences)

    // Reads the generated licence list for Open-source licences
    implementation(libs.aboutlibraries.core)

    // Testing
    testImplementation(libs.junit)
    // Android's bundled org.json classes are stubbed out (return null / no-op)
    // under the default JVM unit-test runner — see SettingsDocumentApiClientTest's
    // KDoc. The real reference implementation shares the org.json.* package name
    // and shadows the stub on the unit-test classpath.
    testImplementation(libs.json)
    // Virtual time for NotificationSettingsSync's push queue, whose debounce
    // and retry backoffs are real delays (250 ms, 5 s, 30 s).
    testImplementation(libs.kotlinx.coroutines.test)
    // In-memory IMAP/SMTP server for the mail tests. Its own bundled
    // org.eclipse.angus:jakarta.mail would duplicate angus-mail's classes.
    testImplementation(libs.greenmail) {
        exclude(group = "org.eclipse.angus", module = "jakarta.mail")
    }
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

val syncLocalizations = tasks.register<Exec>("syncLocalizations") {
    group = "localization"
    description = "Generate Android localization files from shared JSON sources."
    workingDir = rootProject.projectDir
    // Placeholder; the real interpreter is resolved in doFirst so detection
    // happens at execution time, not project sync.
    commandLine("python3", "tools/localization/sync_localizations.py")
    doFirst {
        val script = "tools/localization/sync_localizations.py"
        // Probe the common Python 3 launchers across Linux / macOS / Windows.
        val python = listOf("python3", "python", "py").firstOrNull { candidate ->
            runCatching {
                val proc = ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                proc.waitFor() == 0 && output.contains("Python 3")
            }.getOrDefault(false)
        } ?: throw GradleException(
            "syncLocalizations requires Python 3 on PATH (tried python3, python, py). " +
                    "Install Python 3 from https://www.python.org/ and re-run."
        )
        commandLine(python, script)
    }
}

val copyGeneratedAndroidLocalizations = tasks.register<Copy>("copyGeneratedAndroidLocalizations") {
    group = "localization"
    description = "Copy app-translation/generated/android values-* resources into app/src/main/res."

    // Ensure the generator ran first.
    dependsOn(syncLocalizations)

    val sourceDir = rootProject.layout.projectDirectory.dir("app-translation/generated/android")
    val destDir = layout.projectDirectory.dir("src/main/res")

    // Only copy valid Android resource qualifier directories.
    from(sourceDir) {
        include("values*/strings.xml")
        include("values-b+*/strings.xml")
    }

    into(destDir)
    includeEmptyDirs = false

    // Atomically clear stale generated files just before copying. If syncLocalizations
    // fails, this doFirst never runs, so committed locale files remain on disk.
    doFirst {
        val resDir = destDir.asFile
        fileTree(resDir) {
            include("values*/strings.xml")
            include("values-b+*/strings.xml")
        }.forEach { it.delete() }

        resDir.listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("values-") || it.name.startsWith("values-b+")) }
            ?.forEach { dir ->
                val remaining = dir.listFiles()
                if (remaining == null || remaining.isEmpty()) {
                    dir.delete()
                }
            }
    }
}

if (providers.gradleProperty("syncLocalizations").isPresent) {
    tasks.named("preBuild") {
        dependsOn(syncLocalizations)
        dependsOn(copyGeneratedAndroidLocalizations)
    }
}
