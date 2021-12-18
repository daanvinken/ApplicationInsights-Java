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

package com.microsoft.applicationinsights.agent.internal.quickpulse;

import com.microsoft.applicationinsights.agent.internal.init.TelemetryClientInitializer;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentials;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.file.CloudFile;
import com.microsoft.azure.storage.file.CloudFileClient;
import com.microsoft.azure.storage.file.CloudFileDirectory;
import com.microsoft.azure.storage.file.CloudFileShare;
import com.microsoft.azure.storage.file.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QuickPulseDataSaver implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(TelemetryClientInitializer.class);
  private final ArrayBlockingQueue<QuickPulseDataCollector.FinalCounters> saveQueue;
  private CloudFileClient fileClient;
  private String metricLine;
  private CloudFile cloudFile;
  private UUID metricUUID;
  private FileOutputStream outputStream;
  private static final String csvHeader =
      "exceptions, requests, requestsDuration, unsuccessfulRequests, "
          + "rdds, rddsDuration, unsucccessfulRdds, memoryUsage, cpuUsage\n";

  // TODO better security
  public static final String storageConnectionString =
      "DefaultEndpointsProtocol=https;" +
          "AccountName=<storage_account_name>;" +
          "AccountKey=<storage_account_key>";

  public QuickPulseDataSaver(ArrayBlockingQueue<QuickPulseDataCollector.FinalCounters> saveQueue) {
    this.saveQueue = saveQueue;
    try {
      String storageConnectionString2 = String.format(
          "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s",
          System.getenv("STORAGE_ACCOUNT_NAME"),
          System.getenv("STORAGE_ACCOUNT_KEY")
      );
      logger.info(storageConnectionString2);
      CloudStorageAccount storageAccount = CloudStorageAccount.parse(storageConnectionString);
      this.fileClient = storageAccount.createCloudFileClient();
      StorageCredentials sc = fileClient.getCredentials();

      logger.info("Signed in with default credentials:\n\t" + sc.toString());

      this.metricUUID = UUID.randomUUID();
      this.initRemoteMetricFile();

    } catch (URISyntaxException e) {
      String msg = "Error in URI syntax for storing metrics.";
      logger.error(msg, e);
    } catch (InvalidKeyException e) {
      String msg = "Invalid Account Key for storing metrics.";
      logger.error(msg, e);
    } catch (StorageException e) {
      String msg = "Error during storage of metrics (initialization)";
      logger.error(msg, e);
    } catch (IOException e) {
      String msg = "Could not init metric file.";
      logger.error(msg, e);
    } catch (InterruptedException e) {
      String msg = "Thread interrupted (QuickPulseDataSaver).";
      logger.error(msg, e);
    }
  }

  @Override
  public void run() {
    logger.info("Now running with UUID: " + this.metricUUID.toString());
    try {
      QuickPulseDataCollector.FinalCounters counter = saveQueue.take();
      this.metricLine = parseMetric(counter);
      this.saveLine();
    } catch (InterruptedException e) {
      String msg = "Thread interrupted (QuickPulseDataSaver).";
      logger.error(msg, e);
    } catch (IOException e) {
      String msg = String.format("Unable to write metricLine '%s'", this.metricLine);
      logger.error(msg, e);
    } finally {
      this.closeConnection();
    }
  }

  private static String parseMetric(QuickPulseDataCollector.FinalCounters counter) {
    return String.format("%s, %s, %s, %s, %s, %s, %s, %s, %s, %s,\n",
        LocalDateTime.now(),
        counter.exceptions,
        counter.requests,
        counter.requestsDuration,
        counter.unsuccessfulRequests,
        counter.rdds,
        counter.rddsDuration,
        counter.unsuccessfulRdds,
        counter.memoryCommitted,
        counter.cpuUsage);
  }

  private void initRemoteMetricFile() throws URISyntaxException, StorageException, IOException {
    CloudFileShare share = fileClient.getShareReference("acimetric");
    CloudFileDirectory rootDir = share.getRootDirectoryReference();
    CloudFileDirectory dir = rootDir.getDirectoryReference("metrics");
    dir.createIfNotExists();

    String filename = this.metricUUID.toString() + ".csv";
    this.cloudFile = dir.getFileReference(filename);
    // Set filesize for now to 10mb
    // TODO create new file when exceeded limit (if that ever happens)
    this.outputStream = this.cloudFile.openWriteNew(Long.parseLong("10485760"));
    this.outputStream = this.cloudFile.openWriteExisting();
    this.metricLine = csvHeader;
    this.saveLine();
  }


  private void saveLine() throws IOException {
    this.outputStream.write(this.metricLine.getBytes(StandardCharsets.UTF_8));
    this.outputStream.flush();
  }

  private void closeConnection() {
    try {
      this.outputStream.close();
    } catch (IOException e) {
      String msg = "Could not close file connection.";
      logger.error(msg, e);
    }
  }


}