# Spring + Vue.JS SPA OpenTelemetry Example

This is a sample application demonstrating the usage of OpenTelemetry with Spring and Vue.JS in Kubernetes.

We start with a small bug that you'll use OpenTelemetry to discover, as well as better understand the performance of your application.

## Prerequisites

- Docker
- Kubernetes (Local or GKE)
- Helm
- Codefresh and Lightstep (for building, deploying, and viewing telemetry)

For remote deployment, you'll need to define static IP addresses for your services (backend, frontend, and collector).  

## Add OpenTelemetry to the Server

Add the auto-instrumentation jar to the Docker container and the OTLP exporter by modifying the Dockerfile in /server/. You'll then add some new startup options to the entrypoint command.

```shell
...

ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v0.6.0/opentelemetry-javaagent-all.jar /app/otel.jar
ENV OTEL_RESOURCE_ATTRIBUTES service.name=todo-server
ENV OTEL_OTLP_ENDPOINT otel-collector:55680

...

ENTRYPOINT ["java",\
           "-XX:+UnlockExperimentalVMOptions",\
           "-javaagent:/app/otel.jar",\
            "-Djava.security.egd=file:/dev/./urandom",\
            "-jar","/app/spring-boot-application.jar"]
```

Adding the Java Auto-Instrumentation does the following:

