plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

group = "dev.hotpath"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val defaultLocalPath = "/home/irumi/opt/clion-2026.2.2"
        val customLocalPath = providers.gradleProperty("clionLocalPath")
            .orNull ?: System.getenv("CLION_HOME")

        val localPath = when {
            customLocalPath != null && file(customLocalPath).exists() -> customLocalPath
            file(defaultLocalPath).exists() -> defaultLocalPath
            else -> null
        }

        if (localPath != null) {
            // Использует вашу готовую локальную установку CLion (мгновенный запуск без скачивания)
            local(localPath)
        } else {
            // Для сборки на других машинах / в CI
            intellijIdeaCommunity("2024.3.3")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "HFT Memory Optimizer"
        version = project.version.toString()
    }
    instrumentCode = false
}