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
package com.google.devtools.build.lib.skyframe.packages

import com.google.devtools.build.lib.server.FailureDetails.StarlarkLoading.Code.COMPILE_ERROR

/** Abstract base class of a unit test for a [AbstractPackageLoader] implementation.  */
abstract class AbstractPackageLoaderTest {
    protected var workspaceDir: Path? = null
    protected var handler: StoredEventHandler? = null
    protected var fs: FileSystem? = null
    protected var root: Root? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun init() {
        fs = InMemoryFileSystem(DigestHashFunction.SHA256)
        workspaceDir = fs.getPath("/workspace/")
        workspaceDir.createDirectoryAndParents()
        root = Root.fromPath(workspaceDir)
        reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        handler = StoredEventHandler()
        reporter.addHandler(handler)
    }

    protected abstract fun newPackageLoaderBuilder(workspaceDir: Root?): AbstractPackageLoader.Builder?

    protected fun newPackageLoaderBuilder(): AbstractPackageLoader.Builder {
        return newPackageLoaderBuilder(root).useDefaultStarlarkSemantics().setCommonReporter(reporter)
    }

    protected abstract fun extractLegacyGlobbingForkJoinPool(packageLoader: PackageLoader?): ForkJoinPool

    protected fun newPackageLoader(): PackageLoader {
        return newPackageLoaderBuilder().build()
    }

