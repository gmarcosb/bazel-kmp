// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.actions.Artifact

/** Provides information about jar files produced by a Java rule.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class JavaRuleOutputJarsProvider(javaOutputs: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.java.JavaOutput?>) :
    JavaInfoInternalProvider, JavaRuleOutputJarsProviderApi<com.google.devtools.build.lib.rules.java.JavaOutput?> {
    override fun getJavaOutputs(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.java.JavaOutput?> {
        return this.javaOutputs
    }

    val isImmutable: Boolean
        get() = true // immutable and Starlark-hashable

    val allClassOutputJars: Iterable<Artifact>
        /** Collects all class output jars from [.getJavaOutputs]  */
        get() = this.javaOutputs.stream()
            .map<Any?>(com.google.devtools.build.lib.rules.java.JavaOutput::classJar)
            .collect(Collectors.toList())

    val allSrcOutputJars: com.google.common.collect.ImmutableList<Artifact?>
        /** Collects all source output jars from [.getJavaOutputs]  */
        get() = this.javaOutputs.stream()
            .map<com.google.common.collect.ImmutableList<Artifact>>(com.google.devtools.build.lib.rules.java.JavaOutput::sourceJarsAsList)
            .flatMap<Artifact> { obj: com.google.common.collect.ImmutableList<Artifact>? -> obj.stream() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Artifact?>())

    @get:Deprecated("")
    val jdeps: Artifact?
        get() {
            val jdeps: com.google.common.collect.ImmutableList<Artifact?> =
                this.javaOutputs.stream()
                    .map<Any?>(com.google.devtools.build.lib.rules.java.JavaOutput::jdeps)
                    .filter { obj: Any? -> java.util.Objects.nonNull(obj) }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
            return if (jdeps.size == 1) jdeps.get(0) else null
        }

    @get:Deprecated("")
    val nativeHeaders: Artifact?
        get() {
            val nativeHeaders: com.google.common.collect.ImmutableList<Artifact?> =
                this.javaOutputs.stream()
                    .map<Any?>(com.google.devtools.build.lib.rules.java.JavaOutput::nativeHeadersJar)
                    .filter { obj: Any? -> java.util.Objects.nonNull(obj) }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
            return if (nativeHeaders.size == 1) nativeHeaders.get(0) else null
        }

    /** Builder for [JavaRuleOutputJarsProvider].  */
    class Builder {
        // CompactHashSet preserves insertion order here since we never perform any removals
        private val javaOutputs: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<com.google.devtools.build.lib.rules.java.JavaOutput?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<com.google.devtools.build.lib.rules.java.JavaOutput?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addJavaOutput(javaOutput: com.google.devtools.build.lib.rules.java.JavaOutput?): Builder {
            javaOutputs.add(javaOutput)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addJavaOutput(javaOutputs: MutableCollection<com.google.devtools.build.lib.rules.java.JavaOutput?>?): Builder {
            this.javaOutputs.addAll(javaOutputs)
            return this
        }

        fun build(): JavaRuleOutputJarsProvider {
            return JavaRuleOutputJarsProvider(
                com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.rules.java.JavaOutput?>(
                    javaOutputs
                )
            )
        }
    }

    val javaOutputs: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.java.JavaOutput?>

    init {
        this.javaOutputs = javaOutputs
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.java.JavaOutput?>?>(
            javaOutputs,
            "javaOutputs"
        )
    }

    companion object {
        @SerializationConstant
        val EMPTY: JavaRuleOutputJarsProvider =
            JavaRuleOutputJarsProvider(com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.rules.java.JavaOutput?>())

        fun builder(): Builder {
            return com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.Builder()
        }

        /**
         * Translates the `outputs` field of a [JavaInfo] instance into a native [ ] instance.
         * 
         * 
         * This method first attempts to transform the `outputs` field of the supplied `JavaInfo`. If this is not present (for example, in Bazel), it attempts to create the result
         * from the `java_outputs` field instead.
         * 
         * @param javaInfo the [JavaInfo] instance
         * @return a [JavaRuleOutputJarsProvider] instance
         * @throws EvalException if there are any errors accessing Starlark values
         * @throws RuleErrorException if any of the `output` instances are of incompatible type
         */
        @Throws(net.starlark.java.eval.EvalException::class, RuleErrorException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaRuleOutputJarsProvider? {
            val outputs: Any? = javaInfo.getValue("outputs")
            if (outputs == null) {
                return builder()
                    .addJavaOutput(
                        com.google.devtools.build.lib.rules.java.JavaOutput.wrapSequence(
                            net.starlark.java.eval.Sequence.cast<T?>(
                                javaInfo.getValue("java_outputs"),
                                java.util.Objects::class.java,
                                "java_outputs"
                            )
                        )
                    )
                    .build()
            } else {
                return fromStarlark(outputs)
            }
        }

        /**
         * Translates the supplied object into a [JavaRuleOutputJarsProvider] instance.
         * 
         * @param obj the object to translate
         * @return a [JavaRuleOutputJarsProvider] instance, or null if the supplied object was null
         * or [Starlark.NONE]
         * @throws EvalException if there were any errors reading fields from the supplied object
         * @throws RuleErrorException if the supplied object is not a [JavaRuleOutputJarsProvider]
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(net.starlark.java.eval.EvalException::class, RuleErrorException::class)
        fun fromStarlark(obj: Any): JavaRuleOutputJarsProvider? {
            if (obj === Starlark.NONE) {
                return EMPTY
            } else if (obj is JavaRuleOutputJarsProvider) {
                return obj
            } else if (obj is StructImpl) {
                return builder()
                    .addJavaOutput(
                        com.google.devtools.build.lib.rules.java.JavaOutput.wrapSequence(
                            net.starlark.java.eval.Sequence.cast<T?>(
                                (obj as StructImpl).getValue("jars"),
                                Any::class.java,
                                "jars"
                            )
                        )
                    )
                    .build()
            } else {
                throw RuleErrorException(
                    "expected JavaRuleOutputJarsProvider, got: " + Starlark.type(obj)
                )
            }
        }
    }
}
