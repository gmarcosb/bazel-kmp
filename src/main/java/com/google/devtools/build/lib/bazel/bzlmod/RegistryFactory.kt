// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode
import java.net.URISyntaxException

/** A factory type for [Registry].  */
interface RegistryFactory {
    /**
     * Creates a registry associated with the given URL.
     * 
     * 
     * Outside of tests, only [RegistryFunction] should call this method.
     */
    @Throws(URISyntaxException::class)
    fun createRegistry(
        url: String?,
        lockfileMode: LockfileMode?,
        fileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
        previouslySelectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?,
        vendorDir: java.util.Optional<com.google.devtools.build.lib.vfs.Path?>?,
        moduleMirrors: com.google.common.collect.ImmutableSet<String?>?
    ): com.google.devtools.build.lib.bazel.bzlmod.Registry?
}
