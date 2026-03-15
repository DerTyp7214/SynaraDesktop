package dev.dertyp.synara.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStream

class IconProcessor(
    private val codeGenerator: CodeGenerator
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val lucidePacks = resolver.getSymbolsWithAnnotation("dev.dertyp.synara.ui.LucidePack")
            .filterIsInstance<KSClassDeclaration>().toList()
        val materialPacks = resolver.getSymbolsWithAnnotation("dev.dertyp.synara.ui.MaterialPack")
            .filterIsInstance<KSClassDeclaration>().toList()
        val phosphorPacks = resolver.getSymbolsWithAnnotation("dev.dertyp.synara.ui.PhosphorPack")
            .filterIsInstance<KSClassDeclaration>().toList()

        if (lucidePacks.isEmpty() && materialPacks.isEmpty() && phosphorPacks.isEmpty()) return emptyList()

        val fileStream: OutputStream = codeGenerator.createNewFile(
            Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
            "dev.dertyp.synara.ui",
            "SynaraIconMappings"
        )

        fileStream.use { stream ->
            write(stream, "package dev.dertyp.synara.ui\n\n")
            write(stream, "import androidx.compose.runtime.Composable\n")
            write(stream, "import androidx.compose.ui.graphics.vector.ImageVector\n")
            write(stream, "import com.composables.icons.lucide.*\n")
            write(stream, "import com.composables.icons.materialsymbols.MaterialSymbols\n")
            write(stream, "import com.composables.icons.materialsymbols.outlined.*\n")
            write(stream, "import com.composables.icons.materialsymbols.outlinedfilled.*\n")
            write(stream, "import com.composables.icons.materialsymbols.rounded.*\n")
            write(stream, "import com.composables.icons.materialsymbols.roundedfilled.*\n")
            write(stream, "import com.composables.icons.materialsymbols.sharp.*\n")
            write(stream, "import com.composables.icons.materialsymbols.sharpfilled.*\n")
            write(stream, "import icons.PhIcons\n")
            write(stream, "import icons.regular.*\n")
            write(stream, "import icons.filled.*\n")
            write(stream, "import icons.thin.*\n")
            write(stream, "import icons.light.*\n")
            write(stream, "import icons.bold.*\n")
            write(stream, "import icons.duotone.*\n")

            write(stream, "\n")

            lucidePacks.forEach { pack ->
                generateLucideResolver(pack, stream)
            }

            materialPacks.forEach { pack ->
                generateMaterialResolver(pack, stream)
            }

            phosphorPacks.forEach { pack ->
                generatePhosphorResolver(pack, stream)
            }
        }

        return emptyList()
    }

    private fun write(stream: OutputStream, str: String) {
        stream.write(str.toByteArray(Charsets.UTF_8))
    }

    private fun generateLucideResolver(pack: KSClassDeclaration, stream: OutputStream) {
        write(stream, "actual fun LucideIconPack.generatedGet(id: SynaraIcons): ImageVector = when (id) {\n")
        
        pack.getAllProperties().forEach { prop ->
            prop.annotations.filter { it.shortName.asString() == "MapTo" }.forEach { mapTo ->
                val iconIdValue = mapTo.arguments.find { it.name?.asString() == "iconId" }?.value
                val shortId = iconIdValue.toString().substringAfterLast(".")
                write(stream, "    SynaraIcons.$shortId -> Lucide.${prop.simpleName.asString()}\n")
            }
        }
        
        write(stream, "    else -> Lucide.CircleQuestionMark\n")
        write(stream, "}\n\n")
    }

    private fun generateMaterialResolver(pack: KSClassDeclaration, stream: OutputStream) {
        write(stream, "actual fun MaterialSymbolsIconPack.generatedGet(id: SynaraIcons, style: MaterialSymbolStyle, filled: Boolean): ImageVector = when (id) {\n")

        pack.getAllProperties().forEach { prop ->
            val iconName = prop.simpleName.asString()
            
            prop.annotations.filter { it.shortName.asString() == "MapTo" }.forEach { mapTo ->
                val iconIdValue = mapTo.arguments.find { it.name?.asString() == "iconId" }?.value
                val shortId = iconIdValue.toString().substringAfterLast(".")
                
                val filledModeArg = mapTo.arguments.find { it.name?.asString() == "fillMode" }?.value.toString()
                val filledExpr = when {
                    filledModeArg.contains("Filled") -> "true"
                    filledModeArg.contains("Outlined") -> "false"
                    else -> "filled"
                }
                
                write(stream, "    SynaraIcons.$shortId -> resolve(\n")
                write(stream, "        MaterialSymbols.Rounded.$iconName, MaterialSymbols.RoundedFilled.$iconName,\n")
                write(stream, "        MaterialSymbols.Outlined.$iconName, MaterialSymbols.OutlinedFilled.$iconName,\n")
                write(stream, "        MaterialSymbols.Sharp.$iconName, MaterialSymbols.SharpFilled.$iconName,\n")
                write(stream, "        $filledExpr, style\n")
                write(stream, "    )\n")
            }
        }

        write(stream, "    else -> MaterialSymbols.Rounded.Question_mark\n")
        write(stream, "}\n\n")
    }

    private fun generatePhosphorResolver(pack: KSClassDeclaration, stream: OutputStream) {
        write(stream, "actual fun PhosphorIconPack.generatedGet(id: SynaraIcons, style: PhosphorIconStyle, filled: Boolean): ImageVector = when (id) {\n")

        pack.getAllProperties().forEach { prop ->
            val iconName = prop.simpleName.asString()
            prop.annotations.filter { it.shortName.asString() == "MapTo" }.forEach { mapTo ->
                val iconIdValue = mapTo.arguments.find { it.name?.asString() == "iconId" }?.value
                val shortId = iconIdValue.toString().substringAfterLast(".")

                val filledModeArg = mapTo.arguments.find { it.name?.asString() == "fillMode" }?.value.toString()
                val filledExpr = when {
                    filledModeArg.contains("Filled") -> "true"
                    filledModeArg.contains("Outlined") -> "false"
                    else -> "filled"
                }

                write(stream, "    SynaraIcons.$shortId -> resolve(\n")
                write(stream, "        PhIcons.Thin.${iconName}Thin, PhIcons.Light.${iconName}Light, PhIcons.Regular.$iconName,\n")
                write(stream, "        PhIcons.Bold.${iconName}Bold, PhIcons.Filled.${iconName}Fill, PhIcons.Duotone.${iconName}Duotone,\n")
                write(stream, "        $filledExpr, style\n")
                write(stream, "    )\n")
            }
        }

        write(stream, "    else -> PhIcons.Regular.Question\n")
        write(stream, "}\n\n")
    }
}

class IconProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return IconProcessor(environment.codeGenerator)
    }
}
