package de.anypos.tse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Epson-TSE-Fehlercode als Antwort: Die TSE hat den Aufruf abgelehnt, er wurde also **sicher nicht** ausgeführt
 * (Gegensatz zu [TseResultUnknownException]).
 */
class EpsonException(
    val code: String,
    val function: String,
    /** Zusatzangaben der TSE (`error.fact`, `error.errorinfo`), nur fürs Protokoll. */
    val fact: String? = null,
    val errorInfo: String? = null,
    /** `output` der Fehlerantwort, z. B. `remainingRetries` bei falscher PIN. */
    val output: JsonObject? = null,
) : TseException("$function: $code")

/** Aufbau der JSON-Kommandos und Auswertung der Antworten (Epson TSE JSON-Schnittstelle). */
object EpsonJson {
    const val EXECUTION_OK = "EXECUTION_OK"

    /**
     * `{"storage":{"type","vendor"},"function","input",“compress”}`. `GetStorageInfo` läuft über Storage `COMMON`,
     * alle anderen über `TSE` mit `vendor` = `vendorType` aus `GetStorageInfo`. `null` wird wie bei Epson üblich als "" gesendet.
     */
    fun request(function: String, input: Map<String, Any?> = emptyMap(), common: Boolean = false, vendor: String = ""): String =
        buildJsonObject {
            putJsonObject("storage") {
                put("type", if (common) "COMMON" else "TSE")
                put("vendor", if (common) "" else vendor)
            }
            put("function", function)
            putJsonObject("input") {
                input.forEach { (key, value) ->
                    put(key, when (value) {
                        null -> JsonPrimitive("")
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    })
                }
            }
            putJsonObject("compress") {
                put("required", false)
                put("type", "")
            }
        }.toString()

    /**
     * Liefert `output` einer erfolgreichen Antwort; ein Fehlercode wird zur [EpsonException].
     * Eine unlesbare Antwort kam nach dem Senden – das Ergebnis ist dann unbekannt.
     */
    fun output(function: String, answer: String, alsoAccept: Set<String> = emptySet()): JsonObject {
        val json = parseObject(answer) ?: throw TseResultUnknownException(function, "Unlesbare Antwort auf $function – Ausführung unbekannt")
        val result = json.string("result") ?: throw TseResultUnknownException(function, "Antwort auf $function ohne result – Ausführung unbekannt")
        if (result != EXECUTION_OK && result !in alsoAccept) {
            val error = json["error"] as? JsonObject
            throw EpsonException(result, function, error?.string("fact"), error?.string("errorinfo"), json["output"] as? JsonObject)
        }
        return json["output"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun parseObject(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
