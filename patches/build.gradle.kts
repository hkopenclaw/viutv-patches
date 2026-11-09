import org.jetbrains.kotlin.gradle.dsl.JvmTarget

group = "app.morphe"

patches {
    about {
        name = "ViuTV Patches"
        description = "Morphe patches for ViuTV (Picture-in-Picture support)."
        // 記得改做你自己個 repo 嘅 URL。
        source = "https://github.com/hkopenclaw/viutv-patches"
        author = "hkopenclaw"
        contact = "https://github.com/hkopenclaw"
        website = "https://morphe.software/add-source?github=hkopenclaw/viutv-patches"
        license = "GNU General Public License v3.0"
    }
}

// Resolve local composite build projects (smali, patcher) to jars for compilation,
// same as the runtime classpath. Otherwise, both the classes directory and the jar
// are passed to D8 by buildAndroid, and it fails with "Classpath type already present".
configurations.compileClasspath {
    attributes {
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}
