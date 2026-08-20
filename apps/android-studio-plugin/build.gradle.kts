plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

kotlin { jvmToolchain(21) }

// Bundle the knowledge base into the plugin jar so Ask/Edit work out of the box;
// users can override the root in settings to use a live knowledge checkout.
val knowledgeFileList = tasks.register("knowledgeFileList") {
    val knowledgeDir = rootProject.file("knowledge")
    val outputDir = layout.buildDirectory.dir("generated/knowledgeResources")
    outputs.dir(outputDir)
    doLast {
        val files = knowledgeDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(knowledgeDir).invariantSeparatorsPath }
            .sorted()
        val out = outputDir.get().file("knowledge-file-list.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(files.joinToString("\n"))
    }
}

sourceSets {
    main {
        resources {
            srcDir(rootProject.file("knowledge"))
            srcDir(knowledgeFileList)
        }
    }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.2.6.1")
    }
    implementation(project(":modules:session-shell"))
}

// v1 bundles its own kotlin-stdlib (the platform does not expose one on the compile
// classpath here). Known tradeoff, see docs/android-studio-plugin.md; revisit if runIde
// shows stdlib conflicts.

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
    }
}

tasks {
    buildPlugin { archiveBaseName.set("ftckb-as") }
}
