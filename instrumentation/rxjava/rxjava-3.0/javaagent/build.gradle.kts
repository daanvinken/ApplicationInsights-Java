plugins {
  id("otel.javaagent-instrumentation")
}

muzzle {
  pass {
    group.set("io.reactivex.rxjava3")
    module.set("rxjava")
    versions.set("[3.0.0,)")
    assertInverse.set(true)
  }
}

tasks.withType<Test>().configureEach {
  // TODO run tests both with and without experimental span attributes
  jvmArgs("-Dotel.instrumentation.rxjava.experimental-span-attributes=true")
}

dependencies {
  library("io.reactivex.rxjava3:rxjava:3.0.0")

  implementation(project(":instrumentation:rxjava:rxjava-3.0:library"))

  testImplementation("io.opentelemetry:opentelemetry-extension-annotations")
  testImplementation(project(":instrumentation:rxjava:rxjava-3.0:testing"))
}
