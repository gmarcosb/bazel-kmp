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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.LabelValidator

/**
 * Common utility methods for target pattern resolution.
 */
object TargetPatternResolverUtil {
    @kotlin.jvm.JvmStatic
    fun getParsingErrorMessage(message: String?, originalPattern: String?): String? {
        if (originalPattern == null) {
            return message
        } else {
            return java.lang.String.format("while parsing '%s': %s", originalPattern, message)
        }
    }

    fun resolvePackageTargets(
        pkg: com.google.devtools.build.lib.packages.Package,
        policy: FilteringPolicy
    ): MutableCollection<com.google.devtools.build.lib.packages.Target?>? {
        if (policy === FilteringPolicies.NO_FILTER) {
            return pkg.getTargets().values()
        }
        val builder: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<com.google.devtools.build.lib.packages.Target?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<com.google.devtools.build.lib.packages.Target?>()
        for (target in pkg.getTargets().values()) {
            if (policy.shouldRetain(target, false)) {
                builder.add(target)
            }
        }
        return builder
    }

    @kotlin.jvm.JvmStatic
    @Throws(TargetParsingException::class)
    fun getPathFragment(pathPrefix: String): PathFragment {
        val directory: PathFragment = PathFragment.create(pathPrefix)
        if (directory.containsUplevelReferences()) {
            throw TargetParsingException(
                "up-level references are not permitted: '" + directory.getPathString() + "'",
                TargetPatterns.Code.UP_LEVEL_REFERENCES_NOT_ALLOWED
            )
        }
        if (!pathPrefix.isEmpty() && (LabelValidator.validatePackageName(pathPrefix) != null)) {
            throw TargetParsingException(
                "'" + pathPrefix + "' is not a valid package name",
                TargetPatterns.Code.PACKAGE_NAME_INVALID
            )
        }
        return directory
    }
}
