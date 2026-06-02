// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkSemantics
import java.util.*

/**
 * A collection of artifacts associated with a jar output.
 * 
 * @param sourceJars A [NestedSet] of sources archive files.
 */
@ThreadSafety.Immutable
@AutoCodec
class JavaOutput(
    classJar: Artifact?,
    compileJar: Artifact?,
    headerCompilationJar: Artifact?,
    compileJdeps: Artifact?,
    generatedClassJar: Artifact?,
    generatedSourceJar: Artifact?,
    nativeHeadersJar: Artifact?,
    manifestProto: Artifact?,
    jdeps: Artifact?,
    sourceJars: NestedSet<Artifact?>?
) : JavaOutputApi<Artifact?> {
    override fun getClassJar(): Artifact? {
        return this.classJar
    }

    override fun getCompileJar(): Artifact? {
        return this.compileJar
    }

    override fun getHeaderCompilationJar(): Artifact? {
        return headerCompilationJar
    }

    override fun getCompileJdeps(): Artifact? {
        return this.compileJdeps
    }

    override fun getGeneratedClassJar(): Artifact? {
        return this.generatedClassJar
    }

    override fun getGeneratedSourceJar(): Artifact? {
        return this.generatedSourceJar
    }

    override fun getNativeHeadersJar(): Artifact? {
        return this.nativeHeadersJar
    }

    override fun getManifestProto(): Artifact? {
        return this.manifestProto
    }

    override fun getJdeps(): Artifact? {
        return this.jdeps
    }

    val isImmutable: Boolean
        get() = true // immutable and Starlark-hashable

    @get:Deprecated("")
    val iJar: Artifact?
        get() = this.compileJar

    @get:Deprecated("")
    val srcJar: Artifact?
        get() = Iterables.getOnlyElement<Artifact?>(this.sourceJarsAsList, null)

    val sourceJarsAsList: ImmutableList<Artifact>
        get() = this.sourceJars.toList()

    override fun getSrcJarsStarlark(semantics: StarlarkSemantics?): Depset? {
        return Depset.of(Artifact::class.java, this.sourceJars)
    }

    /** Builder for OutputJar.  */
    @AutoBuilder
    abstract class Builder {
        private val sourceJarsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()

        abstract fun setClassJar(value: Artifact?): Builder?

        abstract fun setCompileJar(value: Artifact?): Builder?

        abstract fun setHeaderCompilationJar(value: Artifact?): Builder?

        abstract fun setCompileJdeps(value: Artifact?): Builder?

        abstract fun setGeneratedClassJar(value: Artifact?): Builder?

        abstract fun setGeneratedSourceJar(value: Artifact?): Builder?

        abstract fun setNativeHeadersJar(value: Artifact?): Builder?

        abstract fun setManifestProto(value: Artifact?): Builder?

        abstract fun setJdeps(value: Artifact?): Builder?

        @CanIgnoreReturnValue
        abstract fun setSourceJars(value: NestedSet<Artifact?>?): Builder?

        fun addSourceJar(value: Artifact?): Builder {
            if (value != null) {
                sourceJarsBuilder.add(value)
            }
            return this
        }

        fun addSourceJars(values: NestedSet<Artifact?>?): Builder {
            sourceJarsBuilder.addTransitive(values)
            return this
        }

        /** Populates the builder with outputs from [JavaCompileOutputs].  */
        fun fromJavaCompileOutputs(value: JavaCompileOutputs<Artifact?>): Builder {
            return fromJavaCompileOutputs(value, true)
        }

        @CanIgnoreReturnValue
        fun fromJavaCompileOutputs(
            value: JavaCompileOutputs<Artifact?>, includeJdeps: Boolean
        ): Builder {
            setClassJar(value.output())
            if (includeJdeps) {
                setJdeps(value.depsProto())
            }
            setGeneratedClassJar(value.genClass())
            setGeneratedSourceJar(value.genSource())
            setNativeHeadersJar(value.nativeHeader())
            setManifestProto(value.manifestProto())
            return this
        }

        abstract fun autoBuild(): JavaOutput?

        fun build(): JavaOutput? {
            setSourceJars(sourceJarsBuilder.build())
            return autoBuild()
        }
    }

    val classJar: Artifact?
    val compileJar: Artifact?
    val headerCompilationJar: Artifact?
    val compileJdeps: Artifact?
    val generatedClassJar: Artifact?
    val generatedSourceJar: Artifact?
    val nativeHeadersJar: Artifact?
    val manifestProto: Artifact?
    val jdeps: Artifact?
    val sourceJars: NestedSet<Artifact?>?

    init {
        this.sourceJars = sourceJars
        this.jdeps = jdeps
        this.manifestProto = manifestProto
        this.nativeHeadersJar = nativeHeadersJar
        this.generatedSourceJar = generatedSourceJar
        this.generatedClassJar = generatedClassJar
        this.compileJdeps = compileJdeps
        this.headerCompilationJar = headerCompilationJar
        this.compileJar = compileJar
        this.classJar = classJar
        Objects.requireNonNull<Any?>(classJar, "classJar")
        Objects.requireNonNull<Any?>(sourceJars, "sourceJars")
    }

    companion object {
        /**
         * Translates a collection of [JavaOutput] for use in native code.
         * 
         * @param outputs the collection of translate
         * @return an immutable list of [JavaOutput] instances
         * @throws EvalException if there were errors reading fields from the `Starlark` object
         * @throws RuleErrorException if any item in the supplied collection is not a valid [     ]
         */
        @VisibleForTesting
        @Throws(EvalException::class, RuleErrorException::class)
        fun wrapSequence(outputs: MutableCollection<*>): ImmutableList<JavaOutput?> {
            val result = ImmutableList.builder<JavaOutput?>()
            for (info in outputs) {
                if (info is JavaOutput) {
                    result.add(info)
                } else if (info is StructImpl) {
                    result.add(fromStarlarkJavaOutput(info))
                } else {
                    throw RuleErrorException("expected JavaOutput, got: " + Starlark.type(info))
                }
            }
            return result.build()
        }

        @Throws(EvalException::class)
        fun fromStarlarkJavaOutput(struct: StructImpl): JavaOutput? {
            val sourceJars: NestedSet<Artifact?>?
            val starlarkSourceJars: Any? = struct.getValue("source_jars")
            if (starlarkSourceJars === Starlark.NONE || starlarkSourceJars is Depset) {
                sourceJars = Depset.noneableCast(starlarkSourceJars, Artifact::class.java, "source_jars")
            } else {
                sourceJars =
                    NestedSetBuilder.wrap(
                        Order.STABLE_ORDER, Sequence.cast<T?>(starlarkSourceJars, Artifact::class.java, "source_jars")
                    )
            }
            return builder()
                .setClassJar(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue("class_jar"),
                        Artifact::class.java
                    )
                )!!
                .setCompileJar(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue("compile_jar"),
                        Artifact::class.java
                    )
                )!!
                .setHeaderCompilationJar(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue("header_compilation_jar"),
                        Artifact::class.java
                    )
                )!!
                .setCompileJdeps(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue("compile_jdeps"),
                        Artifact::class.java
                    )
                )!!
                .setGeneratedClassJar(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue(
                            "generated_class_jar"
                        ), Artifact::class.java
                    )
                )!!
                .setGeneratedSourceJar(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue(
                            "generated_source_jar"
                        ), Artifact::class.java
                    )
                )!!
                .setNativeHeadersJar(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue(
                            "native_headers_jar"
                        ), Artifact::class.java
                    )
                )!!
                .setManifestProto(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue("manifest_proto"),
                        Artifact::class.java
                    )
                )!!
                .setJdeps(
                    com.google.devtools.build.lib.rules.java.JavaOutput.Companion.nullIfNone<T?>(
                        struct.getValue("jdeps"),
                        Artifact::class.java
                    )
                )!!
                .addSourceJars(sourceJars)
                .build()
        }

        fun <T> nullIfNone(`object`: Any?, type: Class<T?>): T? {
            return if (`object` !== Starlark.NONE) type.cast(`object`) else null
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_JavaOutput_Builder()
        }
    }
}
