import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlinx.serialization)
}

group = "io.sourlabs.btc"
version = "0.1.2"

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
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.acinq.bitcoin.kmp)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.acinq.secp256k1.kmp)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.acinq.secp256k1.jni.jvm)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.jvm)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.acinq.secp256k1.jni.android)
            }
        }

        // Android host tests run on the JVM, so they need the JVM JNI library
        val androidHostTest by getting {
            dependencies {
                implementation(libs.acinq.secp256k1.jni.jvm)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        val iosX64Main by getting {
            dependsOn(iosMain)
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }

        val linuxMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        val linuxX64Main by getting {
            dependsOn(linuxMain)
        }

        val linuxArm64Main by getting {
            dependsOn(linuxMain)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    // TODO: Enable this line when created signing for publishing
    //signAllPublications()

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
