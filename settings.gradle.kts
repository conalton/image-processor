pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "image-processor"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("shared", "api", "worker")

// The Gradle root stays at the repository root for IDE import; the modules live in backend/.
project(":shared").projectDir = file("backend/shared")
project(":api").projectDir = file("backend/api")
project(":worker").projectDir = file("backend/worker")
