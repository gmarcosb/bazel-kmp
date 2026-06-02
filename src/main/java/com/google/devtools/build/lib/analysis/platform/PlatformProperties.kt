// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.platform

import com.google.auto.value.AutoValue
import com.google.auto.value.extension.memoized.Memoized
import java.util.LinkedHashMap
import java.util.SequencedMap

/** Proepeties set on a specific [PlatformInfo].  */
@AutoValue
abstract class PlatformProperties {
    abstract fun properties(): com.google.common.collect.ImmutableMap<String?, String?>?

    @Memoized
    abstract override fun hashCode(): Int

    val isEmpty: Boolean
        get() = properties().isEmpty()

    /** Builder class to facilitate creating valid [PlatformProperties] instances.  */
    class Builder {
        private var parent: PlatformProperties? = null
        private var properties: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParent(parent: PlatformProperties?): Builder {
            this.parent = parent
            return this
        }

        /** Returns the current properties (but not any from the parent), for validation.  */
        fun getProperties(): com.google.common.collect.ImmutableMap<String?, String?> {
            return this.properties
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProperties(properties: MutableMap<String?, String?>): Builder {
            this.properties = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(properties)
            return this
        }

        fun build(): PlatformProperties {
            val properties: com.google.common.collect.ImmutableMap<String?, String?>? =
                com.google.devtools.build.lib.analysis.platform.PlatformProperties.Builder.Companion.mergeParent(
                    parent,
                    this.properties
                )

            return AutoValue_PlatformProperties(properties)
        }

        companion object {
            private fun mergeParent(
                parent: PlatformProperties?, properties: com.google.common.collect.ImmutableMap<String?, String?>
            ): com.google.common.collect.ImmutableMap<String?, String?>? {
                if (parent == null || parent.isEmpty) {
                    return properties
                }

                val result: SequencedMap<String?, String?> = LinkedHashMap<String?, String?>()
                if (!parent.properties().isEmpty()) {
                    result.putAll(parent.properties())
                }

                if (!properties.isEmpty()) {
                    for (entry in properties.entries) {
                        if (com.google.common.base.Strings.isNullOrEmpty(entry.value)) {
                            result.remove(entry.key)
                        } else {
                            result.put(entry.key, entry.value)
                        }
                    }
                }

                return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(result)
            }
        }
    }

    companion object {
        /** Returns a new [Builder] for creating a fresh [PlatformProperties] instance.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.platform.PlatformProperties.Builder()
        }
    }
}
