// Copyright 2026 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax

import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build

object TestUtils {
    /**
     * Returns the first error whose string form contains the specified substring, or throws an
     * informative AssertionError if there is none.
     */
    fun assertContainsError(
        errors: MutableList<net.starlark.java.syntax.SyntaxError>,
        substr: String?
    ): net.starlark.java.syntax.SyntaxError {
        for (error in errors) {
            if (error.toString().contains(substr)) {
                return error
            }
        }
        if (errors.isEmpty()) {
            throw java.lang.AssertionError("no errors, want '" + substr + "'")
        } else {
            throw java.lang.AssertionError(
                "error '" + substr + "' not found, but got these:\n" + com.google.common.base.Joiner.on("\n")
                    .join(errors)
            )
        }
    }

    /**
     * A static resolver [net.starlark.java.syntax.Resolver.Module] implementation, for tests of
     * the resolver and type checker.
     * 
     * 
     * This `Module` only supports predeclared symbols, not universals or predefined globals.
     * The absence of universals means that any test cases relying on this `Module` cannot
     * process Starlark code snippets containing builtin singletons (`None`/`True`/`False`) or functions (`len()`, etc.).
     */
    class Module private constructor(
        predeclared: MutableSet<String?>,
        typeConstructors: MutableMap<String?, net.starlark.java.syntax.TypeConstructor?>
    ) : net.starlark.java.syntax.Resolver.Module {
        private val predeclared: com.google.common.collect.ImmutableSet<String?>
        private val typeConstructors: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.TypeConstructor?>

        init {
            this.predeclared = com.google.common.collect.ImmutableSet.copyOf<String?>(predeclared)
            this.typeConstructors =
                com.google.common.collect.ImmutableMap.copyOf<String?, net.starlark.java.syntax.TypeConstructor?>(
                    typeConstructors
                )
        }

        @Throws(net.starlark.java.syntax.Resolver.Module.Undefined::class)
        override fun resolve(name: String?): net.starlark.java.syntax.Resolver.Scope {
            if (predeclared.contains(name)) {
                return net.starlark.java.syntax.Resolver.Scope.PREDECLARED
            } else {
                throw net.starlark.java.syntax.Resolver.Module.Undefined(
                    String.format(
                        "name '%s' is not defined",
                        name
                    ), predeclared
                )
            }
        }

        @Throws(net.starlark.java.syntax.Resolver.Module.Undefined::class)
        override fun getTypeConstructor(name: String?): net.starlark.java.syntax.TypeConstructor? {
            resolve(name) // throws if unknown
            return typeConstructors.get(name)
        }

        override fun getStrFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
            return null
        }

