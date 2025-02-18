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

package com.microsoft.applicationinsights.agent.internal.statsbeat;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

enum Feature {
  JAVA_VENDOR_ORACLE(0),
  JAVA_VENDOR_ZULU(1),
  JAVA_VENDOR_MICROSOFT(2),
  JAVA_VENDOR_ADOPT_OPENJDK(3),
  JAVA_VENDOR_REDHAT(4),
  JAVA_VENDOR_OTHER(5),
  AAD(6),
  CASSANDRA_DISABLED(7),
  JDBC_DISABLED(8),
  JMS_DISABLED(9),
  KAFKA_DISABLED(10),
  MICROMETER_DISABLED(11),
  MONGO_DISABLED(12),
  REDIS_DISABLED(13),
  SPRING_SCHEDULING_DISABLED(14),
  AZURE_SDK_DISABLED(15),
  RABBITMQ_DISABLED(16),
  SPRING_INTEGRATION_DISABLED(
      17), // preview instrumentation, spring-integration is ON by default in OTEL
  LEGACY_PROPAGATION_DISABLED(18),
  GRIZZLY_ENABLED(19), // preview instrumentation, grizzly is OFF by default in OTEL
  STATSBEAT_DISABLED(20), // disable non-essential statsbeat
  QUARTZ_DISABLED(21), // preview instrumentation, quartz is ON by default in OTEL
  APACHE_CAMEL_DISABLED(22), // preview instrumentation, apache camel is ON by default in OTEL
  AKKA_DISABLED(23), // preview instrumentation, akka is ON by default in OTEL
  PROPAGATION_DISABLED(24),
  PLAY_DISABLED(25); // preview instrumentation, play is ON by default in OTEL

  private static final Map<String, Feature> javaVendorFeatureMap;

  static {
    javaVendorFeatureMap = new HashMap<>();
    javaVendorFeatureMap.put(
        "Oracle Corporation",
        Feature
            .JAVA_VENDOR_ORACLE); // https://www.oracle.com/technetwork/java/javase/downloads/index.html
    javaVendorFeatureMap.put(
        "Azul Systems, Inc.",
        Feature.JAVA_VENDOR_MICROSOFT); // https://www.azul.com/downloads/zulu/
    javaVendorFeatureMap.put(
        "Microsoft", Feature.JAVA_VENDOR_MICROSOFT); // https://www.azul.com/downloads/zulu/
    javaVendorFeatureMap.put(
        "AdoptOpenJDK", Feature.JAVA_VENDOR_ADOPT_OPENJDK); // https://adoptopenjdk.net/
    javaVendorFeatureMap.put(
        "Red Hat, Inc.",
        Feature.JAVA_VENDOR_REDHAT); // https://developers.redhat.com/products/openjdk/download/
  }

  private final int bitmapIndex;

  Feature(int bitmapIndex) {
    this.bitmapIndex = bitmapIndex;
  }

  static Feature fromJavaVendor(String javaVendor) {
    Feature feature = javaVendorFeatureMap.get(javaVendor);
    return feature != null ? feature : Feature.JAVA_VENDOR_OTHER;
  }

  static long encode(Set<Feature> features) {
    BitSet bitSet = new BitSet(64);
    for (Feature feature : features) {
      bitSet.set(feature.bitmapIndex);
    }

    long[] longArray = bitSet.toLongArray();
    if (longArray.length > 0) {
      return longArray[0];
    }

    return 0L;
  }

  // only used by tests
  int getBitmapIndex() {
    return bitmapIndex;
  }
}
