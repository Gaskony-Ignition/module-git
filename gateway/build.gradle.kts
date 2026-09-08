plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":common"))

    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")

    // Jython is the platform's and must NEVER be shipped: a second copy would not be the one
    // running project scripts, so the automation payload would be built from foreign PyObject
    // classes. compileOnly, at or below what the gateway bundles (8.3.x ships jython-ia 2.7.4.x).
    compileOnly("org.python:jython-ia:2.7.3.5")

    modlImplementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:6.10.1.202505221210-r")

    // Bundles the Config Versioning React page (built by :web-ui) into the gateway jar under mounted/.
    modlImplementation(project(":web-ui"))
}
