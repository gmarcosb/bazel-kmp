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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue
import com.google.devtools.build.lib.bazel.bzlmod.BazelModTidyValue
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.RootModuleFileFixup
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue.EvalKey
import com.google.devtools.build.lib.bazel.repository.RepositoryUtils
import com.google.devtools.build.lib.cmdline.Label.RepoContext
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyFunctionException
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult

/**
 * Computes all information required for the `bazel mod tidy` command, which in particular
 * requires evaluating all module extensions used by the root module.
 */
class BazelModTidyFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class, SkyFunctionException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val rootModuleFileValue: RootModuleFileValue? =
            env.getValue(ModuleFileValue.Companion.KEY_FOR_ROOT_MODULE) as RootModuleFileValue?
        if (rootModuleFileValue == null) {
            return null
        }
        val depGraphValue: BazelDepGraphValue? = env.getValue(BazelDepGraphValue.Companion.KEY) as BazelDepGraphValue?
        if (depGraphValue == null) {
            return null
        }
        val bazelToolsRepoMapping: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.BAZEL_TOOLS)) as RepositoryMappingValue?
        if (bazelToolsRepoMapping == null) {
            return null
        }
        val buildozerLabel: com.google.devtools.build.lib.cmdline.Label
        try {
            buildozerLabel =
                com.google.devtools.build.lib.cmdline.Label.parseWithRepoContext( // This label always has the ".exe" extension, even on Unix, to get a single static
                    // label that works on all platforms.
                    "@buildozer_binary//:buildozer.exe",
                    com.google.devtools.build.lib.cmdline.Label.RepoContext.of(
                        RepositoryName.BAZEL_TOOLS, bazelToolsRepoMapping.repositoryMapping
                    )
                )
        } catch (e: LabelSyntaxException) {
            throw java.lang.IllegalStateException(e)
        }
        val buildozer: RootedPath?
        try {
            buildozer = RepositoryUtils.getRootedPathFromLabel(buildozerLabel, env)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        }

        val extensionsUsedByRootModule: com.google.common.collect.ImmutableSet<SkyKey?> =
            depGraphValue.getExtensionUsagesTable().column(ModuleKey.Companion.ROOT).keySet()
                .stream() // Use the eval-only key to avoid errors caused by incorrect imports - we can fix them.
                .map<EvalKey?>(java.util.function.Function { id: ModuleExtensionId? ->
                    SingleExtensionValue.Companion.evalKey(
                        id
                    )
                })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
        val result: SkyframeLookupResult = env.getValuesAndExceptions(extensionsUsedByRootModule)
        if (env.valuesMissing()) {
            return null
        }
        val fixups: com.google.common.collect.ImmutableList.Builder<RootModuleFileFixup?> =
            com.google.common.collect.ImmutableList.builder<RootModuleFileFixup?>()
        val errors: com.google.common.collect.ImmutableList.Builder<ExternalDepsException?> =
            com.google.common.collect.ImmutableList.builder<ExternalDepsException?>()
        for (extension in extensionsUsedByRootModule) {
            val value: SkyValue?
            try {
                value = result.getOrThrow<ExternalDepsException?>(extension, ExternalDepsException::class.java)
            } catch (e: ExternalDepsException) {
                // This extension failed, but we can still tidy up other extensions in keep going mode.
                errors.add(e)
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                continue
            }
            if (value == null) {
                return null
            }
            if (result.get(extension) is SingleExtensionValue) {
                evalValue.fixup.ifPresent(java.util.function.Consumer { element: RootModuleFileFixup? ->
                    fixups.add(
                        element
                    )
                })
            }
        }

        return BazelModTidyValue.Companion.create(
            fixups.build(), buildozer.asPath(), rootModuleFileValue.moduleFilePaths, errors.build()
        )
    }
}
