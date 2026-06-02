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

import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode

/**
 * Fake implementation of [Registry], where modules can be freely added and stored in memory.
 * The contents of the modules are expected to be located under a given file path as subdirectories.
 */
class FakeRegistry(val url: String, private val rootPath: String?) : Registry {
    private val modules: MutableMap<ModuleKey?, String?> = HashMap<ModuleKey?, String?>()
    private val yankedVersionMap: MutableMap<String?, com.google.common.collect.ImmutableMap<Version?, String?>?> =
        HashMap<String?, com.google.common.collect.ImmutableMap<Version?, String?>?>()

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addModule(key: ModuleKey?, vararg moduleFileLines: String?): FakeRegistry {
        modules.put(key, JOINER.join(moduleFileLines))
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addYankedVersion(
        moduleName: String?, yankedVersions: com.google.common.collect.ImmutableMap<Version?, String?>?
    ): FakeRegistry {
        yankedVersionMap.put(moduleName, yankedVersions)
        return this
    }

    @Throws(NotFoundException::class)
    public override fun getModuleFile(
        key: ModuleKey, eventHandler: ExtendedEventHandler, downloadManager: DownloadManager?
    ): ModuleFile {
        val uri: String? = java.lang.String.format("%s/modules/%s/%s/MODULE.bazel", url, key.name, key.version())
        val maybeContent: java.util.Optional<ByteArray?> = java.util.Optional.ofNullable<String?>(modules.get(key))
            .map<ByteArray?>(java.util.function.Function { value: String? -> value.toByteArray(java.nio.charset.StandardCharsets.UTF_8) })
        eventHandler.post(RegistryFileDownloadEvent.create(uri, maybeContent))
        if (maybeContent.isEmpty()) {
            throw NotFoundException("module not found: " + key)
        }
        return ModuleFile.create(maybeContent.get(), uri)
    }

    public override fun getRepoSpec(
        key: ModuleKey,
        moduleFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
        eventHandler: ExtendedEventHandler,
        downloadManager: DownloadManager?
    ): RepoSpec? {
        val repoSpec: RepoSpec? =
            LocalPathRepoSpecs.create(rootPath + "/" + key.getCanonicalRepoNameWithVersion().getName())
        eventHandler.post(
            RegistryFileDownloadEvent.create(
                "%s/modules/%s/%s/source.json".formatted(url, key.name, key.version()),
                java.util.Optional.of<T?>(
                    GsonTypeAdapterUtil.SINGLE_EXTENSION_USAGES_VALUE_GSON
                        .toJson(repoSpec)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        )
        return repoSpec
    }

    public override fun getYankedVersions(
        moduleName: String?, eventHandler: ExtendedEventHandler?, downloadManager: DownloadManager?
    ): java.util.Optional<com.google.common.collect.ImmutableMap<Version?, String?>?> {
        return java.util.Optional.ofNullable<com.google.common.collect.ImmutableMap<Version?, String?>?>(
            yankedVersionMap.get(moduleName)
        )
    }

    public override fun tryGetYankedVersionsFromLockfile(
        selectedModuleKey: ModuleKey?
    ): java.util.Optional<YankedVersionsValue?> {
        return java.util.Optional.empty<YankedVersionsValue?>()
    }

    override fun equals(other: Any?): Boolean {
        return other is FakeRegistry
                && this.url == other.url
                && this.modules == other.modules
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(url, modules)
    }

    /** Fake [RegistryFactory] that only supports [FakeRegistry].  */
    class Factory : RegistryFactory {
        private var numFakes = 0
        private val registries: MutableMap<String?, FakeRegistry?> = HashMap<String?, FakeRegistry?>()

        fun newFakeRegistry(rootPath: String?): FakeRegistry {
            val registry = FakeRegistry("fake:" + numFakes++, rootPath)
            registries.put(registry.url, registry)
            return registry
        }

        public override fun createRegistry(
            url: String?,
            lockfileMode: LockfileMode?,
            fileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
            previouslySelectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?,
            vendorDir: java.util.Optional<Path?>?,
            moduleMirrors: com.google.common.collect.ImmutableSet<String?>?
        ): Registry? {
            return com.google.common.base.Preconditions.checkNotNull<FakeRegistry?>(
                registries.get(url),
                "unknown registry url: %s",
                url
            )
        }
    }

    companion object {
        private val JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on('\n')
        val DEFAULT_FACTORY: Factory = com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry.Factory()
    }
}
