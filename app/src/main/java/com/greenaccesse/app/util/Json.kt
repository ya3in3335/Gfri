package com.greenaccesse.app.util

import kotlinx.serialization.json.Json

/** Shared JSON configuration. Unknown keys are ignored for forward compatibility with newer Bridge versions. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
}
