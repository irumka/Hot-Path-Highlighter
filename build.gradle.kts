plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        local("/home/irumi/opt/clion-2026.2.2")
    }
}

intellijPlatform {
    instrumentCode = false
}