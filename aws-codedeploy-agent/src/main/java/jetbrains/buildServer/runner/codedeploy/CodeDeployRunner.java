/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.runner.codedeploy;

import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Map;

/**
 * @author vbedrosova
 */
public class CodeDeployRunner implements AgentBuildRunner {
  @NotNull
  @Override
  public BuildProcess createBuildProcess(@NotNull final AgentRunningBuild runningBuild, @NotNull final BuildRunnerContext context) throws RunBuildException {
    return new SyncBuildProcessAdapter(context.getBuild().getBuildLogger()) {
      @Override
      protected void runProcess() throws RunBuildException {
        final Map<String, String> runnerParameters = context.getRunnerParameters();

        ParametersValidator.validateRuntime(runnerParameters, runningBuild.getCheckoutDirectory());

        final String accessKeyId = runnerParameters.get(CodeDeployConstants.ACCESS_KEY_ID_PARAM);
        final String secretAccessKey = runnerParameters.get(CodeDeployConstants.SECRET_ACCESS_KEY_PARAM);
        final String regionName = runnerParameters.get(CodeDeployConstants.REGION_NAME_PARAM);

        final AWSClient awsClient = createAWSClient(accessKeyId, secretAccessKey, regionName, runningBuild);

        final String revisionPath = runnerParameters.get(CodeDeployConstants.READY_REVISION_PATH_PARAM);
        final File revision = FileUtil.resolvePath(runningBuild.getCheckoutDirectory(), revisionPath);
        final String s3BucketName = runnerParameters.get(CodeDeployConstants.S3_BUCKET_NAME_PARAM);
        final String applicationName = runnerParameters.get(CodeDeployConstants.APP_NAME_PARAM);
        final String deploymentGroupName = runnerParameters.get(CodeDeployConstants.DEPLOYMENT_GROUP_NAME_PARAM);
        final String deploymentConfigName = StringUtil.nullIfEmpty(runnerParameters.get(CodeDeployConstants.DEPLOYMENT_CONFIG_NAME_PARAM));

        awsClient.uploadAndRegisterAndDeployRevision(revision, s3BucketName, applicationName, deploymentGroupName, deploymentConfigName);
      }
    };
  }

  @NotNull
  @Override
  public AgentBuildRunnerInfo getRunnerInfo() {
    return new AgentBuildRunnerInfo() {
      @NotNull
      @Override
      public String getType() {
        return CodeDeployConstants.RUNNER_TYPE;
      }

      @Override
      public boolean canRun(@NotNull BuildAgentConfiguration agentConfiguration) {
        return true;
      }
    };
  }

  @NotNull
  private AWSClient createAWSClient(@NotNull String accessKeyId, @NotNull String secretAccessKey, @NotNull String regionName, @NotNull final AgentRunningBuild runningBuild) {
    final BuildProgressLogger buildLogger = runningBuild.getBuildLogger();

    return new AWSClient(accessKeyId, secretAccessKey, regionName) {
      @Override
      protected void log(@NotNull String message) {
        buildLogger.message(message);
      }

      @Override
      protected void err(@NotNull String message) {
        buildLogger.error(message);
      }

      @Override
      protected void debug(@NotNull String message) {
        buildLogger.message(String.format("##teamcity[message text='%s' tc:tags='tc:internal']", escape(message)));
      }

      @Override
      protected void problem(int identity, @NotNull String type, @NotNull String descr) {
         buildLogger.message(String.format("##teamcity[buildProblem identity='%d' type='%s' description='%s' tc:tags='tc:internal']", identity, type, escape(descr)));
      }

      @NotNull
      private String escape(@NotNull String s) {
        return s.
          replace("|", "||").
          replace("'", "|'").
          replace("\n", "|n").
          replace("\r", "|r").
          replace("\\uNNNN", "|0xNNNN").
          replace("[", "|[").replace("]", "|]");
      }

      @NotNull
      @Override
      protected String getDescription() {
        return runningBuild.getBuildNumber();
      }
    }.withBaseDir(runningBuild.getCheckoutDirectory().getAbsolutePath());
  }
}