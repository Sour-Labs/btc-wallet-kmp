import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.sourlabs.btc"
version = "0.1.0"

kotlin {
    jvm()
    androidLibrary {
        namespace = "io.sourlabs.btc.wallet.library"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11
                )
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation(libs.acinq.bitcoin.kmp)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "library", version.toString())

    pom {
        name = "Bitcoin Wallet KMP Library"
        description = "Multiplatform library to manage a Bitcoin wallet."
        inceptionYear = "2026"
        url = "https://github.com/Sour-Labs/btc-wallet-kmp"
        licenses {
            license {
                name = "Apache License v2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        issueManagement {
            system.set("Github")
            url.set("https://github.com/Sour-Labs/btc-wallet-kmp/issues")
        }
        developers {
            developer {
                name = "Sour Labs"
                email.set("hello@sourlabs.io")
            }
        }
        scm {
            url = "https://github.com/Sour-Labs/btc-wallet-kmp"
            connection = "https://github.com/Sour-Labs/btc-wallet-kmp.git"
        }
    }
}
