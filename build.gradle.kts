plugins {
    kotlin("jvm") version "1.6.10"
    id("application")
    id("java")
    id("idea")

    id("org.graalvm.buildtools.native") version "0.9.12"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("com.github.jk1.dependency-license-report") version "2.0"
}

tasks.withType<JavaCompile> {
    // Avoids "Spinner.java:24: error: unmappable character (0x81) for encoding windows-1252" errors on Windows
    options.encoding = "UTF-8"
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

val javaLanguageVersion = 17

tasks.distZip { enabled = false }
tasks.distTar { enabled = false }
tasks.shadowDistZip { enabled = false }
tasks.shadowDistTar { enabled = false }

// Required for shadow plugin
application.mainClassName = "com.awslabs.superfluid.App"

// Only run shadow plugin if the user explicitly requests it
// From: https://github.com/johnrengelman/shadow/issues/644#issuecomment-977629081
val shadowJarEnabled = gradle.startParameter.taskRequests
    .find { it.args.find { it.contains("shadowJar") } != null } != null

if (!shadowJarEnabled) {
    gradle.startParameter.excludedTaskNames += "shadowJar"
}

fun version(): String {
    val RELEASE_VERSION = "RELEASE_VERSION"
    if (!System.getenv().containsKey(RELEASE_VERSION)) {
        println("No release version")
        return ""
    }

    val releaseVersion = System.getenv(RELEASE_VERSION)
    println("Release version $releaseVersion")
    return releaseVersion
}
version = version()

graalvmNative {
    binaries {
        named("main") {
            // Fixes "Fatal error: Unsupported OptionOrigin" build issues on Windows
            useArgFile.set(false)
            imageName.set("superfluid")
            mainClass.set("com.awslabs.superfluid.App")
            fallback.set(false)
            sharedLibrary.set(false)
            useFatJar.set(true)
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(javaLanguageVersion))
                vendor.set(JvmVendorSpec.matching("GraalVM Community"))
            })
            buildArgs.add("-H:ClassInitialization=org.slf4j:build_time")
            buildArgs.add("-H:EnableURLProtocols=https,http")
            // Prevents warning: Warning: class initialization of class io.netty.util.internal.logging.Log4JLogger failed with exception java.lang.NoClassDefFoundError: org/apache/log4j/Priority
            buildArgs.add("--initialize-at-run-time=io.netty.util.internal.logging.Log4JLogger")
            // Uncomment for faster builds while testing
            // buildArgs.add("-Ob")
        }
    }
}

idea.module.isDownloadSources = true
idea.module.isDownloadJavadoc = true

java.toolchain.languageVersion.set(JavaLanguageVersion.of(javaLanguageVersion))

val gradleDependencyVersion = "7.4.1"

tasks.wrapper {
    gradleVersion = gradleDependencyVersion
    distributionType = Wrapper.DistributionType.ALL
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
    maven(url = "https://repo.gradle.org/gradle/libs-releases-local/")
}

val slf4jVersion = "1.7.36"
val awsSdk2Version = "2.17.209"
val vavrVersion = "0.10.4"
val picoCliVersion = "4.6.3"
val picoCliJansiVersion = "1.2.0"
val commonsLang3 = "3.12.0"
val immutablesValueVersion = "2.9.0"
val jcabiVersion = "0.20.1"

dependencies {
    // Prevents "java.lang.ClassNotFoundException: org.apache.commons.logging.impl.LogFactoryImpl"
    implementation("org.slf4j:jcl-over-slf4j:$slf4jVersion")

    implementation("org.slf4j:slf4j-simple:$slf4jVersion")
    implementation("io.vavr:vavr:$vavrVersion")
    implementation("com.google.code.gson:gson:2.9.0")

    // AWS v2 SDKs
    implementation("software.amazon.awssdk:ec2:$awsSdk2Version")
    implementation("software.amazon.awssdk:iam:$awsSdk2Version")
    implementation("software.amazon.awssdk:greengrassv2:$awsSdk2Version")
    implementation("software.amazon.awssdk:sts:$awsSdk2Version")
    implementation("software.amazon.awssdk:s3:$awsSdk2Version")
    implementation("software.amazon.awssdk:iot:$awsSdk2Version")
    implementation("software.amazon.awssdk.iotdevicesdk:aws-iot-device-sdk:1.9.2")

    // Object mapper
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.3")

    // SSH
    implementation("com.jcraft:jsch:0.1.55")

    implementation("software.amazon.awssdk:xray:$awsSdk2Version")

    implementation("net.jodah:failsafe:2.4.4")

    implementation("info.picocli:picocli:$picoCliVersion")
    annotationProcessor("info.picocli:picocli-codegen:$picoCliVersion")
    implementation("info.picocli:picocli-jansi-graalvm:$picoCliJansiVersion")

    // For SystemUtils
    implementation("org.apache.commons:commons-lang3:$commonsLang3")

    annotationProcessor("org.immutables:value:$immutablesValueVersion")
    api("org.immutables:value:$immutablesValueVersion")

    testImplementation("junit:junit:4.13.2")
}

tasks.register<Copy>("copyDependencies") {
    from(configurations.getByName("default"))
    into("dependencies")
    // With this we can quickly execute code on the command-line without building a shadow JAR like this:
    //   java -cp "build/classes/java/main:dependencies/*" com.awslabs.package.ClassToRun
}

tasks.register("copyJniLibs") {
    dependsOn("copyDependencies")

    // This filter is used to find the binaries in the CRT JAR. Add additional extensions or conditions as needed.
    val binaryFilter: (File) -> Boolean =
        { it.name.endsWith(".so") || it.name.endsWith(".dylib") || it.name.endsWith(".dll") }

    // Find the JAR file for aws-crt and get a File object that represents it
    val awsCrtJar = configurations.getByName("default")
        .resolvedConfiguration
        .resolvedArtifacts
        .filter { it.moduleVersion.id.toString().contains("software.amazon.awssdk.crt") }
        .filter { it.moduleVersion.id.toString().contains("aws-crt") }
        .map { it.file }
        .first()

    val resourcesDirectory = layout.buildDirectory.dir("resources").get()
    val resourcesPath = resourcesDirectory.asFile.toPath()

    // Let Gradle know what files we're going to be outputting so that it can correctly optimize the build process
    // See https://discuss.gradle.org/t/gradle-7-0-seems-to-take-an-overzealous-approach-to-inter-task-dependencies/39656/2 for details on why we need this
    // NOTE: We resolve the path on our own here so there's a chance that this code and the copy code could resolve to
    //         different paths.
    outputs.files(zipTree(awsCrtJar).files
        .filter { binaryFilter(it) }
        .map { resourcesPath.resolve(it.name) }
    )

    doLast {
        copy {
            // Find all the binaries in the AWS CRT JAR and copy them into the resources directory
            from({ zipTree(awsCrtJar) }) {
                include { binaryFilter(it.file) }
                // If this isn't included the resources directory ends up with a lot of empty directories
                includeEmptyDirs = false
            }
            into(resourcesDirectory)
        }
    }
}

tasks.build {
    // Copy the dependencies into a dependencies directory, so we can easily execute code on the command-line without building a shadow JAR
    dependsOn("copyDependencies")
    // Make sure the build task depends on this task so the binaries get copied before nativeCompile runs
    dependsOn("copyJniLibs")
}

tasks.create<Delete>("cleanDependencies") {
    delete("dependencies")
}

tasks.clean {
    dependsOn("cleanDependencies")
}
