// Copyright 2016 The Bazel Authors. All rights reserved.
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

/**
 * A class that provides compilation information in Java rules, for perusal of aspects and tools.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class JavaCompilationInfoProvider(
    getJavacOpts: NestedSet<String?>?,
    runtimeClasspath: NestedSet<Artifact?>?,
    compilationClasspath: NestedSet<Artifact?>?,
    bootClasspath: NestedSet<Artifact?>?
) : JavaInfoInternalProvider, JavaCompilationInfoProviderApi<Artifact?> {
    val isImmutable: Boolean
        get() = true // immutable and Starlark-hashable

    /** Builder for [JavaCompilationInfoProvider].  */
    class Builder {
        private var javacOpts: NestedSet<String?>? = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
        private var runtimeClasspath: NestedSet<Artifact?>? = null
        private var compilationClasspath: NestedSet<Artifact?>? = null
        private var bootClasspath: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setJavacOpts(@javax.annotation.Nonnull javacOpts: NestedSet<String?>): Builder {
            this.javacOpts = javacOpts
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRuntimeClasspath(runtimeClasspath: NestedSet<Artifact?>?): Builder {
            this.runtimeClasspath = runtimeClasspath
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCompilationClasspath(compilationClasspath: NestedSet<Artifact?>?): Builder {
            this.compilationClasspath = compilationClasspath
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBootClasspath(bootClasspath: NestedSet<Artifact?>?): Builder {
            this.bootClasspath = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>?>(bootClasspath)
            return this
        }

        @Throws(RuleErrorException::class)
        fun build(): JavaCompilationInfoProvider {
            return JavaCompilationInfoProvider(
                javacOpts, runtimeClasspath, compilationClasspath, bootClasspath
            )
        }
    }

    val javacOptsStarlark: Depset
        get() = Depset.of(String::class.java, this.getJavacOpts)

    @get:com.google.common.annotations.VisibleForTesting
    val javacOptsList: com.google.common.collect.ImmutableList<String?>?
        get() = JavaHelper.tokenizeJavaOptions(this.getJavacOpts)

    override fun  /*<Artifact>*/getRuntimeClasspath(): Depset? {
        return if (this.runtimeClasspath == null) null else Depset.of(Artifact::class.java, this.runtimeClasspath)
    }

    override fun  /*<Artifact>*/getCompilationClasspath(): Depset? {
        return if (this.compilationClasspath == null)
            null
        else
            Depset.of(Artifact::class.java, this.compilationClasspath)
    }

    val bootClasspathList: com.google.common.collect.ImmutableList<Artifact?>
        get() = this.bootClasspath.toList()

    /*
   * Underrides the @Autovalue implementation.
   * We shouldn't be doing this, but this is necessary to allow Starlark-constructed instances to
   * be compared with natively constructed instances. The difference arises only because of the
   * boot classpath. The Starlark API returns a list, while we store a NestedSet in
   * native for efficiency. When we reconstruct a native instance from a Starlark one, the list is
   * wrapped in a new NestedSet instance. Since NestedSet equality relies on
   * reference-equality, here, we perform a NestedSet#shallowEquals only for the bootClasspath to
   * verify the contents are the same.
   * Note: this is temporary, and is required only while JavaCompilationInfoProvider is still
   * constructed in native code. Once the migration to Starlark is complete, this will be deleted as
   * this class will no longer have any fields but will simply wrap the StarlarkInfo instance and
   * delegate in each of its public methods.
   */
    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is JavaCompilationInfoProvider) {
            return false
        }
        return this.getJavacOpts.shallowEquals(obj.getJavacOpts)
                && runtimeClasspath == obj.runtimeClasspath
                && compilationClasspath == obj.compilationClasspath
                && this.bootClasspath.shallowEquals(obj.bootClasspath)
    }

    /* See comment for #equals above on why we need this. */
    override fun hashCode(): Int {
        return java.util.Objects.hash(
            this.getJavacOpts,
            runtimeClasspath,
            compilationClasspath,
            this.bootClasspath.shallowHashCode()
        )
    }

    val getJavacOpts: NestedSet<String?>?
    val runtimeClasspath: NestedSet<Artifact?>?
    val compilationClasspath: NestedSet<Artifact?>?
    val bootClasspath: NestedSet<Artifact?>?

    init {
        this.bootClasspath = bootClasspath
        this.compilationClasspath = compilationClasspath
        this.runtimeClasspath = runtimeClasspath
        this.getJavacOpts = getJavacOpts
        java.util.Objects.requireNonNull<Any?>(getJavacOpts, "getJavacOpts")
        java.util.Objects.requireNonNull<Any?>(bootClasspath, "bootClasspath")
    }

    companion object {
        /**
         * Transforms the `compilation_info` field from a [JavaInfo] into a native instance.
         * 
         * @param javaInfo A [JavaInfo] instance.
         * @return a [JavaCompilationInfoProvider] instance or `null` if the `compilation_info` field is not present in the supplied `javaInfo`
         * @throws RuleErrorException if the `compilation_info` is of an incompatible type
         * @throws EvalException if there are any errors accessing Starlark values
         */
        @Throws(RuleErrorException::class, net.starlark.java.eval.EvalException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaCompilationInfoProvider? {
            val value: Any? = javaInfo.getValue("compilation_info")
            return fromStarlarkCompilationInfo(value)
        }

        /**
         * Translates an instance of [JavaCompilationInfoProvider] for use in native code.
         * 
         * @param value The object to translate
         * @return a [JavaCompilationInfoProvider] instance, or null if the supplied value is null
         * or [Starlark.NONE]
         * @throws EvalException if there are errors reading any fields from the [StructImpl]
         * @throws RuleErrorException if the supplied value is not compatible with [     ]
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(net.starlark.java.eval.EvalException::class, RuleErrorException::class)
        fun fromStarlarkCompilationInfo(value: Any?): JavaCompilationInfoProvider? {
            if (value == null || value === Starlark.NONE) {
                return null
            } else if (value is JavaCompilationInfoProvider) {
                return value
            } else if (value is StructImpl) {
                val builder: Builder =
                    com.google.devtools.build.lib.rules.java.JavaCompilationInfoProvider.Builder()
                        .setJavacOpts(
                            Depset.cast(value.getValue("javac_options"), String::class.java, "javac_options")
                        )
                        .setBootClasspath(
                            NestedSetBuilder.wrap(
                                Order.NAIVE_LINK_ORDER,
                                net.starlark.java.eval.Sequence.noneableCast<T?>(
                                    value.getValue("boot_classpath"), Artifact::class.java, "boot_classpath"
                                )
                            )
                        )
                val runtimeClasspath: Any? = value.getValue("runtime_classpath")
                if (runtimeClasspath != null) {
                    builder.setRuntimeClasspath(
                        Depset.noneableCast(runtimeClasspath, Artifact::class.java, "runtime_classpath")
                    )
                }
                val compilationClasspath: Any? = value.getValue("compilation_classpath")
                if (compilationClasspath != null) {
                    builder.setCompilationClasspath(
                        Depset.noneableCast(compilationClasspath, Artifact::class.java, "compilation_classpath")
                    )
                }
                return builder.build()
            }
            throw RuleErrorException("expected java_compilation_info, got: " + Starlark.type(value))
        }
    }
}
