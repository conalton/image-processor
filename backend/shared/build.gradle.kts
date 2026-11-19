plugins {
    id("imageprocessor.kotlin-module")
    `java-test-fixtures`
}

val fabrikt = configurations.create("fabrikt")

val openApiSpec = rootProject.layout.projectDirectory.file("contract/openapi.yaml")
val contractModels = layout.buildDirectory.dir("generated/fabrikt")

val generateContractModels = tasks.register<JavaExec>("generateContractModels") {
    classpath = fabrikt
    mainClass = "io.fabrikt.cli.CodeGen"
    inputs.file(openApiSpec)
    outputs.dir(contractModels)
    // Fabrikt only writes files: a schema removed from the spec would leave its class behind.
    val outputDirectory = contractModels
    doFirst { outputDirectory.get().asFile.deleteRecursively() }
    args(
        "--api-file", openApiSpec.asFile.absolutePath,
        "--output-directory", contractModels.get().asFile.absolutePath,
        "--base-package", "imageprocessor.shared.contract",
        "--targets", "HTTP_MODELS",
        "--serialization-library", "KOTLINX_SERIALIZATION",
        "--validation-library", "JAKARTA_VALIDATION",
        "--instant-library", "KOTLIN_TIME_INSTANT",
        "--type-overrides", "UUID_AS_STRING",
        "--type-overrides", "URI_AS_STRING",
        "--http-model-opts", "NON_NULL_MAP_VALUES",
    )
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(generateContractModels.map { contractModels.get().dir("src/main/kotlin") })
    }
}

// Only the main compilation: the test fixtures do not have kotlinx.serialization, whose marker the opt-in names.
tasks.compileKotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.sdk.dynamodb)
    api(libs.aws.sdk.s3)
    api(libs.aws.sdk.url.connection.client)
    // For the Jakarta annotations on the generated models, in the version Hibernate Validator brings. Only the API
    // validates them and has it at run time, so the worker's zip carries neither.
    compileOnly(libs.hibernate.validator)
    runtimeOnly(libs.slf4j.simple)
    fabrikt(libs.fabrikt)
    testImplementation(libs.kotlin.test)
}
