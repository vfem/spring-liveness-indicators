# Spring Liveness Indicators

## Overview

This project is a Spring Boot starter that provides liveness indicators for Kafka consumers.
It monitors consumer progress and provides a liveness state based on their activity,
helping you ensure the health of your Kafka-based applications.


## Quick Start

1. Ensure you meet the requirements:
    - Java 17 or higher
    - Spring Boot 3.4.x (or 3.x)
    - Maven 3.6.0 or higher
    - Kafka

2. Enable liveness checks in `application.yml`:
   ```yaml
   management:
     health:
       livenessstate:
         enabled: true
     endpoint:
       health:
         probes:
           enabled: true
   ```

3. Configure liveness monitoring in `application.properties` / `application.yml` (optional):
   ```yaml
   liveness:
     kafka:
       admin-timeout-ms: 5000       # Timeout for Kafka AdminClient operations (ms)
       max-stalled-checks: 3        # Consecutive stalled checks before marking broken
   ```

## Features

### Kafka Liveness Indicator
- Monitors Kafka consumer progress for Spring Kafka topic listeners
- Evaluates consumer state and group health
- Checks if topics are empty or fully consumed
- Exposes health state via `/actuator/health/liveness` endpoint

## Requirements

- Java 17 or higher
- Spring Boot 3.4.x (or 3.x)
- Maven 3.6.0 or higher
- Kafka

## Configuration

The library beans can be configured using the `application.yml` file. Here are the key properties:

```yaml
liveness:
  kafka:
    # Timeout in milliseconds for Kafka AdminClient operations
    # Optional: default is 5000
    admin-timeout-ms: 5000
    # Maximum consecutive stalled checks before reporting BROKEN
    # Optional: default is 3
    max-stalled-checks: 3
```

## Testing

Execute all tests with:
```sh
mvn test
```


## Usage

The application will automatically start monitoring Kafka consumers upon startup.
You can check the liveness state by accessing the `/actuator/health/liveness` endpoint.
But Liveness Checks must be enabled explicitly in the `application.yml` file.

```yaml
management:
  health:
    livenessstate:
      enabled: true
  endpoint:
    health:
      probes:
        enabled: true
```

### Auto-Configuration evaluation

#### Kafka Liveness Indicator

- Spring Boot Actuator must be present in the classpath.
- Spring Kafka must be present in the classpath.
- Health probes must be enabled in the `application.yml` file.
- Liveness checks must be enabled in the `application.yml` file.

## Contributing

Contributions are welcome! Please fork the repository and submit a pull request.

## License

This project is licensed under the Apache 2.0 License. See the [LICENSE](LICENSE-2.0.txt) file for details.

## Release Process

1. GPG Key Setup:
    - Generate GPG key
    - Deploy to keyserver (e.g., keyserver.ubuntu.com)

2. Maven Central Setup:
    - Obtain token from https://central.sonatype.com/account
    - Configure in settings.xml with server id `central`

3. Release Steps:
   ```sh
   mvn release:prepare                 # Prepare the release
   git checkout <new-version-tag>      # Switch to release tag
   mvn deploy -Prelease               # Deploy to Maven Central
   ```

4. Finalize:
    - Review artifacts at https://central.sonatype.com/publishing/deployments
    - Release or drop the deployment


