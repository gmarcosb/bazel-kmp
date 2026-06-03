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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.util.io.CommandExtensionReporter.NO_OP_COMMAND_EXTENSION_REPORTER

/** Tests for [BlazeRuntime] static methods.  */
@RunWith(JUnit4::class)
class BlazeRuntimeTest {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()
    private val fs: FileSystem = InMemoryFileSystem(clock, DigestHashFunction.SHA256)
    private val serverDirectories: ServerDirectories = ServerDirectories(
        fs.getPath("/install"), fs.getPath("/output"), fs.getPath("/output_user")
    )
    private val blazeDirectories: BlazeDirectories =
        BlazeDirectories(serverDirectories, fs.getPath("/workspace"), "blaze")
    private val optionsParser: OptionsParser = OptionsParser.builder()
        .optionsClasses(
            com.google.common.collect.ImmutableList.of<E?>(
                CommonCommandOptions::class.java, KeepStateAfterBuildOption::class.java, ClientOptions::class.java
            )
        )
        .build()
    private val commandThread: java.lang.Thread? = Mockito.mock<java.lang.Thread?>(java.lang.Thread::class.java)
    private val shutdownReason: AtomicReference<String?> = AtomicReference<String?>()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manageProfiles() {
        val dir: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = fs.getPath("/output")
        dir.createDirectory()
        dir.getChild("foo").createDirectory()
        dir.getChild("bar").getOutputStream().close()
        clock.advanceMillis(10)
        val p1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BlazeRuntime.manageProfiles(dir, "p1", 3)
        assertThat(p1.getBaseName()).isEqualTo("command-p1.profile.gz")
        p1.getOutputStream().close()
        clock.advanceMillis(10)
        val p2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BlazeRuntime.manageProfiles(dir, "p2", 3)
        assertThat(p2.getBaseName()).isEqualTo("command-p2.profile.gz")
        p2.getOutputStream().close()
        clock.advanceMillis(10)
        val p3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BlazeRuntime.manageProfiles(dir, "p3", 3)
        assertThat(p3.getBaseName()).isEqualTo("command-p3.profile.gz")
        p3.getOutputStream().close()
        clock.advanceMillis(10)
        val p4: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BlazeRuntime.manageProfiles(dir, "p4", 3)
        assertThat(p4.getBaseName()).isEqualTo("command-p4.profile.gz")
        p4.getOutputStream().close()
        assertThat(dir.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName))
            .containsExactly(
                "foo",
                "bar",
                "command-p2.profile.gz",
                "command-p3.profile.gz",
                "command-p4.profile.gz"
            )
        clock.advanceMillis(10)
        val p5: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BlazeRuntime.manageProfiles(dir, "p5", 1)
        assertThat(p5.getBaseName()).isEqualTo("command-p5.profile.gz")
        p5.getOutputStream().close()
        assertThat(dir.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName))
            .containsExactly("foo", "bar", "command-p5.profile.gz")
    }

    @org.junit.Test
    fun optionSplitting() {
        val options: BlazeRuntime.CommandLineOptions =
            BlazeRuntime.splitStartupOptions(
                com.google.common.collect.ImmutableList.of<E?>(),
                "--install_base=/foo --host_jvm_args=-Xmx1B",
                "build",
                "//foo:bar",
                "--nobuild"
            )
        assertThat(options.getStartupArgs())
            .containsExactly("--install_base=/foo --host_jvm_args=-Xmx1B")
        assertThat(options.getOtherArgs()).isEqualTo(mutableListOf<String?>("build", "//foo:bar", "--nobuild"))
    }

    // A regression test to make sure that the 'no' prefix is handled correctly.
    @org.junit.Test
    fun optionSplittingNoPrefix() {
        val options: BlazeRuntime.CommandLineOptions =
            BlazeRuntime.splitStartupOptions(com.google.common.collect.ImmutableList.of<E?>(), "--nobatch", "build")
        assertThat(options.getStartupArgs()).containsExactly("--nobatch")
        assertThat(options.getOtherArgs()).containsExactly("build")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun crashTest() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        runtime.beforeCommand(env, optionsParser.getOptions<O?>(CommonCommandOptions::class.java))
        val oom: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setCrash(Crash.newBuilder().setCode(Code.CRASH_OOM))
                    .build()
            )
        runtime.cleanUpForCrash(oom)
        val mainThreadCrash: BlazeCommandResult? =
            BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setCrash(Crash.newBuilder().setCode(Code.CRASH_UNKNOWN))
                    .build()
            )
        assertThat(
            runtime
                .afterCommand( /* forceKeepStateForTesting= */false, env, mainThreadCrash)
                .getDetailedExitCode()
        )
            .isEqualTo(oom)
        // Confirm that runtime interrupted the command thread.
        Mockito.verify<java.lang.Thread?>(commandThread).interrupt()
        Truth.assertThat(shutdownReason.get()).isEqualTo("foo product is crashing: ")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsResponseExtensions() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val anyFoo: Any? = Any.pack(StringValue.of("foo"))
        val anyBar: Any? = Any.pack(BytesValue.of(ByteString.copyFromUtf8("bar")))
        env.addResponseExtensions(com.google.common.collect.ImmutableList.of<E?>(anyFoo, anyBar))
        assertThat(
            runtime
                .afterCommand( /* forceKeepStateForTesting= */
                    false, env, BlazeCommandResult.success()
                )
                .getResponseExtensions()
        )
            .containsExactly(anyFoo, anyBar)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsGcAndInternerShrinkingIdleTask_noStateKeptAfterBuild() {
        val runtime: BlazeRuntime = createRuntime()
        optionsParser.parse("--nokeep_state_after_build")
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val options: CommonCommandOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(CommonCommandOptions::class.java)
        runtime.beforeCommand(env, options)

        val gcIdleTasks: com.google.common.collect.ImmutableList<IdleTask?>? =
            env.getIdleTasks().stream()
                .filter({ t -> t is GcAndInternerShrinkingIdleTask })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(gcIdleTasks).hasSize(1)
        val idleTask: GcAndInternerShrinkingIdleTask = gcIdleTasks.get(0) as GcAndInternerShrinkingIdleTask
        assertThat(idleTask.delay()).isEqualTo(java.time.Duration.ZERO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsGcAndInternerShrinkingIdleTask_stateKeptAfterBuild() {
        val runtime: BlazeRuntime = createRuntime()
        optionsParser.parse("--keep_state_after_build")
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        env.getOptions().getOptions(KeepStateAfterBuildOption::class.java).setKeepStateAfterBuild(true)
        val options: CommonCommandOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(CommonCommandOptions::class.java)

        runtime.beforeCommand(env, options)

        val gcIdleTasks: com.google.common.collect.ImmutableList<IdleTask?>? =
            env.getIdleTasks().stream()
                .filter({ t -> t is GcAndInternerShrinkingIdleTask })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(gcIdleTasks).hasSize(1)
        val idleTask: GcAndInternerShrinkingIdleTask = gcIdleTasks.get(0) as GcAndInternerShrinkingIdleTask
        assertThat(idleTask.delay()).isGreaterThan(java.time.Duration.ZERO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotAddInstallBaseGcIdleTaskWhenDisabled() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val options: CommonCommandOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(CommonCommandOptions::class.java)
        options.setInstallBaseGcMaxAge(java.time.Duration.ZERO)

        runtime.beforeCommand(env, options)

        val gcIdleTasks: com.google.common.collect.ImmutableList<IdleTask?>? =
            env.getIdleTasks().stream()
                .filter({ t -> t is InstallBaseGarbageCollectorIdleTask })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(gcIdleTasks).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsInstallBaseGcIdleTaskWhenEnabled() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val options: CommonCommandOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(CommonCommandOptions::class.java)
        options.setInstallBaseGcMaxAge(java.time.Duration.ofDays(365))

        runtime.beforeCommand(env, options)

        val gcIdleTasks: com.google.common.collect.ImmutableList<IdleTask?>? =
            env.getIdleTasks().stream()
                .filter({ t -> t is InstallBaseGarbageCollectorIdleTask })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(gcIdleTasks).hasSize(1)
        val idleTask: InstallBaseGarbageCollectorIdleTask = gcIdleTasks.get(0) as InstallBaseGarbageCollectorIdleTask
        assertThat(idleTask.delay()).isEqualTo(java.time.Duration.ZERO)
        assertThat(idleTask.getGarbageCollector().getRoot())
            .isEqualTo(blazeDirectories.getInstallBase().getParentDirectory())
        assertThat(idleTask.getGarbageCollector().getOwnInstallBase())
            .isEqualTo(blazeDirectories.getInstallBase())
        assertThat(idleTask.getGarbageCollector().getMaxAge()).isEqualTo(java.time.Duration.ofDays(365))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotAddActionCacheGcIdleTaskWhenDisabled() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val options: CommonCommandOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(CommonCommandOptions::class.java)
        options.setActionCacheGcMaxAge(java.time.Duration.ZERO)
        options.setActionCacheGcIdleDelay(java.time.Duration.ofMinutes(5))
        options.setActionCacheGcThreshold(10)

        runtime.beforeCommand(env, options)

        val gcIdleTasks: com.google.common.collect.ImmutableList<IdleTask?>? =
            env.getIdleTasks().stream()
                .filter({ t -> t is ActionCacheGarbageCollectorIdleTask })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(gcIdleTasks).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsActionCacheGcIdleTaskWhenEnabled() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val options: CommonCommandOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(CommonCommandOptions::class.java)
        options.setActionCacheGcMaxAge(java.time.Duration.ofDays(7))
        options.setActionCacheGcIdleDelay(java.time.Duration.ofMinutes(5))
        options.setActionCacheGcThreshold(10)

        runtime.beforeCommand(env, options)

        val gcIdleTasks: com.google.common.collect.ImmutableList<IdleTask?>? =
            env.getIdleTasks().stream()
                .filter({ t -> t is ActionCacheGarbageCollectorIdleTask })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(gcIdleTasks).hasSize(1)
        val idleTask: ActionCacheGarbageCollectorIdleTask = gcIdleTasks.get(0) as ActionCacheGarbageCollectorIdleTask
        assertThat(idleTask.delay()).isEqualTo(java.time.Duration.ofMinutes(5))
        assertThat(idleTask.threshold).isEqualTo(0.1f)
        assertThat(idleTask.getMaxAge()).isEqualTo(java.time.Duration.ofDays(7))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsIdleTasksFromModules() {
        val runtime: BlazeRuntime = createRuntime()
        val env: CommandEnvironment = createCommandEnvironment(runtime)
        val fooTask: IdleTask =
            object : IdleTask() {
                override fun displayName(): String {
                    return "foo"
                }

                override fun run() {}
            }
        val barTask: IdleTask =
            object : IdleTask() {
                override fun displayName(): String {
                    return "bar"
                }

                override fun run() {}
            }
        env.addIdleTask(fooTask)
        env.addIdleTask(barTask)
        assertThat(
            runtime
                .afterCommand( /* forceKeepStateForTesting= */
                    false, env, BlazeCommandResult.success()
                )
                .getIdleTasks()
        )
            .containsAtLeast(fooTask, barTask)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addsCommandsFromModules() {
        val runtime: BlazeRuntime =
            createRuntime(
                com.google.common.collect.ImmutableList.of<E?>(FooCommandModule(), BarCommandModule()),
                com.google.common.collect.ImmutableList.of<BlazeService?>()
            )

        assertThat(runtime.getCommandMap().keySet()).containsExactly("foo", "bar").inOrder()
        assertThat(
            runtime.getCommandMap().get("foo")
        ).isInstanceOf(com.google.devtools.build.lib.runtime.BlazeRuntimeTest.FooCommandModule.FooCommand::class.java)
        assertThat(
            runtime.getCommandMap().get("bar")
        ).isInstanceOf(com.google.devtools.build.lib.runtime.BlazeRuntimeTest.BarCommandModule.BarCommand::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun returnsBothModulesAndServicesAsOptionsSuppliers() {
        val module: BlazeModule? = object : BlazeModule() {}
        val service: BlazeService? = object : BlazeService() {}

        val runtime: BlazeRuntime = createRuntime(
            com.google.common.collect.ImmutableList.of<E?>(module),
            com.google.common.collect.ImmutableList.of<BlazeService?>(service)
        )

        assertThat(runtime.getOptionsSuppliers()).containsExactly(module, service)
    }

    @Throws(java.lang.Exception::class)
    private fun createRuntime(): BlazeRuntime {
        return createRuntime(
            com.google.common.collect.ImmutableList.of<BlazeModule?>(),
            com.google.common.collect.ImmutableList.of<BlazeService?>()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createRuntime(modules: Iterable<BlazeModule?>, services: Iterable<BlazeService?>): BlazeRuntime {
        val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Builder()
                .setFileSystem(fs)
                .setProductName("foo product")
                .setServerDirectories(serverDirectories)
                .setStartupOptionsProvider(< T > mock < T ? > (OptionsParsingResult::class.java))
        for (module in modules) {
            builder.addBlazeModule(module)
        }
        for (service in services) {
            builder.addBlazeService(service)
        }
        return builder.build()
    }

    @Throws(java.lang.Exception::class)
    private fun createCommandEnvironment(runtime: BlazeRuntime): CommandEnvironment {
        val workspace: BlazeWorkspace? =
            runtime.initWorkspace(blazeDirectories, BinTools.empty(blazeDirectories))
        return CommandEnvironment(
            runtime,
            workspace,
            < T > mock < T ? > (com.google.common.eventbus.EventBus::class.java),
        commandThread,
        VersionCommand::class.java.getAnnotation<A?>(Command::class.java),
        optionsParser,
        InvocationPolicy.getDefaultInstance(),  /* packageLocator= */
        null,
        SyscallCache.NO_CACHE,
        QuiescingExecutorsImpl.forTesting(),  /* warnings= */
        com.google.common.collect.ImmutableList.of<E?>(),  /* waitTimeInMs= */
        0L,  /* commandStartTime= */
        0L,  /* idleTaskResultsFromPreviousIdlePeriod= */
        com.google.common.collect.ImmutableList.of<E?>(),  /* shutdownReasonConsumer= */
        { newValue: V? -> shutdownReason.set(newValue) },  /* commandExtensions= */
        com.google.common.collect.ImmutableList.of<E?>(),
        NO_OP_COMMAND_EXTENSION_REPORTER,  /* attemptNumber= */
        1,  /* buildRequestIdOverride= */
        null,
        ConfigFlagDefinitions.NONE,
        ResourceManager())
    }

    private class FooCommandModule : BlazeModule() {
        @Command(name = "foo", shortDescription = "", help = "")
        private class FooCommand : BlazeCommand {
            public override fun exec(env: CommandEnvironment?, options: OptionsParsingResult?): BlazeCommandResult? {
                return null
            }
        }

        public override fun serverInit(startupOptions: OptionsParsingResult?, builder: ServerBuilder) {
            builder.addCommands(com.google.devtools.build.lib.runtime.BlazeRuntimeTest.FooCommandModule.FooCommand())
        }
    }

    private class BarCommandModule : BlazeModule() {
        @Command(name = "bar", shortDescription = "", help = "")
        private class BarCommand : BlazeCommand {
            public override fun exec(env: CommandEnvironment?, options: OptionsParsingResult?): BlazeCommandResult? {
                return null
            }
        }

        public override fun serverInit(startupOptions: OptionsParsingResult?, builder: ServerBuilder) {
            builder.addCommands(com.google.devtools.build.lib.runtime.BlazeRuntimeTest.BarCommandModule.BarCommand())
        }
    }
}
