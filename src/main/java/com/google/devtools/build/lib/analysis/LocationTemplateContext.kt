// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Expands $(location) and $(locations) tags inside target attributes. You can specify something
 * like this in the BUILD file:
 * 
 * <pre>
 * somerule(name='some name',
 * someopt = [ '$(location //mypackage:myhelper)' ],
 * ...)
</pre> * 
 * 
 * and location will be substituted with //mypackage:myhelper executable output.
 * 
 * 
 * Note that this expander will always expand labels in srcs, deps, and tools attributes, with
 * data being optional.
 * 
 * 
 * DO NOT USE DIRECTLY! Use RuleContext.getExpander() instead.
 */
internal class LocationTemplateContext private constructor(
    delegate: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext,
    root: Label?,
    locationMap: com.google.common.base.Supplier<MutableMap<Label?, MutableCollection<Artifact?>?>?>?,
    execPaths: Boolean,
    repositoryMapping: RepositoryMapping?,
    windowsPath: Boolean,
    workspaceRunfilesDirectory: String?
) : com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext {
    private val delegate: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext
    private val functions: com.google.common.collect.ImmutableMap<String?, LocationFunction?>
    private val repositoryMapping: RepositoryMapping?
    private val windowsPath: Boolean
    private val workspaceRunfilesDirectory: String?

    init {
        this.delegate = delegate
        this.functions = LocationExpander.Companion.allLocationFunctions(root, locationMap, execPaths)
        this.repositoryMapping = repositoryMapping
        this.windowsPath = windowsPath
        this.workspaceRunfilesDirectory = workspaceRunfilesDirectory
    }

    constructor(
        delegate: com.google.devtools.build.lib.analysis.stringtemplate.TemplateContext?,
        ruleContext: RuleContext,
        labelMap: com.google.common.collect.ImmutableMap<Label?, com.google.common.collect.ImmutableCollection<Artifact?>?>?,
        execPaths: Boolean,
        allowData: Boolean,
        collectSrcs: Boolean,
        windowsPath: Boolean
    ) : this(
        delegate,
        ruleContext.getLabel(),  // Use a memoizing supplier to avoid eagerly building the location map.
        com.google.common.base.Suppliers.memoize<T?>(
            com.google.common.base.Supplier {
                LocationExpander.Companion.buildLocationMap(
                    ruleContext,
                    labelMap,
                    allowData,
                    collectSrcs
                )
            }),
        execPaths,
        ruleContext.getRule().getPackageMetadata().repositoryMapping(),
        windowsPath,
        ruleContext.getWorkspaceName()
    )

    @Throws(ExpansionException::class)
    override fun lookupVariable(name: String?): String? {
        var `val`: String? = delegate.lookupVariable(name)
        if (windowsPath) {
            `val` = `val`.replace('/', '\\')
        }
        return `val`
    }

    @Throws(ExpansionException::class)
    override fun lookupFunction(name: String?, param: String?): String {
        var `val` = lookupFunctionImpl(name, param)
        if (windowsPath) {
            `val` = `val`.replace('/', '\\')
        }
        return `val`
    }

    @Throws(ExpansionException::class)
    private fun lookupFunctionImpl(name: String?, param: String?): String {
        try {
            val f: LocationFunction? = functions.get(name)
            if (f != null) {
                return f.apply(param, repositoryMapping, workspaceRunfilesDirectory)
            }
        } catch (e: java.lang.IllegalStateException) {
            throw ExpansionException(e.getMessage(), e)
        }
        return delegate.lookupFunction(name, param)
    }
}
