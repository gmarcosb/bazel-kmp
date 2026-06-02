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
package com.google.devtools.build.lib.query2.query.output

import com.google.devtools.build.lib.packages.DependencyFilter
import net.starlark.java.syntax.Location
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Int
import kotlin.String
import kotlin.toString

/**
 * Given a set of query options, returns a BinaryPredicate suitable for passing to [ ][Rule.getLabels], [XmlOutputFormatter], etc.
 */
internal object FormatUtils {
    fun getDependencyFilter(queryOptions: CommonQueryOptions): DependencyFilter {
        if (queryOptions.getIncludeToolDeps()) {
            return if (queryOptions.getIncludeImplicitDeps())
                DependencyFilter.ALL_DEPS
            else
                DependencyFilter.NO_IMPLICIT_DEPS
        }
        return if (queryOptions.getIncludeImplicitDeps())
            DependencyFilter.ONLY_TARGET_DEPS
        else
            DependencyFilter.NO_IMPLICIT_DEPS.and(DependencyFilter.ONLY_TARGET_DEPS)
    }

    /**
     * Returns the target location string, optionally relative to its package's source root directory
     * and optionally to display the location of source files.
     * 
     * @param relative flag to display the location relative to its package's source root directory.
     */
    fun getLocation(target: Target, relative: Boolean): String {
        var loc: Location = target.getLocation()
        if (target is InputFile) {
            val packageDir: PathFragment = target.getPackageMetadata().getPackageDirectory().asFragment()
            loc = Location.fromFileLineColumn(packageDir.getRelative(target.getName()).toString(), 1, 1)
        }
        if (relative) {
            loc = getRootRelativeLocation(loc, target.getPackageMetadata())
        }
        return loc.toString()
    }

    /**
     * Returns the specified location relative to the optional package's source root directory, if
     * available.
     */
    fun getRootRelativeLocation(location: Location, base: Package.Metadata?): Location {
        var location = location
        if (base != null) {
            val root: Root = base.sourceRoot()
            val file: PathFragment? = PathFragment.create(location.file())
            if (root.contains(file)) {
                val rel: PathFragment = root.relativize(file)
                location = Location.fromFileLineColumn(rel.toString(), location.line(), location.column())
            }
        }
        return location
    }

    /** An ordering of Targets based on the ordering of their labels.  */
    internal class TargetOrdering : Comparator<Target?> {
        override fun compare(o1: Target, o2: Target): Int {
            return o1.getLabel().compareTo(o2.getLabel())
        }
    }
}
