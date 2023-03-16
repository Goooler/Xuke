package io.github.chao2zhang

import io.github.chao2zhang.api.Dependency
import io.github.chao2zhang.api.License
import io.github.chao2zhang.api.LicenseData
import io.github.chao2zhang.format.FormatOptions
import io.github.chao2zhang.format.FormatterFactory
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Node

abstract class XukeTask : DefaultTask() {
    private val deps: Provider<List<Dependency>>

    init {
        description = "Collect software licenses from dependencies"

        deps = project.provider {
            project
                .configurations
                .filter { configuration ->
                    val buildConfigurations = buildConfigurationsProp.getOrElse(emptyList<String>())
                    if (buildConfigurations.isEmpty()) true else configuration.name in buildConfigurations
                }
                .filter { it.isCanBeResolved }
                .flatMap { configuration ->
                    configuration.resolvedConfiguration.resolvedArtifacts
                }
                .map {
                    Dependency(
                        it.moduleVersion.id.group,
                        it.moduleVersion.id.name,
                        it.moduleVersion.id.version,
                    )
                }
        }
    }

    @get:Input
    abstract val buildConfigurationsProp: ListProperty<String>

    @get:Input
    abstract val outputPackage: Property<String>

    @get:OutputFile
    abstract val outputFileProp: RegularFileProperty

    @TaskAction
    fun collect() {
        val licenseData = extractLicenses(deps.get())
        writeLicenses(licenseData)
    }

    private fun extractLicenses(deps: List<Dependency>): LicenseData = deps.associateWith { dep ->
        val pom = queryPom(project, dep)
        (pom?.let(::extractLicenses) ?: emptyList())
    }

    private fun writeLicenses(licenseData: LicenseData) {
        val outputFile = outputFileProp.get().asFile
        outputFile.writeText(
            FormatterFactory
                .fromFileExtension(outputFile.extension)
                .format(
                    licenseData,
                    FormatOptions(
                        packagePath = outputPackage.getOrElse(""),
                    ),
                ),
        )
        logger.info(licenseData.toList().joinToString(separator = "\n"))
    }

    private fun queryPom(project: Project, dep: Dependency): File? =
        project.dependencies
            .createArtifactResolutionQuery()
            .forModule(dep.group, dep.name, dep.version)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute()
            .resolvedComponents
            .singleOrNull()
            ?.getArtifacts(MavenPomArtifact::class.java)
            ?.singleOrNull()
            ?.safeAs<ResolvedArtifactResult>()
            ?.file

    private fun extractLicenses(pomFile: File): List<License> = buildList {
        val pomDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile).documentElement
        val licenseNodes = pomDoc.getElementsByTagName("licenses").singleOrNull()?.childNodes
        licenseNodes?.forEach { licenseNode ->
            if (licenseNode.nodeType == Node.ELEMENT_NODE) {
                add(
                    License(
                        name = licenseNode.namedChildTextContentOrEmpty("name"),
                        url = licenseNode.namedChildTextContentOrEmpty("url"),
                        distribution = licenseNode.namedChildTextContentOrEmpty("distribution"),
                        comments = licenseNode.namedChildTextContentOrEmpty("comments"),
                    ),
                )
            }
        }
    }
}
