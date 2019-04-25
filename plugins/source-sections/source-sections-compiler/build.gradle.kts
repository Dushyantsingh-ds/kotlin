
description = "Kotlin SourceSections Compiler Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}


dependencies {
    compileOnly(project(":compiler:frontend"))
    compileOnly(project(":compiler:plugin-api"))
    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testCompile(project(":compiler:frontend"))
    testCompile(project(":compiler:plugin-api"))
    testCompile(project(":compiler:util"))
    testCompile(project(":compiler:cli"))
    testCompile(project(":compiler:cli-common"))
    testCompile(project(":compiler:frontend.java"))
    testCompile(project(":compiler:daemon-common"))
    testCompile(projectRuntimeJar(":kotlin-daemon-client"))
    testCompile(projectTests(":compiler:tests-common"))
    testCompile(commonDep("junit:junit"))
    testCompileOnly(intellijCoreDep()) { includeJars("intellij-core") }
    testCompile(intellijDep()) { includeJars("idea", "idea_rt", "openapi", "log4j", "jdom", "jps-model") }
    testRuntime(kotlinReflect())
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

projectTest(parallel = true) {
    workingDir = rootDir
}

publish()

runtimeJar()
sourcesJar()
javadocJar()

dist()
