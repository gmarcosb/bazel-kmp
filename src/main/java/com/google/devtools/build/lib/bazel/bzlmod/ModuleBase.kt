// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey

/** Represents a node in the external dependency graph.  */
internal abstract class ModuleBase {
    /**
     * The name of the module, as specified in this module's MODULE.bazel file. Can be empty if this
     * is the root module.
     */
    abstract fun getName(): String?

    /**
     * The version of the module, as specified in this module's MODULE.bazel file. Can be empty if
     * this is the root module, or if this module comes from a [NonRegistryOverride].
     */
    abstract fun getVersion(): com.google.devtools.build.lib.bazel.bzlmod.Version?

    /**
     * The key of this module in the dependency graph. Note that, although a [ModuleKey] is also
     * just a (name, version) pair, its semantics differ from [.getName] and [ ][.getVersion], which are always as specified in the MODULE.bazel file. The [ModuleKey]
     * returned by this method, however, will have the following special semantics:
     * 
     * 
     *  * The name of the [ModuleKey] is the same as [.getName], unless this is the
     * root module, in which case the name of the [ModuleKey] must be empty.
     *  * The version of the [ModuleKey] is the same as [.getVersion], unless this is
     * the root module OR this module has a [NonRegistryOverride], in which case the
     * version of the [ModuleKey] must be empty.
     * 
     */
    abstract fun getKey(): ModuleKey?

    /**
     * The name of the repository representing this module, as seen by the module itself. By default,
     * the name of the repo is the name of the module. This can be specified to ease migration for
     * projects that have been using a repo name for itself that differs from its module name.
     */
    abstract fun getRepoName(): String?

    /**
     * Target patterns identifying execution platforms to register when this module is selected. Note
     * that these are what was written in module files verbatim, and don't contain canonical repo
     * names.
     */
    abstract fun getExecutionPlatformsToRegister(): com.google.common.collect.ImmutableList<String?>?

    /**
     * Target patterns identifying toolchains to register when this module is selected. Note that
     * these are what was written in module files verbatim, and don't contain canonical repo names.
     */
    abstract fun getToolchainsToRegister(): com.google.common.collect.ImmutableList<String?>?

    /** The module extensions used in this module.  */
    abstract fun getExtensionUsages(): com.google.common.collect.ImmutableList<ModuleExtensionUsage?>?

    /**
     * The flag aliases for this module. This is a list of String of the following format `${ALIAS}=${LABEL}`
     */
    abstract fun getFlagAliases(): com.google.common.collect.ImmutableMap<String?, String?>?
}
