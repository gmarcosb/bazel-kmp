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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.events.EventBusEventHandler

/** A helper class for implementing tests of the "foundation" library.  */
abstract class FoundationTestCase {
    protected var rootDirectory: Path? = null
    protected var outputBase: Path? = null

    // May be overridden by subclasses:
    protected var reporter: com.google.devtools.build.lib.events.Reporter? = null

    // The event bus of the reporter
    protected var eventBus: com.google.common.eventbus.EventBus? = null
    protected var eventCollector: EventCollector? = null
    protected var fileSystem: FileSystem? = null
    protected var scratch: Scratch? = null
    protected var root: Root? = null

    /** Returns the Scratch instance for this test case.  */
    fun getScratch(): Scratch {
        return scratch
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystemAndDirectories() {
        fileSystem = createFileSystem()
        scratch = Scratch(fileSystem, "/workspace")
        outputBase = scratch.dir("/usr/local/google/_blaze_jrluser/FAKEMD5/")
        rootDirectory = scratch.dir("/workspace")
        scratch.file(rootDirectory.getRelative("MODULE.bazel").getPathString())
        root = Root.fromPath(rootDirectory)

        // Let the Starlark interpreter know how to read source files.
        net.starlark.java.eval.EvalException.setSourceReaderSupplier(
            java.util.function.Supplier {
                SourceReader { loc: net.starlark.java.syntax.Location? ->
                    try {
                        val content: String = FileSystemUtils.readContent(
                            fileSystem.getPath(loc.file()),
                            java.nio.charset.StandardCharsets.UTF_8
                        )
                        return@SourceReader com.google.common.collect.Iterables.get<String?>(
                            com.google.common.base.Splitter.on(
                                "\n"
                            ).split(content), loc.line() - 1, null
                        )
                    } catch (ignored: java.lang.Exception) {
                        // ignore any exceptions reading the file -- this is just for extra info
                        return@SourceReader null
                    }
                }
            })
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeLogging() {
        eventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERRORS_WARNINGS_AND_INFO)
        eventBus = com.google.common.eventbus.EventBus()
        reporter = com.google.devtools.build.lib.events.Reporter(
            EventBusEventHandler(eventBus),
            eventCollector,
            failFastHandler
        )
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun clearInterrupts() {
        java.lang.Thread.interrupted() // Clear any interrupt pending against this thread,
        // so that we don't cause later tests to fail.
    }

    /** Creates the file system; override to inject FS behavior.  */
    protected fun createFileSystem(): FileSystem {
        return InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
    }

    // Mix-in assertions:
    protected fun assertNoEvents() {
        MoreAsserts.assertNoEvents(eventCollector)
    }

    protected fun assertContainsEvent(expectedMessage: String?): com.google.devtools.build.lib.events.Event {
        return MoreAsserts.assertContainsEvent(eventCollector, expectedMessage)
    }

    protected fun assertContainsEvent(expectedMessagePattern: java.util.regex.Pattern?): com.google.devtools.build.lib.events.Event {
        return MoreAsserts.assertContainsEvent(eventCollector, expectedMessagePattern)
    }

    protected fun assertContainsEvent(
        expectedMessage: String?,
        kinds: MutableSet<com.google.devtools.build.lib.events.EventKind?>?
    ): com.google.devtools.build.lib.events.Event {
        return MoreAsserts.assertContainsEvent(eventCollector, expectedMessage, kinds)
    }

    protected fun assertContainsEventWithFrequency(expectedMessage: String?, expectedFrequency: Int) {
        MoreAsserts.assertContainsEventWithFrequency(
            eventCollector, expectedMessage, expectedFrequency
        )
    }

    protected fun assertDoesNotContainEvent(expectedMessage: String?) {
        MoreAsserts.assertDoesNotContainEvent(eventCollector, expectedMessage)
    }

    protected fun assertContainsEventsInOrder(vararg expectedMessages: String?) {
        MoreAsserts.assertContainsEventsInOrder(eventCollector, expectedMessages)
    }

    protected val commonSerializationDependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>
        get() = com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
            .put<FileSystem?>(FileSystem::class.java, fileSystem)
            .put<Root.RootCodecDependencies?>(Root.RootCodecDependencies::class.java, RootCodecDependencies(root))
            .build()

    companion object {
        // Individual tests can opt-out of this handler if they expect an error, by
        // calling reporter.removeHandler(failFastHandler).
        val failFastHandler: com.google.devtools.build.lib.events.EventHandler =
            object : com.google.devtools.build.lib.events.EventHandler() {
                override fun handle(event: com.google.devtools.build.lib.events.Event) {
                    if (com.google.devtools.build.lib.events.EventKind.ERRORS.contains(event.getKind())) {
                        org.junit.Assert.fail(event.toString())
                    }
                }
            }

        protected val printHandler: com.google.devtools.build.lib.events.EventHandler =
            object : com.google.devtools.build.lib.events.EventHandler() {
                override fun handle(event: com.google.devtools.build.lib.events.Event?) {}
            }
    }
}
