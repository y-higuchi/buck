/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.maven.Publisher;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.net.URL;

import javax.annotation.Nullable;

public class PublishCommand extends BuildCommand {
  public static final String REMOTE_REPO_LONG_ARG = "--remote-repo";
  public static final String REMOTE_REPO_SHORT_ARG = "-r";
  public static final String INCLUDE_SOURCE_LONG_ARG = "--include-source";
  public static final String INCLUDE_SOURCE_SHORT_ARG = "-s";
  public static final String INCLUDE_JAVADOC_LONG_ARG = "--include-javadoc";
  public static final String TO_MAVEN_CENTRAL_LONG_ARG = "--to-maven-central";
  public static final String DRY_RUN_LONG_ARG = "--dry-run";
  public static final String SIGN_LONG_ARG = "--sign";

  @Option(
      name = REMOTE_REPO_LONG_ARG,
      aliases = REMOTE_REPO_SHORT_ARG,
      usage = "A url of the remote repository to publish artifact(s) to")
  @Nullable
  private URL remoteRepo = null;

  @Option(
      name = TO_MAVEN_CENTRAL_LONG_ARG,
      usage = "Same as \"" + REMOTE_REPO_LONG_ARG + " " + Publisher.MAVEN_CENTRAL_URL + "\"")
  private boolean toMavenCentral = false;

  @Option(
      name = INCLUDE_SOURCE_LONG_ARG,
      aliases = INCLUDE_SOURCE_SHORT_ARG,
      usage = "Publish source code as well")
  private boolean includeSource = false;

  @Option(
      name = INCLUDE_JAVADOC_LONG_ARG,
      usage = "Publish javadoc as well")
  private boolean includeJavadoc = false;

  @Option(
      name = DRY_RUN_LONG_ARG,
      usage = "Just print the artifacts to be published")
  private boolean dryRun = false;

  @Option(
      name = SIGN_LONG_ARG,
      usage = "Sign the artifacts")
  private boolean signArtifacts = false;

  private BuckConfig config;

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    // Input validation
    if (remoteRepo == null && !toMavenCentral) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          "Please specify a remote repository to publish to.\n" +
              "Use " + REMOTE_REPO_LONG_ARG + " <URL> or " + TO_MAVEN_CENTRAL_LONG_ARG));
      return 1;
    }

    // Build the specified target(s).
    int exitCode = super.runWithoutHelp(params);
    if (exitCode != 0) {
      return exitCode;
    }

    // Publish starting with the given targets.
    return publishTargets(getBuildTargets(), params) ? 0 : 1;
  }

  private boolean publishTargets(
      ImmutableList<BuildTarget> buildTargets,
      CommandRunnerParams params) {
    ImmutableSet.Builder<MavenPublishable> publishables = ImmutableSet.builder();
    boolean success = true;
    for (BuildTarget buildTarget : buildTargets) {
      BuildRule buildRule = null;
      try {
        buildRule = getBuild().getRuleResolver().requireRule(buildTarget);
      } catch (NoSuchBuildTargetException e) {
        // This doesn't seem physically possible!
        Throwables.propagate(e);
      }
      Preconditions.checkNotNull(buildRule);

      if (!(buildRule instanceof MavenPublishable)) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            "Cannot publish rule of type %s",
            buildRule.getClass().getName()));
        success &= false;
        continue;
      }

      MavenPublishable publishable = (MavenPublishable) buildRule;
      if (!publishable.getMavenCoords().isPresent()) {
        params.getBuckEventBus().post(ConsoleEvent.severe(
            "No maven coordinates specified for %s",
            buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName()));
        success &= false;
        continue;
      }
      publishables.add(publishable);
    }

    Publisher publisher = new Publisher(
        params.getCell().getFilesystem(),
        Optional.fromNullable(remoteRepo),
        params.getConsole().getStdOut(), // FIXME BOC pass through better
        config, // FIXME BOC pass through better
        dryRun,
        signArtifacts);

    try {
      ImmutableSet<DeployResult> deployResults = publisher.publish(publishables.build());
      for (DeployResult deployResult : deployResults) {
        printArtifactsInformation(params, deployResult);
      }
    } catch (DeploymentException e) {
      params.getConsole().printBuildFailureWithoutStacktraceDontUnwrap(e);
      return false;
    }
    return success;
  }

  private static void printArtifactsInformation(
      CommandRunnerParams params,
      DeployResult deployResult) {
    params.getConsole().getStdOut().println(
        "\nPublished artifacts:\n" +
            Joiner.on('\n').join(
                FluentIterable
                    .from(deployResult.getArtifacts())
                    .transform(
                        PublishCommand::artifactToString)));
    params.getConsole().getStdOut().println("\nDeployRequest:\n" + deployResult.getRequest());
  }

  private static String artifactToString(Artifact artifact) {
    return artifact.toString() + " < " + artifact.getFile();
  }

  @Override
  public ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
      BuckConfig config, Iterable<String> targetsAsArgs) {
    ImmutableList<TargetNodeSpec> specs = super.parseArgumentsAsTargetNodeSpecs(
        config,
        targetsAsArgs);

    if (includeSource) {
      specs = ImmutableList.<TargetNodeSpec>builder()
          .addAll(specs)
          .addAll(FluentIterable
              .from(specs)
              .filter(
                  input -> {
                    if (!(input instanceof BuildTargetSpec)) {
                      throw new IllegalArgumentException(
                          "Targets must be explicitly defined when using " +
                              INCLUDE_SOURCE_LONG_ARG);
                    }
                    return !((BuildTargetSpec) input)
                        .getBuildTarget()
                        .getFlavors()
                        .contains(JavaLibrary.SRC_JAR);
                  })
              .transform(
                  input -> BuildTargetSpec.of(
                      ((BuildTargetSpec) input)
                          .getBuildTarget()
                          .withFlavors(JavaLibrary.SRC_JAR),
                      input.getBuildFileSpec())))
          .build();
    }

    // Append "maven" flavor
    ImmutableList.Builder<TargetNodeSpec> builder = ImmutableList.<TargetNodeSpec>builder()
          .addAll(FluentIterable.from(specs)
              .transform(
                  new Function<TargetNodeSpec, TargetNodeSpec>() {
                    @Nullable
                    @Override
                    public TargetNodeSpec apply(@Nullable TargetNodeSpec input) {
                      if (!(input instanceof BuildTargetSpec)) {
                        throw new IllegalArgumentException(
                            "Need to specify build targets explicitly when publishing. " +
                                "Cannot modify " + input);
                      }
                      BuildTargetSpec buildTargetSpec = (BuildTargetSpec) input;
                      BuildTarget buildTarget =
                          Preconditions.checkNotNull(buildTargetSpec.getBuildTarget());
                      return buildTargetSpec.withBuildTarget(
                          BuildTarget
                              .builder(buildTarget)
                              .addFlavors(JavaLibrary.MAVEN_JAR)
                              .build());
                    }
                  }));

    if (includeSource) {
      addFlavorToSpecList(specs, JavaLibrary.SRC_JAR, builder);
    }

    if (includeJavadoc) {
      addFlavorToSpecList(specs, JavaLibrary.JAVADOC_JAR, builder);
    }

    return builder.build();
  }

  @Override
  public String getShortDescription() {
    return "builds and publishes a library to a central repository";
  }

  //FIXME BOC hackcity
  public void setBuckConfig(BuckConfig config) {
    this.config = config;
  }
}
