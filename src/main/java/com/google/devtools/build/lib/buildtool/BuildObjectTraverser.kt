// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

internal class BuildObjectTraverser(
    private val reportConfiguration: Boolean,
    private val reportPrecomputed: Boolean,
    private val reportWorkspaceStatus: Boolean
) : DomainSpecificTraverser {
    override fun isInterned(o: Any?): Boolean {
        if (o is String) {
            return true
        }

        if (o is com.google.devtools.build.lib.cmdline.Label) {
            return true
        }

        if (o is PackageIdentifier) {
            return true
        }

        if (o is RepositoryName) {
            return true
        }

        return false
    }

    override fun maybeTraverse(
        o: Any,
        traversal: com.google.devtools.build.lib.util.ObjectGraphTraverser.Traversal
    ): Boolean {
        when (o) {
            -> {
                traversal.objectFound(o, null)
                traversal.edgeFound(p.getPathString(), null)
                return true
            }

            -> {
                traversal.objectFound(o, null)
                traversal.edgeFound(pf.getPathString(), null)
                return true
            }

            else -> {
                return false
            }
        }
    }

    override fun admit(o: Any?): Boolean {
        if (!reportPrecomputed) {
            if (o is PrecomputedValue) {
                return false
            }
        }

        if (!reportWorkspaceStatus) {
            if (o is WorkspaceStatusValue) {
                return false
            }
        }

        if (!reportConfiguration) {
            if (o is BuildConfigurationValue) {
                return false
            }

            if (o is PlatformMappingValue) {
                return false
            }

            if (o is BaselineOptionsValue) {
                return false
            }

            if (o is BuildConfigurationKey) {
                return false
            }
        }

        if (o is RuleClass) {
            return false
        }

        if (o is com.google.devtools.build.lib.packages.Provider) {
            return false
        }

        if (o is com.google.devtools.build.lib.packages.Type<*>) {
            // These are BUILD types and are all singletons
            return false
        }

        if (o is StarlarkLateBoundDefault) {
            // These are cached and thus not assignable to individual Skyframe objects
            return false
        }

        if (o is net.starlark.java.eval.StarlarkSemantics) {
            return false
        }

        return true
    }

    override fun contextForArrayItem(from: Any?, fromContext: String?, to: Any?): String? {
        return null
    }

    override fun contextForField(from: Any?, fromContext: String?, field: java.lang.reflect.Field?, to: Any?): String? {
        return null
    }

    override fun ignoredFields(clazz: java.lang.Class<*>?): com.google.common.collect.ImmutableSet<String?>? {
        if (clazz == StarlarkDefinedConfigTransition::class.java) {
            return com.google.common.collect.ImmutableSet.of<String?>("ruleTransitionCache")
        }

        if (clazz == StarlarkDefinedAspect::class.java) {
            return com.google.common.collect.ImmutableSet.of<String?>("definitionCache")
        }

        return null
    }
}
