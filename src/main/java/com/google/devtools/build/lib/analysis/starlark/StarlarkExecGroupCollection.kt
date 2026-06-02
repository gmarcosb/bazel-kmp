// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

/**
 * A [StarlarkIndexable] collection of resolved toolchain contexts that can be exposed to
 * starlark.
 */
@AutoValue
abstract class StarlarkExecGroupCollection : ExecGroupCollectionApi {
    protected abstract fun toolchainCollection(): ToolchainCollection<out ResolvedToolchainsDataInterface<*>?>?

    @get:com.google.common.annotations.VisibleForTesting
    val toolchainCollectionForTesting: com.google.common.collect.ImmutableMap<String?, out ResolvedToolchainsDataInterface<*>?>?
        get() = toolchainCollection().contextMap

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any): Boolean {
        val group = castGroupName(semantics, key)
        return DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME != group && toolchainCollection().getExecGroupNames()
            .contains(group)
    }

    /**
     * This creates a new [StarlarkExecGroupContext] object every time this is called. This
     * seems better than pre-creating and storing all [StarlarkExecGroupContext]s since they're
     * just thin wrappers around [ResolvedToolchainContext] objects.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any): StarlarkExecGroupContext {
        val execGroup = castGroupName(semantics, key)
        if (!containsKey(semantics, key)) {
            throw net.starlark.java.eval.Starlark.errorf(
                "In %s, unrecognized exec group '%s' requested. Available exec groups: [%s]",
                toolchainCollection().getDefaultToolchainContext().targetDescription(),
                execGroup,
                java.lang.String.join(", ", this.scrubbedExecGroups)
            )
        }

        val toolchainContext: ResolvedToolchainsDataInterface<*>? = toolchainCollection().getToolchainContext(execGroup)
        if (toolchainContext == null) {
            return StarlarkExecGroupContext(StarlarkToolchainContext.Companion.TOOLCHAINS_NOT_VALID)
        }

        val starlarkToolchainContext: ToolchainContextApi =
            StarlarkToolchainContext.Companion.create( /* targetDescription= */
                toolchainContext.targetDescription(),  /* resolveToolchainDataFunc= */
                toolchainContext::forToolchainType,  /* resolvedToolchainTypeLabels= */
                toolchainContext
                    .requestedToolchainTypeLabels()
                    .keySet()
            )
        return StarlarkExecGroupContext(starlarkToolchainContext)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer
            .append("<ctx.exec_groups: ")
            .append(java.lang.String.join(", ", this.scrubbedExecGroups))
            .append(">")
    }

    private val scrubbedExecGroups: MutableList<String?>
        get() = toolchainCollection().getExecGroupNames().stream()
            .filter { group: String? -> DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME != group }
            .sorted()
            .collect(Collectors.toList())

    /**
     * The starlark object that is returned by ctx.exec_groups[<name>]. Gives information about that
     * exec group.
    </name> */
    class StarlarkExecGroupContext(toolchains: ToolchainContextApi?) : ExecGroupContextApi {
        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<exec_group_context>")
        }

        val toolchains: ToolchainContextApi?

        init {
            this.toolchains = toolchains
            java.util.Objects.requireNonNull<ToolchainContextApi?>(toolchains, "toolchains")
        }
    }

    companion object {
        /**
         * Empty collection of exec groups to be used when exec groups are not valid in the current
         * context.
         */
        val EXEC_GROUP_COLLECTION_NOT_VALID: ExecGroupCollectionApi = object : ExecGroupCollectionApi {
            override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Boolean {
                return false
            }

            @Throws(net.starlark.java.eval.EvalException::class)
            override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Any? {
                throw net.starlark.java.eval.Starlark.errorf("exec_groups are not valid in this context")
            }
        }

        /**
         * Returns a new [StarlarkExecGroupCollection] backed by the given `toolchainCollection`.
         */
        fun create(
            toolchainCollection: ToolchainCollection<out ResolvedToolchainsDataInterface<*>?>?
        ): StarlarkExecGroupCollection {
            return AutoValue_StarlarkExecGroupCollection(toolchainCollection)
        }

        @kotlin.jvm.JvmStatic
        fun isValidGroupName(execGroupName: String): Boolean {
            return execGroupName != DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME && net.starlark.java.syntax.Identifier.isValid(
                execGroupName
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun castGroupName(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any): String {
            if (key !is String) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "exec groups only support indexing by exec group name, got %s of type %s instead",
                    net.starlark.java.eval.Starlark.repr(key, semantics), net.starlark.java.eval.Starlark.type(key)
                )
            }
            return key
        }
    }
}
