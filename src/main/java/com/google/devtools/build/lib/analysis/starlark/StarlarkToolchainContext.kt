// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ResolvedToolchainData

/**
 * An implementation of ToolchainContextApi that can better handle converting strings into Labels.
 */
@AutoValue
abstract class StarlarkToolchainContext : ToolchainContextApi {
    protected abstract fun targetDescription(): String?

    protected abstract fun resolveToolchainFunc(): java.util.function.Function<com.google.devtools.build.lib.cmdline.Label?, ResolvedToolchainData?>?

    protected abstract fun resolvedToolchainTypeLabels(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?

    override fun toolchainTypes(): net.starlark.java.eval.StarlarkList<com.google.devtools.build.lib.cmdline.Label?>? {
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<com.google.devtools.build.lib.cmdline.Label?>(
            resolvedToolchainTypeLabels()
        )
    }

    val isImmutable: Boolean
        get() = true

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<toolchain_context.resolved_labels: ")
        printer.append(
            resolvedToolchainTypeLabels().stream()
                .map<String?> { obj: com.google.devtools.build.lib.cmdline.Label? -> obj.toString() }
                .collect(Collectors.joining(", ")))
        printer.append(">")
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun transformKey(
        starlarkThread: net.starlark.java.eval.StarlarkThread?,
        key: Any
    ): com.google.devtools.build.lib.cmdline.Label? {
        if (key is com.google.devtools.build.lib.cmdline.Label) {
            return key
        } else if (key is ToolchainTypeInfo) {
            return key.typeLabel()
        } else if (key is String) {
            try {
                val converter: LabelConverter = LabelConverter.forBzlEvaluatingThread(starlarkThread)
                return converter.convert(key)
            } catch (e: LabelSyntaxException) {
                throw net.starlark.java.eval.Starlark.errorf("Unable to parse toolchain label '%s': %s", key, e.message)
            }
        } else {
            throw net.starlark.java.eval.Starlark.errorf(
                "Toolchains only supports indexing by toolchain type, got %s instead",
                net.starlark.java.eval.Starlark.type(key)
            )
        }
    }


    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIndex(
        starlarkThread: net.starlark.java.eval.StarlarkThread?,
        semantics: net.starlark.java.eval.StarlarkSemantics?,
        key: Any
    ): net.starlark.java.eval.StarlarkValue? {
        val toolchainTypeLabel: com.google.devtools.build.lib.cmdline.Label? = transformKey(starlarkThread, key)

        if (!containsKey(starlarkThread, semantics, key)) {
            // TODO(bazel-configurability): The list of available toolchain types is confusing in the
            // presence of aliases, since it only contains the actual label, not the alias passed to the
            // rule definition.
            throw net.starlark.java.eval.Starlark.errorf(
                "In %s, toolchain type %s was requested but only types [%s] are configured",
                targetDescription(),
                toolchainTypeLabel,
                resolvedToolchainTypeLabels().stream()
                    .map<String?> { obj: com.google.devtools.build.lib.cmdline.Label? -> obj.toString() }
                    .collect(Collectors.joining(", ")))
        }
        val toolchainInfo: ResolvedToolchainData? = resolveToolchainFunc().apply(toolchainTypeLabel)
        if (toolchainInfo == null) {
            return net.starlark.java.eval.Starlark.NONE
        }
        return toolchainInfo
    }


    @Throws(net.starlark.java.eval.EvalException::class)
    override fun containsKey(
        starlarkThread: net.starlark.java.eval.StarlarkThread?,
        semantics: net.starlark.java.eval.StarlarkSemantics?,
        key: Any
    ): Boolean {
        val toolchainTypeLabel: com.google.devtools.build.lib.cmdline.Label? = transformKey(starlarkThread, key)
        return resolvedToolchainTypeLabels().contains(toolchainTypeLabel)
    }

    companion object {
        val TOOLCHAINS_NOT_VALID: ToolchainContextApi = object : ToolchainContextApi {
            @Throws(net.starlark.java.eval.EvalException::class)
            override fun getIndex(
                starlarkThread: net.starlark.java.eval.StarlarkThread?,
                semantics: net.starlark.java.eval.StarlarkSemantics?,
                key: Any?
            ): Any? {
                throw net.starlark.java.eval.Starlark.errorf("Toolchains are not valid in this context")
            }

            override fun containsKey(
                starlarkThread: net.starlark.java.eval.StarlarkThread?,
                semantics: net.starlark.java.eval.StarlarkSemantics?,
                key: Any?
            ): Boolean {
                return false
            }

            override fun toolchainTypes(): net.starlark.java.eval.StarlarkList<com.google.devtools.build.lib.cmdline.Label?>? {
                return net.starlark.java.eval.StarlarkList.empty<com.google.devtools.build.lib.cmdline.Label?>()
            }
        }

        fun create(
            targetDescription: String?,
            resolveToolchainDataFunc: java.util.function.Function<com.google.devtools.build.lib.cmdline.Label?, ResolvedToolchainData?>?,
            resolvedToolchainTypeLabels: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?
        ): ToolchainContextApi {
            com.google.common.base.Preconditions.checkNotNull<String?>(targetDescription)
            com.google.common.base.Preconditions.checkNotNull<java.util.function.Function<com.google.devtools.build.lib.cmdline.Label?, ResolvedToolchainData?>?>(
                resolveToolchainDataFunc
            )
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>(
                resolvedToolchainTypeLabels
            )

            return AutoValue_StarlarkToolchainContext(
                targetDescription, resolveToolchainDataFunc, resolvedToolchainTypeLabels
            )
        }
    }
}
