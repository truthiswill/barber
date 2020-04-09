package app.cash.barber.models

import app.cash.barber.BarberMustacheFactoryProvider
import app.cash.barber.asParameterNames
import com.github.mustachejava.Mustache
import java.io.StringReader
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * For each DocumentData we have a DocumentTemplate that provides a natural language for the document.
 * It uses Mustache templates to provide openings for the DocumentData fields.
 *
 * Each DocumentTemplate is specific to a locale.
 *
 * @param [fields] Map of a Document output key to a template String value that can contain DocumentData input values
 * @param [source] KClass of DocumentData
 * @param [targets] Set of Documents that DocumentTemplate can render to
 * @param [locale] Barbershop.Locale that scopes DocumentTemplate to a languages/country Locale
 */
data class DocumentTemplate(
  val fields: Map<String, String>,
  val source: KClass<out DocumentData>,
  val targets: Set<KClass<out Document>>,
  val locale: Locale
) {
  override fun toString(): String = """
    |DocumentTemplate(
    | fields = mapOf(
    |   ${fields.map { "$it" }.joinToString("\n")}
    | ),
    | source = $source,
    | targets = $targets,
    | locale = $locale
    |)
  """.trimMargin()

  fun compile(
    mustacheFactoryProvider: BarberMustacheFactoryProvider,
    installedFieldKeyDocumentKParameterMap: Map<String, Pair<KClass<out Document>, KParameter>>
  ): CompiledDocumentTemplate {
    // Pre-compile Mustache templates
    val documentTemplateFields: MutableMap<String, Mustache?> =
      fields.mapValues { (fieldKey, fieldValue) ->
        val documentFieldAnnotations = installedFieldKeyDocumentKParameterMap[fieldKey]?.second?.annotations
        val barberField = documentFieldAnnotations?.find { it is BarberField } as BarberField?
        val encoding = barberField?.encoding ?: BarberFieldEncoding.STRING_HTML
        mustacheFactoryProvider.get(encoding).compile(StringReader(fieldValue), fieldValue)
      }.toMutableMap()

    // Find missing fields in DocumentTemplate
    // Missing fields occur when a nullable field in Document is not an included key in the DocumentTemplate fields
    // In the Parameters Map in the Document constructor though, all parameter keys must be present (including
    // nullable)
    val combinedDocumentParameterNames = targets.map { target ->
      target.primaryConstructor!!.asParameterNames()
    }.map {
      it.entries
    }.reduce { acc, entries ->
      acc + entries
    }.filter {
      it.value.type.isMarkedNullable
    }.mapNotNull {
      it.key
    }.toSet()

    // Initialize keys for missing nullable fields in DocumentTemplate
    combinedDocumentParameterNames.mapNotNull { documentTemplateFields.putIfAbsent(it, null) }

    return CompiledDocumentTemplate(
      fields = documentTemplateFields,
      source = source,
      targets = targets,
      locale = locale)
  }
}
