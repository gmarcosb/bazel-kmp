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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/** An event in which the command line options are discovered.  */
class GotOptionsEvent(
    startupOptions: com.google.devtools.common.options.OptionsParsingResult,
    options: com.google.devtools.common.options.OptionsParsingResult,
    invocationPolicy: InvocationPolicy?
) : BuildEventWithOrderConstraint {
    private val startupOptions: com.google.devtools.common.options.OptionsParsingResult
    private val options: com.google.devtools.common.options.OptionsParsingResult
    private val invocationPolicy: InvocationPolicy?

    /**
     * Construct the options event.
     * 
     * @param startupOptions the parsed startup options
     * @param options the parsed options
     */
    init {
        this.startupOptions = startupOptions
        this.options = options
        this.invocationPolicy = invocationPolicy
    }

    /** @return the parsed startup options
     */
    fun getStartupOptions(): com.google.devtools.common.options.OptionsParsingResult {
        return startupOptions
    }

    /** @return the parsed options.
     */
    fun getOptions(): com.google.devtools.common.options.OptionsParsingResult {
        return options
    }

    /** @return the invocation policy.
     */
    fun getInvocationPolicy(): InvocationPolicy? {
        return invocationPolicy
    }

    val eventId: BuildEventId
        get() = BuildEventIdUtil.optionsParsedId()

    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
        val optionsBuilder: BuildEventStreamProtos.OptionsParsed.Builder =
            BuildEventStreamProtos.OptionsParsed.newBuilder()

        var options: com.google.devtools.common.options.OptionsParsingResult = getStartupOptions()
        optionsBuilder.addAllStartupOptions(OptionsUtils.asArgumentList(options))
        optionsBuilder.addAllExplicitStartupOptions(
            OptionsUtils.asArgumentList(
                com.google.common.collect.Iterables.filter<com.google.devtools.common.options.ParsedOptionDescription?>(
                    options.asListOfExplicitOptions(),
                    com.google.common.base.Predicate { input: com.google.devtools.common.options.ParsedOptionDescription? -> input.getSource() != "default" })
            )
        )
        options = getOptions()
        optionsBuilder.addAllCmdLine(OptionsUtils.asArgumentList(options))
        optionsBuilder.addAllExplicitCmdLine(
            OptionsUtils.asArgumentList(
                com.google.common.collect.Iterables.filter<com.google.devtools.common.options.ParsedOptionDescription?>(
                    options.asListOfExplicitOptions(),
                    com.google.common.base.Predicate { input: com.google.devtools.common.options.ParsedOptionDescription? -> input.getSource() == "command line options" })
            )
        )

        optionsBuilder.setInvocationPolicy(getInvocationPolicy())

        val commonOptions: CommonCommandOptions? =
            getOptions().getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        optionsBuilder.setToolTag(commonOptions.getToolTag())

        return GenericBuildEvent.protoChaining(this).setOptionsParsed(optionsBuilder.build()).build()
    }

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(
            BuildEventIdUtil.buildStartedId(), BuildEventIdUtil.unstructuredCommandlineId()
        )
    }
}
