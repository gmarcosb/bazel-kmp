// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Reports cycles occurring in during the expansion of `test_suite` rules.  */
internal class TestExpansionCycleReporter(packageProvider: PackageProvider?) :
    AbstractLabelCycleReporter(packageProvider) {
    protected override fun canReportCycle(topLevelKey: SkyKey?, cycleInfo: CycleInfo): Boolean {
        return cycleInfo.getCycle().stream()
            .allMatch(java.util.function.Predicate { obj: SkyKey? -> TestExpansionKey::class.java.isInstance(obj) })
    }

    protected override fun shouldSkipOnPathToCycle(key: SkyKey?): Boolean {
        return key !is TestExpansionKey
    }

    protected override fun getLabel(key: SkyKey): Label? {
        return (key as TestExpansionKey).getLabel()
    }
}
