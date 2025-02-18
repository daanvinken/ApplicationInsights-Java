/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.agent.internal.localstorage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.applicationinsights.agent.internal.MockHttpResponse;
import com.microsoft.applicationinsights.agent.internal.statsbeat.NetworkStatsbeat;
import com.microsoft.applicationinsights.agent.internal.statsbeat.StatsbeatModule;
import com.microsoft.applicationinsights.agent.internal.telemetry.TelemetryChannel;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

public class LocalFileLoaderTests {

  private static final String GZIPPED_RAW_BYTES_WITHOUT_IKEY = "gzipped-raw-bytes-without-ikey.trn";
  private static final String BYTE_BUFFERS_TEST_FILE = "read-transmission.txt";
  private static final String INSTRUMENTATION_KEY = "00000000-0000-0000-0000-0FEEDDADBEEF";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir File tempFolder;

  @AfterEach
  public void cleanup() {}

  @Test
  public void testInstrumentationKeyRegex() {
    assertThat(LocalFileLoader.isInstrumentationKeyValid(INSTRUMENTATION_KEY)).isTrue();
    assertThat(LocalFileLoader.isInstrumentationKeyValid("fake-instrumentation-key")).isFalse();
    assertThat(LocalFileLoader.isInstrumentationKeyValid("5ED1AE38-41AF-11EC-81D3")).isFalse();
    assertThat(LocalFileLoader.isInstrumentationKeyValid("5ED1AE38-41AF-11EC-81D3-0242AC130003"))
        .isTrue();
    assertThat(LocalFileLoader.isInstrumentationKeyValid("C6864988-6BF8-45EF-8590-1FD3D84E5A4D"))
        .isTrue();
  }

  @Test
  public void testPersistedFileWithoutInstrumentationKey() throws IOException {
    File sourceFile =
        new File(getClass().getClassLoader().getResource(GZIPPED_RAW_BYTES_WITHOUT_IKEY).getPath());

    File persistedFile = new File(tempFolder, GZIPPED_RAW_BYTES_WITHOUT_IKEY);
    FileUtils.copyFile(sourceFile, persistedFile);
    assertThat(persistedFile.exists()).isTrue();

    LocalFileCache localFileCache = new LocalFileCache(tempFolder);
    localFileCache.addPersistedFilenameToMap(GZIPPED_RAW_BYTES_WITHOUT_IKEY);

    LocalFileLoader localFileLoader = new LocalFileLoader(localFileCache, tempFolder, null);
    LocalFileLoader.PersistedFile loadedPersistedFile = localFileLoader.loadTelemetriesFromDisk();
    assertThat(loadedPersistedFile).isNull();
    assertThat(persistedFile.exists())
        .isFalse(); // verify the old formatted trn is deleted successfully.
  }

