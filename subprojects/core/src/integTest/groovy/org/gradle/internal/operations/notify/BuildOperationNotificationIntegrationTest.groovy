/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.operations.notify


import org.gradle.api.internal.plugins.ApplyPluginBuildOperationType
import org.gradle.configuration.ApplyScriptPluginBuildOperationType
import org.gradle.configuration.project.ConfigureProjectBuildOperationType
import org.gradle.configuration.project.NotifyProjectAfterEvaluatedBuildOperationType
import org.gradle.configuration.project.NotifyProjectBeforeEvaluatedBuildOperationType
import org.gradle.execution.taskgraph.NotifyTaskGraphWhenReadyBuildOperationType
import org.gradle.initialization.ConfigureBuildBuildOperationType
import org.gradle.initialization.EvaluateSettingsBuildOperationType
import org.gradle.initialization.LoadBuildBuildOperationType
import org.gradle.initialization.LoadProjectsBuildOperationType
import org.gradle.initialization.NotifyProjectsEvaluatedBuildOperationType
import org.gradle.initialization.NotifyProjectsLoadedBuildOperationType
import org.gradle.initialization.buildsrc.BuildBuildSrcBuildOperationType
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.internal.execution.ExecuteTaskBuildOperationType
import org.gradle.internal.taskgraph.CalculateTaskGraphBuildOperationType
import org.gradle.launcher.exec.RunBuildBuildOperationType

class BuildOperationNotificationIntegrationTest extends AbstractIntegrationSpec {

    def notifications = new BuildOperationNotificationFixture(testDirectory)

    def "obtains notifications about init scripts"() {
        when:
        executer.requireOwnGradleUserHomeDir()
        def init = executer.gradleUserHomeDir.file("init.d/init.gradle") << """
            println "init script"
        """
        buildScript """
           ${notifications.registerListenerWithDrainRecordings()}
            task t
        """

        file("buildSrc/build.gradle") << ""

        succeeds "t"

        then:
        notifications.started(ApplyScriptPluginBuildOperationType.Details, [targetType: "gradle", targetPath: null, file: init.absolutePath, buildPath: ":", uri: null, applicationId: { it instanceof Number }])
        notifications.started(ApplyScriptPluginBuildOperationType.Details, [targetType: "gradle", targetPath: null, file: init.absolutePath, buildPath: ":buildSrc", uri: null, applicationId: { it instanceof Number }])
    }

    def "can emit notifications from start of build"() {
        when:
        buildScript """
           ${notifications.registerListenerWithDrainRecordings()}
            task t
        """

        succeeds "t", "-S"

        then:
        notifications.started(LoadBuildBuildOperationType.Details, [buildPath: ":"])
        notifications.started(EvaluateSettingsBuildOperationType.Details, [settingsDir: testDirectory.absolutePath, settingsFile: settingsFile.absolutePath, buildPath: ":"])
        notifications.finished(EvaluateSettingsBuildOperationType.Result, [:])
        notifications.started(LoadProjectsBuildOperationType.Details, [buildPath: ":"])
        notifications.finished(LoadProjectsBuildOperationType.Result)
        notifications.started(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":"])
        notifications.finished(NotifyProjectsLoadedBuildOperationType.Result)

