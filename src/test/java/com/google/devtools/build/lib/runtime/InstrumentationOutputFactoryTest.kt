// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader

@RunWith(TestParameterInjector::class)
class InstrumentationOutputFactoryTest : BuildIntegrationTestCase() {
    @org.junit.Test
    fun testInstrumentationOutputFactory_cannotCreateFactoryIfLocalSupplierUnset() {
        val factoryBuilder: InstrumentationOutputFactory.Builder =
            Builder()
        factoryBuilder.setBuildEventArtifactInstrumentationOutputBuilderSupplier(
            { BuildEventArtifactInstrumentationOutput.Builder() })

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            "Cannot create InstrumentationOutputFactory without localOutputBuilderSupplier",
            java.lang.NullPointerException::class.java,
            factoryBuilder::build
        )
    }

    @org.junit.Test
    fun testInstrumentationOutputFactory_cannotCreateFactorIfBepSupplierUnset() {
        val factoryBuilder: InstrumentationOutputFactory.Builder =
            Builder()
        factoryBuilder.setLocalInstrumentationOutputBuilderSupplier(
            { LocalInstrumentationOutput.Builder() })

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            "Cannot create InstrumentationOutputFactory without bepOutputBuilderSupplier",
            java.lang.NullPointerException::class.java,
            factoryBuilder::build
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentationOutputFactory_successfullyCreateLocalOutputWithConvenientLink() {
        val outputFactory: InstrumentationOutputFactory =
            createInstrumentationOutputFactory( /* setLocalTempLoggingDir= */false)

        val env: CommandEnvironment = runtimeWrapper.newCommand()
        val output: InstrumentationOutput =
            outputFactory.createLocalOutputWithConvenientName( /* name= */
                "output",
                env.getWorkspace().getRelative("output-file"),  /* convenienceName= */
                "link-to-output"
            )
        assertThat(output).isInstanceOf(LocalInstrumentationOutput::class.java)

        (output as LocalInstrumentationOutput).makeConvenienceLink()
        assertThat(env.getWorkspace().getRelative("link-to-output").isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentationOutputFactory_localRelativeToOutputBase() {
        val outputFactory: InstrumentationOutputFactory =
            createInstrumentationOutputFactory( /* setLocalTempLoggingDir= */false)

        val env: CommandEnvironment = runtimeWrapper.newCommand()
        val output: InstrumentationOutput =
            outputFactory.createInstrumentationOutput( /* name= */
                "output-baseoutput",
                PathFragment.create("output-baseoutput"),
                DestinationRelativeTo.OUTPUT_BASE,
                env,
                < T > mock < T ? > (com.google.devtools.build.lib.events.EventHandler::class.java),  /* append= */
        null,  /* internal= */
        null)

        assertThat(output).isInstanceOf(LocalInstrumentationOutput::class.java)
        assertThat(output.getPathString())
            .isEqualTo(env.getOutputBase().getRelative("output-baseoutput").getPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentationOutputFactory_localAbsolutePath() {
        val outputFactory: InstrumentationOutputFactory =
            createInstrumentationOutputFactory( /* setLocalTempLoggingDir= */false)

        val env: CommandEnvironment = runtimeWrapper.newCommand()
        val output: InstrumentationOutput =
            outputFactory.createInstrumentationOutput( /* name= */
                "output-absolute",
                PathFragment.create("/tmp/absolute-path-output"),
                DestinationRelativeTo.WORKSPACE_OR_HOME,
                env,
                < T > mock < T ? > (com.google.devtools.build.lib.events.EventHandler::class.java),  /* append= */
        null,  /* internal= */
        null)

        assertThat(output).isInstanceOf(LocalInstrumentationOutput::class.java)
        assertThat(output.getPathString())
            .isEqualTo(
                env.getRuntime().getFileSystem().getPath("/tmp/absolute-path-output").getPathString()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentationOutputFactory_localRelativePath(
        @TestParameter("WORKSPACE_OR_HOME", "WORKING_DIRECTORY_OR_HOME") relativeTo: DestinationRelativeTo
    ) {
        val outputFactory: InstrumentationOutputFactory =
            createInstrumentationOutputFactory( /* setLocalTempLoggingDir= */false)

        val env: CommandEnvironment = runtimeWrapper.newCommand()
        val output: InstrumentationOutput =
            outputFactory.createInstrumentationOutput( /* name= */
                "output-relative",
                PathFragment.create("relative-output"),
                relativeTo,
                env,
                < T > mock < T ? > (com.google.devtools.build.lib.events.EventHandler::class.java),  /* append= */
        null,  /* internal= */
        null)

        assertThat(output).isInstanceOf(LocalInstrumentationOutput::class.java)
        assertThat(output.getPathString())
            .isEqualTo(
                (if (relativeTo.equals(DestinationRelativeTo.WORKSPACE_OR_HOME))
                    env.getWorkspace()
                else
                    env.getWorkingDirectory())
                    .getRelative("relative-output")
                    .getPathString()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentationOutputFactory_localRelativeToTempLogging(
        @TestParameter setLocalTempLoggingDir: Boolean
    ) {
        val outputFactory: InstrumentationOutputFactory =
            createInstrumentationOutputFactory(setLocalTempLoggingDir)

        val env: CommandEnvironment = runtimeWrapper.newCommand()
        val output: InstrumentationOutput =
            outputFactory.createInstrumentationOutput( /* name= */
                "output-relative",
                PathFragment.create("relative-output"),
                DestinationRelativeTo.TEMP_LOGGING_DIRECTORY,
                env,
                < T > mock < T ? > (com.google.devtools.build.lib.events.EventHandler::class.java),  /* append= */
        null,  /* internal= */
        null)

        assertThat(output).isInstanceOf(LocalInstrumentationOutput::class.java)
        val expectedOutputBaseDir: String? =
            if (setLocalTempLoggingDir) "/tmp" else com.google.common.base.StandardSystemProperty.JAVA_IO_TMPDIR.value()
        assertThat(output.getPathString()).isEqualTo(expectedOutputBaseDir + "/relative-output")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentationOutputFactory_successfulFactoryCreation(
        @TestParameter injectRedirectOutputBuilderSupplier: Boolean,
        @TestParameter createRedirectOutput: Boolean
    ) {
        if (createRedirectOutput) {
            runtimeWrapper.addOptions("--redirect_local_instrumentation_output_writes")
        }
        val env: CommandEnvironment = runtimeWrapper.newCommand()

        val factoryBuilder: InstrumentationOutputFactory.Builder =
            Builder()
        factoryBuilder.setLocalInstrumentationOutputBuilderSupplier(
            { LocalInstrumentationOutput.Builder() })
        factoryBuilder.setBuildEventArtifactInstrumentationOutputBuilderSupplier(
            { BuildEventArtifactInstrumentationOutput.Builder() })

        val fakeRedirectInstrumentationOutput: InstrumentationOutput? =
            Mockito.mock<InstrumentationOutput?>(InstrumentationOutput::class.java)
        if (injectRedirectOutputBuilderSupplier) {
            val fakeRedirectInstrumentationBuilder: InstrumentationOutputBuilder =
                object : InstrumentationOutputBuilder() {
                    @com.google.errorprone.annotations.CanIgnoreReturnValue
                    public override fun setName(name: String?): InstrumentationOutputBuilder? {
                        return this
                    }

                    @com.google.errorprone.annotations.CanIgnoreReturnValue
                    public override fun setCreateParent(createParent: Boolean): InstrumentationOutputBuilder? {
                        return this
                    }

                    public override fun build(): InstrumentationOutput? {
                        return fakeRedirectInstrumentationOutput
                    }
                }

            factoryBuilder.setRedirectInstrumentationOutputBuilderSupplier(
                { fakeRedirectInstrumentationBuilder })
        }

        val warningEvents: MutableList<com.google.devtools.build.lib.events.Event?> =
            java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()
        val eventHandler: ExtendedEventHandler =
            object : ExtendedEventHandler() {
                override fun post(obj: Postable?) {}

                override fun handle(event: com.google.devtools.build.lib.events.Event?) {
                    warningEvents.add(event)
                }
            }

        val outputFactory: InstrumentationOutputFactory = factoryBuilder.build()
        val instrumentationOutput: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            outputFactory.createInstrumentationOutput( /* name= */
                "local",  /* destination= */
                PathFragment.create("/file"),
                DestinationRelativeTo.WORKSPACE_OR_HOME,
                env,
                eventHandler,  /* append= */
                null,  /* internal= */
                null
            )

        // Only when redirectOutputBuilderSupplier is provided to the factory, and we intend to create a
        // RedirectOutputBuilder object, we expect a non-LocalInstrumentationOutput to be created. In
        // all other scenarios, a LocalInstrumentationOutput is returned.
        if (createRedirectOutput && injectRedirectOutputBuilderSupplier) {
            assertThat(instrumentationOutput).isEqualTo(fakeRedirectInstrumentationOutput)
        } else {
            assertThat(instrumentationOutput).isInstanceOf(LocalInstrumentationOutput::class.java)
        }

        // When user wants to create a redirectOutputBuilder object but its builder supplier is not
        // provided, eventHandler should post a warning event.
        if (createRedirectOutput && !injectRedirectOutputBuilderSupplier) {
            Truth.assertThat(warningEvents)
                .containsExactly(
                    com.google.devtools.build.lib.events.Event.of(
                        com.google.devtools.build.lib.events.EventKind.WARNING,
                        "Redirecting to write Instrumentation Output on a different machine is not"
                                + " supported. Defaulting to writing output locally."
                    )
                )
        } else {
            Truth.assertThat(warningEvents).isEmpty()
        }
        assertThat(
            outputFactory.createBuildEventArtifactInstrumentationOutput( /* name= */
                "bep", < T > mock < T ? > (BuildEventArtifactUploader::class.java)
        ))
        .isNotNull()
    }

    companion object {
        private fun createInstrumentationOutputFactory(
            setLocalTempLoggingDir: Boolean
        ): InstrumentationOutputFactory {
            val factoryBuilder: InstrumentationOutputFactory.Builder =
                Builder()
            factoryBuilder.setLocalInstrumentationOutputBuilderSupplier(
                { LocalInstrumentationOutput.Builder() })
            factoryBuilder.setBuildEventArtifactInstrumentationOutputBuilderSupplier(
                { BuildEventArtifactInstrumentationOutput.Builder() })
            if (setLocalTempLoggingDir) {
                factoryBuilder.setLocalTempLoggingDirPathStr("/tmp")
            }
            return factoryBuilder.build()
        }
    }
}
