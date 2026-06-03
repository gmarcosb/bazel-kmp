// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

/** Tests [com.google.devtools.build.lib.buildtool.InstrumentationFilterSupport].  */
@RunWith(JUnit4::class)
class InstrumentationFilterSupportTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputeInstrumentationFilter() {
        val events: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.INFO)
        scratch.file("foo/BUILD", "filegroup(name='t', srcs=['t.sh'])")
        scratch.file("foobar/BUILD", "filegroup(name='t', srcs=['t.sh'])")
        val listOfTargets: MutableList<Target?> = java.util.ArrayList<Target?>()
        listOfTargets.add(getTarget("//foo:t"))
        listOfTargets.add(getTarget("//foobar:t"))
        val targets: MutableCollection<Target?> = Collections.unmodifiableCollection<Target?>(listOfTargets)
        val expectedFilter = "^//foo[/:],^//foobar[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
    }
}
