import nebula.plugin.contacts.Contact
import nebula.plugin.contacts.ContactsExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    `maven-publish`
    signing

    id("org.jetbrains.kotlin.jvm") version "1.5.10"
    id("nebula.maven-resolved-dependencies") version "17.3.2"
    id("nebula.release") version "15.3.1"
    id("com.google.cloud.artifactregistry.gradle-plugin") version "2.1.1"
    id("io.github.gradle-nexus.publish-plugin") version "1.0.0"

    id("nebula.maven-shadow-publish") version ("18.2.0")
    id("com.github.johnrengelman.shadow") version ("7.1.2")

    id("org.owasp.dependencycheck") version "latest.release"

    id("nebula.maven-publish") version "17.3.2"
    id("nebula.contacts") version "5.1.0"
    id("nebula.info") version "9.3.0"

    id("nebula.javadoc-jar") version "17.3.2"
    id("nebula.source-jar") version "17.3.2"
    id("nebula.maven-apache-license") version "17.3.2"

    id("org.openrewrite.rewrite") version "latest.release"
}

apply(plugin = "nebula.publish-verification")

rewrite {
    activeRecipe("org.openrewrite.java.format.AutoFormat", "org.openrewrite.java.cleanup.Cleanup")
}

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

dependencyCheck {
    analyzers.assemblyEnabled = false
    failBuildOnCVSS = 9.0F
}

group = "io.moderne"

repositories {
    if (!project.hasProperty("releasing")) {
        mavenLocal()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
    }
    maven {
        name = "gcp"
        url = uri("artifactregistry://us-west1-maven.pkg.dev/moderne-dev/moderne-releases")
    }
    mavenCentral()
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

signing {
    setRequired({
        !project.version.toString().endsWith("SNAPSHOT") || project.hasProperty("forceSigning")
    })
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["nebula"])
}

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
        eachDependency {
            if (requested.group == "org.openrewrite") {
                useVersion(latest)
                because("I said so")
            }
        }
    }
}

val latest = if (project.hasProperty("releasing")) {
    "latest.release"
} else {
    "latest.integration"
}

val astWrite = configurations.create("astwrite")

dependencies {
    "astwrite"("io.moderne:moderne-ast-write:$latest:obfuscated")
    compileOnly("io.moderne:moderne-ast-write:$latest")

    implementation("org.openrewrite:rewrite-test:$latest")
    implementation("com.squareup.okhttp3:okhttp:latest.release")
    implementation("org.assertj:assertj-core:latest.release")

    // eliminates "unknown enum constant DeprecationLevel.WARNING" warnings from the build log
    // see https://github.com/gradle/kotlin-dsl-samples/issues/1301 for why (okhttp is leaking parts of kotlin stdlib)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")

    testRuntimeOnly("org.openrewrite:rewrite-java-11:$latest")
    testRuntimeOnly("org.openrewrite:rewrite-java-8:$latest")
}

tasks.withType(KotlinCompile::class.java).configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames")
}

tasks.named<JavaCompile>("compileJava") {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()

    options.isFork = true
    options.compilerArgs.addAll(listOf("--release", "8"))
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

configure<ContactsExtension> {
    val j = Contact("team@moderne.io")
    j.moniker("Moderne")

    people["team@moderne.io"] = j
}

configure<PublishingExtension> {
    publications {
        named("nebula", MavenPublication::class.java) {
            suppressPomMetadataWarningsFor("runtimeElements")
        }
    }
}

val shadowJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(astWrite)
    archiveClassifier.set(null as String?)
    dependencies {
        include(dependency("io.moderne:moderne-ast-write"))
    }
}

tasks.named<Jar>("jar") {
    finalizedBy(tasks.named("shadowJar"))
}
