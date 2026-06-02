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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.VisibilityProvider
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents

/**
 * Visibility provider implementation.
 * 
 * 
 * This is just used for [AliasConfiguredTarget]; other configured targets inline the
 * `VisibilityProvider` interface into their own definition.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class VisibilityProviderImpl(visibility: NestedSet<PackageGroupContents?>?, isCreatedInSymbolicMacro: Boolean) :
    VisibilityProvider {
    private val visibility: NestedSet<PackageGroupContents?>?
    val isCreatedInSymbolicMacro: Boolean

    init {
        this.visibility = visibility
        this.isCreatedInSymbolicMacro = isCreatedInSymbolicMacro
    }

    override fun getVisibility(): NestedSet<PackageGroupContents?>? {
        return visibility
    }
}
