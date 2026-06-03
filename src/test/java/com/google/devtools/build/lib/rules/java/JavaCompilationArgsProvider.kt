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

/**
 * A collection of recursively collected Java build information.
 * 
 * @param runtimeJars Returns recursively collected runtime jars.
 * @param directCompileTimeJars Returns non-recursively collected compile-time jars. This is the set
 * of jars that compilations are permitted to reference with Strict Java Deps enabled.
 * 
 * If you're reading this, you probably want [.getTransitiveCompileTimeJars] .
 * @param transitiveCompileTimeJars Returns recursively collected compile-time jars. This is the
 * compile-time classpath passed to the compiler.
 * @param directFullCompileTimeJars Returns non-recursively collected, non-interface compile-time
 * jars.
 * 
 * If you're reading this, you probably want [.getTransitiveCompileTimeJars] .
 * @param transitiveFullCompileTimeJars Returns recursively collected, non-interface compile-time
 * jars.
 * 
 * If you're reading this, you probably want [.getTransitiveCompileTimeJars] .
 * @param compileTimeJavaDependencyArtifacts Returns non-recursively collected Java dependency
 * artifacts for computing a restricted classpath when building this target (called when
 * strict_java_deps = 1).
 * 
 * Note that dependency artifacts are needed only when non-recursive compilation args do not
 * provide a safe super-set of dependencies. Non-strict targets such as proto_library, always
 * collecting their transitive closure of deps, do not need to provide dependency artifacts.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class JavaCompilationArgsProvider(
    runtimeJars: NestedSet<Artifact?>?,
    directCompileTimeJars: NestedSet<Artifact?>?,
    transitiveCompileTimeJars: NestedSet<Artifact?>?,
    directFullCompileTimeJars: NestedSet<Artifact?>?,
    transitiveFullCompileTimeJars: NestedSet<Artifact?>?,
    compileTimeJavaDependencyArtifacts: NestedSet<Artifact?>?,
    directHeaderCompilationJars: NestedSet<Artifact?>?
) : JavaInfoInternalProvider {
    val runtimeJars: NestedSet<Artifact?>?
    val directCompileTimeJars: NestedSet<Artifact?>?
    val transitiveCompileTimeJars: NestedSet<Artifact?>?
    val directFullCompileTimeJars: NestedSet<Artifact?>?
    val transitiveFullCompileTimeJars: NestedSet<Artifact?>?
    val compileTimeJavaDependencyArtifacts: NestedSet<Artifact?>?
    val directHeaderCompilationJars: NestedSet<Artifact?>?

    init {
        this.directHeaderCompilationJars = directHeaderCompilationJars
        this.compileTimeJavaDependencyArtifacts = compileTimeJavaDependencyArtifacts
        this.transitiveFullCompileTimeJars = transitiveFullCompileTimeJars
        this.directFullCompileTimeJars = directFullCompileTimeJars
        this.transitiveCompileTimeJars = transitiveCompileTimeJars
        this.directCompileTimeJars = directCompileTimeJars
        this.runtimeJars = runtimeJars
        java.util.Objects.requireNonNull<Any?>(runtimeJars, "runtimeJars")
        java.util.Objects.requireNonNull<Any?>(directCompileTimeJars, "directCompileTimeJars")
        java.util.Objects.requireNonNull<Any?>(transitiveCompileTimeJars, "transitiveCompileTimeJars")
        java.util.Objects.requireNonNull<Any?>(directFullCompileTimeJars, "directFullCompileTimeJars")
        java.util.Objects.requireNonNull<Any?>(transitiveFullCompileTimeJars, "transitiveFullCompileTimeJars")
        java.util.Objects.requireNonNull<Any?>(compileTimeJavaDependencyArtifacts, "compileTimeJavaDependencyArtifacts")
        java.util.Objects.requireNonNull<Any?>(directHeaderCompilationJars, "directHeaderCompilationJars")
    }

    companion object {
        @SerializationConstant
        val EMPTY: JavaCompilationArgsProvider = create(
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.create(Order.NAIVE_LINK_ORDER)
        )

        private fun create(
            runtimeJars: NestedSet<Artifact?>?,
            directCompileTimeJars: NestedSet<Artifact?>?,
            transitiveCompileTimeJars: NestedSet<Artifact?>?,
            directFullCompileTimeJars: NestedSet<Artifact?>?,
            transitiveFullCompileTimeJars: NestedSet<Artifact?>?,
            compileTimeJavaDependencyArtifacts: NestedSet<Artifact?>?,
            directHeaderCompilationJars: NestedSet<Artifact?>?
        ): JavaCompilationArgsProvider {
            return JavaCompilationArgsProvider(
                runtimeJars,
                directCompileTimeJars,
                transitiveCompileTimeJars,
                directFullCompileTimeJars,
                transitiveFullCompileTimeJars,
                compileTimeJavaDependencyArtifacts,
                directHeaderCompilationJars
            )
        }

        /**
         * Constructs a [JavaCompilationArgsProvider] instance for a Starlark-constructed [ ].
         * 
         * @param javaInfo the [JavaInfo] instance from which to extract the relevant fields
         * @return a [JavaCompilationArgsProvider] instance, or `null` if this is a [     ] for a `java_binary` or `java_test`
         * @throws EvalException if there were errors reading any fields
         * @throws TypeException if some field was not a [Depset] of [Artifact]s
         */
        @Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaCompilationArgsProvider? {
            val isBinary: Boolean? = javaInfo.getValue("_is_binary", Boolean::class.java)
            if (isBinary != null && isBinary) {
                return null
            }
            return create( /* runtimeJars= */
                getDepset(javaInfo, "transitive_runtime_jars"),  /* directCompileTimeJars= */
                getDepset(javaInfo, "compile_jars"),  /* transitiveCompileTimeJars= */
                getDepset(javaInfo, "transitive_compile_time_jars"),  /* directFullCompileTimeJars= */
                getDepset(javaInfo, "full_compile_jars"),  /* transitiveFullCompileTimeJars= */
                getDepset(
                    javaInfo, "_transitive_full_compile_time_jars"
                ),  /* compileTimeJavaDependencyArtifacts= */
                getDepset(
                    javaInfo, "_compile_time_java_dependencies"
                ),  /* directHeaderCompilationJars= */
                getDepset(javaInfo, "header_compilation_direct_deps")
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
        private fun getDepset(javaInfo: StructImpl, name: String?): NestedSet<Artifact?> {
            return javaInfo.getValue(name, Depset::class.java).getSet(Artifact::class.java)
        }
    }
}
