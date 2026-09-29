package com.agui.community.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Converts AG-UI tool definitions (JSON Schema parameters) into Koog [ToolDescriptor]s.
 *
 * Koog has no raw-schema pass-through, so the schema is mapped onto [ToolParameterType].
 * The conversion is lenient: constructs Koog cannot express (e.g. `oneOf` with discriminators,
 * missing `type`) degrade to the closest type instead of failing the run.
 */
public object JsonSchemaToolDescriptors {
    private const val MAX_DEPTH = 30

    public fun toToolDescriptor(tool: AgUiTool): ToolDescriptor {
        val schema = tool.parameters ?: JsonObject(emptyMap())
        val defs = (schema["\$defs"] ?: schema["definitions"]) as? JsonObject
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val required = schema.stringList("required").toSet()
        val params = properties.map { (name, element) -> parameter(name, element, defs, 0) }
        return ToolDescriptor(
            name = tool.name,
            description = tool.description,
            requiredParameters = params.filter { it.name in required },
            optionalParameters = params.filter { it.name !in required },
        )
    }

    private fun parameter(name: String, element: JsonElement, defs: JsonObject?, depth: Int): ToolParameterDescriptor {
        val obj = element as? JsonObject ?: JsonObject(emptyMap())
        return ToolParameterDescriptor(name, obj.string("description").orEmpty(), type(obj, defs, depth))
    }

    private fun type(schema: JsonObject, defs: JsonObject?, depth: Int): ToolParameterType {
        require(depth <= MAX_DEPTH) { "JSON schema nesting deeper than $MAX_DEPTH (circular \$ref?)" }

        schema.string("\$ref")?.let { ref ->
            val resolved = defs?.get(ref.substringAfterLast('/')) as? JsonObject
            return if (resolved != null) type(resolved, defs, depth + 1) else ToolParameterType.String
        }

        val enum = schema["enum"] as? JsonArray
        if (enum != null && enum.isNotEmpty()) {
            return ToolParameterType.Enum(enum.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.toTypedArray())
        }

        val (typeName, nullable) = when (val t = schema["type"]) {
            is JsonPrimitive -> t.contentOrNull to false
            is JsonArray -> {
                val names = t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                (names.firstOrNull { it != "null" } ?: "null") to ("null" in names)
            }
            else -> null to false
        }

        val parsed = when (typeName) {
            "string" -> ToolParameterType.String
            "integer" -> ToolParameterType.Integer
            "number" -> ToolParameterType.Float
            "boolean" -> ToolParameterType.Boolean
            "null" -> ToolParameterType.Null
            "array" -> ToolParameterType.List(
                (schema["items"] as? JsonObject)?.let { type(it, defs, depth + 1) } ?: ToolParameterType.String
            )
            "object" -> objectType(schema, defs, depth)
            null -> {
                val variants = (schema["anyOf"] ?: schema["oneOf"]) as? JsonArray
                when {
                    variants != null -> ToolParameterType.AnyOf(
                        variants.map { parameter("", it, defs, depth + 1) }.toTypedArray()
                    )
                    schema["properties"] != null -> objectType(schema, defs, depth)
                    else -> ToolParameterType.String
                }
            }
            else -> ToolParameterType.String
        }

        return if (nullable && parsed != ToolParameterType.Null) {
            ToolParameterType.AnyOf(
                arrayOf(
                    ToolParameterDescriptor("", "", ToolParameterType.Null),
                    ToolParameterDescriptor("", "", parsed),
                )
            )
        } else {
            parsed
        }
    }

    private fun objectType(schema: JsonObject, defs: JsonObject?, depth: Int): ToolParameterType.Object {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
            .map { (name, element) -> parameter(name, element, defs, depth + 1) }
        val additional = schema["additionalProperties"]
        return ToolParameterType.Object(
            properties = properties,
            requiredProperties = schema.stringList("required"),
            additionalProperties = when (additional) {
                is JsonPrimitive -> additional.booleanOrNull
                is JsonObject -> true
                else -> null
            },
            additionalPropertiesType = (additional as? JsonObject)
                ?.takeIf { it.isNotEmpty() }
                ?.let { type(it, defs, depth + 1) },
        )
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
