package com.soywiz.korge.gradle.targets.android

import com.android.build.gradle.internal.dsl.*
import com.android.builder.core.*
import com.soywiz.korge.gradle.*
import com.soywiz.korge.gradle.targets.*
import com.soywiz.korge.gradle.util.*
import org.gradle.api.*

fun Project.configureAndroidDirect() {
    project.ensureAndroidLocalPropertiesWithSdkDir()

    project.plugins.apply("com.android.application")

    val android = project.extensions.getByType(BaseAppModuleExtension::class.java)
    //val android = project.extensions.getByName("android")

    project.kotlin.android()

    project.afterEvaluate {
        //println("@TODO: Info is not generated")
        writeAndroidManifest(project.rootDir, project.korge, AndroidInfo(null))
    }

    android.apply {
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        // @TODO: Android Build Gradle newer version
        //installation {
        //    installOptions = listOf("-r")
        //    timeOutInMs = project.korge.androidTimeoutMs
        //}
        packagingOptions {
            for (pattern in project.korge.androidExcludePatterns) {
                resources.excludes.add(pattern)
            }
        }
        compileSdk = project.korge.androidCompileSdk
        defaultConfig {
            multiDexEnabled = true
            applicationId = project.korge.id
            minSdk = project.korge.androidMinSdk
            targetSdk = project.korge.androidTargetSdk
            versionCode = project.korge.versionCode
            versionName = project.korge.version
            testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
            //val manifestPlaceholdersStr = korge.configs.map { it.key + ":" + it.value.quoted }.joinToString(", ")
            //manifestPlaceholders = if (manifestPlaceholdersStr.isEmpty()) "[:]" else "[$manifestPlaceholdersStr]" }
        }
        signingConfigs {
            maybeCreate("release").apply {
                storeFile = project.file(project.findProperty("RELEASE_STORE_FILE") ?: korge.androidReleaseSignStoreFile)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD")?.toString() ?: korge.androidReleaseSignStorePassword
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS")?.toString() ?: korge.androidReleaseSignKeyAlias
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD")?.toString() ?: korge.androidReleaseSignKeyPassword
            }
        }
        buildTypes {
            maybeCreate("debug").apply {
                isMinifyEnabled = false
                signingConfig = signingConfigs.getByName("release")
            }
            maybeCreate("release").apply {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                signingConfig = signingConfigs.getByName("release")
            }
        }
        sourceSets {
            maybeCreate("main").apply {
                val (resourcesSrcDirs, kotlinSrcDirs) = androidGetResourcesFolders()
                //println("@ANDROID_DIRECT:")
                //println(resourcesSrcDirs.joinToString("\n"))
                //println(kotlinSrcDirs.joinToString("\n"))
                assets.srcDirs(*resourcesSrcDirs.map { it.absoluteFile }.toTypedArray())
                java.srcDirs(*kotlinSrcDirs.map { it.absoluteFile }.toTypedArray())
            }
        }
    }
    project.dependencies.apply {
        add("implementation", project.fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
        add("implementation", "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${BuildVersions.KOTLIN}")
        add("implementation", "com.android.support:multidex:1.0.3")

        //line("api 'org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion'")
        project.afterEvaluate {
            //run {

            val resolvedArtifacts = LinkedHashMap<String, String>()

            project.configurations.all {
                resolutionStrategy.eachDependency {
                    val cleanFullName = "${requested.group}:${requested.name}".removeSuffix("-js").removeSuffix("-jvm")
                    //println("RESOLVE ARTIFACT: ${it.requested}")
                    //if (cleanFullName.startsWith("org.jetbrains.intellij.deps:trove4j")) return@eachDependency
                    //if (cleanFullName.startsWith("org.jetbrains:annotations")) return@eachDependency
                    if (isKorlibsDependency(cleanFullName)) {
                        resolvedArtifacts[cleanFullName] = requested.version.toString()
                    }
                }
            }

            for ((name, version) in resolvedArtifacts) {
                if (name.startsWith("org.jetbrains.kotlin")) continue
                if (name.contains("-metadata")) continue
                //if (name.startsWith("com.soywiz.korlibs.krypto:krypto")) continue
                if (name.startsWith("com.soywiz.korlibs.korge2:korge")) {
                    add("implementation", "$name-android:$version")
                }
            }

            for (dependency in korge.plugins.pluginExts.getAndroidDependencies()) {
                add("implementation", dependency)
            }
        }

        add("implementation", "com.android.support:appcompat-v7:28.0.0")
        add("implementation", "com.android.support.constraint:constraint-layout:1.1.3")
        add("testImplementation", "junit:junit:4.12")
        add("androidTestImplementation", "com.android.support.test:runner:1.0.2")
        add("androidTestImplementation", "com.android.support.test.espresso:espresso-core:3.0.2")
        //line("implementation 'com.android.support:appcompat-v7:28.0.0'")
        //line("implementation 'com.android.support.constraint:constraint-layout:1.1.3'")
        //line("testImplementation 'junit:junit:4.12'")
        //line("androidTestImplementation 'com.android.support.test:runner:1.0.2'")
        //line("androidTestImplementation 'com.android.support.test.espresso:espresso-core:3.0.2'")
    }

    installAndroidRun(listOf(), direct = true)

    //println("android: ${android::class.java}")
    /*
    line("defaultConfig") {
    }
    */
    //project.plugins.apply("kotlin-android")
    //project.plugins.apply("kotlin-android-extensions")
    //for (res in project.getResourcesFolders()) println("- $res")
}
