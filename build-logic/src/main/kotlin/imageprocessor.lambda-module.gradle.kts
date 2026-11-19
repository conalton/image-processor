plugins {
    id("imageprocessor.kotlin-module")
}

val lambdaZip = tasks.register<Zip>("lambdaZip") {
    from(sourceSets.main.get().output)
    into("lib") {
        from(configurations.runtimeClasspath)
    }
}

tasks.assemble {
    dependsOn(lambdaZip)
}
