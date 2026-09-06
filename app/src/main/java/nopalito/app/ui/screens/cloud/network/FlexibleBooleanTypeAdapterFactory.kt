/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.screens.cloud.network

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Gson [TypeAdapterFactory] that returns a flexible [TypeAdapter] for both
 * `Boolean` (java.lang.Boolean, nullable) and `boolean` (primitive, non-nullable).
 *
 * In Kotlin:
 * - `Boolean::class.java` returns `boolean.class` (primitive)
 * - `Boolean::class.javaObjectType` returns `java.lang.Boolean.class` (wrapper)
 * - `java.lang.Boolean.TYPE` is also `boolean.class` (primitive)
 *
 * We must check ALL of these because Kotlin nullable `Boolean?` compiles to
 * `java.lang.Boolean` (wrapper), while non-nullable `Boolean` compiles to
 * `boolean` (primitive).
 */
class FlexibleBooleanTypeAdapterFactory : TypeAdapterFactory {

    override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
        val rawType = typeToken.rawType
        // Handle all Boolean type representations:
        // - java.lang.Boolean (wrapper, used for nullable Boolean?)
        // - boolean (primitive, used for non-nullable Boolean)
        val isBoolean = rawType == Boolean::class.javaObjectType ||
                rawType == Boolean::class.java ||
                rawType == java.lang.Boolean.TYPE
        if (!isBoolean) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return FlexibleBooleanAdapter() as TypeAdapter<T>
    }
}

/**
 * Gson TypeAdapter for [Boolean] that tolerates multiple representations.
 */
class FlexibleBooleanAdapter : TypeAdapter<Boolean>() {

    override fun write(out: JsonWriter, value: Boolean?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value)
        }
    }

    override fun read(`in`: JsonReader): Boolean? {
        when (`in`.peek()) {
            JsonToken.NULL -> {
                `in`.nextNull()
                return null
            }

            JsonToken.NUMBER -> {
                val number = `in`.nextInt()
                return number != 0
            }

            JsonToken.STRING -> {
                val text = `in`.nextString()
                return parseString(text)
            }

            JsonToken.BOOLEAN -> {
                return `in`.nextBoolean()
            }

            else -> {
                `in`.skipValue()
                return null
            }
        }
    }

    private fun parseString(text: String): Boolean? {
        return when (text.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off", "" -> false
            else -> {
                // Unknown string — return null to be safe
                null
            }
        }
    }
}
