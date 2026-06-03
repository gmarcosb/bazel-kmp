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
package com.google.devtools.build.lib.analysis.configuredtargets

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * Dummy ConfiguredTarget for package groups. Contains no functionality, since package groups are
 * not really first-class Targets.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class PackageGroupConfiguredTarget @VisibleForSerialization @AutoCodec.Instantiator internal constructor(
    lookupKey: ActionLookupKey?,
    packageSpecificationProvider: PackageSpecificationProvider
) : AbstractConfiguredTarget(lookupKey, VisibilityProvider.PUBLIC_VISIBILITY) {
    private val packageSpecificationProvider: PackageSpecificationProvider

    override fun <P : TransitiveInfoProvider?> getProvider(provider: java.lang.Class<P?>?): P? {
        if (provider == FileProvider::class.java) {
            return provider.cast(FileProvider.EMPTY) // can't fail
        }
        if (provider == PackageSpecificationProvider::class.java) {
            return provider.cast(packageSpecificationProvider)
        } else {
            return super.getProvider<P?>(provider)
        }
    }

    constructor(actionLookupKey: ActionLookupKey?, targetContext: TargetContext?, packageGroup: PackageGroup?) : this(
        actionLookupKey,
        PackageSpecificationProvider.Companion.create(targetContext, packageGroup)
    )

    init {
        // Package groups are always public (see PackageGroup#getVisibility).
        this.packageSpecificationProvider = packageSpecificationProvider
    }

    public override fun isCreatedInSymbolicMacro(): Boolean {
        // Answer is irrelevant because package groups are always public.
        return false
    }

    override fun rawGetStarlarkProvider(providerKey: Provider.Key): Info? {
        if (providerKey.equals(packageSpecificationProvider.getProvider().getKey())) {
            return packageSpecificationProvider
        }
        return null
    }

    override fun rawGetStarlarkProvider(providerKey: String?): Any? {
        return null
    }
}
