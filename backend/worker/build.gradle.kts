plugins {
    id("imageprocessor.lambda-module")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.lambda.core)
    implementation(libs.lambda.events)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.metadata.extractor)
    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(project(":shared")))
}

tasks.test {
    // ImageDecoderTest's zTXt case catches a regression only as an out-of-memory error at this heap size.
    maxHeapSize = "512m"
}
