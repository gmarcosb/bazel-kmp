// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Types.STRING_LIST
import com.google.devtools.build.lib.rules.java.JavaModuleFlagsProvider.Companion.create

/**
 * Provides information about `--add-exports=` and `--add-opens=` flags for Java
 * targets.
 */
@AutoCodec
internal class JavaModuleFlagsProvider(addExports: NestedSet<String?>?, addOpens: NestedSet<String?>?) :
    JavaInfoInternalProvider, JavaModuleFlagsProviderApi {
    val isImmutable: Boolean
        get() = true

    override fun  /*String*/getAddExports(): Depset {
        return Depset.of(String::class.java, this.addExports)
    }

    override fun  /*String*/getAddOpens(): Depset {
        return Depset.of(String::class.java, this.addOpens)
    }

    val addExports: NestedSet<String?>?
    val addOpens: NestedSet<String?>?

    init {
        this.addOpens = addOpens
        this.addExports = addExports
        java.util.Objects.requireNonNull<Any?>(addExports, "addExports")
        java.util.Objects.requireNonNull<Any?>(addOpens, "addOpens")
    }

    companion object {
        fun create(
            addExports: NestedSet<String?>?, addOpens: NestedSet<String?>?
        ): JavaModuleFlagsProvider {
            return JavaModuleFlagsProvider(addExports, addOpens)
        }

        val EMPTY: JavaModuleFlagsProvider? = create(
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        )

        fun create(
            addExports: MutableList<String?>?,
            addOpens: MutableList<String?>?,
            transitive: java.util.stream.Stream<JavaModuleFlagsProvider?>
        ): JavaModuleFlagsProvider? {
            val addExportsBuilder: NestedSetBuilder<String?> = NestedSetBuilder.stableOrder()
            val addOpensBuilder: NestedSetBuilder<String?> = NestedSetBuilder.stableOrder()
            addExportsBuilder.addAll(addExports)
            addOpensBuilder.addAll(addOpens)
            transitive.forEach { provider: JavaModuleFlagsProvider? ->
                addExportsBuilder.addTransitive(provider!!.addExports)
                addOpensBuilder.addTransitive(provider.addOpens)
            }
            if (addExportsBuilder.isEmpty() && addOpensBuilder.isEmpty()) {
                return EMPTY
            }
            return create(addExportsBuilder.build(), addOpensBuilder.build())
        }

        fun create(
            ruleContext: RuleContext, transitive: java.util.stream.Stream<JavaModuleFlagsProvider?>
        ): JavaModuleFlagsProvider? {
            val attributes: AttributeMap = ruleContext.attributes()
            return create(
                attributes.getOrDefault("add_exports", STRING_LIST, com.google.common.collect.ImmutableList.of<E?>()),
                attributes.getOrDefault("add_opens", STRING_LIST, com.google.common.collect.ImmutableList.of<E?>()),
                transitive
            )
        }

        @kotlin.jvm.JvmOverloads
        fun toFlags(
            addExports: MutableList<String?> = this.addExports.toList(),
            addOpens: MutableList<String?> = this.addOpens.toList()
        ): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.Streams.concat<String?>(
                addExports.stream().map<String?> { x: String? -> String.format("--add-exports=%s=ALL-UNNAMED", x) },
                addOpens.stream().map<String?> { x: String? -> String.format("--add-opens=%s=ALL-UNNAMED", x) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        }

        /**
         * Translates the `module_flags_info` from a [JavaInfo] to the native class.
         * 
         * @param javaInfo a [JavaInfo] provider instance
         * @return a [JavaModuleFlagsProvider] instance or `null` if `module_flags_info`
         * is absent or `None`
         * @throws EvalException if there are any errors accessing Starlark values
         * @throws TypeException if any depset values are of an incompatible type
         * @throws RuleErrorException if the `module_flags_info` is of an incompatible type
         */
        @Throws(net.starlark.java.eval.EvalException::class, TypeException::class, RuleErrorException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaModuleFlagsProvider? {
            val value: Any? = javaInfo.getValue("module_flags_info")
            if (value == null || value === Starlark.NONE) {
                return null
            } else if (value is JavaModuleFlagsProvider) {
                return value
            } else if (value is StructImpl) {
                return create(
                    value.getValue("add_exports", Depset::class.java).toList(String::class.java),
                    value.getValue("add_opens", Depset::class.java).toList(String::class.java),
                    java.util.stream.Stream.empty<JavaModuleFlagsProvider?>()
                )
            }
            throw RuleErrorException("expected JavaModuleFlagsInfo, got: " + Starlark.type(value))
        }
    }
}
