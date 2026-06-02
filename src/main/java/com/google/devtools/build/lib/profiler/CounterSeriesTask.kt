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
package com.google.devtools.build.lib.profiler

/**
 * Describes counter series to be logged into profile.
 * 
 * @param laneName The lane name for the counter series. Series with the same lane name should be
 * stacked when displaying.
 * @param seriesName The name for the counter series.
 * @param color The color for the counter series. If `null`, the profile viewer will pick a
 * color automatically.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
@kotlin.jvm.JvmRecord
data class CounterSeriesTask(laneName: String?, seriesName: String?, color: Color?) {
    /** The revered color for rendering the bar chart.  */
    enum class Color(value: String) {
        // Pick acceptable counter colors manually, unfortunately we have to pick from these
        // weird reserved names from
        // https://github.com/catapult-project/catapult/blob/master/tracing/tracing/base/color_scheme.html
        THREAD_STATE_UNINTERRUPTIBLE("thread_state_uninterruptible"),
        THREAD_STATE_IOWAIT("thread_state_iowait"),
        THREAD_STATE_RUNNING("thread_state_running"),
        THREAD_STATE_RUNNABLE("thread_state_runnable"),
        THREAD_STATE_SLEEPING("thread_state_sleeping"),
        THREAD_STATE_UNKNOWN("thread_state_unknown"),
        BACKGROUND_MEMORY_DUMP("background_memory_dump"),
        LIGHT_MEMORY_DUMP("light_memory_dump"),
        DETAILED_MEMORY_DUMP("detailed_memory_dump"),
        VSYNC_HIGHLIGHT_COLOR("vsync_highlight_color"),
        GENERIC_WORK("generic_work"),
        GOOD("good"),
        BAD("bad"),
        TERRIBLE("terrible"),
        BLACK("black"),
        GREY("grey"),
        WHITE("white"),
        YELLOW("yellow"),
        OLIVE("olive"),
        RAIL_RESPONSE("rail_response"),
        RAIL_ANIMATION("rail_animation"),
        RAIL_IDLE("rail_idle"),
        RAIL_LOAD("rail_load"),
        STARTUP("startup"),
        HEAP_DUMP_STACK_FRAME("heap_dump_stack_frame"),
        HEAP_DUMP_OBJECT_TYPE("heap_dump_object_type"),
        HEAP_DUMP_CHILD_NODE_ARROW("heap_dump_child_node_arrow"),
        CQ_BUILD_RUNNING("cq_build_running"),
        CQ_BUILD_PASSED("cq_build_passed"),
        CQ_BUILD_FAILED("cq_build_failed"),
        CQ_BUILD_ABANDONED("cq_build_abandoned"),
        CQ_BUILD_ATTEMPT_RUNNIG("cq_build_attempt_runnig"),
        CQ_BUILD_ATTEMPT_PASSED("cq_build_attempt_passed"),
        CQ_BUILD_ATTEMPT_FAILED("cq_build_attempt_failed");

        private val value: String?

        init {
            this.value = value
        }

        fun value(): String? {
            return value
        }
    }

    val laneName: String?
    val seriesName: String?
    val color: Color?

    init {
        this.laneName = laneName
        this.seriesName = seriesName
        this.color = color
    }
}
