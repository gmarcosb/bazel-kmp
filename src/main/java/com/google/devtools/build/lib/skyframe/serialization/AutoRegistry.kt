// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.CodecScanner
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import java.io.IOException

/**
 * A lazy, automatically populated registry.
 * 
 * 
 * Must not be accessed by any [CodecRegisterer] or [ObjectCodec] constructors or
 * static initializers.
 */
object AutoRegistry {
    private val SUPPLIER: java.util.function.Supplier<ObjectCodecRegistry?> =
        com.google.common.base.Suppliers.memoize<ObjectCodecRegistry?>(com.google.common.base.Supplier { obj: AutoRegistry? -> create() })

    // Build codecs only for Bazel and Starlark classes.
    private fun packageFilter(name: String): Boolean {
        return name.startsWith("com.google.devtools.build.lib")
                || name.startsWith("com.google.devtools.build.sky")
                || name.startsWith("com.google.devtools.common.options") // e.g. for Tristate
                || name.startsWith("net.starlark.java")
    }

    /** Class name prefixes to forbid for [DynamicCodec].  */
    private val CLASS_NAME_PREFIX_BLACKLIST: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>(
            "com.google.devtools.build.lib.google",
            "com.google.devtools.build.lib.vfs",
            "com.google.devtools.build.lib.actions.ArtifactFactory",
            "com.google.devtools.build.lib.packages.PackageFactory\$BuiltInRuleFunction",
            "com.google.devtools.build.skyframe.SkyFunctionEnvironment"
        )

    /** Classes outside [AutoRegistry.packageFilter] that need to be serialized.  */
    private val EXTERNAL_CLASS_NAMES_TO_REGISTER: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>(
            "java.io.FileNotFoundException",
            "java.io.IOException",
            "java.lang.StackTraceElement",
            "java.lang.invoke.SerializedLambda",
            "java.lang.ref.SoftReference",
            "java.time.Instant",
            "com.google.common.base.Predicates\$InPredicate",  // Implementation class for com.google.common.base.Optional.
            "com.google.common.base.Present",
            "com.google.common.collect.ImmutableEntry",  // Sadly, these builders are serialized as part of StarlarkCustomCommandLine$Builder,
            // which apparently can be preserved through analysis. We may investigate if this actually
            // has performance/correctness implications.
            "com.google.common.collect.ImmutableList\$Builder",
            "java.util.concurrent.atomic.AtomicReference",
            "java.util.concurrent.atomic.AtomicReferenceArray",  // These list types are internal to the Java Collections API but persisted in Skyframe.
            "java.util.Arrays\$ArrayList",
            "java.util.AbstractMap\$SimpleEntry",
            "java.util.AbstractMap\$SimpleImmutableEntry",
            "java.util.Collections\$SingletonList",
            "java.util.Collections\$UnmodifiableList",
            "java.util.Collections\$UnmodifiableRandomAccessList",
            "java.util.KeyValueHolder",
            "java.util.Optional"
        )

    private val REFERENCE_CONSTANTS_TO_REGISTER: com.google.common.collect.ImmutableList<Any?> =
        com.google.common.collect.ImmutableList.of<Any?>(
            com.google.common.base.Predicates.alwaysTrue<Any?>(),
            com.google.common.base.Predicates.alwaysFalse<Any?>(),
            com.google.common.base.Predicates.isNull<Any?>(),
            com.google.common.base.Predicates.notNull<Any?>(),
            com.google.common.collect.ImmutableList.of<Any?>(),
            com.google.common.collect.ImmutableSet.of<Any?>(),
            java.util.Comparator.naturalOrder<T?>(),
            com.google.common.collect.Ordering.natural<Comparable<*>?>()
        )

    @kotlin.jvm.JvmStatic
    fun get(): ObjectCodecRegistry? {
        return SUPPLIER.get()
    }

    private fun create(): ObjectCodecRegistry? {
        try {
            val registry: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
                CodecScanner.initializeCodecRegistry(java.util.function.Predicate { obj: String? -> AutoRegistry.packageFilter() })
            for (className in EXTERNAL_CLASS_NAMES_TO_REGISTER) {
                registry.addClassName(className)
            }
            for (constant in REFERENCE_CONSTANTS_TO_REGISTER) {
                registry.addReferenceConstant(constant)
            }
            for (classNamePrefix in CLASS_NAME_PREFIX_BLACKLIST) {
                registry.excludeClassNamePrefix(classNamePrefix)
            }
            return registry.build()
        } catch (e: IOException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalStateException(e)
        }
    }
}