    @org.junit.Test
    fun simpleNoPackage() {
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("nope"))
        val expected: NoSuchPackageException?
        newPackageLoader().use { pkgLoader ->
            expected = org.junit.Assert.assertThrows<T?>(
                NoSuchPackageException::class.java,
                org.junit.function.ThrowingRunnable { pkgLoader.loadPackage(pkgId) })
        }
        assertThat(expected)
            .hasMessageThat()
            .startsWith("no such package 'nope': BUILD file not found")
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleBadPackage() {
        file("bad/BUILD", "invalidBUILDsyntax")
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("bad"))
        val badPkg: Package
        newPackageLoader().use { pkgLoader ->
            badPkg = pkgLoader.loadPackage(pkgId)
        }
        assertThat(badPkg.containsErrors()).isTrue()
        assertContainsEvent(handler.getEvents(), "invalidBUILDsyntax")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleGoodPackage() {
        file("good/BUILD", "filegroup(name = 'good')")
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("good"))
        val goodPkg: Package
        newPackageLoader().use { pkgLoader ->
            goodPkg = pkgLoader.loadPackage(pkgId)
        }
        assertThat(goodPkg.containsErrors()).isFalse()
        assertThat(goodPkg.getTarget("good").getAssociatedRule().getRuleClass()).isEqualTo("filegroup")
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleMultipleGoodPackage() {
        file("good1/BUILD", "filegroup(name = 'good1')")
        file("good2/BUILD", "filegroup(name = 'good2')")
        val pkgId1: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("good1"))
        val pkgId2: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("good2"))
        val pkgs: com.google.common.collect.ImmutableMap<PackageIdentifier?, ValueOrException<Package?, NoSuchPackageException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<PackageIdentifier?, Package?, NoSuchPackageException?> =
                pkgLoader.makeLoadingContext()
                    .loadPackages(com.google.common.collect.ImmutableList.of<E?>(pkgId1, pkgId2))
            pkgs = result.getLoadedValues()
            events = result.getEvents()
        }
        assertThat(pkgs.get(pkgId1).get().containsErrors()).isFalse()
        assertThat(pkgs.get(pkgId2).get().containsErrors()).isFalse()
        assertThat(pkgs.get(pkgId1).get().getTarget("good1").getAssociatedRule().getRuleClass())
            .isEqualTo("filegroup")
        assertThat(pkgs.get(pkgId2).get().getTarget("good2").getAssociatedRule().getRuleClass())
            .isEqualTo("filegroup")

        MoreAsserts.assertNoEvents(events)
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGoodAndBadAndMissingPackages() {
        file("bad/BUILD", "invalidBUILDsyntax")
        val badPkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("bad"))

        file("good/BUILD", "filegroup(name = 'good')")
        val goodPkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("good"))

        val missingPkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo("missing")

        val pkgs: com.google.common.collect.ImmutableMap<PackageIdentifier?, ValueOrException<Package?, NoSuchPackageException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<PackageIdentifier?, Package?, NoSuchPackageException?> =
                pkgLoader
                    .makeLoadingContext()
                    .loadPackages(com.google.common.collect.ImmutableList.of<E?>(badPkgId, goodPkgId, missingPkgId))
            pkgs = result.getLoadedValues()
            events = result.getEvents()
        }
        val goodPkg: Package = pkgs.get(goodPkgId).get()
        assertThat(goodPkg.containsErrors()).isFalse()

        val badPkg: Package = pkgs.get(badPkgId).get()
        assertThat(badPkg.containsErrors()).isTrue()

        org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable { pkgs.get(missingPkgId).get() })

        assertContainsEvent(events, "invalidBUILDsyntax")
        assertContainsEvent(handler.getEvents(), "invalidBUILDsyntax")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadPackagesToleratesDuplicates() {
        file("good1/BUILD", "filegroup(name = 'good1')")
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("good1"))
        val pkgs: com.google.common.collect.ImmutableMap<PackageIdentifier?, ValueOrException<Package?, NoSuchPackageException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<PackageIdentifier?, Package?, NoSuchPackageException?> =
                pkgLoader.makeLoadingContext()
                    .loadPackages(com.google.common.collect.ImmutableList.of<E?>(pkgId, pkgId))
            pkgs = result.getLoadedValues()
            events = result.getEvents()
        }
        assertThat(pkgs.get(pkgId).get().containsErrors()).isFalse()
        assertThat(pkgs.get(pkgId).get().getTarget("good1").getAssociatedRule().getRuleClass())
            .isEqualTo("filegroup")
        MoreAsserts.assertNoEvents(events)
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleGoodPackage_Starlark() {
        file(
            "good/good.bzl",
            """
        def f(x):
            native.filegroup(name = x)
        
        """.trimIndent()
        )
        file(
            "good/BUILD",
            """
        load("//good:good.bzl", "f")

        f("good")
        
        """.trimIndent()
        )
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("good"))
        val goodPkg: Package
        newPackageLoader().use { pkgLoader ->
            goodPkg = pkgLoader.loadPackage(pkgId)
        }
        assertThat(goodPkg.containsErrors()).isFalse()
        assertThat(goodPkg.getTarget("good").getAssociatedRule().getRuleClass()).isEqualTo("filegroup")
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun externalFile_SupportedByDefault() {
        val externalPath: Path = file(absolutePath("/external/BUILD"), "filegroup(name = 'foo')")
        symlink("foo/BUILD", externalPath)
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("foo"))
        val fooPkg: Package
        newPackageLoader().use { pkgLoader ->
            fooPkg = pkgLoader.loadPackage(pkgId)
        }
        assertThat(fooPkg.containsErrors()).isFalse()
        assertThat(fooPkg.getTarget("foo").getTargetKind()).isEqualTo("filegroup rule")
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun externalFile_AssumeNonExistentAndImmutable() {
        val externalPath: Path = file(absolutePath("/external/BUILD"), "filegroup(name = 'foo')")
        symlink("foo/BUILD", externalPath)
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("foo"))
        val expected: NoSuchPackageException?
        newPackageLoaderBuilder()
            .setExternalFileAction(
                ExternalFileAction.ASSUME_NON_EXISTENT_AND_IMMUTABLE_FOR_EXTERNAL_PATHS
            )
            .build().use { pkgLoader ->
                expected = org.junit.Assert.assertThrows<T?>(
                    NoSuchPackageException::class.java,
                    org.junit.function.ThrowingRunnable { pkgLoader.loadPackage(pkgId) })
            }
        assertThat(expected).hasMessageThat().contains("no such package 'foo': BUILD file not found")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonPackageEventsReported() {
        path("foo").createDirectoryAndParents()
        symlink("foo/infinitesymlinkpkg", path("foo/infinitesymlinkpkg/subdir"))
        val pkgId: PackageIdentifier = PackageIdentifier.createInMainRepo("foo/infinitesymlinkpkg")
        val pkgs: com.google.common.collect.ImmutableMap<PackageIdentifier?, ValueOrException<Package?, NoSuchPackageException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<PackageIdentifier?, Package?, NoSuchPackageException?> =
                pkgLoader.makeLoadingContext().loadPackages(com.google.common.collect.ImmutableList.of<E?>(pkgId))
            pkgs = result.getLoadedValues()
            events = result.getEvents()
        }
        org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable { pkgs.get(pkgId).get() })
        assertContainsEvent(events, "infinite symlink expansion detected")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClosesForkJoinPool() {
        val pkgLoader: PackageLoader = newPackageLoader()
        val forkJoinPool: ForkJoinPool = extractLegacyGlobbingForkJoinPool(pkgLoader)
        Truth.assertThat(forkJoinPool.isShutdown()).isFalse()
        pkgLoader.close()
        Truth.assertThat(forkJoinPool.isShutdown()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadingContext_loadModules_basicFunctionality() {
        file("x/BUILD")
        file(
            "x/foo.bzl",
            """
        '''Module foo'''

        load('//y:bar.bzl', 'bar')

        def foo(): bar()
        
        """.trimIndent()
        )
        file("y/BUILD")
        file(
            "y/bar.bzl",
            """
        '''Module bar'''

        def bar(): pass
        
        """.trimIndent()
        )
        val fooLabel: Label? = Label.parseCanonicalUnchecked("//x:foo.bzl")
        val barLabel: Label? = Label.parseCanonicalUnchecked("//y:bar.bzl")
        val modules: com.google.common.collect.ImmutableMap<Label?, ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<Label?, net.starlark.java.eval.Module?, StarlarkModuleLoadingException?> =
                pkgLoader.makeLoadingContext()
                    .loadModules(com.google.common.collect.ImmutableList.of<E?>(fooLabel, barLabel))
            modules = result.getLoadedValues()
            events = result.getEvents()
        }
        Truth.assertThat(modules.keys).containsExactly(fooLabel, barLabel)

        assertThat(modules.get(fooLabel).isPresent).isTrue()
        assertThat(modules.get(fooLabel).get().documentation).isEqualTo("Module foo")
        assertThat(modules.get(fooLabel).get()).isSameInstanceAs(modules.get(fooLabel).getUnchecked())

        assertThat(modules.get(barLabel).isPresent).isTrue()
        assertThat(modules.get(barLabel).get().documentation).isEqualTo("Module bar")
        assertThat(modules.get(barLabel).get()).isSameInstanceAs(modules.get(barLabel).getUnchecked())

        MoreAsserts.assertNoEvents(events)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadingContext_loadModules_failsOnBrokenModule() {
        file("x/BUILD")
        file("x/foo.bzl", "syntax error")
        val fooLabel: Label = Label.parseCanonicalUnchecked("//x:foo.bzl")
        val modules: com.google.common.collect.ImmutableMap<Label?, ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<Label?, net.starlark.java.eval.Module?, StarlarkModuleLoadingException?> =
                pkgLoader.makeLoadingContext().loadModules(com.google.common.collect.ImmutableList.of<E?>(fooLabel))
            modules = result.getLoadedValues()
            events = result.getEvents()
        }
        Truth.assertThat(modules.keys).containsExactly(fooLabel)

        val valueOrException: ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>? =
            modules.get(fooLabel)
        assertThat(valueOrException.isPresent).isFalse()
        val exception: StarlarkModuleLoadingException =
            org.junit.Assert.assertThrows<T>(StarlarkModuleLoadingException::class.java, valueOrException::get)
        assertThat(exception).hasMessageThat().contains("compilation of module 'x/foo.bzl' failed")
        assertThat(exception).hasCauseThat().isInstanceOf(BzlLoadFailedException::class.java)
        assertThat(exception.getFailureDetail().get().getStarlarkLoading().getCode())
            .isEqualTo(COMPILE_ERROR)
        val uncheckedException: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                valueOrException::getUnchecked
            )
        Truth.assertThat(uncheckedException).hasCauseThat().isEqualTo(exception)

        Truth.assertThat(handler.getEvents()).containsExactlyElementsIn(events)
        assertContainsEvent(events, "syntax error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadingContext_loadModules_failsOnCycle() {
        file("x/BUILD")
        file(
            "x/foo.bzl",
            """
        load("//y:bar.bzl", "bar")

        def foo(): return bar
        
        """.trimIndent()
        )

        file("y/BUILD")
        file(
            "y/bar.bzl",
            """
        load("//x:foo.bzl", "foo")

        def bar(): return foo
        
        """.trimIndent()
        )
        val fooLabel: Label = Label.parseCanonicalUnchecked("//x:foo.bzl")
        val modules: com.google.common.collect.ImmutableMap<Label?, ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>?>
        val events: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>?
        newPackageLoader().use { pkgLoader ->
            val result: PackageLoader.Result<Label?, net.starlark.java.eval.Module?, StarlarkModuleLoadingException?> =
                pkgLoader.makeLoadingContext().loadModules(com.google.common.collect.ImmutableList.of<E?>(fooLabel))
            modules = result.getLoadedValues()
            events = result.getEvents()
        }
        Truth.assertThat(modules.keys).containsExactly(fooLabel)

        val valueOrException: ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>? =
            modules.get(fooLabel)
        assertThat(valueOrException.isPresent).isFalse()
        val exception: StarlarkModuleLoadingException =
            org.junit.Assert.assertThrows<T>(StarlarkModuleLoadingException::class.java, valueOrException::get)
        assertThat(exception).hasMessageThat().contains("Cycle encountered while loading //x:foo.bzl")
        assertThat(exception).hasCauseThat().isNull()
        assertThat(exception.getFailureDetail())
            .isEmpty() // TODO(b/331221948): we ought to define a failure detail for load() cycles
        val uncheckedException: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                valueOrException::getUnchecked
            )
        Truth.assertThat(uncheckedException).hasCauseThat().isEqualTo(exception)

        Truth.assertThat(events).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadingContext_resetsLoadedEvents() {
        file("x/BUILD", "invalidSyntax_pkg_x")
        file("x/foo.bzl", "invalidSyntax_foo_bzl")
        file("y/BUILD", "invalidSyntax_pkg_y")
        file("y/bar.bzl", "invalidSyntax_bar_bzl")
        val fooLabel: Label = Label.parseCanonicalUnchecked("//x:foo.bzl")
        val barLabel: Label = Label.parseCanonicalUnchecked("//y:bar.bzl")
        val eventsAfterLoadingFooBzl: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>
        val eventsAfterLoadingPkgX: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>
        val eventsAfterLoadingBarBzl: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>
        val eventsAfterLoadingPkgY: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>
        newPackageLoader().use { pkgLoader ->
            val loadingContext: LoadingContext = pkgLoader.makeLoadingContext()
            eventsAfterLoadingFooBzl =
                loadingContext.loadModules(com.google.common.collect.ImmutableList.of<E?>(fooLabel)).getEvents()
            eventsAfterLoadingPkgX =
                loadingContext
                    .loadPackages(
                        com.google.common.collect.ImmutableList.of<E?>(
                            PackageIdentifier.createInMainRepo(
                                PathFragment.create(
                                    "x"
                                )
                            )
                        )
                    )
                    .getEvents()
            eventsAfterLoadingPkgY =
                loadingContext
                    .loadPackages(
                        com.google.common.collect.ImmutableList.of<E?>(
                            PackageIdentifier.createInMainRepo(
                                PathFragment.create(
                                    "y"
                                )
                            )
                        )
                    )
                    .getEvents()
            eventsAfterLoadingBarBzl =
                loadingContext.loadModules(com.google.common.collect.ImmutableList.of<E?>(barLabel)).getEvents()
        }
        assertContainsEvent(eventsAfterLoadingFooBzl, "invalidSyntax_foo_bzl")
        MoreAsserts.assertDoesNotContainEvents(
            eventsAfterLoadingFooBzl,
            "invalidSyntax_pkg_x",
            "invalidSyntax_pkg_y",
            "invalidSyntax_bar_bzl"
        )

        assertContainsEvent(eventsAfterLoadingPkgX, "invalidSyntax_pkg_x")
        MoreAsserts.assertDoesNotContainEvents(
            eventsAfterLoadingPkgX,
            "invalidSyntax_pkg_y",
            "invalidSyntax_foo_bzl",
            "invalidSyntax_bar_bzl"
        )

        assertContainsEvent(eventsAfterLoadingPkgY, "invalidSyntax_pkg_y")
        MoreAsserts.assertDoesNotContainEvents(
            eventsAfterLoadingPkgY,
            "invalidSyntax_pkg_x",
            "invalidSyntax_foo_bzl",
            "invalidSyntax_bar_bzl"
        )

        assertContainsEvent(eventsAfterLoadingBarBzl, "invalidSyntax_bar_bzl")
        MoreAsserts.assertDoesNotContainEvents(
            eventsAfterLoadingBarBzl,
            "invalidSyntax_pkg_x",
            "invalidSyntax_pkg_y",
            "invalidSyntax_foo_bzl"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun loadingContext_getRepositoryMapping_basicFunctionality() {
        val repositoryMapping: RepositoryMapping
        newPackageLoader().use { pkgLoader ->
            val loadingContext: LoadingContext = pkgLoader.makeLoadingContext()
            repositoryMapping = loadingContext.repositoryMapping
        }
        assertThat(repositoryMapping.get("")).isEqualTo(RepositoryName.MAIN)
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    protected fun path(rootRelativePath: String?): Path {
        return workspaceDir.getRelative(PathFragment.create(rootRelativePath))
    }

    protected fun absolutePath(absolutePath: String?): Path {
        return fs.getPath(absolutePath)
    }

    @Throws(java.lang.Exception::class)
    protected fun file(fileName: String?, vararg contents: String?): Path {
        return file(path(fileName), *contents)
    }

    @Throws(java.lang.Exception::class)
    protected fun file(path: Path, vararg contents: String?): Path {
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(path, com.google.common.base.Joiner.on("\n").join(contents))
        return path
    }

    @Throws(java.lang.Exception::class)
    protected fun symlink(linkPathString: String?, linkTargetPath: Path?): Path {
        val path: Path = path(linkPathString)
        FileSystemUtils.ensureSymbolicLink(path, linkTargetPath)
        return path
    }
}
