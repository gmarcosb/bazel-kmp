// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/** Common parent class of SkyKeys that wrap a [RecursivePkgKey].  */
abstract class RecursivePkgSkyKey(
    repositoryName: RepositoryName?,
    rootedPath: RootedPath,
    excludedPaths: IgnoredSubdirectories
) : RecursivePkgKey(repositoryName, rootedPath, excludedPaths), SkyKey {
    override fun toString(): String {
        return functionName().toString() + " " + super.toString()
    }

    override fun equals(o: Any?): Boolean {
        return super.equals(o)
                && o is RecursivePkgSkyKey
                && o.functionName() == functionName()
    }

    /** Don't bother to memoize hashCode because [RecursivePkgKey.hashCode] is cheap enough.  */
    override fun hashCode(): Int {
        return 37 * super.hashCode() + functionName().hashCode()
    }
}
