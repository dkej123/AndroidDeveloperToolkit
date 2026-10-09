package io.github.dkej123.devicecockpit.application.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One tool argument for the JSON Schema of `tools/list`. */
data class Param(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val oneOf: List<String>? = null,
)

fun str(name: String, description: String, required: Boolean = false, oneOf: List<String>? = null) = Param(name, "string", description, required, oneOf)

fun num(name: String, description: String, required: Boolean = false) = Param(name, "number", description, required)

fun int(name: String, description: String, required: Boolean = false) = Param(name, "integer", description, required)

fun bool(name: String, description: String) = Param(name, "boolean", description)

val SERIAL = str("serial", "Device serial from list_devices. Default: the device selected in Device Cockpit.")

fun schema(vararg params: Param): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        params.forEach { p ->
            putJsonObject(p.name) {
                put("type", p.type)
                put("description", p.description)
                p.oneOf?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
            }
        }
    }
    val required = params.filter { it.required }
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it.name)) } })
    put("additionalProperties", false)
}
