package ktx.tools

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the [BundleLinesCreator] class functions.
 * */
class BundleLinesCreatorTest {
  private val bundleContentA = """
key=value
secondKey=value
%&weird\u0020key=value
=empty key's value
"""

  private val bundleContentAEs = """
key=valor
secondKey=valor
%&weird\u0020key=valor
=valor de la clave vacía
"""

  private val bundleContentB = """
key=valueB
secondKey=valueB
thirdKey=valueB
"""

  private lateinit var workingDir: File
  private lateinit var assetsDir: File
  private lateinit var basePropertiesFiles: List<File>
  private lateinit var srcDir: File
  private lateinit var pathPrefix: String

  @Before
  fun `create properties files`() {
    workingDir = Files.createTempDirectory("working").toFile()
    assetsDir = File(workingDir, "android/assets/i18n").apply { mkdirs() }
    srcDir = File(workingDir, "core/src").apply { mkdirs() }
    val allPropertiesFiles = listOf(
      bundleContentA to "menu.properties",
      bundleContentAEs to "menu_ES.properties",
      bundleContentB to "stage strings.properties"
    ).map { (content, fileName) ->
      File(assetsDir, fileName).apply { writeText(content) }
    }
    basePropertiesFiles = listOf(allPropertiesFiles[0], allPropertiesFiles[2])
    pathPrefix = workingDir.absolutePath + "/"
  }

  @Test
  fun `should find fallback assets directory`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun findFallbackAssetDirectory(pathPrefix: String) = super.findFallbackAssetDirectory(pathPrefix)
    }
    val foundDirectory = bundleLinesCreator.findFallbackAssetDirectory(pathPrefix)?.exists() ?: false
    assertTrue(foundDirectory)
  }

  @Test
  fun `should find properties files`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun findBasePropertiesFiles(parentDirectory: File, searchSubdirectories: Boolean) =
        super.findBasePropertiesFiles(parentDirectory, searchSubdirectories)
    }
    val files = bundleLinesCreator.findBasePropertiesFiles(assetsDir, false)
    assertEquals(basePropertiesFiles.toSet(), files.toSet())
  }

  @Test
  fun `should collect merged properties`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun collectEnums(propertiesFiles: Collection<File>, commonEnumName: String?) =
        super.collectEnums(propertiesFiles, commonEnumName)
    }
    val collected = bundleLinesCreator.collectEnums(basePropertiesFiles, "Nls")
    val expected = mapOf("Nls" to setOf("key", "secondKey", "`%&weird key`", "thirdKey"))
    assertEquals(expected, collected)
  }

  @Test
  fun `should collect distinct properties`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun collectEnums(propertiesFiles: Collection<File>, commonEnumName: String?) =
        super.collectEnums(propertiesFiles, commonEnumName)
    }
    val collected = bundleLinesCreator.collectEnums(basePropertiesFiles, null)
    val expected = mapOf(
      "Menu" to setOf("key", "secondKey", "`%&weird key`"),
      "StageStrings" to setOf("key", "secondKey", "thirdKey")
    )
    assertEquals(expected, collected)
  }

  @Test
  fun `should sanitize enum names`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun convertToEnumName(name: String) = super.convertToEnumName(name)
    }
    val input = listOf("%#my bundle", " stage strings", "话")
    val output = input.map(bundleLinesCreator::convertToEnumName)
    assertTrue(output.all { string ->
      (!string.first().isDigit() && string.all { it.isLetterOrDigit() || it == '_' }) ||
        (string.first() == '`' && string.last() == '`' && string.count { it == '`' } == 2)
    })
  }

  @Test
  fun `should create valid enum entry names`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun convertToEntryName(name: String) = super.convertToEntryName(name)
    }
    val input = listOf("key", "%&weird\\u0020key", "怪异的钥匙", "", " ", "\t", "backtick`key")
    val output = input.mapNotNull(bundleLinesCreator::convertToEntryName)
    assertTrue(output.all(String::isNotEmpty))
    assertTrue(output.all { string ->
      (!string.first().isDigit() && string.all { it.isLetterOrDigit() || it == '_' }) ||
        (string.first() == '`' && string.last() == '`' && string.count { it == '`' } == 2)
    })
  }

  @Test
  fun `should produce expected code`() {
    val bundleLinesCreator = object : BundleLinesCreator() {
      public override fun generateKtFileContent(
        packageName: String,
        enumClassName: String,
        entryNames: Set<String>,
        indent: String
      ): String {
        return super.generateKtFileContent(packageName, enumClassName, entryNames, indent)
      }
    }
    val packageName = "com.packagename"
    val className = "Nls"
    val indent = "\t"
    val entryNames = setOf("key", "secondKey", "`%&weird key`")
    val code = bundleLinesCreator.generateKtFileContent(packageName, className, entryNames, indent)

    val packageDeclaration = Regex("""^package\s.*""").find(code)?.value?.trim()
    assertEquals("package $packageName", packageDeclaration)

    val importDeclarations = Regex("""\nimport\s.*""").findAll(code)
      .map { it.value.trim() }
      .toList()
    val requiredDeclarations = setOf(
      "import ktx.i18n.BundleLine",
      "import com.badlogic.gdx.utils.I18NBundle"
    )
    assertTrue(
      "Must include proper BundleLine and I18NBundle imports, but the imports were $importDeclarations.",
      importDeclarations.containsAll(requiredDeclarations)
    )

    val enumDeclaration = Regex("""enum\s.*(?=\{)""").find(code)?.value?.trim()
      ?.replace(Regex("""\b\s*:\s*\b"""), ":") // trim unnecessary inner whitespace
    assertEquals("enum class $className:BundleLine", enumDeclaration)

    val generatedEntryNames = Regex("""(?<=\{)[\s\S]*(?=;)""").find(code)
      ?.value?.trim()
      ?.split(Regex("""\s*,\s*"""))
      ?.toSet()
    assertEquals(entryNames, generatedEntryNames)

    fun checkBrackets(open: Char, close: Char): Boolean {
      var openCount = 0
      for (c in code) when (c) {
        open -> ++openCount
        close -> if (--openCount < 0) return false
      }
      return openCount == 0
    }

    assertTrue(
      "Improperly matched parentheses in generated code:\n$code",
      checkBrackets('(', ')')
    )
    assertTrue(
      "Improperly matched curly braces in generated code:\n$code",
      checkBrackets('{', '}')
    )
    assertTrue(
      "Improperly matched square brackets in generated code:\n$code",
      checkBrackets('[', ']')
    )
  }

  @Test
  fun `should create source code file`() {
    BundleLinesCreator.execute(
      targetPackage = "com.test",
      targetSourceDirectory = "core/src",
      pathPrefix = pathPrefix
    )
    val expectedFile = File(srcDir, "com/test/Nls.kt")
    assertTrue(expectedFile.exists())
  }

  @After
  fun `remove created files`() {
    assetsDir.deleteRecursively()
    srcDir.deleteRecursively()
  }
}
