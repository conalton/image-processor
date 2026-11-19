plugins {
    id("imageprocessor.lambda-module")
}

val openApiSpec = rootProject.layout.projectDirectory.file("contract/openapi.yaml")

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.lambda.core)
    implementation(libs.lambda.events)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hibernate.validator)
    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(project(":shared")))
    testImplementation(libs.openapi.request.validator)
    testImplementation(libs.swagger.parser)
    testImplementation(libs.swagger.core)
}

tasks.test {
    inputs.file(openApiSpec)
    systemProperty("openapi.spec", openApiSpec.asFile.absolutePath)
}
