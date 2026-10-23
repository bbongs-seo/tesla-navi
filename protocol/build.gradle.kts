plugins {
    kotlin("jvm")
    id("com.google.protobuf")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks { all().forEach { it.builtins { named("java") { option("lite") } } } }
}
