package app.snipnet.shared.model

import kotlinx.serialization.json.Json

/**
 * The single [Json] configuration used for all API payloads. Unknown keys are ignored so a newer backend that adds
 * fields does not break older clients, and nulls are written explicitly because the contract lists nullable
 * fields as always present.
 */
val SnipnetJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }
