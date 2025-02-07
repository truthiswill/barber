package app.cash.barber

import app.cash.barber.models.BarberFieldEncoding
import app.cash.barber.models.BarberKey
import app.cash.barber.models.CompiledDocumentTemplate
import app.cash.barber.models.CompiledDocumentTemplate.Companion.reduceToValuesSet
import app.cash.barber.models.Document
import app.cash.barber.models.DocumentData
import app.cash.barber.models.DocumentTemplate
import app.cash.barber.models.Locale
import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

class BarbershopBuilder : Barbershop.Builder {
  private val installedCompiledDocumentTemplates =
      HashBasedTable.create<KClass<out DocumentData>, Locale, CompiledDocumentTemplate>()
  private val installedDocumentTemplates =
      HashBasedTable.create<KClass<out DocumentData>, Locale, DocumentTemplate>()
  private val installedDocuments =
      HashBasedTable.create<String, KClass<out Document>, KParameter>()
  private var mustacheFactoryProvider = BarberMustacheFactoryProvider()
  private var localeResolver: LocaleResolver = MatchOrFirstLocaleResolver
  private var warningsAsErrors: Boolean = false
  private val warnings = mutableListOf<String>()

  override fun installDocumentTemplate(
    documentDataClass: KClass<out DocumentData>,
    documentTemplate: DocumentTemplate
  ) = apply {
    if (installedDocumentTemplates.contains(documentDataClass, documentTemplate.locale)) {
      throw BarberException(errors = listOf("""
        |Attempted to install DocumentTemplate that will overwrite an already installed DocumentTemplate with locale
        |${documentTemplate.locale}.
        |Already Installed
        |DocumentData: $documentDataClass
        |Locales:
        |${installedDocumentTemplates.row(documentDataClass).keys.joinToString("\n")}
        |DocumentTemplates: [
        |${installedDocumentTemplates.row(documentDataClass).values.joinToString("\n")}]
        |
        |Attempted to Install
        |$documentTemplate
        """.trimMargin()))
    }
    installedDocumentTemplates.put(documentDataClass, documentTemplate.locale,
        documentTemplate)
  }

  inline fun <reified DD : DocumentData> installDocumentTemplate(documentTemplate: DocumentTemplate) = installDocumentTemplate(
      DD::class, documentTemplate)

  override fun installDocument(document: KClass<out Document>) = apply {
    val documentConstructor = document.primaryConstructor
    if (documentConstructor == null) {
      throw BarberException(errors = listOf("No primary constructor for Document [$document]"))
    } else if (documentConstructor.parameters.isEmpty()) {
      throw BarberException(errors = listOf("No fields included for Document [$document]"))
    }
    documentConstructor.asParameterNames().forEach { (fieldName, kParameter) ->
      fieldName?.let {
        installedDocuments.put(fieldName, document, kParameter)
      }
    }
  }

  inline fun <reified D : Document> installDocument() = installDocument(D::class)

  override fun setLocaleResolver(resolver: LocaleResolver): Barbershop.Builder = apply {
    localeResolver = resolver
  }

  override fun setWarningsAsErrors(): Barbershop.Builder = apply {
    warningsAsErrors = true
  }

  override fun setDefaultBarberFieldEncoding(encoding: BarberFieldEncoding): Barbershop.Builder = apply {
    mustacheFactoryProvider = BarberMustacheFactoryProvider(encoding)
  }

  override fun build(): Barbershop = installedDocumentTemplates.validateAndCompile().asBarbershop()

