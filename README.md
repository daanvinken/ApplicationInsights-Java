[![Build Status](https://github-private.visualstudio.com/microsoft/_apis/build/status/CDPX/applicationinsights-java/applicationinsights-java-Windows-Buddy-master?branchName=refs%2Fpull%2F1583%2Fmerge)](https://github-private.visualstudio.com/microsoft/_build/latest?definitionId=224&branchName=refs%2Fpull%2F1583%2Fmerge)

# Application Insights for Java - Modification for saving azure metrics
In order to enable the metrics for a Java application, one has to point the JVM to an additional Jar file which sends metrics to the dashboard. 
It does this by default without any modifications. 
One can then see the metrics on the Azure live metrics dashboard. 
Now as these metrics are not stored anywhere, we had to modify this open-source instrumentation Jar,
such that it stores these fine-grained metrics. 
We decided to go with Azure File Shares to store these metrics as CSVs. 

Main magic happens [here](https://github.com/daanvinken/ApplicationInsights-Java/blob/main/agent/agent-tooling/src/main/java/com/microsoft/applicationinsights/agent/internal/quickpulse/QuickPulseDataSaver.java). Unfortunately you still have to enter your Azure File Share credentials manually in `storageConnectionString`.

# Application Insights for Java

See documentation at https://docs.microsoft.com/en-us/azure/azure-monitor/app/java-in-process-agent.

## Log4j 2 questions?

See [response to CVE-2021-44228 Apache Log4j 2](
https://github.com/microsoft/ApplicationInsights-Java/discussions/2008).

## If you need to build locally

Download the main repo and submodule:

```
git clone https://github.com/microsoft/ApplicationInsights-Java
cd ApplicationInsights-Java
git submodule init
git submodule update
```

Publish all the artifacts from the submodule to your local maven repository:

```
cd otel
./gradlew publishToMavenLocal
cd ..
```

Build the agent jar file:

```
./gradlew -DskipWinNative=true :agent:agent:shadowJar
```

The agent jar file should now be available under `agent/agent/build/libs`.

## If you are contributing...

We follow the same
[style guidelines](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/style-guideline.md)
and
[recommended Intellij setup](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/intellij-setup.md)
as the OpenTelemetry Java Instrumentation repo.

While developing, if you find errorprone is getting in your way (e.g. it won't let you add
`System.out.println` to your code), you can disable it by adding the following to your
`~/.gradle/gradle.properties`:

```
disableErrorProne=true
```

## Microsoft Open Source Code of Conduct

This project has adopted the
[Microsoft Open Source Code of Conduct](https://opensource.microsoft.com/codeofconduct/). For more
information see the
[Code of Conduct FAQ](https://opensource.microsoft.com/codeofconduct/faq/)
or contact [opencode@microsoft.com](mailto:opencode@microsoft.com)
with any additional questions or comments.