        override fun getListFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
            return null
        }

        override fun getDictFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
            return null
        }

        override fun getSetFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
            return null
        }

        override fun getPredeclaredSymbolType(name: String?): net.starlark.java.syntax.StarlarkType? {
            return if (predeclared.contains(name)) net.starlark.java.syntax.Types.ANY else null
        }

        override fun getUniversalSymbolType(name: String?): net.starlark.java.syntax.StarlarkType? {
            throw java.lang.UnsupportedOperationException("universal types not supported")
        }

        companion object {
            /**
             * Returns a Module with the given names as predeclared symbols, which are not type
             * constructors.
             */
            fun withPredeclared(vararg names: String?): Module {
                return net.starlark.java.syntax.TestUtils.Module.Companion.withPredeclared(
                    com.google.common.collect.ImmutableSet.copyOf<String?>(
                        names
                    )
                )
            }

            /**
             * Returns a Module with the given names as predeclared symbols, which are not type
             * constructors.
             */
            fun withPredeclared(names: MutableCollection<String?>): Module {
                return net.starlark.java.syntax.TestUtils.Module(
                    com.google.common.collect.ImmutableSet.copyOf<String?>(
                        names
                    ), com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.TypeConstructor?>()
                )
            }

            /**
             * Returns a Module with the given predeclared names and (optional) associated type
             * constructors.
             * 
             * 
             * Arguments must be alternating pairs of `String`s and [TypeConstructor]s. The
             * `TypeConstructor` references may be null, which indicates that the corresponding name
             * cannot be used as a type constructor. For example, `withTypes("foo", null)` is
             * equivalent to `withPredeclared("foo")`.
             */
            fun withTypes(vararg args: Any?): Module {
                return net.starlark.java.syntax.TestUtils.Module.Companion.withTypes(
                    com.google.common.collect.ImmutableMap.of<String?, net.starlark.java.syntax.TypeConstructor?>(),
                    *args
                )
            }

            /**
             * Same as [.withTypes], but accepts entries specified via `base` in
             * addition to the alternating pairs given in `args`.
             */
            fun withTypes(
                base: MutableMap<String?, net.starlark.java.syntax.TypeConstructor?>,
                vararg args: Any?
            ): Module {
                val predeclared: com.google.common.collect.ImmutableSet.Builder<String?> =
                    com.google.common.collect.ImmutableSet.builder<String?>()
                val typeConstructors: com.google.common.collect.ImmutableMap.Builder<String?, net.starlark.java.syntax.TypeConstructor?> =
                    com.google.common.collect.ImmutableMap.builder<String?, net.starlark.java.syntax.TypeConstructor?>()
                for (entry in base.entries) {
                    predeclared.add(entry.key)
                    if (entry.value != null) {
                        typeConstructors.put(entry)
                    }
                }
                com.google.common.base.Preconditions.checkArgument(
                    args.size % 2 == 0,
                    "`args` must have an even length"
                )
                var i = 0
                while (i < args.size) {
                    val name = args[i] as String?
                    val tc: net.starlark.java.syntax.TypeConstructor? =
                        args[i + 1] as net.starlark.java.syntax.TypeConstructor?
                    predeclared.add(name)
                    if (tc != null) {
                        typeConstructors.put(name, tc)
                    }
                    i += 2
                }
                return net.starlark.java.syntax.TestUtils.Module(predeclared.build(), typeConstructors.buildOrThrow())
            }

            /** Returns a Module with the universal type constructors.  */
            fun withUniversalTypes(): Module {
                return net.starlark.java.syntax.TestUtils.Module.Companion.withTypes(net.starlark.java.syntax.Types.TYPE_UNIVERSE)
            }

            /** Same as [.withTypes], but includes the universal types.  */
            fun withUniversalTypesAnd(vararg args: Any?): Module {
                return net.starlark.java.syntax.TestUtils.Module.Companion.withTypes(
                    net.starlark.java.syntax.Types.TYPE_UNIVERSE,
                    *args
                )
            }
        }
    }

    /** A static [TypeTagger.LoadableModule] implementation, for tests of the type checker.  */
    class LoadableModule(exports: MutableMap<String?, net.starlark.java.syntax.StarlarkType?>) :
        net.starlark.java.syntax.TypeTagger.LoadableModule {
        private val exports: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.StarlarkType?>

        init {
            this.exports =
                com.google.common.collect.ImmutableMap.copyOf<String?, net.starlark.java.syntax.StarlarkType?>(exports)
        }

        override fun getExports(): MutableSet<String?> {
            return exports.keys
        }

        override fun hasExport(name: String?): Boolean {
            return exports.containsKey(name)
        }

        override fun getExportType(name: String?): net.starlark.java.syntax.StarlarkType? {
            return exports.get(name)
        }

        companion object {
            /** Creates a LoadableModule with exports expressed as flattened name-type pairs.  */
            fun of(vararg args: Any?): LoadableModule {
                com.google.common.base.Preconditions.checkArgument(args.size % 2 == 0)
                val exports: com.google.common.collect.ImmutableMap.Builder<String?, net.starlark.java.syntax.StarlarkType?> =
                    com.google.common.collect.ImmutableMap.builder<String?, net.starlark.java.syntax.StarlarkType?>()
                var i = 0
                while (i < args.size) {
                    exports.put(args[i] as String?, args[i + 1] as net.starlark.java.syntax.StarlarkType?)
                    i += 2
                }
                return net.starlark.java.syntax.TestUtils.LoadableModule(exports.buildOrThrow())
            }
        }
    }
}
