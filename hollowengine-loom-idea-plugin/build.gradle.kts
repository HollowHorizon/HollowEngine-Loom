plugins {
	id("java")
	id("org.jetbrains.intellij.platform") version "2.12.0"
}

val platformType = providers.gradleProperty("platformType").get()
val platformVersion = providers.gradleProperty("platformVersion").get()

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	withSourcesJar()
}

repositories {
	mavenCentral()

	intellijPlatform {
		defaultRepositories()
	}
}

dependencies {
	intellijPlatform {
		create(platformType, platformVersion)
		bundledPlugin("com.intellij.java")
		bundledPlugin("org.jetbrains.kotlin")
		pluginVerifier()
		testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
	}
}

intellijPlatform {
	buildSearchableOptions = false
	pluginConfiguration {
		name = "HollowEngine Loom"
		version = providers.gradleProperty("pluginVersion")
		description = """
			Provides Java and Kotlin inspections for HollowEngine Loom multiversion development.
			The plugin validates usages of APIs marked with @RequiresApi and recognizes direct Constants guards.
		""".trimIndent()
		vendor {
			name = "HollowHorizon"
		}
		ideaVersion {
			sinceBuild = "253"
			untilBuild = "253.*"
		}
	}
}

tasks {
	withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.release = 17
	}

	runIde {
		jvmArgs("-Xmx2g")
	}

	patchPluginXml {
		sinceBuild.set("253")
		untilBuild.set("253.*")
	}
}
