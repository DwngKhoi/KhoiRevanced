package io.github.nexalloy

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.support.ParameterDeclarations
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream
import kotlin.io.path.Path
import kotlin.io.path.name

class FilePathArgumentsProvider : ArgumentsProvider {
    override fun provideArguments(
        parameters: ParameterDeclarations,
        context: ExtensionContext
    ): Stream<out Arguments> {
        val projectDir = Paths.get(".")
        val testInputPath = projectDir.resolve("binaries")

        if (!Files.isDirectory(testInputPath)) {
            throw IllegalStateException(
                "APK fixtures folder not found: ${testInputPath.toAbsolutePath()}. " +
                    "The fingerprint tests need real host APKs; see DEVELOPMENT.md."
            )
        }

        return Files.walk(testInputPath).filter { path ->
                Files.isRegularFile(path) && path.normalize().none { it.name.startsWith(".") }
            }.map { Arguments.of(it) }
    }
}