- Installs OpenTelemetry on application startup, and looks for packages that can be instrumented. You can configure this to specifically enable or disable particular libraries (see [the GitHub repository for more information](https://github.com/open-telemetry/opentelemetry-java-instrumentation).
)
- Configures a resource attribute that specifies the service name (in this case, `todo-server`).
- Configures export of telemetry data to an OpenTelemetry Collector -- running at `otel-collector:55680` in this case.

## Add OpenTelemetry to the Client

Since our Vue application runs entirely client-side, we'll be importing browser-based instrumentation. Adding this is fairly straightforward, and the principles should apply to any SPA (including a React application).

First, add the necessary dependencies to your `package.json` -

```json
"dependencies": {
    "@opentelemetry/context-zone": "^0.10.0",
    "@opentelemetry/exporter-collector": "^0.10.0",
    "@opentelemetry/plugin-document-load": "^0.8.0",
    "@opentelemetry/plugin-user-interaction": "^0.8.0",
    "@opentelemetry/plugin-xml-http-request": "^0.10.0",
    "@opentelemetry/tracing": "^0.10.0",
    "@opentelemetry/web": "^0.10.0",
    ...
  },
```

You'll then need to add a new file, `tracer.js` to the Vue application at `client/src/tracer.js`.

```js
import { ConsoleSpanExporter, SimpleSpanProcessor } from '@opentelemetry/tracing';
import { WebTracerProvider } from '@opentelemetry/web';
import { XMLHttpRequestPlugin } from '@opentelemetry/plugin-xml-http-request';
import { UserInteractionPlugin } from '@opentelemetry/plugin-user-interaction';
import { DocumentLoad } from '@opentelemetry/plugin-document-load';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { CollectorTraceExporter } from '@opentelemetry/exporter-collector';

/* eslint-disable no-undef */
const collectorUrl = config.VUE_APP_ENV_Collector || 'http://localhost:30011/v1/trace'
const serverBaseUrl = config.VUE_APP_ENV_ServerBase || 'localhost:30005'
const baseLocation = window.location.hostname || 'localhost'
/* eslint-enable no-undef */

const exporterOptions = {
  serviceName: 'todo-client',
  url: collectorUrl,
};

const providerWithZone = new WebTracerProvider({
  plugins: [
    new DocumentLoad(),
    new UserInteractionPlugin(),
    new XMLHttpRequestPlugin({
      ignoreUrls: [new RegExp(`/${baseLocation}:8090/sockjs-node/`)],
      propagateTraceHeaderCorsUrls: new RegExp(`/${serverBaseUrl}/`),
    }),
  ],
});

providerWithZone.addSpanProcessor(new SimpleSpanProcessor(new ConsoleSpanExporter()));
providerWithZone.addSpanProcessor(new SimpleSpanProcessor(new CollectorTraceExporter(exporterOptions)));

providerWithZone.register({
  contextManager: new ZoneContextManager()
});
```

Let's briefly explain what's going on here.

First, we need to get several configuration options from our `config.js` file - so OpenTelemetry knows where the collector endpoint is, and what the base URL of the server is for propagating trace context. The Collector Exporter requires a service name, which we're setting to `todo-client`. Finally, the call to `new WebTracerProvider` performs several tasks -- we register the plugins we're interested in running, all of which will generate telemetry for us without having to make any code changes. `DocumentLoad` will create a trace corresponding to the operations required for loading the page, `UserInteractionPlugin` creates traces when users click on buttons in our SPA, and `XMLHttpRequestPlugin` will allow us to trace outgoing HTTP requests from our application. In the XML HTTP Request Plugin, `ignoreUrls` tells the plugin to not trace requests to certain endpoints, and `propagateTraceHeaderCorlsUrls` allows us to propagate trace context to locations that we normally wouldn't due to cross-origin limitations.

Now, to actually start using this tracer, we need to import it in our application. At the _top_ of `main.js`, add `import './tracer'`. This is important, as the instrumentation needs to be loaded first in order to accurately hook into and trace document loading and HTTP requests.

## Add the OpenTelemetry Collector

We've got telemetry coming from our backend and frontend services, but now we need a place to aggregate it, and export it to our analysis system. While you can directly export telemetry from each SDK, the OpenTelemetry Collector is a convenient way to aggregate and sample telemetry before sending it to an analysis backend.

Adding the collector is as simple as creating a new template in our Helm chart - add the following to `helm/templates` to get started:

```yaml
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: otel-collector-conf
  labels:
    app: opentelemetry
    component: otel-collector-conf
data:
  otel-collector-config: |
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: "0.0.0.0:55680"
          http:
            endpoint: "0.0.0.0:55681"
    processors:
      batch:
      queued_retry:
    extensions:
      health_check: {}
      zpages:
        endpoint: "0.0.0.0:55679"
    exporters:
      prometheus:
        endpoint: "0.0.0.0:8889"
        namespace: "collector"
      logging:
      otlp:
        endpoint: "ingest.lightstep.com:443"
        compression: gzip
        headers:
          "lightstep-access-token": "{{ .Values.lightstepKey }}"
    service:
      extensions: [health_check, zpages]
      pipelines:
        traces:
          receivers: [otlp]
          processors: [batch, queued_retry]
          exporters: [logging, otlp]
        metrics:
          receivers: [otlp]
          exporters: [logging, prometheus]
---
apiVersion: v1
kind: Service
metadata:
  name: otel-collector
  labels:
    app: opentelemetry
    component: otel-collector
  annotations: 
    prometheus.io/scrape: "true"
    prometheus.io/path: /metrics
    prometheus.io/port: "8889"
spec:
  type: {{ .Values.collector.serviceType }}
  {{ if eq .Values.collector.serviceType "LoadBalancer" }}
  loadBalancerIP: {{ .Values.collector.ip }}
  {{ end }}
  ports:
  - name: otlp # Default endpoint for OpenTelemetry receiver.
    port: 55680
    {{ if eq .Values.collector.serviceType "LoadBalancer" }}
    targetPort: {{ .Values.collector.grpcPort }}
    {{ end }}
  - name: otlp-http
    port: 55681
    {{ if eq .Values.collector.serviceType "LoadBalancer" }}
    targetPort: {{ .Values.collector.httpPort }}
    {{ else }}
    nodePort: {{ .Values.collector.httpPort }}
    {{ end }}
  - name: metrics # Default endpoint for querying metrics.
    port: 8889
  - name: healthz
    port: 55679
  selector:
    component: otel-collector
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
  labels:
    app: opentelemetry
    component: otel-collector
spec:
  selector:
    matchLabels:
      app: opentelemetry
      component: otel-collector
  minReadySeconds: 5
  progressDeadlineSeconds: 120
  replicas: 1 #TODO - adjust this to your own requirements
  template:
    metadata:
      labels:
        app: opentelemetry
        component: otel-collector
    spec:
      containers:
      - command:
          - "/otelcol"
          - "--config=/conf/otel-collector-config.yaml"
#           Memory Ballast size should be max 1/3 to 1/2 of memory.
          - "--mem-ballast-size-mib=683"
        image: otel/opentelemetry-collector:0.6.1
        name: otel-collector
        env:
          - name: REDEPLOYED_AT
            value: "{{now}}"
        resources:
          limits:
            cpu: 1
            memory: 2Gi
          requests:
            cpu: 200m
            memory: 400Mi
        ports:
        - containerPort: 55679 # Default endpoint for ZPages.
        - containerPort: 55680 # Default endpoint for OpenTelemetry receiver.
        - containerPort: 8889  # Default endpoint for querying metrics.
        - containerPort: 55681 # HTTP OTLP Endpoint
        volumeMounts:
        - name: otel-collector-config-vol
          mountPath: /conf
        livenessProbe:
          httpGet:
            path: /
            port: 13133 # Health Check extension default port.
        readinessProbe:
          httpGet:
            path: /
            port: 13133 # Health Check extension default port.
      volumes:
        - configMap:
            name: otel-collector-conf
            items:
              - key: otel-collector-config
                path: otel-collector-config.yaml
          name: otel-collector-config-vol
```

There's a lot going on here, so let's talk about it in parts. The Collector deployment has three main components - a `ConfigMap` to store the collector's configuration, a `Deployment` to configure the pod, and a `Service` to expose it to the world. You can learn more about the configuration at the [GitHub repository](https://github.com/open-telemetry/opentelemetry-collector) for the collector, so we're not going to dwell on it much other than to discuss the important parts of the `ConfigMap`.

The collector functions by exposing _receivers_, which are endpoints that can collect telemetry data in a variety of formats. That telemetry can be transformed by _processors_ -- we use these to batch and queue exports in this case, but other options exist. The collector itself has several _extensions_ that provide useful functionality, such as health checks or zPages to collect diagnostic information. Finally, the collector has _exporters_ which transmit telemetry to analysis and storage service(s).  

In this case, we're registring an OTLP (OpenTeLemetry Protocol) receiver which will listen for data from our front and backend services. That data will be processed in batches, before being exported to Lightstep. We also set up a logging exporter that will print information to `stdout` when traces are received, allowing us to easily spot check that we're receiving telemetry from our services. We're also setting up an exporter for metrics, which will export metrics about our collector to Prometheus (as well as any metrics we might choose to export from our application).

## Run The Application

At this point, you're ready to run! If you're deploying to a managed Kubernetes cluster, like GKE, then make sure you've filled out the `values.yaml` file appropriately with static IP addresses for the client, server, and collector and set the `serviceType` to `LoadBalancer`. If deploying locally, you'll want to use `NodePort` for the service type. You'll also need to set the `lightstepKey` value with the project access token from your Lightstep project.

You can run `helm install <name> ./helm` to deploy everything to Kubernetes locally. If you're using Codefresh, make sure you update the `codefresh.yml` file 

## Further Work

You may notice that there's a problem -- you aren't seeing any cat facts in the frontend! How can we find, and fix, this issue just using OpenTelemetry? I'll leave this to you, but here's some ideas on how to keep going -

- Find the code path that gets cat facts in the server, and using OpenTelemetry's `@WithSpan` annotations, create a new span on the method that could throw an exception. Redeploy, and check Lightstep for the `/facts` route on your server service, and you should be able to find the error.
- What other information might you want to know about your service performance? Try adding metrics, or more spans, to the existing instrumentation on the client or server side. Some interesting things to track might be the number of items added to the todo list, the amount of time it takes to make SQL queries, or really anything else you can think of.
- Compare different ways of exporting and visualizing data from the collector! You can add Jaeger as an open source trace visualizer, or add Grafana to make dashboards out of your metrics.