  @Test
  public void testLoadFile() throws IOException {
    File sourceFile =
        new File(getClass().getClassLoader().getResource(BYTE_BUFFERS_TEST_FILE).getPath());

    File persistedFile = new File(tempFolder, BYTE_BUFFERS_TEST_FILE);

    FileUtils.copyFile(sourceFile, persistedFile);
    assertThat(persistedFile.exists()).isTrue();

    LocalFileCache localFileCache = new LocalFileCache(tempFolder);
    localFileCache.addPersistedFilenameToMap(BYTE_BUFFERS_TEST_FILE);

    LocalFileLoader localFileLoader = new LocalFileLoader(localFileCache, tempFolder, null);
    LocalFileLoader.PersistedFile loadedPersistedFile = localFileLoader.loadTelemetriesFromDisk();
    assertThat(loadedPersistedFile.instrumentationKey).isEqualTo(INSTRUMENTATION_KEY);
    String bytesString = new String(loadedPersistedFile.rawBytes.array(), UTF_8);

    String[] stringArray = bytesString.split("\n");
    assertThat(stringArray.length).isEqualTo(10);

    for (int i = 0; i < stringArray.length; i++) {
      JsonNode jsonNode = MAPPER.readTree(stringArray[i]);

      // verify common properties
      assertThat(jsonNode).hasSize(7);
      assertThat(jsonNode.get("ver").asInt()).isEqualTo(1);
      verifyTelemetryName(i, jsonNode.get("name").asText());
      verifyTelemetryTime(i, jsonNode.get("time").asText());
      assertThat(jsonNode.get("sampleRate").asInt()).isEqualTo(100);
      assertThat(jsonNode.get("iKey").asText()).isEqualTo(INSTRUMENTATION_KEY);

      // verify tags
      JsonNode tagsNode = jsonNode.get("tags");
      verifyTagsNodeSize(i, tagsNode.size());

      assertThat(tagsNode.get("ai.internal.sdkVersion").asText()).isEqualTo("java:3.1.1");
      assertThat(tagsNode.get("ai.internal.nodeName").asText()).isEqualTo("test-role-name");
      assertThat(tagsNode.get("ai.cloud.roleInstance").asText()).isEqualTo("test-role-instance");
      if (i == 8) { // RemoteDependency
        assertThat(tagsNode.get("ai.operation.id").asText())
            .isEqualTo("891b332db33c65cc6497c014f02db26d");
      } else if (i == 9) {
        assertThat(tagsNode.get("ai.operation.id").asText())
            .isEqualTo("0cb22c0f071802f7f314569b007c9a1e");
        assertThat(tagsNode.get("ai.operation.name").asText()).isEqualTo("GET /webjars/**");
        assertThat(tagsNode.get("ai.user.userAgent").asText())
            .isEqualTo(
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.77 Safari/537.36");
      }

      // verify data
      JsonNode data = jsonNode.get("data");
      verifyDataBaseType(i, data.get("baseType").asText());

      JsonNode baseData = data.get("baseData");
      assertThat(baseData.get("ver").asInt()).isEqualTo(2);
      JsonNode metrics = baseData.get("metrics");

      if (i < 7) { // metrics is only applicable to Metric Telemetry type
        assertThat(metrics.get(0).get("name").asText()).isEqualTo(expectedMetricsName(i));
        assertThat(metrics.get(0).get("kind").asInt()).isEqualTo(0);
        assertThat(metrics.get(0).get("value").asInt()).isEqualTo(expectedMetricsValue(i));
      }

      if (i == 7) { // Message
        assertThat(baseData.get("message").asText())
            .isEqualTo("Tomcat initialized with port(s): 8080 (http)");
        assertThat(baseData.get("severityLevel").asText()).isEqualTo("Information");
      }

      if (i == 8) { // RemoteDependency's baseData
        verifyRemoteDependencyBaseData(baseData);
      }

      if (i == 9) { // Request's baseData
        verifyRequestBaseData(baseData);
      }

      // verify properties
      verifyProperties(i, baseData.get("properties"));
    }
  }

  @Test
  public void testWriteAndReadRandomText() {
    String text = "hello world";
    LocalFileCache cache = new LocalFileCache(tempFolder);
    LocalFileWriter writer = new LocalFileWriter(cache, tempFolder, null);
    writer.writeToDisk(singletonList(ByteBuffer.wrap(text.getBytes(UTF_8))), INSTRUMENTATION_KEY);

    LocalFileLoader loader = new LocalFileLoader(cache, tempFolder, null);
    LocalFileLoader.PersistedFile persistedFile = loader.loadTelemetriesFromDisk();
    assertThat(new String(persistedFile.rawBytes.array(), UTF_8)).isEqualTo(text);
    assertThat(persistedFile.instrumentationKey).isEqualTo(INSTRUMENTATION_KEY);
  }

  @Test
  public void testWriteGzipRawByte() throws IOException {
    String text =
        "1. Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore \n"
            + "2. magna aliquyam erat, sed diam voluptua. \n"
            + "3. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum \n"
            + "4. dolor sit amet. Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore\n"
            + "5. magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, \n"
            + "6. no sea takimata sanctus est Lorem ipsum dolor sit amet.";

    // gzip
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(byteArrayOutputStream)) {
      out.write(text.getBytes(UTF_8));
    } finally {
      byteArrayOutputStream.close();
    }

    // write gzipped bytes[] to disk
    byte[] result = byteArrayOutputStream.toByteArray();
    LocalFileCache cache = new LocalFileCache(tempFolder);
    LocalFileWriter writer = new LocalFileWriter(cache, tempFolder, null);
    writer.writeToDisk(singletonList(ByteBuffer.wrap(result)), INSTRUMENTATION_KEY);

    // read gzipped byte[] from disk
    LocalFileLoader loader = new LocalFileLoader(cache, tempFolder, null);
    LocalFileLoader.PersistedFile persistedFile = loader.loadTelemetriesFromDisk();
    byte[] bytes = persistedFile.rawBytes.array();

    // ungzip
    ByteArrayInputStream inputStream = new ByteArrayInputStream(result);
    byte[] ungzip = new byte[bytes.length * 3];
    int read;
    try (GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream)) {
      read = gzipInputStream.read(ungzip, 0, ungzip.length);
    } finally {
      inputStream.close();
    }

