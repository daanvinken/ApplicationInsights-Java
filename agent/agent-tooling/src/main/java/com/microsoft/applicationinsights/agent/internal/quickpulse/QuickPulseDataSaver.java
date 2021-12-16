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
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QuickPulseDataSaver implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(TelemetryClientInitializer.class);
  private final ArrayBlockingQueue<QuickPulseDataCollector.FinalCounters> saveQueue;
  private QuickPulseDataCollector.FinalCounters counter;
  public QuickPulseDataSaver(ArrayBlockingQueue<QuickPulseDataCollector.FinalCounters> saveQueue) {
    this.saveQueue = saveQueue;
  }
    @Override
    public void run () {
      int count = 0;
      while (true) {
        try {
          counter = saveQueue.take();
          logger.info(String.format("Cpu usage = %f\n", counter.cpuUsage));
          logger.info(String.format("Memory usage = %02d\n", counter.memoryCommitted));
        } catch (InterruptedException e) {
          logger.error(Arrays.toString(e.getStackTrace()));
        }
        if (count++ > 20) {
          break;
        }
      }
    }

}