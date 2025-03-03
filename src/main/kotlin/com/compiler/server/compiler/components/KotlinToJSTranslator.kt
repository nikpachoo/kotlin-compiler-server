package com.compiler.server.compiler.components

import com.compiler.server.model.ErrorDescriptor
import com.compiler.server.model.TranslationJSResult
import com.compiler.server.model.toExceptionDescriptor
import component.KotlinEnvironment
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.ir.backend.js.CompilerResult
import org.jetbrains.kotlin.ir.backend.js.WholeWorldStageController
import org.jetbrains.kotlin.ir.backend.js.compile
import org.jetbrains.kotlin.ir.backend.js.prepareAnalyzedSourceModule
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.TranslationMode
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImplForJsIC
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.js.facade.K2JSTranslator
import org.jetbrains.kotlin.js.facade.MainCallParameters
import org.jetbrains.kotlin.js.facade.TranslationResult
import org.jetbrains.kotlin.js.facade.exceptions.TranslationException
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.springframework.stereotype.Service

@Service
class KotlinToJSTranslator(
  private val kotlinEnvironment: KotlinEnvironment,
  private val errorAnalyzer: ErrorAnalyzer
) {
  companion object {
    private const val JS_CODE_FLUSH = "kotlin.kotlin.io.output.flush();\n"
    private const val JS_CODE_BUFFER = "\nkotlin.kotlin.io.output.buffer;\n"

    private const val JS_IR_CODE_BUFFER = "moduleId.output.buffer_1;\n"

    private val JS_IR_OUTPUT_REWRITE = """
        if (kotlin.isRewrite) {
            _init_properties_console_kt__rfg7jv();
            output = new BufferedOutput()
        }
        """.trimIndent()

    const val BEFORE_MAIN_CALL_LINE = 4
  }

  fun translate(
    files: List<KtFile>,
    arguments: List<String>,
    coreEnvironment: KotlinCoreEnvironment,
    translate: (List<KtFile>, List<String>, KotlinCoreEnvironment) -> TranslationJSResult
  ): TranslationJSResult {
    val (errors, _) = errorAnalyzer.errorsFrom(files, coreEnvironment, isJs = true)
    return try {
      if (errorAnalyzer.isOnlyWarnings(errors)) {
        translate(files, arguments, coreEnvironment).also {
          it.addWarnings(errors)
        }
      } else {
        TranslationJSResult(errors = errors)
      }
    } catch (e: Exception) {
      TranslationJSResult(exception = e.toExceptionDescriptor())
    }
  }

  @Throws(TranslationException::class)
  fun doTranslate(
    files: List<KtFile>,
    arguments: List<String>,
    coreEnvironment: KotlinCoreEnvironment
  ): TranslationJSResult {
    val currentProject = coreEnvironment.project
    val configuration = JsConfig(
      currentProject,
      kotlinEnvironment.jsConfiguration,
      CompilerEnvironment,
      kotlinEnvironment.JS_METADATA_CACHE,
      kotlinEnvironment.JS_LIBRARIES.toSet()
    )
    val reporter = object : JsConfig.Reporter() {
      override fun error(message: String) {}
      override fun warning(message: String) {}
    }
    val translator = K2JSTranslator(configuration)
    val result = translator.translate(
      reporter = reporter,
      files = files,
      mainCallParameters = MainCallParameters.mainWithArguments(arguments)
    )
    return if (result is TranslationResult.Success) {
      TranslationJSResult(JS_CODE_FLUSH + result.getCode() + JS_CODE_BUFFER)
    } else {
      val errors = HashMap<String, List<ErrorDescriptor>>()
      for (psiFile in files) {
        errors[psiFile.name] = ArrayList()
      }
      errorAnalyzer.errorsFrom(result.diagnostics.all(), errors, isJs = true)
      TranslationJSResult(errors = errors)
    }
  }

  fun doTranslateWithIr(
    files: List<KtFile>,
    arguments: List<String>,
    coreEnvironment: KotlinCoreEnvironment
  ): TranslationJSResult {
    val currentProject = coreEnvironment.project

    val sourceModule = prepareAnalyzedSourceModule(
      currentProject,
      files,
      kotlinEnvironment.jsConfiguration,
      kotlinEnvironment.JS_LIBRARIES,
      friendDependencies = emptyList(),
      analyzer = AnalyzerWithCompilerReport(kotlinEnvironment.jsConfiguration),
    )
    val ir = compile(
      sourceModule,
      kotlinEnvironment.jsIrPhaseConfig,
      irFactory = IrFactoryImplForJsIC(WholeWorldStageController())
    )
    val transformer = IrModuleToJsTransformer(
      ir.context,
      arguments
    )

    val compiledModule: CompilerResult = transformer.generateModule(
      modules = ir.allModules,
      modes = setOf(TranslationMode.FULL_PROD),
      relativeRequirePath = false
    )

    val jsCode = getJsCodeFromModule(compiledModule)

    val listLines = jsCode
      .lineSequence()
      .toMutableList()

    listLines.add(listLines.size - BEFORE_MAIN_CALL_LINE, JS_IR_OUTPUT_REWRITE)
    listLines.add(listLines.size - BEFORE_MAIN_CALL_LINE, "_.output = output")
    listLines.add(listLines.size - 1, JS_IR_CODE_BUFFER)

    return TranslationJSResult(listLines.joinToString("\n"))
  }

  private fun getJsCodeFromModule(compiledModule: CompilerResult): String {
    val jsCodeObject = compiledModule.outputs.values.single()

    val jsCodeClass = jsCodeObject.javaClass
    val jsCode = jsCodeClass.getDeclaredField("rawJsCode").let {
      it.isAccessible = true
      it.get(jsCodeObject) as String
    }
    return jsCode
  }
}