    assertThat(new String(Arrays.copyOf(ungzip, read), UTF_8)).isEqualTo(text);
    assertThat(persistedFile.instrumentationKey).isEqualTo(INSTRUMENTATION_KEY);
  }

  @Test
  public void testDeleteFilePermanentlyOnSuccess() throws Exception {
    HttpClient mockedClient = getMockHttpClientSuccess();
    HttpPipelineBuilder pipelineBuilder = new HttpPipelineBuilder().httpClient(mockedClient);
    LocalFileCache localFileCache = new LocalFileCache(tempFolder);
    LocalFileWriter localFileWriter = new LocalFileWriter(localFileCache, tempFolder, null);
    LocalFileLoader localFileLoader = new LocalFileLoader(localFileCache, tempFolder, null);

    StatsbeatModule mockedStatsbeatModule = Mockito.mock(StatsbeatModule.class);
    when(mockedStatsbeatModule.getNetworkStatsbeat())
        .thenReturn(Mockito.mock(NetworkStatsbeat.class));
    TelemetryChannel telemetryChannel =
        new TelemetryChannel(
            pipelineBuilder.build(),
            new URL("http://foo.bar"),
            localFileWriter,
            mockedStatsbeatModule,
            false);

    // persist 10 files to disk
    for (int i = 0; i < 10; i++) {
      localFileWriter.writeToDisk(
          singletonList(ByteBuffer.wrap("hello world".getBytes(UTF_8))), INSTRUMENTATION_KEY);
    }

    assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(10);

    Collection<File> files = FileUtils.listFiles(tempFolder, new String[] {"trn"}, false);
    assertThat(files.size()).isEqualTo(10);

    int expectedCount = 10;

    // send persisted files one by one and then delete it permanently.
    for (int i = 0; i < 10; i++) {
      LocalFileLoader.PersistedFile persistedFile = localFileLoader.loadTelemetriesFromDisk();
      CompletableResultCode completableResultCode =
          telemetryChannel.sendRawBytes(persistedFile.rawBytes, persistedFile.instrumentationKey);
      completableResultCode.join(10, SECONDS);
      assertThat(completableResultCode.isSuccess()).isEqualTo(true);
      localFileLoader.updateProcessedFileStatus(true, persistedFile.file);

      // sleep 1 second to wait for delete to complete
      Thread.sleep(1000);

      files = FileUtils.listFiles(tempFolder, new String[] {"trn"}, false);
      assertThat(files.size()).isEqualTo(--expectedCount);
    }

    assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(0);
  }

  @Test
  public void testDeleteFilePermanentlyOnFailure() throws Exception {
    HttpClient mockedClient = mock(HttpClient.class);
    when(mockedClient.send(any(HttpRequest.class), any(Context.class)))
        .then(
            invocation ->
                Mono.error(
                    () -> new Exception("this is expected to be logged by the operation logger")));
    HttpPipelineBuilder pipelineBuilder = new HttpPipelineBuilder().httpClient(mockedClient);
    LocalFileCache localFileCache = new LocalFileCache(tempFolder);

    LocalFileLoader localFileLoader = new LocalFileLoader(localFileCache, tempFolder, null);
    LocalFileWriter localFileWriter = new LocalFileWriter(localFileCache, tempFolder, null);

    TelemetryChannel telemetryChannel =
        new TelemetryChannel(
            pipelineBuilder.build(),
            new URL("http://foo.bar"),
            localFileWriter,
            Mockito.mock(StatsbeatModule.class),
            false);

    // persist 10 files to disk
    for (int i = 0; i < 10; i++) {
      localFileWriter.writeToDisk(
          singletonList(ByteBuffer.wrap("hello world".getBytes(UTF_8))), INSTRUMENTATION_KEY);
    }

    assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(10);

    Collection<File> files = FileUtils.listFiles(tempFolder, new String[] {"trn"}, false);
    assertThat(files.size()).isEqualTo(10);

    // fail to send persisted files and expect them to be kept on disk
    for (int i = 0; i < 10; i++) {
      LocalFileLoader.PersistedFile persistedFile = localFileLoader.loadTelemetriesFromDisk();
      assertThat(persistedFile.instrumentationKey).isEqualTo(INSTRUMENTATION_KEY);

      CompletableResultCode completableResultCode =
          telemetryChannel.sendRawBytes(persistedFile.rawBytes, persistedFile.instrumentationKey);
      completableResultCode.join(10, SECONDS);
      assertThat(completableResultCode.isSuccess()).isEqualTo(false);
      localFileLoader.updateProcessedFileStatus(false, persistedFile.file);
    }

    files = FileUtils.listFiles(tempFolder, new String[] {"trn"}, false);
    assertThat(files.size()).isEqualTo(10);
    assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(10);
  }

  private static void verifyTelemetryName(int index, String actualName) {
    String expectedName = null;
    if (index < 6) {
      expectedName = "Metric";
    } else if (index == 6) {
      expectedName = "Statsbeat";
    } else if (index == 7) {
      expectedName = "Message";
    } else if (index == 8) {
      expectedName = "RemoteDependency";
    } else if (index == 9) {
      expectedName = "Request";
    }

    assertThat(actualName).isEqualTo(expectedName);
  }

  private static void verifyTelemetryTime(int index, String actualTime) {
    String expectedTime = null;
    if (index < 6) {
      expectedTime = "2021-06-14T17:24:28.983-0700";
    } else if (index == 6) {
      expectedTime = "2021-06-15T12:01:02.852-0700";
    } else if (index == 7) {
      expectedTime = "2021-06-15T08:36:09.569-0700";
    } else if (index == 8) {
      expectedTime = "2021-06-15T08:36:15.229-0700";
    } else if (index == 9) {
      expectedTime = "2021-06-16T12:15:50.433-0700";
    }

    assertThat(actualTime).isEqualTo(expectedTime);
  }

  private static void verifyTagsNodeSize(int index, int actualSize) {
    int expectedSize = 0;
    if (index < 8) {
      expectedSize = 3;
    } else if (index == 8) {
      expectedSize = 4;
    } else if (index == 9) {
      expectedSize = 6;
    }

    assertThat(actualSize).isEqualTo(expectedSize);
  }

  private static void verifyDataBaseType(int index, String actualBaseType) {
    String expectedBaseType = null;
    if (index < 7) {
      expectedBaseType = "MetricData";
    } else if (index == 7) {
      expectedBaseType = "MessageData";
    } else if (index == 8) {
      expectedBaseType = "RemoteDependencyData";
    } else if (index == 9) {
      expectedBaseType = "RequestData";
    }

    assertThat(actualBaseType).isEqualTo(expectedBaseType);
  }

  private static void verifyRemoteDependencyBaseData(JsonNode baseData) {
    assertThat(baseData.get("name").asText()).isEqualTo("DROP TABLE vet_specialties IF EXISTS");
    assertThat(baseData.get("id").asText()).isEqualTo("d54e451407c13ad2");
    assertThat(baseData.get("duration").asText()).isEqualTo("00:00:00.0130000");
    assertThat(baseData.get("success").asText()).isEqualTo("true");
    assertThat(baseData.get("data").asText()).isEqualTo("DROP TABLE vet_specialties IF EXISTS");
    assertThat(baseData.get("type").asText()).isEqualTo("SQL");
    assertThat(baseData.get("target").asText()).isEqualTo("b8f14b49-a2ad-4fa9-967e-c00b1d6addc4");
  }

  private static void verifyRequestBaseData(JsonNode baseData) {
    assertThat(baseData.get("id").asText()).isEqualTo("c0bfdc8f7963802c");
    assertThat(baseData.get("duration").asText()).isEqualTo("00:00:00.0210000");
    assertThat(baseData.get("responseCode").asText()).isEqualTo("304");
    assertThat(baseData.get("success").asText()).isEqualTo("true");
    assertThat(baseData.get("name").asText()).isEqualTo("GET /webjars/**");
    assertThat(baseData.get("url").asText())
        .isEqualTo("http://localhost:8080/webjars/jquery/2.2.4/jquery.min.js");
  }

  private static String expectedMetricsName(int index) {
    switch (index) {
      case 0:
        return "jvm_threads_states";
      case 1:
        return "hikaricp_connections_max";
      case 2:
        return "process_uptime";
      case 3:
        return "jvm_memory_used";
      case 4:
        return "jvm_threads_live";
      case 5:
        return "jdbc_connections_min";
      case 6:
        return "Request Success Count";
      default:
        throw new AssertionError("Unexpected index: " + index);
    }
  }

  private static int expectedMetricsValue(int index) {
    switch (index) {
      case 0:
        return 3;
      case 1:
        return 10;
      case 2:
        return 3131610;
      case 3:
        return 12958128;
      case 4:
        return 150;
      case 5:
        return 110;
      case 6:
        return 2;
      default:
        throw new AssertionError("Unexpected index: " + index);
    }
  }

  private static void verifyProperties(int index, JsonNode properties) {
    switch (index) {
      case 0:
        assertThat(properties.get("state").asText()).isEqualTo("blocked");
        return;
      case 1:
        assertThat(properties.get("pool").asText()).isEqualTo("HikariPool-1");
        return;
      case 3:
        assertThat(properties.get("area").asText()).isEqualTo("nonheap");
        assertThat(properties.get("id").asText()).isEqualTo("Compressed Class Space");
        return;
      case 4:
        assertThat(properties.get("state").asText()).isEqualTo("runnable");
        return;
      case 5:
        assertThat(properties.get("name").asText()).isEqualTo("dataSource");
        return;
      case 6: // Statsbeat
        verifyStatsbeatCustomDimensions(properties);
        return;
      case 7: // Message
        assertThat(properties.get("LoggerName").asText())
            .isEqualTo("org.springframework.boot.web.embedded.tomcat.TomcatWebServer");
        assertThat(properties.get("LoggingLevel").asText()).isEqualTo("INFO");
        assertThat(properties.get("SourceType").asText()).isEqualTo("Logger");
        return;
      case 2:
      case 8:
      case 9:
        assertThat(properties).isNull();
        return;
      default:
        throw new AssertionError("Unexpected index " + index);
    }
  }

  private static void verifyStatsbeatCustomDimensions(JsonNode properties) {
    assertThat(properties.get("runtimeVersion").asText()).isEqualTo("11.0.7");
    assertThat(properties.get("os").asText()).isEqualTo("Windows");
    assertThat(properties.get("language").asText()).isEqualTo("java");
    assertThat(properties.get("attach").asText()).isEqualTo("codeless");
    assertThat(properties.get("instrumentation").asText()).isEqualTo("0");
    assertThat(properties.get("cikey").asText()).isEqualTo(INSTRUMENTATION_KEY);
    assertThat(properties.get("version").asText()).isEqualTo("3.1.1");
    assertThat(properties.get("rp").asText()).isEqualTo("unknown");
  }

  private static HttpClient getMockHttpClientSuccess() {
    return new MockHttpClient(
        request -> {
          return Mono.just(new MockHttpResponse(request, 200));
        });
  }

  private static class MockHttpClient implements HttpClient {
    private final Function<HttpRequest, Mono<HttpResponse>> handler;

    MockHttpClient(Function<HttpRequest, Mono<HttpResponse>> handler) {
      this.handler = handler;
    }

    @Override
    public Mono<HttpResponse> send(HttpRequest httpRequest) {
      return handler.apply(httpRequest);
    }
  }
}