  /**
   * Validates BarbershopBuilder inputs and returns a Barbershop instance with the installed and
   * validated elements.
   */
  private fun Table<KClass<out DocumentData>, Locale, DocumentTemplate>.validateAndCompile(): Table<KClass<out DocumentData>, Locale, CompiledDocumentTemplate> {
    val errors: MutableList<String> = mutableListOf()

    // Warn if Barber elements are not installed
    if (cellSet().isEmpty()) {
      warnings.add("""
        |No DocumentData or DocumentTemplates installed
      """.trimMargin())
    }
    if (installedDocuments.isEmpty) {
      warnings.add("""
        |No Documents installed
      """.trimMargin())
    }

    // Warn if Documents are unused in DocumentTemplates
    if (!installedDocuments.isEmpty && cellSet().isNotEmpty()) {
      val usedDocuments = cellSet()
          .map { it.value!!.targets }
          .reduce { acc, targets ->
            acc + targets
          }.toSet()
      if (!usedDocuments.containsAll(installedDocuments.columnKeySet())) {
        val danglingDocuments = installedDocuments.columnKeySet().filter { document ->
          !usedDocuments.contains(document)
        }
        warnings.add("""
          |Document installed that is not used in any installed DocumentTemplates
          |$danglingDocuments
          |
          """.trimMargin())
      }
    }

    maybeThrowBarberException(errors = errors, warnings = warnings)

    // Compile DocumentTemplates and perform initial validation
    cellSet().forEach { cell ->
      val documentDataClass = cell.rowKey!!
      val documentTemplate = cell.value!!

      // DocumentTemplate must be installed with a DocumentData that is listed in its Source
      if (documentDataClass != documentTemplate.source) {
        errors.add("""
          |Attempted to install DocumentTemplate with a DocumentData not specified in the DocumentTemplate source.
          |DocumentTemplate.source: ${documentTemplate.source}
          |DocumentData: $documentDataClass
          """.trimMargin())
      }

      // Documents listed in DocumentTemplate.Targets must be installed
      val notInstalledDocument = documentTemplate.targets.filter {
        !installedDocuments.columnKeySet().contains(it)
      }
      if (notInstalledDocument.isNotEmpty()) {
        errors.add("""
          |Attempted to install DocumentTemplate without the corresponding Document being installed.
          |Not installed DocumentTemplate.targets:
          |$notInstalledDocument
          """.trimMargin())
      }

      // Compile templates according to the MustacheFactory matching to the Document field encoding
      val compiledDocumentTemplate = documentTemplate
          .compile(mustacheFactoryProvider, installedDocuments)
      installedCompiledDocumentTemplates.put(
          documentDataClass,
          documentTemplate.locale,
          compiledDocumentTemplate
      )
    }

    maybeThrowBarberException(errors = errors, warnings = warnings)

    // Standard validation
    cellSet().forEach { cell ->
      val documentDataClass = cell.rowKey!!
      val documentTemplate = cell.value!!
      val compiledDocumentTemplate =
          installedCompiledDocumentTemplates.get(cell.rowKey, cell.columnKey)

      // DocumentTemplates must only use variables from source DocumentData in their fields
      val documentDataConstructor = documentDataClass.primaryConstructor!!
      val documentDataParameterNames = documentDataConstructor.asParameterNames()
      compiledDocumentTemplate.reducedFieldCodeMap().forEach { (name, codes) ->
        // Check for missing variables in field templates
        codes.forEach { code ->
          if (!documentDataParameterNames.contains(code.rootKey())) {
            errors.add(
                "Missing variable [$code] in DocumentData [$documentDataClass] for DocumentTemplate field [${documentTemplate.fields[name]}]")
          }
        }
      }

      // Document targets must have primaryConstructor
      // and installedDocumentTemplates must be able to fulfill Document target parameter requirements
      val allTargetParameters = documentTemplate.targets.map { document ->
        installedDocuments.column(document).values.toSet()
      }.reduce { acc, params ->
        acc + params
      }.toSet()
      val allTargetFields = allTargetParameters.map {
        it.name
      }
      val requiredTargetFields = allTargetParameters.filter {
        !it.type.isMarkedNullable
      }.map { it.name }

      // Confirm that required field keys are present in installedDocumentTemplates
      if (!documentTemplate.fields.keys.containsAll(requiredTargetFields)) {
        val missingFields = requiredTargetFields.filter {
          !documentTemplate.fields.containsKey(it)
        }
        val documentsThatRequireMissingField =
            documentTemplate.targets.map { documentClass ->
              documentClass to documentClass.primaryConstructor!!.parameters.map { it.name }
                  .filter {
                    missingFields.contains(it)
                  }
            }.toMap().map { "[${it.key}] requires missing fields ${it.value}" }.joinToString("\n")

        errors.add("""
              |Installed DocumentTemplate missing required fields for Document targets
              |Missing fields:
              |$documentsThatRequireMissingField
              |
              |DocumentTemplate: $documentTemplate
              """.trimMargin())
      }
      if (documentTemplate.fields.keys.size > allTargetFields.size) {
        val additionalFields = documentTemplate.fields.keys.filter { field ->
          !allTargetFields.contains(field)
        }
        errors.add("""
              |Installed DocumentTemplate has additional fields that are not used in any target Document
              |Additional fields:
              |${additionalFields.joinToString("\n")}
            """.trimMargin())
      }

      documentTemplate.targets.forEach { documentClass ->
        // Lookup installed DocumentTemplates that corresponds to DocumentData
        val documentTemplates = row(documentDataClass)

        if (documentTemplates.isEmpty()) {
          errors.add("""
            |Attempting to build Barber<$documentDataClass, $documentClass>.
            |No installed DocumentTemplates for DocumentData key: $documentDataClass.
            |Check usage of BarbershopBuilder to ensure that all DocumentTemplates are installed to prevent dangling DocumentData.
            """.trimMargin()
          )
        }

        // Confirm that output Document is a valid target for the DocumentTemplate
        documentTemplates.forEach { (_, documentTemplate) ->
          if (!documentTemplate.targets.contains(documentClass)) {
            errors.add("""
              |Specified target $documentClass not a valid target for DocumentData's corresponding DocumentTemplate.
              |Valid targets:
              |${documentTemplate.targets}
              """.trimMargin()
            )
          }
        }
      }
    }

    maybeThrowBarberException(errors = errors, warnings = warnings)

    // Check for unused DocumentData variable not used in any installed DocumentTemplate field
    installedCompiledDocumentTemplates.rowMap()
        .forEach { (documentDataClass, compiledDocumentTemplates) ->
          val codes = compiledDocumentTemplates.mapValues { (_, compiledDocumentTemplate) ->
            compiledDocumentTemplate.reducedFieldCodeSet()
          }.reduceToValuesSet()

          val documentDataConstructor = documentDataClass.primaryConstructor
          if (documentDataConstructor == null) {
            errors.add("Null primary constructor for DocumentData $documentDataClass")
          } else {
            val documentDataParameterNames =
                documentDataConstructor.parameters.map { it.name }.toList()
            documentDataParameterNames.forEach { parameter ->
              if (!codes.map { it.rootKey() }.contains(parameter)) {
                warnings.add("""
                |Unused DocumentData variable [$parameter] in [$documentDataClass] with no usage in installed DocumentTemplate Locales:
                |${compiledDocumentTemplates.keys.joinToString("\n")}
              """.trimMargin())
              }
            }
          }
        }

    maybeThrowBarberException(errors = errors, warnings = warnings)

    return installedCompiledDocumentTemplates
  }

  /**
   * Throwing early makes debugging simpler for Barber developers as the above simple warnings
   * can be raised before a flood of other errors below fail as a result of the above
   */
  private fun maybeThrowBarberException(errors: List<String>, warnings: List<String>) {
    if (errors.isNotEmpty() || (warnings.isNotEmpty() && warningsAsErrors)) {
      throw BarberException(errors = errors, warnings = warnings)
    }
  }

  private fun Table<KClass<out DocumentData>, Locale, CompiledDocumentTemplate>.asBarbershop(): Barbershop {
    val barbers: LinkedHashMap<BarberKey, Barber<DocumentData, Document>> = linkedMapOf()
    cellSet().forEach { cell ->
      val documentDataClass = cell.rowKey!!
      val documentTemplate = cell.value!!
      documentTemplate.targets.forEach { documentClass ->
        val documentTemplatesBySource = row(documentTemplate.source)
        barbers[BarberKey(documentDataClass, documentClass)] = RealBarber(
            document = documentClass,
            installedDocuments = installedDocuments,
            compiledDocumentTemplateLocales = documentTemplatesBySource.mapValues { it.value },
            localeResolver = localeResolver
        )
      }
    }
    return RealBarbershop(barbers = barbers, warnings = warnings)
  }
}