        notifications.started(ConfigureProjectBuildOperationType.Details, [buildPath: ':', projectPath: ':'])
        notifications.started(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ':', projectPath: ':'])
        notifications.started(ApplyPluginBuildOperationType.Details, [pluginId: "org.gradle.help-tasks", pluginClass: "org.gradle.api.plugins.HelpTasksPlugin", targetType: "project", targetPath: ":", buildPath: ":"])
        notifications.finished(ApplyPluginBuildOperationType.Result, [:])
        notifications.started(ApplyScriptPluginBuildOperationType.Details, [targetType: "project", targetPath: ":", file: buildFile.absolutePath, buildPath: ":", uri: null, applicationId: { it instanceof Number }])
        notifications.finished(ApplyScriptPluginBuildOperationType.Result, [:])
        notifications.started(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ':', projectPath: ':'])
        notifications.finished(ConfigureProjectBuildOperationType.Result, [:])
        notifications.started(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ':'])
        notifications.finished(NotifyProjectsEvaluatedBuildOperationType.Result, [:])

        notifications.started(CalculateTaskGraphBuildOperationType.Details, [buildPath: ':'])
        notifications.finished(CalculateTaskGraphBuildOperationType.Result, [excludedTaskPaths: [], requestedTaskPaths: [":t"]])
        notifications.started(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ':'])
        notifications.started(ExecuteTaskBuildOperationType.Details, [taskPath: ":t", buildPath: ":", taskClass: "org.gradle.api.DefaultTask"])
        notifications.finished(ExecuteTaskBuildOperationType.Result, [actionable: false, originExecutionTime: null, cachingDisabledReasonMessage: "Cacheability was not determined", upToDateMessages: null, cachingDisabledReasonCategory: "UNKNOWN", skipMessage: "UP-TO-DATE", originBuildInvocationId: null])
    }

    def "can emit notifications from point of registration"() {
        when:
        buildScript """
           ${notifications.registerListener()}
            task t
        """

        succeeds "t", "-S"

        then:
        // Operations that started before the listener registration are not included (even if they finish _after_ listener registration)
        notifications.notIncluded(EvaluateSettingsBuildOperationType.Details)
        notifications.notIncluded(LoadProjectsBuildOperationType.Details)
        notifications.notIncluded(NotifyProjectsLoadedBuildOperationType.Details)
        notifications.notIncluded(ApplyPluginBuildOperationType.Details)
        notifications.notIncluded(ConfigureProjectBuildOperationType.Details)

        notifications.started(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ':'])
        notifications.started(CalculateTaskGraphBuildOperationType.Details, [buildPath: ':'])
        notifications.finished(CalculateTaskGraphBuildOperationType.Result, [excludedTaskPaths: [], requestedTaskPaths: [":t"]])
        notifications.started(ExecuteTaskBuildOperationType.Details, [taskPath: ":t", buildPath: ":", taskClass: "org.gradle.api.DefaultTask"])
        notifications.finished(ExecuteTaskBuildOperationType.Result, [actionable: false, originExecutionTime: null, cachingDisabledReasonMessage: "Cacheability was not determined", upToDateMessages: null, cachingDisabledReasonCategory: "UNKNOWN", skipMessage: "UP-TO-DATE", originBuildInvocationId: null])
    }

    def "can emit notifications for nested builds"() {
        when:
        file("buildSrc/build.gradle") << ""
        file("a/buildSrc/build.gradle") << ""
        file("a/build.gradle") << "task t"
        file("a/settings.gradle") << ""
        file("settings.gradle") << "includeBuild 'a'"
        buildScript """
           ${notifications.registerListenerWithDrainRecordings()}
            task t {
                dependsOn gradle.includedBuild("a").task(":t")
            }
        """

        succeeds "t"

        then:
        notifications.started(LoadBuildBuildOperationType.Details, [buildPath: ":"])

        notifications.started(LoadBuildBuildOperationType.Details, [buildPath: ":"])
        notifications.started(LoadBuildBuildOperationType.Details, [buildPath: ":buildSrc"])
        notifications.started(LoadBuildBuildOperationType.Details, [buildPath: ":a"])
        notifications.started(LoadBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"])

        notifications.started(EvaluateSettingsBuildOperationType.Details, [settingsDir: file('buildSrc').absolutePath, settingsFile: file('buildSrc/settings.gradle').absolutePath, buildPath: ":buildSrc"])
        notifications.started(EvaluateSettingsBuildOperationType.Details, [settingsDir: file('a').absolutePath, settingsFile: file('a/settings.gradle').absolutePath, buildPath: ":a"])
        notifications.started(EvaluateSettingsBuildOperationType.Details, [settingsDir: file('a/buildSrc').absolutePath, settingsFile: file('a/buildSrc/settings.gradle').absolutePath, buildPath: ":a:buildSrc"])
        notifications.started(EvaluateSettingsBuildOperationType.Details, [settingsDir: file('.').absolutePath, settingsFile: file('settings.gradle').absolutePath, buildPath: ":"])

        notifications.started(LoadProjectsBuildOperationType.Details, [buildPath: ":buildSrc"])
        notifications.started(LoadProjectsBuildOperationType.Details, [buildPath: ":a:buildSrc"])
        notifications.started(LoadProjectsBuildOperationType.Details, [buildPath: ":a"])
        notifications.started(LoadProjectsBuildOperationType.Details, [buildPath: ":"])

        notifications.started(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":buildSrc"])
        notifications.started(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":a:buildSrc"])
        notifications.started(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":a"])
        notifications.started(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":"])

        notifications.started(ConfigureProjectBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"])
        notifications.started(ConfigureProjectBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"])
        notifications.started(ConfigureProjectBuildOperationType.Details, [buildPath: ":a", projectPath: ":"])
        notifications.started(ConfigureProjectBuildOperationType.Details, [buildPath: ":", projectPath: ":"])

        notifications.started(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"])
        notifications.started(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"])
        notifications.started(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":a", projectPath: ":"])
        notifications.started(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":", projectPath: ":"])

        notifications.started(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"])
        notifications.started(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"])
        notifications.started(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":a", projectPath: ":"])
        notifications.started(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":", projectPath: ":"])

        notifications.started(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":buildSrc"])
        notifications.started(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":a:buildSrc"])
        notifications.started(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":a"])
        notifications.started(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":"])

        notifications.started(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":buildSrc"])
        notifications.started(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":a:buildSrc"])
        notifications.started(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":a"])
        notifications.started(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":"])

        // evaluate hierarchies
        notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(RunBuildBuildOperationType.Details).id
        notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":"]).id
        notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(BuildBuildSrcBuildOperationType.Details, [buildPath: ':']).id
        notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(BuildBuildSrcBuildOperationType.Details, [buildPath: ':a']).id

        notifications.op(EvaluateSettingsBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":"]).id
        notifications.op(EvaluateSettingsBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":a"]).id
        notifications.op(EvaluateSettingsBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":buildSrc"]).id
        notifications.op(EvaluateSettingsBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(LoadBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"]).id

        notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(RunBuildBuildOperationType.Details).id
        notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":"]).id
        notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(BuildBuildSrcBuildOperationType.Details, [buildPath: ':']).id
        notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(BuildBuildSrcBuildOperationType.Details, [buildPath: ':a']).id

        notifications.op(LoadProjectsBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":"]).id
        notifications.op(LoadProjectsBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a"]).id
        notifications.op(LoadProjectsBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":buildSrc"]).id
        notifications.op(LoadProjectsBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"]).id

        notifications.op(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":"]).id
        notifications.op(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a"]).id
        notifications.op(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":buildSrc"]).id
        notifications.op(NotifyProjectsLoadedBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"]).id

        notifications.op(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":"]).id
        notifications.op(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a"]).id
        notifications.op(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":buildSrc"]).id
        notifications.op(NotifyProjectsEvaluatedBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(ConfigureBuildBuildOperationType.Details, [buildPath: ":a:buildSrc"]).id

        notifications.op(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":", projectPath: ":"]).id
        notifications.op(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":a", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":a", projectPath: ":"]).id
        notifications.op(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"]).id
        notifications.op(NotifyProjectBeforeEvaluatedBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"]).id

        notifications.op(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":", projectPath: ":"]).id
        notifications.op(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":a", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":a", projectPath: ":"]).id
        notifications.op(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":buildSrc", projectPath: ":"]).id
        notifications.op(NotifyProjectAfterEvaluatedBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"]).parentId == notifications.op(ConfigureProjectBuildOperationType.Details, [buildPath: ":a:buildSrc", projectPath: ":"]).id

        notifications.op(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":"]).parentId == notifications.op(RunBuildBuildOperationType.Details).id
        notifications.op(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":a"]).parentId == notifications.op(RunBuildBuildOperationType.Details).id
        notifications.op(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":buildSrc"]).parentId == notifications.op(BuildBuildSrcBuildOperationType.Details, [buildPath: ':']).id
        notifications.op(NotifyTaskGraphWhenReadyBuildOperationType.Details, [buildPath: ":a:buildSrc"]).parentId == notifications.op(BuildBuildSrcBuildOperationType.Details, [buildPath: ':a']).id
    }

    def "emits for GradleBuild tasks"() {
        when:
        def initScript = file("init.gradle") << """
            if (parent == null) {
                ${notifications.registerListener()}
            }
        """

        buildScript """
            task t(type: GradleBuild) {
                tasks = ["o"]
                startParameter.searchUpwards = false
            }
            task o
        """

        succeeds "t", "-I", initScript.absolutePath

        then:
        executedTasks.find { it.endsWith(":o") }

        notifications.started(ConfigureProjectBuildOperationType.Details, [buildPath: ":", projectPath: ":"])
        notifications.started(ConfigureProjectBuildOperationType.Details) {
            it.projectPath == ":" && it.buildPath != ":"
        }
        notifications.started(ExecuteTaskBuildOperationType.Details) {
            it.taskPath == ":o"
        }
    }

    def "listeners are deregistered after build"() {
        when:
        executer.requireDaemon().requireIsolatedDaemons()
        buildFile << notifications.registerListener() << "task t"
        succeeds("t")

        then:
        notifications.finished(CalculateTaskGraphBuildOperationType.Result, [excludedTaskPaths: [], requestedTaskPaths: [":t"]])

        when:
        // remove listener
        buildFile.text = "task x"
        succeeds("x")

        then:
        notifications.recordedOps.findAll { it.detailsType == CalculateTaskGraphBuildOperationType.Result.name }.size() == 0
    }

    // This test simulates what the build scan plugin does.
    def "drains notifications for buildSrc build"() {
        given:
        file("buildSrc/build.gradle") << ""
        file("build.gradle") << """
            ${notifications.registerListenerWithDrainRecordings()}
            task t
        """

        when:
        succeeds "t"

        then:
        output.contains(":buildSrc:compileJava") // executedTasks check fails with in process executer
        notifications.recordedOps.findAll { it.detailsType == ConfigureProjectBuildOperationType.Details.name }.size() == 2
        notifications.recordedOps.findAll { it.detailsType == ExecuteTaskBuildOperationType.Details.name }.size() == 14 // including all buildSrc task execution events
    }


}
