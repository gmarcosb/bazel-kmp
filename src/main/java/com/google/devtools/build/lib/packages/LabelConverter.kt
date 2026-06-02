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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.BazelModuleContext

/**
 * Converts a label literal string into a [Label] object, using the appropriate base package
 * and repo mapping.
 * 
 * 
 * Instances are not thread-safe, due to an internal cache.
 */
class LabelConverter private constructor(
    packageContext: Label.PackageContext,
    repoMappingRecorder: Label.RepoMappingRecorder?
) {
    private val packageContext: Label.PackageContext
    private val labelCache: MutableMap<String?, Label?> = HashMap<String?, Label?>()
    private val repoMappingRecorder: Label.RepoMappingRecorder?

    init {
        this.packageContext = packageContext
        this.repoMappingRecorder = repoMappingRecorder
    }

    constructor(packageContext: Label.PackageContext) : this(packageContext, null)

    /** Creates a label converter using the given base package and repo mapping.  */
    constructor(base: PackageIdentifier?, repositoryMapping: RepositoryMapping?) : this(
        Label.PackageContext.of(
            base,
            repositoryMapping
        )
    )

    /**
     * Creates a label converter using the given base package and repo mapping, recording all repo
     * mapping lookups in the given recorder.
     */
    constructor(
        base: PackageIdentifier?,
        repositoryMapping: RepositoryMapping?,
        repoMappingRecorder: Label.RepoMappingRecorder?
    ) : this(Label.PackageContext.of(base, repositoryMapping), repoMappingRecorder)

    /** Returns the base package identifier that relative labels will be resolved against.  */
    fun getBasePackage(): PackageIdentifier {
        return packageContext.packageIdentifier()
    }

    /** Returns the Label corresponding to the input, using the current conversion context.  */
    @Throws(LabelSyntaxException::class)
    fun convert(input: String?): Label? {
        // Optimization: First check the package-local map, avoiding Label validation, Label
        // construction, and global Interner lookup. This approach tends to be very profitable
        // overall, since it's common for the targets in a single package to have duplicate
        // label-strings across all their attribute values.
        var converted: Label? = labelCache.get(input)
        if (converted == null) {
            converted = Label.parseWithPackageContext(input, packageContext, repoMappingRecorder)
            labelCache.put(input, converted)
        }
        return converted
    }

    override fun toString(): String {
        return getBasePackage().toString()
    }

    companion object {
        /**
         * Returns a label converter for the given thread, which MUST be currently evaluating Starlark
         * code in a .bzl file (top-level, macro, rule implementation function, etc.). It uses the package
         * containing the .bzl file as the base package, and the repo mapping of the repo containing the
         * .bzl file.
         */
        fun forBzlEvaluatingThread(thread: net.starlark.java.eval.StarlarkThread?): LabelConverter {
            val moduleContext: BazelModuleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread)
            return LabelConverter(moduleContext.packageContext())
        }
    }
}
